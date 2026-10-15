// Cinder Lite: a stand-in for iSH-AOK's Theme.h, with only what TerminalView reads. One fixed
// palette, Cinder's own colors (the Android app's).
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface Palette : NSObject
@property (readonly) NSString *foregroundColor;
@property (readonly) NSString *backgroundColor;
@property (readonly, nullable) NSArray<NSString *> *colorPaletteOverrides;
@end

@interface Theme : NSObject
@property (readonly) Palette *lightPalette;
@property (readonly) Palette *darkPalette;
@end

NS_ASSUME_NONNULL_END
