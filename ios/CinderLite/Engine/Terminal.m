//
//  Terminal.m
//  iSH
//
//  Created by Theodore Dubois on 10/18/17.
//

#import "Terminal.h"
#import "DelayedUITask.h"
#import "UserPreferences.h"
#include "fs/dev.h"
#include "fs/devices.h"
#include "fs/tty.h"
#include "util/ro_locks.h"
#include <stdlib.h>
#include <string.h>

extern struct tty_driver ios_pty_driver;

typedef struct tty *tty_t;

@interface Terminal () <WKScriptMessageHandler, WKNavigationDelegate> {
    lock_t _dataLock;
    cond_t _dataConsumed;
}

@property BOOL loaded;
@property (nonatomic) tty_t tty;
// lock with dataLock for !linux and @synchronized(self) for linux
@property (nonatomic) NSMutableData *pendingData;
// sending output is an asynchronous thing due to javascript, this is used to ensure it doesn't happen twice at once
@property (nonatomic) BOOL outputInProgress;
@property (nonatomic) NSData *inFlightData;
@property (nonatomic) NSUInteger outputGeneration;
@property (nonatomic) CFTimeInterval outputStartedAt;

@property DelayedUITask *refreshTask;
@property DelayedUITask *scrollToBottomTask;

@property BOOL applicationCursor;

@property NSNumber *terminalsKey;

@end

@implementation WeakScriptMessageHandler
- (instancetype)initWithHandler:(id <WKScriptMessageHandler>)handler {
    if (self = [super init])
        self.handler = handler;
    return self;
}
- (void)userContentController:(WKUserContentController *)userContentController didReceiveScriptMessage:(WKScriptMessage *)message {
    [self.handler userContentController:userContentController didReceiveScriptMessage:message];
}
@end

@interface CustomWebView : WKWebView
@end
@implementation CustomWebView
- (BOOL)becomeFirstResponder {
    if (@available(iOS 13.4, *)) {
        return [super becomeFirstResponder];
    }
    return NO;
}

- (BOOL)canPerformAction:(SEL)action withSender:(id)sender {
    if (action == @selector(copy:) || action == @selector(paste:)) {
        return NO;
    }
    return [super canPerformAction:action withSender:sender];
}

- (UIView *)snapshotViewAfterScreenUpdates:(BOOL)afterUpdates {
    if (self.window == nil || self.hidden || self.alpha <= 0.0) {
        return [super snapshotViewAfterScreenUpdates:YES];
    }
    return [super snapshotViewAfterScreenUpdates:afterUpdates];
}

- (UIView *)resizableSnapshotViewFromRect:(CGRect)rect
                       afterScreenUpdates:(BOOL)afterUpdates
                            withCapInsets:(UIEdgeInsets)capInsets {
    if (self.window == nil || self.hidden || self.alpha <= 0.0) {
        return [super resizableSnapshotViewFromRect:rect
                                 afterScreenUpdates:YES
                                      withCapInsets:capInsets];
    }
    return [super resizableSnapshotViewFromRect:rect
                             afterScreenUpdates:afterUpdates
                                  withCapInsets:capInsets];
}
@end

@implementation Terminal
@synthesize webView = _webView;

static const int BUF_SIZE = 1<<14;

static NSMapTable<NSNumber *, Terminal *> *terminals;

static NSString *ISHJavaScriptLiteralForTerminalData(NSData *data) {
    const unsigned char *bytes = data.bytes;
    NSUInteger len = data.length;
    if (len == 0)
        return @"";
    // This runs on every screen refresh and dominates bulk-output throughput.
    // The previous version did an Objective-C method dispatch (and, for every
    // printable byte, a format-string parse via appendFormat:@"%c") per byte.
    // Build the whole escaped literal in one C buffer instead and make a single
    // NSString. Worst case is 4 output chars ("\xNN") per input byte. Every
    // byte emitted is ASCII, so NSASCIIStringEncoding is lossless.
    char *out = malloc(len * 4 + 1);
    if (out == NULL)
        return @"";
    static const char hex[] = "0123456789abcdef";
    size_t o = 0;
    for (NSUInteger i = 0; i < len; i++) {
        unsigned char byte = bytes[i];
        switch (byte) {
            case '\\': out[o++] = '\\'; out[o++] = '\\'; break;
            case '"':  out[o++] = '\\'; out[o++] = '"';  break;
            case '\r': out[o++] = '\\'; out[o++] = 'r';  break;
            case '\n': out[o++] = '\\'; out[o++] = 'n';  break;
            case '\t': out[o++] = '\\'; out[o++] = 't';  break;
            default:
                if (byte >= 0x20 && byte <= 0x7e) {
                    out[o++] = (char) byte;
                } else {
                    // Prompts and line editing emit raw escape/control bytes.
                    out[o++] = '\\';
                    out[o++] = 'x';
                    out[o++] = hex[byte >> 4];
                    out[o++] = hex[byte & 0xf];
                }
                break;
        }
    }
    NSString *literal = [[NSString alloc] initWithBytes:out length:o encoding:NSASCIIStringEncoding];
    free(out);
    return literal ?: @"";
}

static const CFTimeInterval ISHTerminalOutputWatchdogSeconds = 1.5;

- (instancetype)initWithType:(int)type number:(int)num {
    @synchronized (Terminal.class) {
        self.terminalsKey = @(dev_make(type, num));
        Terminal *terminal = [terminals objectForKey:self.terminalsKey];
        if (terminal)
            return terminal;

        if (self = [super init]) {
            self.pendingData = [[NSMutableData alloc] initWithCapacity:BUF_SIZE];
            self.refreshTask = [[DelayedUITask alloc] initWithTarget:self action:@selector(refresh)];
            self.scrollToBottomTask = [[DelayedUITask alloc] initWithTarget:self action:@selector(scrollToBottom)];
            lock_init(&_dataLock, "datalock\0");
            cond_init(&_dataConsumed);

            [terminals setObject:self forKey:self.terminalsKey];
        }
        return self;
    }
}

- (void)resetOutputStateAndRequeueInFlightDataLocked {
    NSData *retryData = self.inFlightData;
    if (retryData.length > 0) {
        NSMutableData *restored = [[NSMutableData alloc] initWithCapacity:retryData.length + self.pendingData.length];
        [restored appendData:retryData];
        [restored appendData:self.pendingData];
        self.pendingData = restored;
    }
    self.inFlightData = nil;
    self.outputInProgress = NO;
    self.outputStartedAt = 0;
    self.outputGeneration++;
}

// The web view went away (iOS reclaimed its process, or a write to it failed): a new one, with what
// it hadn't shown yet queued again. TerminalView puts it back on screen once it loads.
- (void)recoverTerminalWebView {
    dispatch_async(dispatch_get_main_queue(), ^{
        lock(&self->_dataLock, 0);
        [self resetOutputStateAndRequeueInFlightDataLocked];
        unlock(&self->_dataLock);
        self.loaded = NO;
        WKWebView *oldWebView = self->_webView;
        oldWebView.navigationDelegate = nil;
        [oldWebView stopLoading];
        [oldWebView removeFromSuperview];
        self->_webView = nil;
        [self webView];
    });
}

- (WKWebView *)webView {
    if (_webView == nil) {
        WKWebViewConfiguration *config = [WKWebViewConfiguration new];
        // Through a weak handler: the configuration keeps its handlers, so registering self would keep
        // this Terminal (and its web view) alive after its session is gone.
        id <WKScriptMessageHandler> handler = [[WeakScriptMessageHandler alloc] initWithHandler:self];
        for (NSString *name in @[@"load", @"sendInput", @"resize", @"propUpdate"])
            [config.userContentController addScriptMessageHandler:handler name:name];
        // Make the web view really big so that if a program tries to write to the terminal before it's displayed, the text probably won't wrap too badly.
        CGRect webviewSize = CGRectMake(0, 0, 10000, 10000);
        _webView = [[CustomWebView alloc] initWithFrame:webviewSize configuration:config];
        _webView.navigationDelegate = self;
        _webView.scrollView.scrollEnabled = NO;
        NSURL *html = [NSBundle.mainBundle URLForResource:@"term" withExtension:@"html"];
        [_webView loadFileURL:html allowingReadAccessToURL:html.URLByDeletingLastPathComponent];
    }
    return _webView;
}

+ (Terminal *)createPseudoTerminal:(struct tty **)tty {
    *tty = pty_open_fake(&ios_pty_driver);
    if (IS_ERR(*tty))
        return nil;
    return (__bridge Terminal *) (*tty)->data;
}

- (void)setTty:(tty_t)tty {
    @synchronized (self) {
        _tty = tty;
    }
    dispatch_async(dispatch_get_main_queue(), ^{
        [self syncWindowSize];
    });
}

- (void)userContentController:(WKUserContentController *)userContentController didReceiveScriptMessage:(WKScriptMessage *)message {
    if ([message.name isEqualToString:@"load"]) {
        self.loaded = YES;
        [self syncWindowSize];
        [self.refreshTask schedule];
        // make sure this setting works if it's set before loading
        self.enableVoiceOverAnnounce = self.enableVoiceOverAnnounce;
    } else if ([message.name isEqualToString:@"sendInput"]) {
        [self sendInput:[message.body dataUsingEncoding:NSUTF8StringEncoding]];
    } else if ([message.name isEqualToString:@"resize"]) {
        [self syncWindowSize];
    } else if ([message.name isEqualToString:@"propUpdate"]) {
        [self setValue:message.body[1] forKey:message.body[0]];
    }
}

- (void)webView:(WKWebView *)webView didFailNavigation:(WKNavigation *)navigation withError:(NSError *)error {
    NSLog(@"the terminal's page failed to load: %@", error);
}

- (void)webView:(WKWebView *)webView didFailProvisionalNavigation:(WKNavigation *)navigation withError:(NSError *)error {
    NSLog(@"the terminal's page failed to load: %@", error);
}

// Routine when memory is short: a new web view brings the terminal back within a second.
- (void)webViewWebContentProcessDidTerminate:(WKWebView *)webView {
    self.loaded = NO;
    [self recoverTerminalWebView];
}

- (void)syncWindowSize {
    if (!self.loaded)
        return;
    [self.webView evaluateJavaScript:@"exports.getSize()" completionHandler:^(NSArray<NSNumber *> *dimensions, NSError *error) {
        if (error != nil || dimensions.count < 2)
            return;
        int cols = dimensions[0].intValue;
        int rows = dimensions[1].intValue;
        // Read the unowned back-pointer once. ios_tty_cleanup clears it from
        // the emulator thread, so re-reading self.tty for each use means the
        // NULL check guards nothing: the pointer could still be live here and
        // NULL by the time it is used.
        //
        // Read it rather than holding @synchronized(self) across tty->lock:
        // tty_release calls ops->cleanup (-> setTty: -> @synchronized(self))
        // with tty->lock already held, so taking those two in the other order
        // here would be an AB-BA deadlock.
        tty_t tty;
        @synchronized (self) {
            tty = self->_tty;
        }
        if (tty == NULL)
            return;
        // Not enough on its own: tty_release frees the struct, so even a
        // correctly-read pointer can go stale before it is dereferenced. Re-find
        // the tty under ttys_lock -- the lock that free is required to hold --
        // and hold a reference for as long as it is used. The value read above
        // is passed only as an identity token (compared, never dereferenced),
        // so a recycled pty number can't point this at another terminal's tty.
        tty = tty_lookup_ref(self.type, self.number, tty);
        if (tty == NULL)
            return;
        lock(&tty->lock, 0);
        tty_set_winsize(tty, (struct winsize_) {.col = cols, .row = rows});
        unlock(&tty->lock);
        tty_put(tty);
    }];
}

- (void)setEnableVoiceOverAnnounce:(BOOL)enableVoiceOverAnnounce {
    _enableVoiceOverAnnounce = enableVoiceOverAnnounce;
    if (!self.loaded)
        return;
    [self.webView evaluateJavaScript:[NSString stringWithFormat:@"term.setAccessibilityEnabled(%@)",
                                      enableVoiceOverAnnounce ? @"true" : @"false"]
                   completionHandler:nil];
}

- (int)sendOutput:(const void *)buf length:(int)len {
    lock(&_dataLock, 0);
    if (!NSThread.isMainThread) {
        if (!self.loaded) {
            // A terminal whose web view hasn't loaded (yet, or after a reclaim) may not drain for
            // a while. Keep a bounded tail of recent output instead of blocking guest writers
            // forever once the buffer fills.
            if (len > BUF_SIZE) {
                buf = (const char *) buf + (len - BUF_SIZE);
                len = BUF_SIZE;
                [_pendingData setLength:0];
            } else {
                NSUInteger needed = (NSUInteger) len;
                NSUInteger available = _pendingData.length >= BUF_SIZE ? 0 : (NSUInteger) (BUF_SIZE - _pendingData.length);
                if (needed > available) {
                    NSUInteger discard = MIN(_pendingData.length, needed - available);
                    [_pendingData replaceBytesInRange:NSMakeRange(0, discard) withBytes:NULL length:0];
                }
            }
        } else {
            // The main thread is the only one that can unblock this, so sleeping
            // here would be a deadlock. The only reason for this to be called on
            // the main thread is if input is echoed.
            while (_pendingData.length > BUF_SIZE)
                wait_for_ignore_signals(&_dataConsumed, &_dataLock, NULL);
        }
    }
    [_pendingData appendBytes:buf length:(NSUInteger) len];
    [self.refreshTask schedule];
    unlock(&_dataLock);
    return len;
}


// The control characters xterm and hterm strip out of a bracketed paste:
// everything below 0x20 except backspace, tab, newline and carriage return.
// ESC falls inside that range, which is what stops a payload from closing its
// own bracket.
static NSCharacterSet *ISHPasteForbiddenCharacters(void) {
    static NSCharacterSet *forbidden;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        NSMutableCharacterSet *set = [NSMutableCharacterSet new];
        [set addCharactersInRange:NSMakeRange(0x00, 0x08)];  // NUL .. BEL
        [set addCharactersInRange:NSMakeRange(0x0b, 0x02)];  // VT, FF
        [set addCharactersInRange:NSMakeRange(0x0e, 0x12)];  // SO .. US (incl. ESC)
        forbidden = [set copy];
    });
    return forbidden;
}

NSString *ISHPasteSequence(NSString *text, BOOL bracketed, BOOL execute) {
    NSString *body = [text ?: @"" stringByReplacingOccurrencesOfString:@"\n" withString:@"\r"];
    if (bracketed) {
        NSCharacterSet *forbidden = ISHPasteForbiddenCharacters();
        if ([body rangeOfCharacterFromSet:forbidden].location != NSNotFound)
            body = [[body componentsSeparatedByCharactersInSet:forbidden] componentsJoinedByString:@""];
        body = [NSString stringWithFormat:@"\x1b[200~%@\x1b[201~", body];
    }
    if (execute)
        body = [body stringByAppendingString:@"\r"];
    return body;
}

- (void)sendInput:(NSData *)input {
    // Cinder Lite: everything typed or pasted passes the filter first (autocorrect, and Enter on a
    // finished session); it returns what to send on, or nil to send nothing.
    if (self.inputFilter != nil) {
        input = self.inputFilter(input);
        if (input == nil)
            return;
    }
    [self sendRawInput:input];
}

- (void)sendRawInput:(NSData *)input {
    tty_t tty;
    @synchronized (self) {
        tty = self->_tty;
    }
    if (tty == NULL)
        return;
    // Hold a reference across tty_input rather than dereferencing the unowned
    // back-pointer, which tty_release can free between the check above and the
    // call (see -syncWindowSize). A reference rather than holding ttys_lock
    // across the call: this runs for every keystroke, and tty_input does line
    // discipline and signal delivery.
    tty = tty_lookup_ref(self.type, self.number, tty);
    if (tty != NULL)
        tty_input(tty, input.bytes, input.length, 0);
    if (self.loaded) {
        [self.webView evaluateJavaScript:@"exports.setUserGesture()" completionHandler:nil];
        [self.scrollToBottomTask schedule];
    }
    // Last, and after every use of self: if this drops the final reference,
    // tty_release runs ios_tty_cleanup, whose CFBridgingRelease releases the
    // tty's own strong reference to this Terminal.
    tty_put(tty);
}

- (void)requestRefresh {
    [self.refreshTask schedule];
    if (self.loaded)
        [self.scrollToBottomTask schedule];
}

- (void)scrollToBottom {
    if (!self.loaded)
        return;
    [self.webView evaluateJavaScript:@"exports.scrollToBottom()" completionHandler:nil];
}

- (NSString *)arrow:(char)direction {
    return [NSString stringWithFormat:@"\x1b%c%c", self.applicationCursor ? 'O' : '[', direction];
}

- (void)refresh {
    if (!self.loaded)
        return;

    lock(&_dataLock, 0);
    CFTimeInterval now = CACurrentMediaTime();
    if (_outputInProgress) {
        if (_outputStartedAt > 0 && now - _outputStartedAt > ISHTerminalOutputWatchdogSeconds) {
            // Watchdog: a previous evaluateJavaScript write has not reported
            // completion within the timeout. Crucially, do NOT re-queue the
            // in-flight data. evaluateJavaScript cannot be cancelled, so a write
            // that is merely slow (not failed) may still apply after we give up
            // waiting. Re-queuing it would then draw the same bytes a second time,
            // and a partial-redraw TUI like htop never rewrites cells it believes
            // are already correct -- so the duplicate produces doubled / garbled
            // regions (most visible in the fastest-updating area, e.g. the CPU
            // meters) that persist until a full repaint (^L). If the slow write did
            // apply, its bytes are already on screen; if the webview is genuinely
            // wedged, re-queuing to it would not have helped. Either way, just drop
            // the in-flight tracking and let the next batch proceed.
            self.inFlightData = nil;
            _outputInProgress = NO;
            _outputStartedAt = 0;
            self.outputGeneration++;
        } else {
            [self.refreshTask schedule];
            unlock(&_dataLock);
            return;
        }
    }
    NSData *data = _pendingData;
    _pendingData = [[NSMutableData alloc] initWithCapacity:BUF_SIZE];
    _outputInProgress = YES;
    self.inFlightData = data;
    _outputStartedAt = now;
    NSUInteger generation = ++self.outputGeneration;
    notify(&self->_dataConsumed);
    unlock(&_dataLock);

    if (data.length == 0) {
        lock(&_dataLock, 0);
        if (self.outputGeneration == generation) {
            _outputInProgress = NO;
            self.inFlightData = nil;
            _outputStartedAt = 0;
        }
        unlock(&_dataLock);
        return;
    }

    NSString *dataString = ISHJavaScriptLiteralForTerminalData(data);
    NSString *jsToEvaluate = [NSString stringWithFormat:@"exports.write(\"%@\")", dataString];
    [self.webView evaluateJavaScript:jsToEvaluate completionHandler:^(id result, NSError *error) {
        lock(&self->_dataLock, 0);
        if (self.outputGeneration == generation) {
            self->_outputInProgress = NO;
            self.inFlightData = nil;
            self->_outputStartedAt = 0;
        }
        unlock(&self->_dataLock);
        if (error != nil) {
            NSLog(@"error sending bytes to the terminal: %@", error);
            [self recoverTerminalWebView];
            return;
        }
        lock(&self->_dataLock, 0);
        bool hasPendingData = self->_pendingData.length > 0;
        unlock(&self->_dataLock);
        if (hasPendingData)
            [self.refreshTask schedule];
    }];
}

+ (Terminal *)terminalWithType:(int)type number:(int)number {
    return [[Terminal alloc] initWithType:type number:number];
}

- (int)type {
    return dev_major((dev_t_) self.terminalsKey.unsignedIntValue);
}

- (int)number {
    return dev_minor((dev_t_) self.terminalsKey.unsignedIntValue);
}

- (void)destroy {
    tty_t tty;
    @synchronized (self) {
        tty = self->_tty;
    }
    // Own a reference for as long as this method touches the tty. Everything
    // below dereferences it, and tty_release can free it concurrently -- see
    // -syncWindowSize for why the unowned back-pointer alone isn't enough.
    tty = tty_lookup_ref(self.type, self.number, tty);
    if (tty != NULL) {
        lock(&tty->lock, 0);
        tty_hangup(tty);
        unlock(&tty->lock);
    }
    @synchronized (Terminal.class) {
        [terminals removeObjectForKey:self.terminalsKey];
    }
    // Drop the reference taken above, last and after every use of self: if this
    // drops the final reference, tty_release runs ios_tty_cleanup, whose
    // CFBridgingRelease releases the tty's own strong reference to this
    // Terminal -- which for a terminal being destroyed may well be the last one.
    tty_put(tty);
}

+ (void)initialize {
    if (self == Terminal.class) {
        terminals = [NSMapTable strongToWeakObjectsMapTable];
    }
}

@end


static int ios_tty_init(struct tty *tty) {
    // This is called with ttys_lock but that results in deadlock since the main thread can also acquire ttys_lock. So release it.
    unlock(&ttys_lock);
    void (^init_block)(void) = ^{
        Terminal *terminal = [Terminal terminalWithType:tty->type number:tty->num];
        tty->data = (void *) CFBridgingRetain(terminal);
        terminal.tty = tty;
    };
    if ([NSThread isMainThread])
        init_block();
    else
        dispatch_sync(dispatch_get_main_queue(), init_block);

    lock(&ttys_lock, 0);
    return 0;
}

static int ios_tty_write(struct tty *tty, const void *buf, size_t len, bool blocking) {
    Terminal *terminal = (__bridge Terminal *) tty->data;
    return [terminal sendOutput:buf length:(int) len];
}

static void ios_tty_cleanup(struct tty *tty) {
    Terminal *terminal = CFBridgingRelease(tty->data);
    tty->data = NULL;
    terminal.tty = NULL;
}

struct tty_driver_ops ios_tty_ops = {
    .init = ios_tty_init,
    .write = ios_tty_write,
    .cleanup = ios_tty_cleanup,
};
DEFINE_TTY_DRIVER(ios_console_driver, &ios_tty_ops, TTY_CONSOLE_MAJOR, 64);
struct tty_driver ios_pty_driver = {.ops = &ios_tty_ops};
