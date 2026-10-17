import Foundation

/// The app's own Linux sandbox: iSH-AOK's emulator on an Alpine root, in the app's private
/// storage. Laid out like the Android app's, with the same guest paths:
///
///     Library/Cinder/
///       root/         the Alpine root (fakefs: data/ + meta.db), unpacked once from the bundle
///       host/bin      /usr/local/bin       helper commands (xdg-open, and enabled modules' helpers)
///       host/libexec  /usr/local/libexec   setup and launch scripts, Claude's brief and hooks
///       host/cinder   /root/.cinder        requests the app watches (notifications)
///       host/dl       /usr/local/dl        the verified Claude Code installer, while installing
///     Documents/      /workspace           Claude's working directory, shown in the Files app
final class Sandbox {
    static let shared = Sandbox()

    let root: URL
    let bin: URL
    let libexec: URL
    let requests: URL
    let downloads: URL
    let workspace: URL
    private let stamp: URL

    private init() {
        let fm = FileManager.default
        let base = fm.urls(for: .libraryDirectory, in: .userDomainMask)[0].appendingPathComponent("Cinder")
        root = base.appendingPathComponent("root")
        let host = base.appendingPathComponent("host")
        bin = host.appendingPathComponent("bin")
        libexec = host.appendingPathComponent("libexec")
        requests = host.appendingPathComponent("cinder")
        downloads = host.appendingPathComponent("dl")
        workspace = fm.urls(for: .documentDirectory, in: .userDomainMask)[0]
        stamp = base.appendingPathComponent(".bootstrap-v1")
    }

    var isBootstrapped: Bool { FileManager.default.fileExists(atPath: stamp.path) }

    /// The guest's files are real files under root/data (fakefs keeps only their metadata apart).
    private func guestFile(_ path: String) -> URL { root.appendingPathComponent("data" + path) }

    var shell: String { FileManager.default.fileExists(atPath: guestFile("/bin/bash").path) ? "/bin/bash" : "/bin/sh" }

    /// Unpacks the bundled Alpine root, once. The stamp is written last, so an unpack that was
    /// interrupted starts over next time.
    func bootstrap() throws {
        for dir in [bin, libexec, requests, downloads, workspace] {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        if UserDefaults.standard.bool(forKey: Self.resetKey) { try reset() }
        if !isBootstrapped {
            try? FileManager.default.removeItem(at: root)
            let archive = Bundle.main.path(forResource: "rootfs", ofType: "tgz")!
            try CinderKernel.importRootArchive(archive, into: root.path, progress: nil)
            try "1".write(to: stamp, atomically: true, encoding: .utf8)
        }
        writeHelpers()
    }

    /// Mounts the root and the host folders and starts init. Once per process.
    func boot() throws {
        try CinderKernel.bootRoot(root.path, mounts: mounts.mapValues(\.path))
    }

    private var mounts: [String: URL] {
        ["/usr/local/bin": bin, "/usr/local/libexec": libexec, "/root/.cinder": requests, "/usr/local/dl": downloads,
         "/workspace": workspace]
    }

    /// The host folders whose files the sandbox can see by name: the root and the mounts, but not
    /// /root/.cinder (hidden; it holds the app's requests and iCTRL's pairing key).
    var visible: [URL] { [guestFile("/"), bin, libexec, downloads, workspace] }

    /// The real file behind a path in the sandbox, through the same mounts as boot.
    func hostFile(_ guest: String) -> URL {
        for (path, dir) in mounts where guest == path || guest.hasPrefix(path + "/") {
            return dir.appendingPathComponent(String(guest.dropFirst(path.count)))
        }
        return guestFile(guest)
    }

    /// Asks for a fresh install at the next launch: the kernel holds the root until the process ends.
    func requestReset() { UserDefaults.standard.set(true, forKey: Self.resetKey) }
    private static let resetKey = "resetPending"

    /// Back to a fresh install: the root, with everything installed into it, Claude Code's binaries,
    /// and the helper folders (rewritten at launch, whatever the sandbox left in them). As on Android,
    /// the login, settings and history stay: /root (but Claude Code itself) is moved aside first and
    /// cinder-setup puts it back. /workspace isn't in the root.
    private func reset() throws {
        let fm = FileManager.default
        for dir in [bin, libexec] {
            try? fm.removeItem(at: dir)
            try fm.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        let keep = downloads.appendingPathComponent("keep")
        if !fm.fileExists(atPath: keep.path) {
            let home = guestFile("/root")
            if fm.fileExists(atPath: home.path) {
                try fm.moveItem(at: home, to: keep)
                for path in [".local/share/claude", ".local/bin/claude", ".cinder"] {
                    try? fm.removeItem(at: keep.appendingPathComponent(path))
                }
            }
        }
        try? fm.removeItem(at: stamp)
        try? fm.removeItem(at: root)
        for name in ["claude-installer", "progress", "error", "request"] {
            try? fm.removeItem(at: downloads.appendingPathComponent(name))
        }
        UserDefaults.standard.removeObject(forKey: Self.resetKey)
    }

    /// The bundled scripts and settings, rewritten every launch (an app update's fixes reach
    /// existing installs) and whenever a module is turned on or off: the base's, then each enabled
    /// module's (Guest/modules/<id>/bin), while a disabled module's helpers are removed. Claude's
    /// brief is the base's, the line about modules, then each enabled module's brief.md. A file that
    /// can't be written is skipped: the sandbox can put things in the way, and it mustn't stop the app.
    func writeHelpers() {
        let guest = Bundle.main.url(forResource: "Guest", withExtension: nil)!
        let modules = guest.appendingPathComponent("modules")
        var dirs = [(guest.appendingPathComponent("bin"), bin), (guest.appendingPathComponent("libexec"), libexec)]
        for module in Modules.all {
            let helpers = modules.appendingPathComponent(module.id).appendingPathComponent("bin")
            if Modules.isOn(module) { dirs.append((helpers, bin)) }
            else { for name in files(in: helpers) { try? FileManager.default.removeItem(at: bin.appendingPathComponent(name)) } }
        }
        for (from, to) in dirs {
            for name in files(in: from) where name != "cinder-brief.md" {
                try? install(Data(contentsOf: from.appendingPathComponent(name)), as: to.appendingPathComponent(name))
            }
        }
        func text(_ path: String) -> String { (try? String(contentsOf: guest.appendingPathComponent(path), encoding: .utf8)) ?? "" }
        let brief = text("libexec/cinder-brief.md") + Modules.briefLine + "\n" +
            Modules.enabled.map { text("modules/\($0.id)/brief.md") }.joined()
        try? install(Data(brief.utf8), as: libexec.appendingPathComponent("cinder-brief.md"))
    }

    /// A bundled folder's files, without dotfiles (the build takes out .claude and __pycache__).
    private func files(in dir: URL) -> [String] {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []
        return names.filter { !$0.hasPrefix(".") }.sorted()
    }

    /// Through a temp file and a rename, so a running script keeps its file. The folders are the
    /// sandbox's to write too, so nothing it put there is followed: whatever is at the temp name goes
    /// first, the temp file is created new (not through a link), and the rename replaces the name itself.
    private func install(_ data: Data, as file: URL) throws {
        let part = file.deletingLastPathComponent().appendingPathComponent(".\(file.lastPathComponent).part")
        try? FileManager.default.removeItem(at: part)
        // Scripts run as commands; the brief and the settings are only read.
        let name = file.lastPathComponent
        let mode: mode_t = name.hasSuffix(".md") || name.hasSuffix(".json") ? 0o644 : 0o755
        let fd = open(part.path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, mode)
        guard fd >= 0 else { throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO) }
        let handle = FileHandle(fileDescriptor: fd, closeOnDealloc: true)
        try handle.write(contentsOf: data)
        fchmod(fd, mode)   // whatever the umask took off
        guard rename(part.path, file.path) == 0 else { throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO) }
    }

    /// The environment every session starts with (Android's prelude, minus its Android parts).
    var environment: [String] {
        // The phone's time zone (tzdata has the rules), so clocks in here read local time.
        let zone = TimeZone.current.identifier
        let tz = zone.range(of: "^[A-Za-z0-9_+-]+(/[A-Za-z0-9_+-]+)*$", options: .regularExpression) != nil ? ["TZ=\(zone)"] : []
        return tz + [
            "PATH=/usr/local/bin:/root/.npm-global/bin:/root/.local/bin:/usr/bin:/bin:/usr/sbin:/sbin",
            "HOME=/root", "TMPDIR=/tmp", "SHELL=\(shell)", "TERM=xterm-256color", "LANG=C.UTF-8",
            // npm -g and pip install into the home directory, which is on PATH.
            "NPM_CONFIG_PREFIX=/root/.npm-global",
            "PIP_USER=1", "PIP_BREAK_SYSTEM_PACKAGES=1", "PIP_DISABLE_PIP_VERSION_CHECK=1",
            "PYTHONUSERBASE=/root/.local",
            // Nothing in here can open a browser; xdg-open hands the URL to iOS (Claude Code's /login).
            "BROWSER=xdg-open",
            // Claude Code runs on Bun, whose JIT only adds work in an emulator: the code it compiles is
            // emulated like any other. In testing, a one-line `claude -p` went from ~15 s to ~10 s.
            "BUN_JSC_useJIT=0",
            // No telemetry, error reports or update check holding up every start (then ~10 s down to ~5 s);
            // cinder-claude runs `claude update` once a day instead.
            "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1",
        ]
    }
}
