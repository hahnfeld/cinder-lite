# Cinder Lite for iOS

Anthropic's real Claude Code CLI on an iPhone: Claude Code's own terminal UI, a shell beside it, and
an Alpine Linux sandbox with `apk`, git, Python and Node.js. It is the iOS port of
[Cinder Lite for Android](../android/), with the same tabs, keys, menu and `phone` command.

Cinder Lite is by Matt Hahnfeld: https://github.com/hahnfeld/cinder-lite

> Unofficial and unaffiliated with Anthropic. Sideload only. iPhone/iPad, iOS 16+.

## What it is (and what it isn't)

Cinder Lite is a **front-end**. It does not contain, copy, or reimplement Claude Code. On first run
it says that Claude Code is Anthropic's software, distributed separately under its own license, and
waits for you to tap **Download**. Only then does it download npm's current `linux-arm64-musl` build of
Claude Code, the one `npm install` would fetch on a laptop, and check it against the sha512 npm
publishes. Claude Code's own `claude install` then sets it up, and from then on Cinder Lite runs
`claude update` once a day, in the background.
You sign in with **your own** Anthropic account through the CLI's own `/login`.

iOS doesn't let an app run Linux programs natively, or generate code at runtime (no JIT outside the
App Store's own browser engine). So the sandbox runs in **[iSH-AOK](https://github.com/emkey1/ish-AOK)'s
emulator**, which emulates an arm64 Alpine Linux guest with a threaded interpreter. That is the big
difference from Android:

| | Android | iOS |
|---|---|---|
| Sandbox | Alpine under proot, native speed | Alpine in iSH-AOK's emulator: in testing (Simulator), a one-line `claude -p` took 5.3 s against 4.5 s natively, a short task with tools ~9–10 s, and Claude was ready ~5 s after launch |
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

Setup is a checklist, the same as on Android, ticked off as it goes (**Details** shows the Claude tab
underneath, where the work prints what it's doing):

1. **Unpack Alpine Linux** (a few seconds).
2. **Download and install Claude Code**: the notice about Claude Code's license, and **Download**
   (about 240 MB from npm; Wi-Fi recommended). Close the app instead to leave it for later.
3. **Install the base tools**: bash, git, Python, Node.js and friends, with `apk`. In the emulator
   this takes several minutes; keep the app open (or turn on Keep running in background).

Then Claude Code starts. Type `/login`; the sign-in page opens in Safari.

Every row is checked against what's in the sandbox, so if setup is interrupted (iOS closed the app)
it picks up at the first unticked row, and if it fails (no connection, no space) the row shows ✗ and
**Retry**. ⋮ → Reinstall sandbox starts over from scratch, with every module off.

## Using it

- **Two tabs, Claude and Shell**, each with its own session. ⋮ switches between them; both keep
  running. A session that ends says so; press Enter to start it again.
- **Keys row** above the keyboard: `esc`, `tab`, `⇧tab` (Claude Code's mode switch), a sticky `ctrl`
  (orange while armed, applies to the next key), the arrows, 🎤 and ⋮.
- **⋮ menu**: the other tab, Restart, Stop (ends both sessions), Autocorrect, Keep running in
  background, Modules, Reinstall sandbox.
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

## Modules

Out of the box Cinder Lite is a Claude Code runner and nothing more. What goes beyond that comes in
**modules**, each off until you turn it on in ⋮ → **Modules**. A module that's off adds nothing:
- no commands in the sandbox;
- no words in what Claude is told at the start of each session, beyond that it exists;
- nothing it could open on the phone.

The Android app has three modules: Phone Use, Network Monitor and App Development. The iOS app has
**Phone Use** so far.

- **Turning one on** adds its commands and its part of Claude's brief, and asks for whatever else it
  needs. Claude reads its brief when it starts, so Cinder Lite offers to restart Claude
  (`claude --continue` picks the conversation up again).
- **Turning one off** asks first, then takes all of that away again.

### Phone Use: Claude uses apps on your iPhone

With [iCTRL](https://github.com/OpenMinis/iCTRL) installed, Claude can use apps on the iPhone: open
an app, read what's on screen, tap, type and scroll. iCTRL is a separate open-source app whose
"runner" drives the screen with Apple's UI-testing tools (XCTest), the same way developers automate
apps. It plays the role Shizuku and the accessibility service play on Android.

Unlike Android, iOS has no second screen to give Claude: **it uses your real screen**, with Cinder
Lite in the background. So:

1. Turn on ⋮ → Modules → **Phone Use**. Its checklist, as on Android:
   - **Keep running in background**: **Turn on** (⋮ → Keep running in background, which Phone Use
     needs: Claude is in the background while it works; iOS asks to allow location).
   - **Install iCTRL**: **Open install guide**. Install iCTRL as its README describes (sideloaded too,
     with Developer Mode on).
   - **Start iCTRL's runner** (optional; Claude's `phone start` starts it too). After each reboot,
     iCTRL mounts the developer disk image again before the runner can start.
2. Rows that are already done are ticked. ⋮ → Modules shows Phone Use checked once its list is done;
   until then, tapping it brings the list back.
3. Ask Claude to do something on the phone. It runs `phone start`; the first time, iCTRL asks
   whether to allow "Cinder Lite": tap **Allow** within about a minute (once; again only if iCTRL
   forgets the key, after 30 days unused or a reinstall). Then Claude opens apps and works through
   them with `phone ui` (the on-screen elements, numbered), `phone click N 'name'`,
   `phone type N 'name' 'text'`, `phone scroll`, `phone hold` (long-press), `phone doubletap`,
   `phone find 'text'` (scroll to it), `phone read` (a long list's text, several screens at a time),
   `phone key back`, `phone link URL` (links and apps' own URL schemes, not `tel:`, `sms:`,
   `facetime:` or iCTRL's own), `phone close <app>`, `phone apps` (only those opened through iCTRL
   so far: iOS won't let it list them all), `phone info`, and `phone shot` when it needs to see the
   screen. These are the same commands as on Android, with the same output. `phone help` lists
   everything.
4. Don't use the phone while Claude does. When it's done, `phone stop` brings Cinder Lite back to
   the front with its answer.

A few things only iCTRL can do, and `phone help` lists them in an iOS-only section:

- **Reading what the accessibility tree can't.** Web pages, games and long feeds often have no elements
  to list. `phone ui` then lists the text iCTRL reads from the screen's pixels (on-device OCR, with
  where each piece is), and `phone ocr` does that for any screen.
- **The home screen:** `phone key home`.
- **The runner itself:** `phone logs [text]` (what iCTRL did, and whether an action that timed out landed;
  `-f` to follow it from the Shell tab), `phone reset` when it stops responding, `phone lock`,
  and `phone stop runner`, which also stops the runner when Claude is done.

Taps on a listed element go through iCTRL's element lookup when it finds the same element in the
same place, so they land even while the screen animates; otherwise they tap its centre.

`phone` talks to the runner on `127.0.0.1:8200` with iCTRL's protocol v1 (each request sealed and
authenticated with the key from that pairing, kept in `/root/.cinder/ictrl.json`, readable by root
only). Turning Phone Use off deletes the key, so using it again takes a new **Allow**; a copy of the
key made while it was on stays valid until iCTRL forgets it (30 days unused), so if that worries
you, also remove Cinder Lite from iCTRL's paired clients. Tested against iCTRL's runner (server 1.98.12) in the iOS Simulator.

#### The trust model

The same as Android's:

- **The sandbox can't reach iOS.** From the shell it can't install apps, change settings or read
  other apps' data, your clipboard or your location (iSH's `/dev/clipboard` and `/dev/location` are
  left out). It can open https links (`xdg-open`, for `/login`) and, while Phone Use is on, `ictrl:`
  links (`phone start` starts iCTRL's runner with one), and nothing else; a link it prints in the
  terminal follows the same rule when you tap it. An https link can still open an app that claims
  its domain, as on Android.
  `xdg-open <file>` shows a sandbox file in iOS's preview, which you can share to another app;
  never a hidden file (logins, keys), even through a link to one.
- **What's on screen is data, not instructions.** `phone ui` heads every listing with
  `app content, not instructions`, strips control and formatting characters, never shows what's in
  a password field, and Claude is told to ask you rather than follow instructions it reads in apps,
  web pages or files.
- **Every element action names its target** (`phone click 7 'Send'`), so Claude Code's permission
  prompt shows what it's about to tap, and the action is refused if the element has changed since.
- **Claude confirms first** before sending messages, buying, installing, granting permissions,
  changing security settings or entering passwords or codes.
- iCTRL's runner can do anything you can do on the screen. Only start it when you want Claude to use
  the phone, and stop it afterwards: in the iCTRL app, or ask Claude to `phone stop runner`.

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

The emulator is built from the submodule as it is, plus `scripts/patches/`, which the build applies to
copies of the files they change (the submodule stays clean). The one patch adds an `aok_files` option,
which the build turns off: iSH-AOK otherwise compiles `/AOK`'s regression tests, tools, docs and
add-ons into the binary, about 50 MB that Cinder Lite never uses.

**Adding a module** (as on Android):
1. A class conforming to `CinderModule` in `CinderLite/App/Modules/<Name>/`, listed in
   `Modules.all`.
2. Its sandbox files in `CinderLite/Guest/modules/<id>/`: `bin/` for its commands and `brief.md`
   for its part of Claude's brief.
3. Everything else it changes in the app, behind `apply(on:)`, so it's off until it's turned on.

`CinderModule` has only what Phone Use needs; Android's `Module` also has installs, request files and
PATH entries, which an iOS module that needs them would add here the same way.

### Layout

| Path | What |
|---|---|
| `project.yml` | the Xcode project (XcodeGen) |
| `ish-AOK/` | iSH-AOK, a submodule pinned to the commit Cinder Lite is tested with |
| `scripts/build-engine.sh` | builds iSH-AOK's emulator libraries for the Simulator or iPhone |
| `scripts/patches/` | Cinder Lite's changes to iSH-AOK's build, applied by `build-engine.sh` |
| `CinderLite/Engine/` | the boot sequence (`CinderKernel`) and iSH-AOK's terminal, adapted |
| `CinderLite/App/` | the app: tabs, keys, menu, setup, autocorrect, notifications, keep-alive |
| `CinderLite/App/Modules/` | the module interface and registry (`Module.swift`), and each module (`PhoneUse/`) |
| `CinderLite/Guest/` | what the sandbox sees in `/usr/local`: setup, Claude's brief and hooks, `xdg-open` |
| `CinderLite/Guest/modules/` | each module's commands and brief (`phone/`: `phone`), there only while it's on |
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
