// Cinder Lite: a stand-in for iSH-AOK's UserPreferences, with only the settings its terminal reads.
// Cinder has no settings screen; the font size follows pinch zoom (see TerminalController.swift).
#import <Foundation/Foundation.h>
#import "Theme.h"

NS_ASSUME_NONNULL_BEGIN

typedef NS_ENUM(NSInteger, CapsLockMapping) {
    CapsLockMapNone = 0,
    CapsLockMapControl,
    CapsLockMapEscape,
};

typedef NS_ENUM(NSInteger, OptionMapping) {
    OptionMapNone = 0,
    OptionMapEsc,
};

@interface UserPreferences : NSObject
+ (instancetype)shared;
@property (nonatomic) NSNumber *fontSize;
@property (readonly) NSString *fontFamily;
@property (readonly) NSNumber *lineHeight;
@property (readonly) BOOL blinkCursor;
@property (readonly) NSString *htermCursorShape;
@property (readonly) Palette *palette;
@property (readonly) Theme *theme;
@property (readonly) CapsLockMapping capsLockMapping;
@property (readonly) OptionMapping optionMapping;
@property (readonly) BOOL backtickMapEscape;
@property (readonly) BOOL overrideControlSpace;
@end

NS_ASSUME_NONNULL_END
