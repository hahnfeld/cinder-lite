//
//  Terminal.h
//  iSH
//
//  Created by Theodore Dubois on 10/18/17.
//

#import <UIKit/UIKit.h>
#import <WebKit/WebKit.h>

// Cinder Lite annotates inputFilter below; the rest of this header predates nullability.
#pragma clang diagnostic ignored "-Wnullability-completeness"

struct tty;

@interface Terminal : NSObject

+ (Terminal *)terminalWithType:(int)type number:(int)number;
// Returns a strong struct tty and a Terminal that has a weak reference to the same tty
+ (Terminal *)createPseudoTerminal:(struct tty **)tty;

@property (readonly) int type;
@property (readonly) int number;

- (int)sendOutput:(const void *)buf length:(int)len;
- (void)sendInput:(NSData *)input;
// Cinder Lite: input that skips inputFilter (what the filter itself types, like autocorrect's fixes).
- (void)sendRawInput:(NSData *)input;
@property (copy, nullable) NSData * _Nullable (^inputFilter)(NSData * _Nonnull input);

// Bracketed paste mode (DECSET 2004) as hterm currently has it, pushed up from
// the web view every time the guest turns it on or off. It says how to deliver
// a PASTE and nothing about ordinary keystrokes: those are typing, and typing
// is never bracketed.
@property (nonatomic) BOOL bracketedPasteEnabled;
- (void)requestRefresh;

- (NSString *)arrow:(char)direction;

// Make this terminal no longer be the singleton terminal with its type and number. Will happen eventually if all references go away, but sometimes you want it to happen now.
- (void)destroy;

@property (readonly) WKWebView *webView;
@property (nonatomic) BOOL enableVoiceOverAnnounce;
// Use KVO on this
@property (readonly) BOOL loaded;

@end

// Wraps `text` the way a terminal is meant to deliver a paste, given whether the
// guest currently has bracketed paste mode on, and appends the carriage return
// that RUNS the result when `execute` is set.
//
// More than a concatenation, in three ways. A newline inside pasted text is a
// carriage return on the command line, which is how hterm's own paste handler
// writes it. When the mode is on the payload is filtered of the control
// characters that could end the bracket early -- an \e[201~ smuggled inside a
// snippet would otherwise hand everything after it to the shell as typed input,
// which is a command the user did not choose to run -- keeping the same
// whitespace (\b \t \n \r) that xterm and hterm keep. And the execute
// newline goes OUTSIDE the closing bracket: inside, the shell takes it as
// literal text in the pasted block and the line never runs at all, which is the
// whole point of bracketing.
//
// Exposed for the unit test.
extern NSString *ISHPasteSequence(NSString *text, BOOL bracketed, BOOL execute);

extern struct tty_driver ios_console_driver;

// Forwards a web view's script messages to `handler` without keeping it alive: a WKUserContentController
// holds its handlers strongly.
@interface WeakScriptMessageHandler : NSObject <WKScriptMessageHandler>
@property (weak) id <WKScriptMessageHandler> handler;
- (instancetype)initWithHandler:(id <WKScriptMessageHandler>)handler;
@end
