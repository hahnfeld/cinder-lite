# Third-party software in Cinder Lite for iOS

Cinder Lite for iOS is GPL-3.0 (`GPL-3.0.txt`; the repository's `ios/LICENSE`), because it is built on
iSH-AOK. The full source of this app, including the exact iSH-AOK commit it builds, is at
https://github.com/hahnfeld/cinder-lite (`ios/`).

## Bundled components

| Component | Used for | License | Source |
|---|---|---|---|
| iSH-AOK, commit `651f974a` | the emulator (`libish`, `libish_emu`, `libfakefs`, built from source) and, adapted, the terminal and devices in `CinderLite/Engine/` (Terminal, TerminalView, ScrollbarView, DelayedUITask, URLDevice) and `terminal/term.*`. Copyright Theodore Dubois, Michael Miller and the iSH and iSH-AOK contributors | GPL-3.0 (`GPL-3.0.txt`), with iSH's additional permission for app stores (below) | https://github.com/emkey1/ish-AOK |
| hterm (libapps) | the terminal emulator in the web view (`hterm_all.js`) | BSD-3-Clause (`hterm-libapps-LICENSE.txt`) | https://chromium.googlesource.com/apps/libapps, as vendored in iSH-AOK `deps/libapps` |
| libarchive | unpacking the Alpine root and Claude Code's npm package | BSD-2-Clause (`libarchive-COPYING.txt`) | https://github.com/libarchive/libarchive, as vendored in iSH-AOK `deps/libarchive` |
| liblzma (XZ Utils) 5.8.3 | `.xz` support in libarchive | 0BSD (`0BSD-liblzma.txt`) | https://tukaani.org/xz/, as vendored in iSH-AOK `deps/liblzma-static` |
| Noto Sans Symbols 2 (`symbols.ttf`) | the terminal's symbol fallback: a subset of `NotoSansSymbols2-Regular.ttf` (arrows, technical symbols, shapes, dingbats; no emoji) | SIL OFL 1.1 (`OFL-1.1-Noto-Sans-Symbols-2.txt`) | https://github.com/notofonts/symbols |
| iCTRL protocol client | `phone`'s request envelope (`Guest/bin/phone`), adapted from iCTRL's reference client `Scripts/ictrl_rpc.py`, by the iCTRL authors (OpenMinis) | Apache-2.0 (`Apache-2.0.txt`) | https://github.com/OpenMinis/iCTRL |

iSH's additional permission (`LICENSE.IOS` in iSH-AOK): the iSH developers will not pursue a license
violation that results solely from the conflict between the GNU GPL and the Apple App Store terms of
service, as long as the GPL is complied with in all other respects, including providing users with
source code and the text of the license.

## Alpine Linux minirootfs (`rootfs.tgz`)

The official `alpine-minirootfs-3.24.2-aarch64.tar.gz` (sha256 `9bf70a7f…2ce773`), unmodified. It
contains these Alpine packages:

| Package | Version | License |
|---|---|---|
| alpine-baselayout, alpine-baselayout-data | 3.7.2-r1 | GPL-2.0-only |
| alpine-keys | 2.6-r0 | MIT |
| alpine-release | 3.24.2-r0 | MIT |
| apk-tools, libapk | 3.0.8-r0 | GPL-2.0-only |
| busybox, busybox-binsh, ssl_client | 1.37.0-r31 | GPL-2.0-only |
| ca-certificates-bundle | 20260909-r0 | MPL-2.0 AND MIT (`MPL-2.0.txt`) |
| libcrypto3, libssl3 (OpenSSL) | 3.5.8-r0 | Apache-2.0 |
| musl | 1.2.6-r2 | MIT (`musl-COPYRIGHT.txt`) |
| musl-utils | 1.2.6-r2 | MIT AND BSD-2-Clause AND GPL-2.0-or-later (`musl-utils-BSD-2-Clause.txt`) |
| scanelf | 1.3.9-r1 | GPL-2.0-only |
| zlib | 1.3.2-r0 | Zlib (`zlib-LICENSE.txt`) |

Corresponding source: the build recipes and patches are in Alpine's aports repository at tag
`v3.24.2` (https://gitlab.alpinelinux.org/alpine/aports/-/tree/v3.24.2), and the upstream source
archives they build from are mirrored by Alpine at https://distfiles.alpinelinux.org/distfiles/v3.24/.

## Not part of this app

Packages installed later (by first-run setup or `apk add`) are downloaded from Alpine's own
repositories at that time, under their own licenses. iCTRL, which `phone` talks to, is a separate app
the user installs.

**Claude Code is not included.** It is Anthropic's software under its own terms
(https://code.claude.com/docs/en/legal-and-compliance), downloaded from Anthropic's official npm
package only when the user agrees to it on first run.
