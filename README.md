# Cinder Lite

**Claude Code on your phone, and nothing in the way.**

Cinder Lite is an ultralightweight wrapper that runs Anthropic's own Claude Code CLI on Android and
iOS. It's a terminal and a minimal, bundled Alpine Linux, and that's all. Claude Code gets a real
shell with `apk`, git, Python and Node.js, and you get Claude Code's own terminal UI, exactly as on a
laptop, with a second tab for your own shell.

The Android app is about 5 MB, the iOS app about 8 MB.

## The real Claude Code

Cinder Lite doesn't reimplement, patch or proxy Claude Code. It runs Anthropic's own CLI,
unmodified and installed the way Anthropic distributes it, so you can legitimately use it with
anything Claude Code supports, under Anthropic's terms, just as on a laptop:

- **A Claude subscription**, personal or business (Pro, Max, Team or Enterprise): sign in with
  `/login`.
- **An Anthropic API key** from the Claude Console.
- **Claude Code's other supported platforms**, such as Amazon Bedrock, Google Vertex AI and
  Microsoft Foundry. Set them up with Claude Code's usual environment variables or its
  `~/.claude/settings.json`, as on any computer.

## Small by design

- **A tiny base.** The app bundles only a terminal and a minimal Alpine root. Claude Code isn't
  bundled: it's Anthropic's software, so on first launch Cinder Lite asks before downloading it from
  npm. It checks the download against npm's published hash before installing it.
- **Only what Claude Code needs.** First launch installs a short list of tools: bash, coreutils,
  git, ripgrep, curl, Python, Node.js, npm, ssh, make, jq and a few more. Anything else is one
  `apk add`, `pip install` or `npm install -g` away.
- **Fast to start.** Claude Code's telemetry, error reports and update check are off, so nothing
  holds up a start. A one-line `claude -p` takes about 2.5 s on a Pixel. Claude Code is updated
  once a day in the background instead.
- **Nothing extra running.** Cinder Lite has no accounts, analytics or servers of its own. You sign
  in to Claude with your own account.

## Modules

Everything beyond running Claude Code is an optional module, off until you check it in
⋮ → **Modules**.

| Module | What Claude can do | Android | iOS |
|---|---|---|---|
| **Phone Use** | Use apps on the phone: open, read, tap, type, scroll | Through [Shizuku](https://shizuku.rikka.app) on a separate screen in a floating window, plus adb-shell commands | Through [iCTRL](https://github.com/OpenMinis/iCTRL), on your real screen |
| **Network Monitor** | See what the phone's apps send and receive | A VPN into mitmproxy; captures start and stop with `traffic start` / `traffic stop` | — |
| **App Development** | Build Android apps and take APKs apart | JDK, aapt2, d8, apksigner, apktool, jadx | — |

Each module adds and removes cleanly:

- **Turning one on** downloads what it needs, the first time only. It asks for the permissions it
  needs right then, and offers to restart Claude so Claude learns about it.
- **Turning one off** takes away its commands, Claude's instructions for it and its parts of the
  app. On Android those parts are switched off in the system itself, so an unused module doesn't
  show up in Settings. You can keep its downloads for next time or delete them.

In the code, each module is one folder behind one small interface, with the same shape on both
platforms.

## Platforms

| | [Android](android/) | [iOS](ios/) |
|---|---|---|
| Runs on | Android 11+, arm64 | iOS 16+, iPhone and iPad |
| Sandbox | Alpine under proot, at native speed | Alpine in iSH-AOK's emulator, which is slower: iOS allows no JIT |
| Install | The APK from [Releases](https://github.com/hahnfeld/cinder-lite/releases) | Build the IPA and sideload it (SideStore or AltStore) |
| License | MIT | GPL-3.0 (iSH-AOK is GPL) |

[android/README.md](android/README.md) and [ios/README.md](ios/README.md) have the details:
installing, each module, the trust model and building.

## Credits and license

Cinder Lite is by Matt Hahnfeld, a lighter fork of
[Cinder](https://huggingface.co/SmallAICreator/cinder) by SmallAICreator. It's unofficial and not
affiliated with Anthropic.

Everything outside `ios/` is MIT ([LICENSE](LICENSE)). `ios/` is GPL-3.0 ([ios/LICENSE](ios/LICENSE)),
because it's built on [iSH-AOK](https://github.com/emkey1/ish-AOK).
