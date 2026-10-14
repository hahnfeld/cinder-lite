# Third-party software in Cinder Lite

Cinder Lite's own code is MIT licensed. The APK also contains the components below; their license
texts are in this directory. Corresponding source for each is at the link given.

| Component | Version | License | Source |
|---|---|---|---|
| proot (+ `loader`) | 5.1.107.96 — Termux package `proot_5.1.107.96_aarch64.deb` | GPL-2.0 (`GPL-2.0.txt`) | https://github.com/termux/proot/archive/refs/tags/v5.1.107.96.zip |
| libtalloc (`libtalloc.so.2`) | talloc 2.5.0 — Termux package `libtalloc_2.5.0_aarch64.deb` (sha256 `556591f4…c58ad5a7`) | LGPL-3.0 (`LGPL-3.0.txt`, `GPL-3.0.txt`) | https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz — build recipe: https://github.com/termux/termux-packages/tree/master/packages/libtalloc |
| libandroid-shmem | 0.7 — Termux package `libandroid-shmem_0.7_aarch64.deb` | BSD-3-Clause (`libandroid-shmem-LICENSE.txt`) | https://github.com/termux/libandroid-shmem/tree/v0.7 |
| Alpine Linux minirootfs (`rootfs.tgz`: busybox, musl, apk-tools, alpine-baselayout, …) | 3.24.2, aarch64 (official `alpine-minirootfs-3.24.2-aarch64.tar.gz`) | GPL-2.0 (busybox, apk-tools), MIT (musl, `musl-COPYRIGHT.txt`), others per package | https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/ — package sources: https://gitlab.alpinelinux.org/alpine/aports/-/tree/v3.24.2 |
| Termux terminal-view / terminal-emulator (incl. `libtermux.so`, built from `termux.c` in this repo) | 0.118.0 | Apache-2.0 (`Apache-2.0.txt`) | https://github.com/termux/termux-app/tree/v0.118.0 |
| Kotlin standard library, AndroidX annotation | per build | Apache-2.0 (`Apache-2.0.txt`) | https://github.com/JetBrains/kotlin, https://android.googlesource.com/platform/frameworks/support |

Packages installed later (by first-run setup or by `apk add` / `termux-install`) are downloaded
from Alpine's and Termux's own repositories at that time, under their own licenses, and are not
part of this APK.

**Claude Code is not included.** It is Anthropic's software under its own terms
(https://code.claude.com/docs/en/legal-and-compliance) and is downloaded at runtime from
Anthropic's npm package. Claude and Claude Code are trademarks of Anthropic PBC; Cinder Lite is
unofficial and not endorsed by or affiliated with Anthropic.
