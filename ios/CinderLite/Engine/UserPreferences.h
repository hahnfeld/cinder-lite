// Cinder Lite: a stand-in for iSH-AOK's UserPreferences, with only the settings its terminal reads.
// Cinder has no settings screen; the font size follows pinch zoom (see TerminalController.swift), and
// the colors are fixed, Cinder's own (the Android app's).
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface UserPreferences : NSObject
+ (instancetype)shared;
@property (nonatomic) NSNumber *fontSize;
@property (readonly) NSString *fontFamily;
@property (readonly) NSNumber *lineHeight;
@property (readonly) BOOL blinkCursor;
@property (readonly) NSString *htermCursorShape;
@property (readonly) NSString *foregroundColor;
@property (readonly) NSString *backgroundColor;
@end

NS_ASSUME_NONNULL_END
