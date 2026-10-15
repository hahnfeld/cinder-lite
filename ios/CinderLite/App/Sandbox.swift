import Foundation

/// The app's own Linux sandbox: iSH-AOK's emulator on an Alpine root, in the app's private
/// storage. Laid out like the Android app's, with the same guest paths:
///
///     Library/Cinder/
///       root/         the Alpine root (fakefs: data/ + meta.db), unpacked once from the bundle
///       host/bin      /usr/local/bin       helper commands (xdg-open, phone)
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
        try writeHelpers()
    }

    /// Mounts the root and the host folders and starts init. Once per process.
    func boot() throws {
        try CinderKernel.bootRoot(root.path, mounts: mounts.mapValues(\.path))
    }

    private var mounts: [String: URL] {
        ["/usr/local/bin": bin, "/usr/local/libexec": libexec, "/root/.cinder": requests, "/usr/local/dl": downloads,
         "/workspace": workspace]
    }

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

    /// Back to a fresh install: the root, with everything installed into it, and Claude Code's
    /// binaries. As on Android, the login, settings and history stay: /root (but Claude Code itself)
    /// is moved aside first and cinder-setup puts it back. /workspace isn't in the root.
    private func reset() throws {
        let fm = FileManager.default
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
    /// existing installs). Each through a temp file and a rename, so a running script keeps its file.
    private func writeHelpers() throws {
        let guest = Bundle.main.url(forResource: "Guest", withExtension: nil)!
        for (from, to) in [(guest.appendingPathComponent("bin"), bin), (guest.appendingPathComponent("libexec"), libexec)] {
            for name in try FileManager.default.contentsOfDirectory(atPath: from.path) where !name.hasPrefix(".") {
                let part = to.appendingPathComponent(".\(name).part")
                try? FileManager.default.removeItem(at: part)
                try FileManager.default.copyItem(at: from.appendingPathComponent(name), to: part)
                // Scripts run as commands; the brief and the settings are only read.
                let mode = name.hasSuffix(".md") || name.hasSuffix(".json") ? 0o644 : 0o755
                try FileManager.default.setAttributes([.posixPermissions: mode], ofItemAtPath: part.path)
                _ = try FileManager.default.replaceItemAt(to.appendingPathComponent(name), withItemAt: part)
            }
        }
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
        ]
    }
}
