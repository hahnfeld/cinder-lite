#import "UserPreferences.h"

@implementation UserPreferences

+ (instancetype)shared {
    static UserPreferences *shared;
    static dispatch_once_t once;
    dispatch_once(&once, ^{ shared = [UserPreferences new]; });
    return shared;
}

- (instancetype)init {
    if ((self = [super init])) {
        NSNumber *saved = [NSUserDefaults.standardUserDefaults objectForKey:@"fontSize"];
        _fontSize = saved ?: @12;
    }
    return self;
}

- (void)setFontSize:(NSNumber *)fontSize {
    _fontSize = fontSize;
    [NSUserDefaults.standardUserDefaults setObject:fontSize forKey:@"fontSize"];
}

// Menlo first, then the bundled symbols font for what it lacks (Claude Code's ⏵⏵ mode marker).
- (NSString *)fontFamily { return @"Menlo, CinderSymbols, monospace"; }
- (NSNumber *)lineHeight { return @1.0; }
- (BOOL)blinkCursor { return NO; }
- (NSString *)htermCursorShape { return @"BLOCK"; }
- (NSString *)foregroundColor { return @"#E6E1D8"; }
- (NSString *)backgroundColor { return @"#15130F"; }   // the Android app's window color

@end
