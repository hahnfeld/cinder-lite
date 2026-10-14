---
license: mit
tags:
  - android
  - claude-code
  - mobile-app
  - proot
  - sandbox
---

# Cinder Lite

> A fork of [Cinder](https://huggingface.co/SmallAICreator/cinder) by SmallAICreator. Installs as
> `app.cinderlite`, so it can sit alongside the original.

**Claude Code on an Android phone, in its own terminal UI.** Anthropic's official `claude` binary
runs inside a self-contained Linux sandbox that ships with the app, and its interactive TUI is shown
in a real terminal emulator (Termux's). Where upstream Cinder re-renders everything natively over
stream-json, Cinder Lite is just the sandbox, the downloader, and a terminal — three Kotlin files
and a ~7 MB APK.

> Unofficial and unaffiliated with Anthropic. Sideload only. arm64-v8a, Android 11+.

<br>

## What it is (and what it isn't)

Cinder Lite is a **front-end**. It does not contain, copy, or reimplement Claude Code. The `claude`
binary is Anthropic's, published on the public npm registry, and **the app downloads it at runtime
from npm** — exactly as it would install on a laptop — and checks it against the sha512 npm publishes
before running it. You then sign in with **your own** Anthropic account through the CLI's own login.
Cinder Lite provides a Linux environment for the binary to run in, and a terminal to show it.

| Layer | What it is |
|---|---|
| UI | Termux `terminal-view` showing the `claude` TUI, plus a row of keys phones lack (esc, tab, ⇧tab, ctrl, arrows) and a ⋮ menu |
| Sandbox | proot + an Alpine Linux 3.24 (musl, aarch64) rootfs, in app-private storage; phone storage at `/sdcard` |
| Engine | Anthropic's `claude` binary, a pinned release — **downloaded at runtime and verified, never bundled** |
| Auth | The CLI's own `/login`. Its browser hand-off goes through an `xdg-open` shim that opens the phone's browser |

<br>

## Features

- **The real Claude Code TUI** — model picker, permission prompts, `/resume`, slash commands and
  everything else come from Claude Code itself
- **Verified downloader** — installs a pinned `linux-arm64-musl` release from npm and checks its
  sha512; an app update that bumps the pin re-downloads automatically
- **Phone files** — grant storage access once and the phone's shared storage is `/sdcard` inside
  the sandbox; ask Claude to save things to `/sdcard/Download`
- **Survives being backgrounded** — a foreground service and wake lock hold the process, since the
  CLI is a child of it. Stop it from the notification or ⋮ → Stop
- **⋮ menu** — switch between Claude and a Shell (bash in the same sandbox; both keep running and
  keep their scrollback) · Restart the one you're looking at · Stop · Reinstall sandbox (a clean
  reset that keeps your login and `/workspace`)
- **Package management without root** — `apk add`, `npm -g`, `pip install`, plus Android build and
  APK decompile tools

**Storage:** the APK is ~7 MB, but after first-run setup the sandbox takes about 500 MB (Claude Code
is ~240 MB, plus the rootfs and base tools). Installing the Android toolchains adds ~200 MB of the
device's own libraries plus the tools themselves.

**Trust model:** Claude Code and anything it runs can read and write everything under `/sdcard`
once storage access is granted, and can reach the network. That's the point — but use Claude Code's
own permission prompts accordingly.

<br>

## The engineering: six problems worth writing down

Most of the work (done upstream in Cinder) was making a 250 MB Linux binary run on Android at all.
Each of these first presented as something unrelated.

### 1. The CLI is dynamically linked against musl

Claude Code ships one prebuilt native binary per platform — no source to cross-compile, no Android
target. The `linux-arm64-musl` build is the closest fit, but its ELF headers show it is *dynamic*:
its interpreter is `/lib/ld-musl-aarch64.so.1`, a loader at an absolute path Android doesn't have and
won't let you create without root. Hence proot with an Alpine rootfs — and because it's *musl*, that
rootfs ships as a 4 MB tarball, not a 400 MB Debian.

### 2. `targetSdk = 28`, deliberately

Apps targeting API 29+ cannot execute binaries from their own writable data directory, which the
sandbox depends on. Termux solved it the same way. This is why Cinder Lite is sideload-only — the
Play Store requires a current target API.

### 3. aapt silently renames `.gz` assets

The rootfs shipped as `assets/rootfs.tar.gz`, but the build tool gunzips and renames such assets, so
it arrived as `assets/rootfs.tar` — and opening `rootfs.tar.gz` threw FileNotFoundException. Bootstrap
failed on every launch while everything looked fine. Renaming to `rootfs.tgz` plus `noCompress` fixed it.

### 4. Claude Code wants a real terminal

`claude auth login` puts the terminal in raw mode and reads keystrokes directly; given a pipe it
never reads stdin. Upstream solved it with a small forkpty JNI library just for login. Cinder Lite
runs everything in a PTY via Termux's terminal-emulator, so the TUI, login and resizing all just work.

### 5. Android has no SysV IPC — so `apk` needed a workaround

Alpine's minirootfs ships no `/etc/resolv.conf`, so every request died with a DNS timeout until
nameservers were written in from ConnectivityManager. Then apk failed its database write: apk-tools v3
locks the DB with a SysV semaphore, and Android kernels are built without `CONFIG_SYSVIPC`. The
workaround: run apk under a nested proot `-0` (userspace fake root), which extracts the full
dependency tree correctly — the only failure is the final DB bookkeeping, which happens after every
file is on disk. The side effect: apk can't remember what it installed, so `apk del` doesn't work.

### 6. A long linker path can panic Android 15 kernels

`sudo`, `apk add` and every Termux binary run a copy of Android's `linker64` staged by the app.
Android 15 kernels with 16 KB page-size compatibility check whether a `madvise()` comes from the
linker by resolving its file path into a small buffer, and on some builds (seen on the
`6.6.30-android15-8` emulator kernel) don't handle the path being too long: the kernel dereferences
an error pointer and panics, rebooting the device. Upstream staged the linker at a 70+ character
path. Cinder Lite keeps the guest path (`/usr/local/androidlibexec/linker64`) but stores the file at
a short host path (`files/lx/`) bound in there.

<br>

## Installing packages inside the sandbox

| Need | Command |
|---|---|
| Alpine packages | `apk add <pkg>` (stable); `apk add <pkg>@edge` / `<pkg>@testing` for rolling ones |
| Node modules | `npm install -g <pkg>` |
| Python modules | `pip install <pkg>` |
| Android build tools | `android-sdk-install` (or `termux-install <pkg>`) |
| APK decompile tools | `apk-tools-install` (apktool + jadx) |

Nothing runs as device root. proot's `-0` is userspace fake root — no Magisk, no unlocked bootloader,
no kernel involvement.

**Building Android apps on the phone:** `android-sdk-install` sets up Alpine's OpenJDK 17 (`javac`,
`keytool`), Termux's aarch64 `aapt2`, the `d8` and `apksigner` jars, and Google's `android.jar` —
a `javac` → `d8` → `aapt2` → `apksigner` pipeline. Google's own SDK build-tools and NDK are x86-64
binaries and can't run on an ARM phone, and neither can Gradle-based Android builds (the Android Gradle
Plugin fetches x86-64 tools). The first Termux install asks the app to copy the device's bionic
libraries (~200 MB) into the sandbox, so keep the app open while it runs. apktool and jadx run on
Alpine's JDK too.

<br>

## Building

Needs JDK 17+, the Android SDK with platform 37 and NDK r30 (`30.0.16248370`, for the small
`libtermux.so`), and **Git LFS** — `proot` and `rootfs.tgz` are LFS objects, and a clone without LFS
builds an APK full of pointer files that fails on first run. Gradle 9.8 / AGP 9.4 / Kotlin 2.4.

```sh
git lfs install            # once per machine
git clone <this repo> && cd cinder-lite
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

**Signing.** Release builds are signed with the key named in `local.properties` (gitignored):

```properties
cinder.storeFile=keystore/cinder-lite-release.jks
cinder.storePassword=…
cinder.keyAlias=cinder-lite
cinder.keyPassword=…
```

Without those lines `assembleRelease` produces an unsigned APK. Every update must be signed with the
same key, or Android refuses to install it over the previous version — back the keystore up. Don't
hand out debug builds: they're debuggable, so anyone with adb access to the phone could read the
Claude Code login out of the app's storage.

**Bumping Claude Code.** `CLAUDE_VERSION` and `CLAUDE_SHA512` in `Sandbox.kt` pin the release. Take
both from `https://registry.npmjs.org/@anthropic-ai/claude-code-linux-arm64-musl/<version>`
(`version` and `dist.integrity`), test, and ship; installed apps re-download on next launch.

**First run:** allow storage access, then wait while the app unpacks the sandbox, downloads Claude
Code (~240 MB from npm, wifi recommended) and installs bash, git, Python, Node.js and friends. Claude Code then
starts; type `/login`.

**Installing on Android 16–17.** Android 17 still installs apps targeting API 24+, so targetSdk 28 is
fine, and the app has been tested on Android 17 (API 37). Two platform policies affect sideloading it,
not the code:
- *Developer verification.* Since September 2026 (Brazil, Indonesia, Singapore, Thailand; globally
  from 2027), certified devices only install apps from developers registered with Google, including
  sideloaded ones. Unverified apps need an "advanced" install flow with a waiting period. To avoid
  that for your users, register in the Android Developer Console and register the `app.cinderlite`
  package name with your release signing key.
- *Advanced Protection mode*, if a user turns it on, blocks installing apps from outside an app store.

**16 KB page sizes.** Every 64-bit native binary the app ships is 16 KB-aligned, so it runs natively
on 16 KB-page devices. That includes `libtermux.so`: Termux's published build (0.118.0) is 4 KB-aligned,
so the app builds it from the same release's `termux.c` (`app/src/main/cpp/`) and packages that copy
instead.

<br>

## Licensing and attribution

Application code in this repository is MIT licensed. The APK bundles open-source components —
proot (GPL-2.0), libtalloc (LGPL-3.0), libandroid-shmem (BSD-3-Clause), the Alpine Linux 3.24.2
minirootfs (busybox and apk-tools GPL-2.0, musl MIT, …) and Termux terminal-view/terminal-emulator
(Apache-2.0). Their versions, exact source links and license texts are in
[`app/src/main/assets/licenses/`](app/src/main/assets/licenses/THIRD-PARTY-NOTICES.md), which ships
inside the APK.

**Claude Code itself is not included.** It is Anthropic's software under its own terms
(https://code.claude.com/docs/en/legal-and-compliance) and is downloaded at runtime by the user, from
Anthropic's official npm package. Claude and Claude Code are trademarks of Anthropic PBC; this
project is unofficial and not endorsed by or affiliated with Anthropic.
