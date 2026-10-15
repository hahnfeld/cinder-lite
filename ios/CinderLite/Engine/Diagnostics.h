// Cinder Lite: iSH-AOK's diagnostics store, reduced to a no-op (Cinder keeps no breadcrumbs).
#import <Foundation/Foundation.h>

@interface ISHDiagnosticsStore : NSObject
+ (void)recordBreadcrumb:(NSString *)event details:(NSDictionary *)details;
@end
