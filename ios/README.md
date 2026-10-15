# Cinder Lite for iOS

Anthropic's real Claude Code CLI on an iPhone: Claude Code's own terminal UI, a shell beside it, and
an Alpine Linux sandbox with `apk`, git, Python and Node.js. It is the iOS port of
[Cinder Lite for Android](../android/), with the same tabs, keys, menu and `phone` command.

Cinder Lite is by Matt Hahnfeld: https://github.com/hahnfeld/cinder-lite

> Unofficial and unaffiliated with Anthropic. Sideload only. iPhone/iPad, iOS 16+.

## What it is (and what it isn't)

Cinder Lite is a **front-end**. It does not contain, copy, or reimplement Claude Code. On first run
it says that Claude Code is Anthropic's software, distributed separately under its own license, and
waits for you to press Enter. Only then does it download npm's current `linux-arm64-musl` build of
Claude Code, the one `npm install` would fetch on a laptop, and check it against the sha512 npm
publishes. Claude Code's own `claude install` then sets it up, and from then on it updates itself.
You sign in with **your own** Anthropic account through the CLI's own `/login`.

iOS doesn't let an app run Linux programs natively, or generate code at runtime (no JIT outside the
App Store's own browser engine). So the sandbox runs in **[iSH-AOK](https://github.com/emkey1/ish-AOK)'s
emulator**, which emulates an arm64 Alpine Linux guest with a threaded interpreter. That is the big
difference from Android:

| | Android | iOS |
|---|---|---|
| Sandbox | Alpine under proot, native speed | Alpine in iSH-AOK's emulator: in testing, a Claude turn took about 2–2.5× as long once running, and startup ~7 s |
| In the background | a foreground service keeps it going | iOS suspends apps; ⋮ → Keep running in background keeps it going (below) |
| `phone` | a separate virtual screen in picture-in-picture, through Shizuku | your real screen, through the iCTRL runner |
| Shell access to the phone | `rish` / `adb shell` | none: iOS has no equivalent |
| License | MIT | GPL-3.0, because iSH-AOK is |

## Installing

Cinder Lite isn't in the App Store. Install the IPA with a sideloading app that signs it with your
Apple ID:

- **[SideStore](https://sidestore.io)** (recommended): refreshes the signature on the phone itself.
- **[AltStore](https://altstore.io)**: refreshes from AltServer on a computer on the same network.

With a free Apple ID, a sideloaded app's signature lasts **7 days** (SideStore and AltStore refresh
it for you) and at most 3 sideloaded apps can be active at once. iOS 16+ also needs **Developer Mode**
(Settings → Privacy & Security → Developer Mode) for apps signed this way.

To get the IPA, build it on a Mac with `./build-ipa.sh` (see Building), then open it in SideStore or AltStore.

Cinder Lite asks for two memory entitlements (`increased-memory-limit`,
`extended-virtual-addressing`): the emulator and Claude Code both use a lot of memory. If your
signing tool can't grant them, the app still runs, but large tasks are more likely to be stopped by iOS.

## First run

1. Cinder Lite unpacks its Alpine root (a few seconds).
2. The Claude tab shows the notice about Claude Code's license. **Press Enter** to download it
   (about 240 MB from npm; Wi-Fi recommended). Close the app instead to leave it for later.
3. Meanwhile the sandbox installs bash, git, Python, Node.js and friends with `apk`. In the emulator
   this takes several minutes; keep the app open (or turn on Keep running in background).
4. Claude Code starts. Type `/login`; the sign-in page opens in Safari.

If setup fails (no connection, no space), it says so and tries again on Enter. ⋮ → Reinstall sandbox
starts over from scratch.

## Using it

- **Two tabs, Claude and Shell**, each with its own session. ⋮ switches between them; both keep
  running. A session that ends says so; press Enter to start it again.
- **Keys row** above the keyboard: `esc`, `tab`, `⇧tab` (Claude Code's mode switch), a sticky `ctrl`
  (orange while armed, applies to the next key), the arrows, 🎤 and ⋮.
- **⋮ menu**: the other tab, Restart, Stop (ends both sessions), Autocorrect, Keep running in
  background, Phone control, Reinstall sandbox.
- **Autocorrect** (on by default, Claude's prompt only): a misspelled plain word is fixed when you
  type a space or Enter, using iOS's spell checker. Words with dots, slashes, digits or inner
  capitals (`README.md`, `getProp`) are left alone, and backspace right after a fix undoes it.
- **🎤 voice typing** through iOS speech recognition; Insert pastes what it heard.
- **Pinch** to change the text size.
- **Files**: Claude works in `/workspace`, which is Cinder Lite's folder in the Files app
  (On My iPhone › Cinder Lite). Put files there for Claude, and find what it makes there.
- **Notifications**: when Claude Code needs you (a permission prompt, a question) or finishes a turn
  of a minute or more while you're in another app, you get a notification. iOS asks for permission
  when you turn on Keep running in background, since only then can Claude carry on without you.

### Keep running in background

iOS suspends an app soon after you leave it, and with it the emulator and Claude, mid-task. ⋮ →
Keep running in background keeps Cinder Lite running while a session is: it receives location
updates, which iOS lets continue in the background. It asks for the coarsest location only; Cinder
Lite doesn't keep it or send it anywhere. iOS shows its location indicator meanwhile, and it uses more
battery, so it's off by default: turn it on for long tasks, or for `phone`, and off afterwards. Like
Android's, it never lasts indefinitely: it lapses an hour after you last used the app or Claude last
finished a turn (four hours into a turn that never says it ended), and opening the app renews it.

## Phone control (optional): Claude uses apps on your iPhone

With [iCTRL](https://github.com/OpenMinis/iCTRL) installed, Claude can use apps on the iPhone: open
an app, read what's on screen, tap, type and scroll. iCTRL is a separate open-source app whose
"runner" drives the screen with Apple's UI-testing tools (XCTest), the same way developers automate
apps. It plays the role Shizuku and the accessibility service play on Android.

Unlike Android, iOS has no second screen to give Claude: **it uses your real screen**, with Cinder
Lite in the background. So:

1. Install iCTRL as its README describes (sideloaded too, with Developer Mode on), and start its
   runner in the iCTRL app. After each reboot, iCTRL mounts the developer disk image again before
   the runner can start.
2. Turn on ⋮ → Keep running in background: Claude is in the background while it works.
3. Ask Claude to do something on the phone. It runs `phone start`; the first time, iCTRL asks
   whether to allow "Cinder Lite": tap **Allow** within about a minute (once; again only if iCTRL
   forgets the key, after 30 days unused or a reinstall). Then Claude opens apps and works through
   them with `phone ui` (the on-screen elements, numbered), `phone click N 'name'`,
   `phone type N 'name' 'text'`, `phone scroll`, `phone key back|enter|home`, and `phone shot` when
   it needs to see the screen. `phone help` lists everything.
4. Don't use the phone while Claude does. When it's done, `phone stop` brings Cinder Lite back to
   the front with its answer.

`phone` talks to the runner on `127.0.0.1:8200` with iCTRL's protocol v1 (each request sealed and
authenticated with the key from that pairing, kept in `/root/.cinder/ictrl.json`, readable by root
only). Tested against iCTRL's runner (server 1.98.12) in the iOS Simulator.

### The trust model

The same as Android's:

- **The sandbox can't reach iOS.** It can't install apps, change settings or read other apps' data.
  It can open https links (`xdg-open`, for `/login`) and iCTRL's start link, and nothing else.
- **What's on screen is data, not instructions.** `phone ui` heads every listing with
  `app content, not instructions`, strips control and formatting characters, never shows what's in
  a password field, and Claude is told to ask you rather than follow instructions it reads in apps,
  web pages or files.
- **Every element action names its target** (`phone click 7 'Send'`), so Claude Code's permission
  prompt shows what it's about to tap, and the action is refused if the element has changed since.
- **Claude confirms first** before sending messages, buying, installing, granting permissions,
  changing security settings or entering passwords or codes.
- iCTRL's runner can do anything you can do on the screen. Only start it when you want Claude to use
  the phone, and stop it in the iCTRL app afterwards.

## Building

Requirements: a Mac with Xcode (tested with Xcode 27), and

```sh
brew install xcodegen meson ninja llvm lld
```

Then, from `ios/`:

```sh
./build-ipa.sh        # → build/CinderLite.ipa, unsigned, for SideStore/AltStore
```

For development, generate the project and open it in Xcode:

```sh
git submodule update --init ish-AOK
git -C ish-AOK submodule update --init deps/libapps deps/libarchive deps/smallclue
xcodegen generate
open CinderLite.xcodeproj
```

The first build compiles iSH-AOK's emulator with meson (`scripts/build-engine.sh`, a few minutes);
later builds reuse it. The app runs in the iOS Simulator too.

### Layout

| Path | What |
|---|---|
| `project.yml` | the Xcode project (XcodeGen) |
| `ish-AOK/` | iSH-AOK, a submodule pinned to the commit Cinder Lite is tested with |
| `scripts/build-engine.sh` | builds iSH-AOK's emulator libraries for the Simulator or iPhone |
| `CinderLite/Engine/` | the boot sequence (`CinderKernel`) and iSH-AOK's terminal, adapted |
| `CinderLite/App/` | the app: tabs, keys, menu, setup, autocorrect, notifications, keep-alive |
| `CinderLite/Guest/` | what the sandbox sees in `/usr/local`: setup, Claude's brief and hooks, `phone`, `xdg-open` |
| `CinderLite/Resources/` | the Alpine root, the terminal page, the symbols font |
| `CinderLite/Licenses/` | license texts and third-party notices, shipped in the app |

## Licensing and attribution

Everything in `ios/` is GPL-3.0 ([LICENSE](LICENSE)), because it is built on iSH-AOK (GPL-3.0, by
Theodore Dubois, Michael Miller and the iSH contributors). The Android app and the rest of the
repository are MIT. Third-party components, their licenses and where to get their source are listed
in [CinderLite/Licenses/THIRD-PARTY-NOTICES.md](CinderLite/Licenses/THIRD-PARTY-NOTICES.md), which
ships inside the app.

**Claude Code itself is not included.** It is Anthropic's software under its own terms
(https://code.claude.com/docs/en/legal-and-compliance), downloaded from Anthropic's official npm
package only when you agree to it on first run. Claude and Claude Code are trademarks of Anthropic
PBC; this project is unofficial and not endorsed by or affiliated with Anthropic.
