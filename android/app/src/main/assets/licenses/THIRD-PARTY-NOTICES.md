# Third-party software in Cinder Lite

Cinder Lite is a fork of [Cinder](https://huggingface.co/SmallAICreator/cinder) by SmallAICreator.
Its own code is MIT licensed (see `LICENSE` in the source repository,
https://github.com/hahnfeld/cinder-lite). The APK also contains the components below. Their license
texts are in this directory, and corresponding source for each is at the link given.

## Bundled components

| Component | Version | License | Source |
|---|---|---|---|
| proot (+ `loader`) | 5.1.107.96 — Termux package `proot_5.1.107.96_aarch64.deb` | GPL-2.0 (`GPL-2.0.txt`) | https://github.com/termux/proot/archive/refs/tags/v5.1.107.96.zip |
| libtalloc (`libtalloc.so.2`) | talloc 2.5.0 — Termux package `libtalloc_2.5.0_aarch64.deb` (sha256 `556591f4…c58ad5a7`) | LGPL-3.0 (`LGPL-3.0.txt`, `GPL-3.0.txt`) | https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz — build recipe: https://github.com/termux/termux-packages/tree/master/packages/libtalloc |
| libandroid-shmem | 0.7 — Termux package `libandroid-shmem_0.7_aarch64.deb` | BSD-3-Clause (`libandroid-shmem-LICENSE.txt`) | https://github.com/termux/libandroid-shmem/tree/v0.7 |
| Termux terminal-view / terminal-emulator | 0.118.0 | Apache-2.0 (`Apache-2.0.txt`) | https://github.com/termux/termux-app/tree/v0.118.0 |
| `libtermux.so` | built from termux-app v0.118.0 `terminal-emulator/src/main/jni/termux.c`, unmodified | Apache-2.0 (`Apache-2.0.txt`) | `app/src/main/cpp/termux.c` in the Cinder Lite repository |
| Shizuku API (`dev.rikka.shizuku:api`, `provider`) | 13.1.5 | MIT (`Shizuku-API-LICENSE.txt`) | https://github.com/RikkaApps/Shizuku-API |
| Kotlin standard library, AndroidX annotation | per build | Apache-2.0 (`Apache-2.0.txt`) | https://github.com/JetBrains/kotlin, https://android.googlesource.com/platform/frameworks/support |
| Noto Sans Symbols 2 (`fonts/symbols.ttf`) | the terminal's symbol fallback: a subset of `NotoSansSymbols2-Regular.ttf` (arrows, technical symbols, shapes, dingbats; no emoji) | SIL OFL 1.1 (`OFL-1.1-Noto-Sans-Symbols-2.txt`) | https://github.com/notofonts/symbols |

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

## Not part of this APK

Packages installed later — by first-run setup, `apk add`, `termux-install` or the toolchain
installers — are downloaded from Alpine's and Termux's own repositories at that time, under their own
licenses.

**Claude Code is not included.** It is Anthropic's software under its own terms
(https://code.claude.com/docs/en/legal-and-compliance) and is downloaded at runtime from Anthropic's
npm package. Claude and Claude Code are trademarks of Anthropic PBC; Cinder Lite is unofficial and
not endorsed by or affiliated with Anthropic.
