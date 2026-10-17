// The emulated Linux machine Claude Code runs in: iSH-AOK's kernel booted on an Alpine root, with
// Cinder Lite's host folders mounted into it and terminal sessions started as children of init.
#import <Foundation/Foundation.h>
#import "Terminal.h"

NS_ASSUME_NONNULL_BEGIN

@interface CinderKernel : NSObject

/// Unpacks a rootfs tarball into a new fakefs at `rootDir` (data/ + meta.db). `progress` gets
/// 0…1 on an arbitrary thread.
+ (BOOL)importRootArchive:(NSString *)archive into:(NSString *)rootDir
                 progress:(nullable void (^)(double fraction))progress error:(NSError **)error;

/// Boots once per process: mounts the root, the host folders (guest path → host path; the guest
/// path must be a directory in the root, created if missing), /proc, /sys, /dev/pts and Cinder's
/// /dev/url, then starts init, which only sleeps.
+ (BOOL)bootRoot:(NSString *)rootDir mounts:(NSDictionary<NSString *, NSString *> *)mounts
           error:(NSError **)error;

/// Starts `argv` (argv[0] a guest path) on a new pseudo-terminal, as a child of init. `pid` is
/// set to its process id.
+ (nullable Terminal *)startSession:(NSArray<NSString *> *)argv
                        environment:(NSArray<NSString *> *)environment
                                pid:(int *)pid error:(NSError **)error;

/// Copies one entry (e.g. "package/claude") out of a .tgz to `output`.
+ (BOOL)extractEntry:(NSString *)entry fromArchive:(NSString *)archive to:(NSString *)output
               error:(NSError **)error;

/// Sends `signal` to a session's whole process group (its leader's pid).
+ (void)signalSession:(int)pid signal:(int)signal;

/// Called on the main queue when a session's leader exits: its pid and a wait()-style status.
@property (class, nonatomic, copy, nullable) void (^sessionExited)(int pid, int status);

@end

NS_ASSUME_NONNULL_END
