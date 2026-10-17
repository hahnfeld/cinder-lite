// Cinder Lite: boots iSH-AOK's emulator. Adapted from iSH-AOK's -[AppDelegate boot] and
// -[TerminalViewController startSession] (GPL-3.0), reduced to what Cinder Lite needs.
#import "CinderKernel.h"
#include <resolv.h>
#include <arpa/inet.h>
#include "kernel/init.h"
#include "kernel/fs.h"
#include "kernel/task.h"
#include "kernel/signal.h"
#include "kernel/calls.h"
#include "kernel/errno.h"
#include "fs/path.h"
#include "fs/real.h"
#include "fs/devices.h"
#include "fs/dyndev.h"
#include "fs/tty.h"
#include "tools/fakefs.h"
#include "archive.h"
#include "archive_entry.h"
#import "URLDevice.h"


static void (^sessionExitedBlock)(int, int);
static struct task *initTask;

static NSError *KernelError(NSString *what, long err) {
    return [NSError errorWithDomain:@"CinderKernel" code:err
                           userInfo:@{NSLocalizedDescriptionKey: [NSString stringWithFormat:@"%@ (error %ld)", what, err]}];
}

/// Creates `path` and its parents in the guest, as directories.
static void EnsureDirectory(const char *path) {
    char partial[MAX_PATH];
    for (const char *p = path + 1; ; p++) {
        if (*p == '/' || *p == '\0') {
            size_t n = (size_t) (p - path);
            if (n >= sizeof(partial))
                return;
            memcpy(partial, path, n);
            partial[n] = '\0';
            generic_mkdirat(AT_PWD, partial, 0755);   // EEXIST is fine
            if (*p == '\0')
                return;
        }
    }
}

/// Makes a device node, replacing whatever is at `path` if it isn't that device.
static void EnsureDevice(const char *path, mode_t_ mode, dev_t_ device) {
    struct statbuf stat;
    if (generic_statat(AT_PWD, path, &stat, AT_SYMLINK_NOFOLLOW_) == 0) {
        if ((stat.mode & S_IFMT) == (mode & S_IFMT) && stat.rdev == device)
            return;
        generic_unlinkat(AT_PWD, path);
    }
    generic_mknodat(AT_PWD, path, mode, device);
}

static void WriteGuestFile(const char *path, NSString *contents) {
    struct fd *fd = generic_open(path, O_WRONLY_ | O_CREAT_ | O_TRUNC_, 0644);
    if (IS_ERR(fd))
        return;
    NSData *data = [contents dataUsingEncoding:NSUTF8StringEncoding];
    fd->ops->write(fd, data.bytes, data.length);
    fd_close(fd);
}

/// The device's DNS servers, then public ones (a VPN or a captive network can hide the real ones),
/// as Android's Sandbox.configureNetwork writes them.
static NSString *ResolvConf(void) {
    NSMutableOrderedSet<NSString *> *servers = [NSMutableOrderedSet orderedSet];
    struct __res_state state;
    if (res_ninit(&state) == 0) {
        for (int i = 0; i < state.nscount; i++) {
            char buf[INET6_ADDRSTRLEN];
            struct sockaddr_in *addr = &state.nsaddr_list[i];
            if (addr->sin_family == AF_INET && inet_ntop(AF_INET, &addr->sin_addr, buf, sizeof(buf)))
                [servers addObject:@(buf)];
        }
        res_ndestroy(&state);
    }
    [servers addObject:@"1.1.1.1"];
    [servers addObject:@"8.8.8.8"];
    NSMutableString *conf = [NSMutableString string];
    NSUInteger count = 0;
    for (NSString *server in servers) {
        if (count++ == 4)
            break;
        [conf appendFormat:@"nameserver %@\n", server];
    }
    [conf appendString:@"options timeout:2 attempts:2\n"];
    return conf;
}

// Under pids_lock (kernel/exit.c), so it only records and hands off.
/// The emulator calls this as each process ends, with its last thread to exit: for a multi-threaded
/// program (Claude Code) that's seldom the leader, so the process is looked at through its leader.
static void HandleExit(struct task *task, int status) {
    struct task *leader = task != NULL && task->group != NULL ? task->group->leader : NULL;
    if (leader == NULL || leader->parent == NULL || leader->parent->parent != NULL)
        return;   // only session leaders: init's own children
    int pid = task->tgid;
    dispatch_async(dispatch_get_main_queue(), ^{
        if (sessionExitedBlock != nil)
            sessionExitedBlock(pid, status);
    });
}

static char *NulSeparated(NSArray<NSString *> *strings) {
    NSMutableData *data = [NSMutableData data];
    for (NSString *s in strings) {
        const char *c = s.UTF8String;
        [data appendBytes:c length:strlen(c) + 1];
    }
    [data appendBytes:"" length:1];
    char *out = malloc(data.length);
    memcpy(out, data.bytes, data.length);
    return out;
}

static void ImportProgress(void *cookie, double progress, const char *message, bool *cancel) {
    void (^block)(double) = (__bridge void (^)(double)) cookie;
    if (block != nil)
        block(progress);
}

@implementation CinderKernel

+ (void (^)(int, int))sessionExited { return sessionExitedBlock; }
+ (void)setSessionExited:(void (^)(int, int))block { sessionExitedBlock = [block copy]; }

+ (BOOL)importRootArchive:(NSString *)archive into:(NSString *)rootDir
                 progress:(void (^)(double))progress error:(NSError **)error {
    fakefs_ensure_utf8_locale();
    struct fakefsify_error err = {};
    struct progress p = {.cookie = (__bridge void *) progress, .callback = ImportProgress};
    if (!fakefs_import(archive.fileSystemRepresentation, rootDir.fileSystemRepresentation, &err, p)) {
        if (error != NULL)
            *error = KernelError([NSString stringWithFormat:@"couldn't unpack the Linux root: %s",
                                  err.message ?: "unknown error"], err.code);
        free(err.message);
        return NO;
    }
    return YES;
}

+ (BOOL)bootRoot:(NSString *)rootDir mounts:(NSDictionary<NSString *, NSString *> *)mounts
           error:(NSError **)error {
    NSAssert(initTask == NULL, @"booted twice");
    NSString *data = [rootDir stringByAppendingPathComponent:@"data"];
    int err = mount_root(&fakefs, data.fileSystemRepresentation);
    if (err < 0) {
        if (error != NULL) *error = KernelError(@"couldn't mount the Linux root", err);
        return NO;
    }
    // A current task first: everything below runs as init.
    err = become_first_process();
    if (err < 0) {
        if (error != NULL) *error = KernelError(@"couldn't start the first process", err);
        return NO;
    }

    EnsureDirectory("/dev/pts");
    EnsureDevice("/dev/tty", S_IFCHR | 0666, dev_make(TTY_ALTERNATE_MAJOR, DEV_TTY_MINOR));
    EnsureDevice("/dev/console", S_IFCHR | 0666, dev_make(TTY_ALTERNATE_MAJOR, DEV_CONSOLE_MINOR));
    EnsureDevice("/dev/ptmx", S_IFCHR | 0666, dev_make(TTY_ALTERNATE_MAJOR, DEV_PTMX_MINOR));
    EnsureDevice("/dev/null", S_IFCHR | 0666, dev_make(MEM_MAJOR, DEV_NULL_MINOR));
    EnsureDevice("/dev/zero", S_IFCHR | 0666, dev_make(MEM_MAJOR, DEV_ZERO_MINOR));
    EnsureDevice("/dev/full", S_IFCHR | 0666, dev_make(MEM_MAJOR, DEV_FULL_MINOR));
    EnsureDevice("/dev/random", S_IFCHR | 0666, dev_make(MEM_MAJOR, DEV_RANDOM_MINOR));
    EnsureDevice("/dev/urandom", S_IFCHR | 0666, dev_make(MEM_MAJOR, DEV_URANDOM_MINOR));
    // The one bridge to iOS: /dev/url opens a link (Claude Code's /login, iCTRL's start link). iSH's
    // others (/dev/clipboard, /dev/location) aren't registered: nothing here needs the user's
    // clipboard or position, and anything in the sandbox could read them.
    dyn_dev_register(&url_dev, DEV_CHAR, DYN_DEV_MAJOR, DEV_URL_MINOR);
    EnsureDevice("/dev/url", S_IFCHR | 0666, dev_make(DYN_DEV_MAJOR, DEV_URL_MINOR));

    do_mount(&procfs, "proc", "/proc", "", 0);
    do_mount(&sysfs, "sysfs", "/sys", "", 0);
    do_mount(&devptsfs, "devpts", "/dev/pts", "", 0);
    // The app's own folders: helpers, Claude's settings, the request files the app watches.
    for (NSString *guest in mounts) {
        EnsureDirectory(guest.fileSystemRepresentation);
        err = do_mount(&realfs, mounts[guest].fileSystemRepresentation, guest.fileSystemRepresentation, "", MOUNT_ISH_SHARED_);
        if (err < 0) {
            if (error != NULL) *error = KernelError([NSString stringWithFormat:@"couldn't mount %@", guest], err);
            return NO;
        }
    }
    WriteGuestFile("/etc/resolv.conf", ResolvConf());

    exit_hook = HandleExit;
    tty_drivers[TTY_CONSOLE_MAJOR] = &ios_console_driver;
    set_console_device(TTY_CONSOLE_MAJOR, 1);
    create_stdio("/dev/null", MEM_MAJOR, DEV_NULL_MINOR);
    // init only has to exist: sessions are its children, so it outlives them.
    static const char argv[] = "/bin/sh\0-c\0while :; do sleep 86400; done\0";
    err = do_execve("/bin/sh", 3, argv, "\0");
    if (err < 0) {
        if (error != NULL) *error = KernelError(@"couldn't start init", err);
        return NO;
    }
    initTask = current;
    if (task_start(current) < 0) {
        if (error != NULL) *error = KernelError(@"couldn't start init's thread", _EAGAIN);
        return NO;
    }
    return YES;
}

+ (Terminal *)startSession:(NSArray<NSString *> *)argv environment:(NSArray<NSString *> *)environment
                       pid:(int *)pid error:(NSError **)error {
    NSAssert(initTask != NULL, @"not booted");
    intptr_t err = become_new_init_child();
    if (err < 0) {
        current = initTask;
        if (error != NULL) *error = KernelError(@"couldn't create a process", err);
        return nil;
    }
    struct tty *tty;
    Terminal *terminal = [Terminal createPseudoTerminal:&tty];
    if (terminal == nil) {
        current = initTask;
        if (error != NULL) *error = KernelError(@"couldn't open a terminal", PTR_ERR(tty));
        return nil;
    }
    NSString *pts = [NSString stringWithFormat:@"/dev/pts/%d", tty->num];
    err = create_stdio(pts.fileSystemRepresentation, TTY_PSEUDO_SLAVE_MAJOR, tty->num);
    tty_release(tty);
    char *args = NulSeparated(argv);
    char *env = NulSeparated(environment);
    if (err >= 0)
        err = do_execve(argv[0].fileSystemRepresentation, argv.count, args, env);
    free(args);
    free(env);
    if (err < 0) {
        current = initTask;
        if (error != NULL) *error = KernelError([NSString stringWithFormat:@"couldn't run %@", argv[0]], err);
        return nil;
    }
    *pid = current->pid;
    err = task_start(current);
    current = initTask;   // this thread goes back to being init
    if (err < 0) {
        if (error != NULL) *error = KernelError(@"couldn't start the session's thread", err);
        return nil;
    }
    return terminal;
}

+ (BOOL)extractEntry:(NSString *)entry fromArchive:(NSString *)archive to:(NSString *)output
               error:(NSError **)error {
    struct archive *a = archive_read_new();
    archive_read_support_filter_gzip(a);
    archive_read_support_format_tar(a);
    BOOL found = NO;
    if (archive_read_open_filename(a, archive.fileSystemRepresentation, 1 << 16) == ARCHIVE_OK) {
        struct archive_entry *e;
        while (archive_read_next_header(a, &e) == ARCHIVE_OK) {
            if (strcmp(archive_entry_pathname(e), entry.UTF8String) != 0) {
                archive_read_data_skip(a);
                continue;
            }
            FILE *f = fopen(output.fileSystemRepresentation, "wb");
            if (f == NULL)
                break;
            char buf[1 << 16];
            la_ssize_t n;
            while ((n = archive_read_data(a, buf, sizeof(buf))) > 0)
                fwrite(buf, 1, (size_t) n, f);
            found = fclose(f) == 0 && n == 0;
            break;
        }
    }
    archive_read_free(a);
    if (!found && error != NULL)
        *error = KernelError([NSString stringWithFormat:@"%@ isn't in the download", entry], 0);
    return found;
}

+ (void)signalSession:(int)pid signal:(int)signal {
    struct siginfo_ info = {.sig = signal, .code = SI_USER_};
    send_group_signal(pid, signal, info);
}

@end
