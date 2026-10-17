# Cinder Lite

Anthropic's real Claude Code CLI on your phone: Claude Code's own terminal UI, a shell beside it,
and an Alpine Linux sandbox with `apk`, git, Python and Node.js, set up on first launch. Optional
modules add more, such as Claude driving the phone itself.

Cinder Lite is by Matt Hahnfeld, a lighter-weight fork of
[Cinder](https://huggingface.co/SmallAICreator/cinder) by SmallAICreator. Unofficial and
unaffiliated with Anthropic; you sign in with your own Claude account. Sideload only.

| | [Android](android/) | [iOS](ios/) |
|---|---|---|
| Sandbox | Alpine under proot, native speed | Alpine in iSH-AOK's emulator (no JIT on iOS), slower |
| Claude Code | Downloaded from npm on first launch once you agree to Anthropic's license, sha512-verified, updated daily | The same |
| **Phone Use** module: phone control | A separate virtual screen in picture-in-picture (Shizuku + accessibility) | Your real screen, through the iCTRL runner |
| **Phone Use** module: shell access to the phone | `rish` / `adb shell` through Shizuku | None (iOS has no equivalent) |
| **Network Monitor** module | What the phone's apps send and receive, through a VPN into mitmproxy | Not available |
| **App Development** module | Building Android apps on the phone, and taking APKs apart | Not available |
| License | MIT | GPL-3.0 (iSH-AOK's emulator is GPL) |

## Modules

The base app runs Claude Code and nothing else. Each module is off until you turn it on in the
app's ⋮ menu → **Modules**, which downloads what it needs the first time. While a module is off,
Claude doesn't see its commands and its parts of the app stay switched off. In the code, each module
lives in a folder of its own, plugged into the base through one small interface that's the same
shape on both platforms.

See [android/README.md](android/README.md) and [ios/README.md](ios/README.md) for details,
installation and building.

## License

Everything outside `ios/` is MIT ([LICENSE](LICENSE)). `ios/` is GPL-3.0 ([ios/LICENSE](ios/LICENSE)),
because it is built on [iSH-AOK](https://github.com/emkey1/ish-AOK).
