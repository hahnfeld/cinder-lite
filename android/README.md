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
phone: a proot + Alpine sandbox, the musl binary, the targetSdk 28 trick, the nested-proot fake root for
`apk`, and a full native chat UI on top. All of that groundwork is Cinder's, and this fork keeps its
history (upstream is the first 24 commits).

Cinder Lite keeps Cinder's sandbox and drops its custom UI. Instead of driving `claude --print` over
stream-json and re-rendering the conversation in Jetpack Compose, it shows **Claude Code's own
terminal UI in a real terminal emulator** (Termux's). Everything the TUI already does (model
picker, permission prompts, `/resume`, slash commands) comes from Claude Code itself, so the app
is just the sandbox, a verified downloader and a terminal: four Kotlin files at its core (four
more for the optional Shizuku features) and a ~4.5 MB APK.
It installs as `app.cinderlite`, so it can sit alongside the original.

> Unofficial and unaffiliated with Anthropic. Sideload only. arm64-v8a, Android 11+.
> This is the Android app; the iOS port lives in [`../ios`](../ios).

<br>

## What it is (and what it isn't)

Cinder Lite is a **front-end**. It does not contain, copy, or reimplement Claude Code. The `claude`
binary is Anthropic's, published on the public npm registry, and **the app downloads it at runtime
from npm**, only after you agree: it first says that Claude Code is distributed separately under
Anthropic's own license, and waits for a tap. The download is what would install on a laptop; the app
checks it against the sha512 npm publishes, and lets Claude Code's own `claude install` set it up.
From then on Cinder Lite runs `claude update` once a day, in the background. You then sign in with **your own** Anthropic account through the CLI's own login.
Cinder Lite provides a Linux environment for the binary to run in, and a terminal to show it.

| Layer | What it is |
|---|---|
| UI | Termux `terminal-view` showing the `claude` TUI, plus a row of keys phones lack (esc, tab, ⇧tab, ctrl, arrows), a 🎤 voice-typing key and a ⋮ menu |
| Sandbox | proot + an Alpine Linux 3.24 (musl, aarch64) rootfs, in app-private storage; phone storage at `/sdcard` |
| Engine | Anthropic's `claude` binary, the latest release — **downloaded at runtime and verified, never bundled**; updated daily |
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
| Claude Code install | Latest from npm, unverified | Latest from npm, sha512-verified, then Claude Code's native install, updated daily |
| Code | ~6,500 lines of Kotlin + JNI PTY library | ~1,500 lines in 4 Kotlin files, plus ~1,100 in 4 for the optional Shizuku features |
| Release APK | ~33 MB (debug) | ~4.5 MB, signed, R8-shrunk |

**Removed:** the stream-json bridge and transcript UI, markdown/LaTeX rendering, model/effort/
permission pickers, the session-history drawer, the custom JNI PTY library, the in-app `notify`,
`preview` and `install-apk` bridges, file upload/export, in-app dictation, the diagnostics screen and
the 32-bit proot loader.

**Added or changed:**
- Two terminals, Claude and a shell, switched from the ⋮ menu without losing either.
- An extra-keys row for what phone keyboards lack: esc, tab, ⇧tab (Claude Code's permission-mode
  switch), ctrl, arrows, and 🎤 for voice typing through the phone's own speech recognizer.
- Autocorrect for Claude's prompt (⋮ → Autocorrect, on by default). A terminal takes keys raw, so
  the keyboard can't correct them; instead Cinder Lite asks Android's spell checker (the keyboard's
  own, on the phone) about each word as you finish it and fixes clear typos, the way Gboard does.
  Backspace right after a fix undoes it. Only plain words are touched, never commands, paths or
  code, and never in the shell. Gboard's top strip and number row can be hidden in Gboard's own
  settings (Preferences → Suggestion strip, and Number row for passwords: the terminal counts as one).
- `/login` opens the phone's default browser through an `xdg-open` shim limited to https links.
  `xdg-open <file>` hands a file from the sandbox or `/sdcard` to the app you pick (browsers for
  web pages), read-only; never hidden files (logins, keys) or APKs.
- A bundled symbol font (a 70 KB subset of Noto Sans Symbols 2) for characters the system fonts
  lack, such as Claude Code's ⏵⏵ mode marker.
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
  keep their scrollback) · Restart the one you're looking at · Stop · Autocorrect (on/off) ·
  Shizuku (on/off; see below) · Reinstall sandbox (a clean reset that keeps your login and
  `/workspace`)
- **Package management without root** — `apk add`, `npm -g`, `pip install`, plus Android build and
  APK decompile tools
- **Optional adb-shell access via [Shizuku](https://shizuku.rikka.app/)** — off by default; see
  [below](#adb-shell-access-with-shizuku-optional). It also lets Claude drive apps on a separate
  phone screen in a picture-in-picture window

**Storage:** the APK is ~4.5 MB, but after first-run setup the sandbox takes about 500 MB (Claude Code
is ~240 MB, plus the rootfs and base tools). Installing the Android toolchains adds ~200 MB of the
device's own libraries plus the tools themselves.

**Trust model:** Claude Code and anything it runs can read and write everything under `/sdcard`
once storage access is granted, and can reach the network. That's the point — but use Claude Code's
own permission prompts accordingly. With Shizuku on, it also has adb-shell power
([see below](#be-deliberate-about-turning-it-on)).

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

### 5. `apk` needs root and hard links, and Android allows neither

Alpine's minirootfs ships no `/etc/resolv.conf`, so every request died with a DNS timeout until
nameservers were written in from ConnectivityManager. apk also refuses to run unless it is root, and
fakeroot needs a SysV IPC daemon that Android kernels (built without `CONFIG_SYSVIPC`) can't host, so
`sudo` and the `apk` shim run it under a nested proot `-0` (userspace fake root) instead.

That still left apk failing its database write with "Permission denied", which upstream put down to a
SysV semaphore and treated as cosmetic, since every file is on disk by then. The real cause: SELinux
denies hard links in an app's storage, and apk commits its database through one. Without the
database apk forgot everything it installed, so `apk del` and `apk upgrade` didn't work and each
`apk add` reinstalled shared dependencies. Cinder Lite runs proot with `--link2symlink`, as Termux's
proot-distro does, which emulates hard links with symlinks, and apk keeps its records.
Installs from before this fix get their base packages installed, and recorded, again on the next launch.

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

## adb-shell access with Shizuku (optional)

Inside the sandbox, Claude and the shell run as Cinder Lite's own app user, which Android gives very
few rights. So the Android system tools don't work there: `pm` for packages, `am` for activities,
`settings`, `dumpsys`, `cmd`, `input`, `wm` and so on. On a computer you'd reach them with
`adb shell`. With Shizuku, Cinder Lite can give the sandbox the same access with nothing but the
phone.

### What Shizuku is

[Shizuku](https://shizuku.rikka.app/) ([source](https://github.com/RikkaApps/Shizuku)) is a free,
open-source Android app by RikkaApps. It doesn't root the phone. Instead, it uses Android's own
**Wireless debugging** (Android 11+) once to start a small background server. That server runs with
the same privileges as `adb shell`: the `shell` user, uid 2000. Other apps can then ask Shizuku to do
things with those privileges. Each app needs your permission, granted in a dialog and revocable in
the Shizuku app at any time.

The server keeps running until the phone restarts. After every reboot you start it again from the
Shizuku app, with a couple of taps once Wireless debugging is paired. Rooted phones can start it
without Wireless debugging.

`rish` runs any command `adb shell` can: everything in `/system/bin`, the hundreds of system
services behind `cmd`, and shell built-ins, pipes and loops. Some examples:

| Command | What it does |
|---|---|
| `pm` | list, install, uninstall, disable or clear apps; grant or revoke their permissions |
| `am` | start activities and services, send broadcasts, force-stop apps |
| `cmd <service> …` | talk to a system service directly, e.g. `cmd wifi`, `cmd bluetooth_manager`, `cmd uimode night yes`, `cmd notification`, `cmd statusbar`, `cmd battery`; `cmd -l` lists them all |
| `settings` | read and change system, secure and global settings |
| `svc` | turn Wi-Fi, mobile data, Bluetooth and NFC on or off |
| `dumpsys` | inspect a service's state (battery, activities, notifications, …) |
| `appops` | view and change per-app permission operations |
| `wm` | change the screen size and density |
| `input`, `uiautomator dump` | inject taps, swipes, keys and text; dump the on-screen UI as XML |
| `screencap`, `screenrecord` | take screenshots and screen recordings |
| `logcat` | read the system log |
| `content` | query and modify content providers |
| `getprop`, `setprop` | read device properties, and set the ones the shell user may set |

They don't allow reading other apps' private data or changing the system partition. That needs root.

### Setting it up

1. Install Shizuku from [Google Play](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api)
   or [GitHub](https://github.com/RikkaApps/Shizuku/releases). Start it via Wireless debugging, as
   its app walks you through.
2. In Cinder Lite: ⋮ → **Shizuku**, then allow Cinder Lite in Shizuku's permission dialog. The
   setting is remembered, and the menu item shows a check while it's on.
3. In the sandbox:

   ```sh
   rish -c 'pm list packages -3'           # one command
   rish -c 'pm list packages -3' | wc -l   # pipes and redirects work as usual
   echo hello | rish -c cat                # stdin is passed through when it isn't a terminal
   rish                                    # an interactive shell; type exit to leave
   adb shell settings get global airplane_mode_on
   ```

   `rish` exits with the command's exit status, and Ctrl-C stops the command on the phone too.

Claude knows about all this: ask it something like *"how many apps are installed?"* or *"turn on
dark mode"* and it uses `rish` on its own. If Shizuku support is off or Shizuku isn't running, it
tells you what to do.

If you start Shizuku while Cinder Lite is open, switch to another app and back: Shizuku hands its
connection to an app when the app comes to the front. Your sessions keep running.

### The `adb` shim

Many scripts, and Claude's own habits, reach for `adb shell …`. The sandbox's `adb` is a small shim:

| You type | What runs |
|---|---|
| `adb shell <command…>` | `rish -c '<command…>'`. The arguments are joined with spaces, as real `adb shell` does. |
| `adb shell` | `rish`, the interactive shell |
| anything else (`adb devices`, `adb connect`, `adb install`, …) | the real adb client, if you've installed it with `apk add android-tools`, for example to drive *another* device over the network. Without it, the shim explains that only `adb shell` works. |

To install an APK on this phone, you don't need `adb install`. Copy the file to `/sdcard/Download`
and open it there, or run `rish -c 'pm install /sdcard/Download/app.apk'`.

### How it works

Shizuku comes with its own `rish` command, but it can't run here. It starts Android's Java runtime
(`app_process`), and inside proot that can't load: Android lets the app *run* files in `/system` but
not *read* them, and proot needs to read them. So the app itself talks to Shizuku, and the sandbox
reaches the app through a small bridge:

```
sandbox   rish -c 'pm list packages'          (a short Python script)
            │  Unix socket /usr/local/libexec/rish.sock: the command, plus rish's own stdout and stderr
            ▼
app       ShizukuBridge   checks the caller is the app's own sandbox (same uid)
            │  binder
            ▼
Shizuku   ShellService    runs /system/bin/sh -c '…' as the shell user (uid 2000)
            │  output through pipes, back through the app to rish's stdout/stderr
            ▼
sandbox   rish            exits with the command's exit status
```

- **The bridge** (`ShizukuBridge.kt`) is a Unix socket at the sandbox's `/usr/local/libexec/rish.sock`,
  in Cinder Lite's private storage, open only while the setting is on. Other apps can't reach it,
  and every connection is checked anyway: only processes running as Cinder Lite's own uid (its
  sandbox) are served. `rish` in turn checks that the socket belongs to Cinder Lite.
- **The service** (`ShellService.kt`) is a Shizuku *user service*. Shizuku starts it in a separate
  process with shell privileges, from Cinder Lite's own APK. It runs one shell per `rish` call.
- **Output goes through pipes**, not straight to your terminal. Android's security policy (SELinux)
  doesn't let the shell user write to the app's terminal device, so the app copies the output
  across. Input travels the other way, from `rish` over the socket and into the command's stdin.
- **Ctrl-C or closing `rish`** kills the command and everything it started, so a pipeline like
  `logcat | grep x` ends too. When the setting is turned off, the bridge stops listening and the
  Shizuku service is shut down.

### Limits

- **No real terminal.** Interactive `rish` is line-based, like piping into `adb shell`. Full-screen
  programs like `top` (without `-n 1`) or `vi` won't work, and there's no prompt editing.
- **`rish -c` at a terminal gets no keyboard input.** Pipe the input in if a command needs it.
- **Signals end the command rather than reaching it.** Ctrl-C, SIGTERM and SIGHUP kill it; other
  signals aren't forwarded.
- **Commands see the phone's filesystem, not the sandbox's.** `/sdcard` is the same in both, but
  `/workspace`, `/tmp` and the rest of the sandbox live in Cinder Lite's private storage, which the
  shell user can't read. To hand a file to a `rish` command (an APK for `pm install`, say), put it
  under `/sdcard` first.
- **Shizuku has to be restarted after every reboot**, unless the phone is rooted.

### The phone screen: Claude drives apps in a picture-in-picture window

With Shizuku on, Claude can also use apps for you, on a **separate virtual screen** shown in a
picture-in-picture window that floats over everything, like a video. The apps it opens run there,
not on your screen, so you can keep using your phone, watch what Claude is doing, drag the window
anywhere, or expand it to take it over. Ask something like *"open Settings on the phone screen and tell me
the Android version"* and Claude uses the `phone` command:

```sh
phone start                  # open the phone screen (the PiP window); `phone start landscape` for
                             # apps that only run in landscape, which otherwise show in a strip
phone open Settings           # by name, or its package (com.android.settings); phone apps lists them
phone ui                     # the on-screen elements, numbered, with text and bounds
phone click 15 'About phone' # click element 15, named as phone ui lists it
phone type 9 'Search' 'wifi' # put text into field 9 (no keyboard involved)
phone scroll 10 down
phone hold 7 'Photo'         # long-press (a context menu); phone doubletap 7 'Photo' double-taps
phone find 'Battery'         # scroll until it's on screen, down then up
phone read                   # a long list's text, 4 screens at a time; again reads on
phone shot                   # screenshot to /tmp/phone.png, for when the list isn't enough
phone tap 300 147            # …and coordinates in that screenshot's pixels
phone key back
phone link https://example.com   # a link, or an app's own URL scheme, on the phone screen
phone close Settings
phone info                   # the phone screen's size, whether it's asleep, whether Accessibility is on
phone stop                   # close it, and the apps on it
```

Each action (`open`, `close`, `link`, `click`, `type`, `scroll`, `hold`, `doubletap`, `find`, `read`, `tap`, `swipe`, `text`, `key`) waits for the screen to
settle and then lists it the way `phone ui` does (when Accessibility is on, below), so every step
shows Claude its result. `click` and `type` name their element, so their approval prompts say what Claude
is acting on. An element that has changed since, or sits behind a dialog or popup, is refused and
the current screen listed instead. Password fields are flagged and their text is never listed, and
a very long screen is cut off with a note to scroll.

These are the same commands as on iOS ([ios/README.md](../ios/README.md)), with the same output.
`phone help` lists the few that only Android has: `phone start landscape`, and keys beyond back,
enter, tab, del, space, letters and digits.

`phone ui` reads the screen through Android's accessibility API, the same way a screen reader does,
so Claude works from real buttons and text fields rather than guessing from pictures. For that, turn
on **Cinder Lite phone screen** in Settings → Accessibility once. Cinder Lite offers to take you there
when you turn Shizuku on, and if Claude tries the phone screen without it, a message on the phone
says what to turn on (on a sideloaded install Android may first call it a restricted setting: App
info → ⋮ → Allow restricted settings). Until then Claude can still use `phone shot` and
coordinates. The service only reads the phone screen, and only when a `phone` command asks.

**Taking over.** Expand the window to full size and the phone screen is yours: touches go to its
apps and Back is its Back. Claude's next action waits until you give it back, then tells Claude
the screen may have changed. Swipe home or switch apps to shrink it back into its small window
without closing it.

How it works: Cinder Lite's Shizuku service creates the virtual display with adb-shell privileges,
the same way scrcpy's `--new-display` does, and the display draws straight into the PiP window, with
no video encoding or streaming. Taps, swipes and keys reach it as input events from the Shizuku
service, without starting a process for each (`phone key` only sends keys an app handles: never
power, volume or media keys, which Android applies phone-wide). The display has its own focus and
no keyboard, so taps and typing there never reach your screen, and yours never reach it. Closing
the window (its ✕, a swipe, or `phone stop`) closes the display and the apps on it; they don't move
onto your screen. Turning Shizuku off closes it too.

Limits:

- An app that's already open on your screen moves to the phone screen when Claude opens it there:
  Android keeps one window per app.
- Apps that block screenshots (banking apps, for example) show black in `phone shot`, though
  `phone ui` still sees them. Some refuse to run at all while an accessibility service is on.
- With Android's Advanced Protection on, accessibility services from outside the Play Store are
  blocked, so only `phone shot` and coordinates work.
- In the small window you watch rather than drive: Android keeps its touches for its own controls.
- While the phone is asleep, the phone screen's apps pause with it. Claude is told, and they carry
  on when you turn the screen back on.
- When you take it over, you can tap, scroll and go Back, but no keyboard comes up for its text
  fields (it would type into your own screen's app); ask Claude to type instead.

### Be deliberate about turning it on

While it's on, everything in the sandbox has adb-shell power, Claude included. That means it can
uninstall apps, change settings, grant permissions and tap the screen, and use apps on the phone
screen, where you're signed in to them. Claude Code's permission prompts still apply to its own
commands, so review `rish`, `adb shell` and `phone` calls as you would any other command. But
they don't cover what those commands run in turn: a package's install script or a cloned
project's build gets the same power. Claude is told to treat what it reads on screen, on the web
and in files as data rather than instructions, and to check with you before sending, paying,
installing or changing security settings; that's guidance, not a guarantee against a page or app
built to mislead it.

Turn it off when you don't need it: ⋮ → **Shizuku** again. Code in the sandbox runs as the app
itself, so it could turn that switch back on; revoking Cinder Lite in the Shizuku app is the off
switch nothing inside can undo.

<br>

## Known issues

**A stray `u` in the top-left corner when Claude Code starts.** It's cosmetic. Cinder Lite starts
Claude Code with `CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1`, which makes starts about 2 s faster
(2.4–2.9 s against 4.3–4.6 s for a one-line `claude -p` on a Pixel). Starting that quickly,
Claude Code turns its enhanced keyboard mode off before it has asked the terminal whether it
supports that mode. Until the terminal answers, Claude Code assumes it might, so the switch-off
includes kitty's "pop keyboard mode" code, `ESC [ < u`. Termux's terminal (0.118.0) doesn't know
`ESC [ <` codes: it stops reading at the `<` and prints the `u`. On a slower start Claude Code
asks first, Termux doesn't answer, and Claude Code leaves that code out. Turning off only
telemetry, error reports and auto-update (`DISABLE_TELEMETRY`, `DISABLE_ERROR_REPORTING`,
`DISABLE_AUTOUPDATER`) avoids the `u` but gives back most of the time saved.

<br>

## Building

Needs JDK 17+, the Android SDK with platform 37 and NDK r30 (`30.0.16248370`, for the small
`libtermux.so`), and **Git LFS** — `proot` and `rootfs.tgz` are LFS objects, and a clone without LFS
builds an APK full of pointer files that fails on first run. Gradle 9.8 / AGP 9.4 / Kotlin 2.4.

```sh
git lfs install            # once per machine
git clone https://github.com/hahnfeld/cinder-lite.git && cd cinder-lite/android
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
in the sandbox's home (`/root/.local/bin/claude`). Its own update check slowed every start, so it's
off, and the Claude tab runs `claude update` in the background once a day instead. `claude update` in
the shell checks right away; ⋮ → Reinstall sandbox installs the latest release from scratch.

**First run:** allow storage access. The app unpacks the sandbox, then says that Claude Code is
Anthropic's software under its own license and asks before getting it: tap **Download Claude Code**.
It then downloads Claude Code (~240 MB from npm, wifi recommended) and installs bash, git, Python,
Node.js and friends. Claude Code then starts; type `/login`.

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
by SmallAICreator. Both are MIT licensed; [`LICENSE`](../LICENSE) carries the original Cinder copyright notice
alongside Cinder Lite's, as the MIT license requires.

A few files in this repository are under other licenses: `app/src/main/cpp/termux.c` (Apache-2.0,
unmodified from termux-app v0.118.0), the Gradle wrapper (Apache-2.0), and the bundled binaries in
`app/src/main/assets/`.

The APK bundles open-source components: proot (GPL-2.0), libtalloc (LGPL-3.0), libandroid-shmem
(BSD-3-Clause), the unmodified Alpine Linux 3.24.2 minirootfs (busybox, apk-tools and others under
GPL-2.0; musl MIT; OpenSSL Apache-2.0; ca-certificates MPL-2.0; zlib), Termux terminal-view /
terminal-emulator (Apache-2.0), the Kotlin standard library (Apache-2.0), the Shizuku API (MIT)
and a subset of the Noto Sans Symbols 2 font (OFL-1.1). Exact versions, a
per-package license list, links to corresponding source and the full license texts are in
[`app/src/main/assets/licenses/`](app/src/main/assets/licenses/THIRD-PARTY-NOTICES.md), which ships
inside the APK.

**Claude Code itself is not included.** It is Anthropic's software under its own terms
(https://code.claude.com/docs/en/legal-and-compliance) and is downloaded at runtime by the user, from
Anthropic's official npm package. Claude and Claude Code are trademarks of Anthropic PBC; this
project is unofficial and not endorsed by or affiliated with Anthropic.
