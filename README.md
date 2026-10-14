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

**A lighter-weight fork of [Cinder](https://huggingface.co/SmallAICreator/cinder) by SmallAICreator.**

Cinder Lite is by Matt Hahnfeld: https://github.com/hahnfeld/cinder-lite

Cinder is the project that worked out how to run Anthropic's real Claude Code CLI on an Android
phone: a proot + Alpine sandbox, the musl binary, the targetSdk 28 trick, the SysV-IPC workaround for
`apk`, and a full native chat UI on top. All of that groundwork is Cinder's, and this fork keeps its
history (upstream is the first 24 commits).

Cinder Lite keeps Cinder's sandbox and drops its custom UI. Instead of driving `claude --print` over
stream-json and re-rendering the conversation in Jetpack Compose, it shows **Claude Code's own
terminal UI in a real terminal emulator** (Termux's). Everything the TUI already does (model
picker, permission prompts, `/resume`, slash commands) comes from Claude Code itself, so the app
is just the sandbox, a verified downloader and a terminal: three Kotlin files and a ~4.5 MB APK.
It installs as `app.cinderlite`, so it can sit alongside the original.

> Unofficial and unaffiliated with Anthropic. Sideload only. arm64-v8a, Android 11+.

<br>

## What it is (and what it isn't)

Cinder Lite is a **front-end**. It does not contain, copy, or reimplement Claude Code. The `claude`
binary is Anthropic's, published on the public npm registry, and **the app downloads it at runtime
from npm** — exactly as it would install on a laptop — checks it against the sha512 npm publishes,
and lets Claude Code's own `claude install` set it up. From then on Claude Code updates itself, as it
does on a laptop. You then sign in with **your own** Anthropic account through the CLI's own login.
Cinder Lite provides a Linux environment for the binary to run in, and a terminal to show it.

| Layer | What it is |
|---|---|
| UI | Termux `terminal-view` showing the `claude` TUI, plus a row of keys phones lack (esc, tab, ⇧tab, ctrl, arrows), a 🎤 voice-typing key and a ⋮ menu |
| Sandbox | proot + an Alpine Linux 3.24 (musl, aarch64) rootfs, in app-private storage; phone storage at `/sdcard` |
| Engine | Anthropic's `claude` binary, the latest release — **downloaded at runtime and verified, never bundled**; it updates itself |
| Auth | The CLI's own `/login`. Its browser hand-off goes through an `xdg-open` shim that opens the phone's browser |

<br>

## What changed from Cinder

|  | Cinder | Cinder Lite |
|---|---|---|
| UI | Native Compose chat transcript, tool cards, diffs, markdown/LaTeX | Claude Code's own TUI in Termux's terminal view |
| Talks to Claude via | `claude --print` + stream-json | An interactive PTY session |
| Model, permissions, history | App's own pickers and session store | Claude Code's `/model`, `/permissions`, `/resume` |
| Shell | One-shot commands on Android's host shell | A real bash in the sandbox, kept alongside Claude |
| Phone files | Upload/export buttons | Shared storage mounted at `/sdcard` |
| Claude Code install | Latest from npm, unverified | Latest from npm, sha512-verified, then Claude Code's native install with self-updates |
| Code | ~6,500 lines of Kotlin + JNI PTY library | ~1,150 lines in 3 Kotlin files |
| Release APK | ~33 MB (debug) | ~4.5 MB, signed, R8-shrunk |

**Removed:** the stream-json bridge and transcript UI, markdown/LaTeX rendering, model/effort/
permission pickers, the session-history drawer, the custom JNI PTY library, the in-app `notify`,
`preview` and `install-apk` bridges, file upload/export, voice dictation, the diagnostics screen and
the 32-bit proot loader.

**Added or changed:**
- Two terminals, Claude and a shell, switched from the ⋮ menu without losing either.
- An extra-keys row for what phone keyboards lack: esc, tab, ⇧tab (Claude Code's permission-mode
  switch), ctrl, arrows, and 🎤 for voice typing through the phone's own speech recognizer.
- `/login` opens the phone's browser through an `xdg-open` shim limited to http(s) links.
- First-run setup that can't run twice at once, resumes after interruption, shows download and
  install progress, and preinstalls bash, git, ripgrep, Python, Node.js and common tools.
- Sandbox helper scripts live as real files in `assets/helpers/` instead of Kotlin strings.
- `apk` reports real failures (exit codes) and streams its output; edge/testing repos are opt-in.
- Android build tools on demand without Gradle or duplicate JDKs: `javac` → `d8` → `aapt2` →
  `apksigner`, verified by building and installing an APK on a phone.
- Device fixes: a kernel panic on Android 15 from the staged linker's path length, the Android
  linker's config warning, 16 KB page-size support (`libtermux.so` built from source).
- Updated toolchain and runtime: AGP 9.4, Gradle 9.8, Kotlin 2.4, compileSdk 37 (Android 17),
  proot 5.1.107.96, talloc 2.5.0, Alpine 3.24.2. Third-party licences ship in the APK.

<br>

## Features

- **The real Claude Code TUI** — model picker, permission prompts, `/resume`, slash commands and
  everything else come from Claude Code itself
- **Always the latest Claude Code** — first run installs npm's current `linux-arm64-musl` release
  (checked against its sha512) through Claude Code's own `claude install`; after that Claude Code
  keeps itself up to date
- **Phone files** — grant storage access once and the phone's shared storage is `/sdcard` inside
  the sandbox; ask Claude to save things to `/sdcard/Download`
- **Survives being backgrounded** — a foreground service and wake lock hold the process, since the
  CLI is a child of it. Stop it from the notification or ⋮ → Stop
- **Phone notifications from Claude** — while the app isn't on screen, Claude Code's alerts when it
  needs you (permission requests, questions) and "finished" for turns of a minute or more become
  phone notifications, via hooks
  passed with `--settings`; your own Claude settings are untouched
- **⋮ menu** — switch between Claude and a Shell (bash in the same sandbox; both keep running and
  keep their scrollback) · Restart the one you're looking at · Stop · Reinstall sandbox (a clean
  reset that keeps your login and `/workspace`)
- **Package management without root** — `apk add`, `npm -g`, `pip install`, plus Android build and
  APK decompile tools

**Storage:** the APK is ~4.5 MB, but after first-run setup the sandbox takes about 500 MB (Claude Code
is ~240 MB, plus the rootfs and base tools). Installing the Android toolchains adds ~200 MB of the
device's own libraries plus the tools themselves.

**Trust model:** Claude Code and anything it runs can read and write everything under `/sdcard`
once storage access is granted, and can reach the network. That's the point — but use Claude Code's
own permission prompts accordingly.

<br>

## The engineering: six problems worth writing down

Most of this work was done upstream in Cinder (problems 1–5): making a 250 MB Linux binary run on
Android at all. Number 6 turned up while testing this fork. Each of these first presented as
something unrelated.

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
git clone https://github.com/hahnfeld/cinder-lite.git && cd cinder-lite
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

**Claude Code updates.** Nothing to do in the app: Claude Code lives in its standard native install
in the sandbox's home (`/root/.local/bin/claude`) and updates itself. `claude update` in the shell
checks right away; ⋮ → Reinstall sandbox installs the latest release from scratch.

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

Cinder Lite is by Matt Hahnfeld, forked from [Cinder](https://huggingface.co/SmallAICreator/cinder)
by SmallAICreator. Both are MIT licensed; `LICENSE` carries the original Cinder copyright notice
alongside Cinder Lite's, as the MIT license requires.

A few files in this repository are under other licenses: `app/src/main/cpp/termux.c` (Apache-2.0,
unmodified from termux-app v0.118.0), the Gradle wrapper (Apache-2.0), and the bundled binaries in
`app/src/main/assets/`.

The APK bundles open-source components: proot (GPL-2.0), libtalloc (LGPL-3.0), libandroid-shmem
(BSD-3-Clause), the unmodified Alpine Linux 3.24.2 minirootfs (busybox, apk-tools and others under
GPL-2.0; musl MIT; OpenSSL Apache-2.0; ca-certificates MPL-2.0; zlib), Termux terminal-view /
terminal-emulator (Apache-2.0) and the Kotlin standard library (Apache-2.0). Exact versions, a
per-package license list, links to corresponding source and the full license texts are in
[`app/src/main/assets/licenses/`](app/src/main/assets/licenses/THIRD-PARTY-NOTICES.md), which ships
inside the APK.

**Claude Code itself is not included.** It is Anthropic's software under its own terms
(https://code.claude.com/docs/en/legal-and-compliance) and is downloaded at runtime by the user, from
Anthropic's official npm package. Claude and Claude Code are trademarks of Anthropic PBC; this
project is unofficial and not endorsed by or affiliated with Anthropic.
