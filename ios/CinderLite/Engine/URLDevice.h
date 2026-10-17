//
//  URLDevice.h
//  iSH-AOK
//
//  /dev/url: write a URL, iOS opens it.
//

#ifndef URLDevice_h
#define URLDevice_h

#include "fs/dev.h"

// The /dev/url character device. Writing a URL to it hands that URL to iOS,
// which routes it to whatever app claims the scheme -- the same thing tapping
// a link does. Reads return nothing; there is no state to read back.
//
// This is a device rather than a command on purpose: it composes with
// redirection and pipes the way a shell expects, and it needs no new binary in
// the guest filesystem.
extern struct dev_ops url_dev;

#ifdef __OBJC__
#import <Foundation/Foundation.h>
// Whether the sandbox may open `url`: /dev/url and links tapped in the terminal
// (which the sandbox prints) both ask. https, plus the schemes enabled modules
// need (Phone Use: ictrl, for iCTRL's runner), only. Anything in the sandbox can
// ask, and other schemes reach payment, messaging and other apps' deep links. An
// https link can still open an app that claims its domain, as on Android.
BOOL CinderURLAllowed(NSURL *url);
#endif

#endif /* URLDevice_h */
