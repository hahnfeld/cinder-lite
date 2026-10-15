import CryptoKit
import QuickLook
import UIKit
import UserNotifications

/// Calls `changed` on the main queue whenever an entry is added to, removed from or renamed in `dir`.
final class DirectoryWatcher {
    private let source: DispatchSourceFileSystemObject

    init?(_ dir: URL, changed: @escaping () -> Void) {
        let fd = open(dir.path, O_EVTONLY)
        if fd < 0 { return nil }
        source = DispatchSource.makeFileSystemObjectSource(fileDescriptor: fd, eventMask: .write, queue: .main)
        source.setEventHandler(handler: changed)
        source.setCancelHandler { close(fd) }
        source.resume()
        changed()   // anything left from before the watch started
    }

    deinit { source.cancel() }
}

/// Reads a request file and removes it. Anything in the sandbox can write these, so only a small
/// regular file is read: not a link (to /dev/zero, say), a pipe that would block, or a huge file.
func take(_ file: URL) -> Data? {
    defer { unlink(file.path) }
    var st = stat()
    guard lstat(file.path, &st) == 0, st.st_mode & S_IFMT == S_IFREG, st.st_size <= 1 << 16 else { return nil }
    let fd = open(file.path, O_RDONLY | O_NOFOLLOW)
    if fd < 0 { return nil }
    return FileHandle(fileDescriptor: fd, closeOnDealloc: true).readDataToEndOfFile()
}

/// xdg-open on a file: /root/.cinder/open-request names it, and it opens in iOS's preview, whose share
/// button hands it to the app the user picks. Never to an app chosen for them, since anything in the
/// sandbox can call xdg-open; hidden files (logins, keys) aren't handed out.
final class FileOpener: NSObject, QLPreviewControllerDataSource {
    static let shared = FileOpener()
    private var watcher: DirectoryWatcher?
    private weak var presenter: UIViewController?
    private var file: URL?

    func start(presenter: UIViewController) {
        guard watcher == nil else { return }
        self.presenter = presenter
        let request = Sandbox.shared.requests.appendingPathComponent("open-request")
        watcher = DirectoryWatcher(Sandbox.shared.requests) { [weak self] in
            guard FileManager.default.fileExists(atPath: request.path), let data = take(request),
                  let guest = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines) else { return }
            self?.open(guest)
        }
    }

    private func open(_ guest: String) {
        guard guest.hasPrefix("/"), !guest.split(separator: "/").contains(where: { $0.hasPrefix(".") }) else { return }
        let file = Sandbox.shared.hostFile(guest).resolvingSymlinksInPath()
        let fm = FileManager.default
        let roots = [fm.urls(for: .libraryDirectory, in: .userDomainMask)[0].appendingPathComponent("Cinder"), Sandbox.shared.workspace]
        var isDir: ObjCBool = false
        guard roots.contains(where: { file.path.hasPrefix($0.resolvingSymlinksInPath().path + "/") }),
              fm.fileExists(atPath: file.path, isDirectory: &isDir), !isDir.boolValue,
              var top = presenter else { return }
        while let shown = top.presentedViewController { top = shown }
        self.file = file
        let preview = QLPreviewController()
        preview.dataSource = self
        top.present(preview, animated: true)
    }

    func numberOfPreviewItems(in _: QLPreviewController) -> Int { file == nil ? 0 : 1 }
    func previewController(_: QLPreviewController, previewItemAt _: Int) -> QLPreviewItem { file! as NSURL }
}

/// Claude Code's hooks (cinder-settings.json) write each event to /root/.cinder/notify-request.
/// Stop notifies only for turns that ran a minute or more; Notification does when Claude needs
/// something (permission, a question), but not its idle "waiting for your input" reminder, which
/// would ping after every reply. Nothing is posted while the app is on screen.
final class Notifier {
    static let shared = Notifier()
    private var watcher: DirectoryWatcher?
    private var turnStarted = Date.distantFuture   // unknown start: never counts as long

    func start() {
        guard watcher == nil else { return }
        let file = Sandbox.shared.requests.appendingPathComponent("notify-request")
        watcher = DirectoryWatcher(Sandbox.shared.requests) { [weak self] in
            guard FileManager.default.fileExists(atPath: file.path), let data = take(file),
                  let event = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return }
            self?.onHook(event)
        }
    }

    /// Asked when Keep running in background is turned on: only then can Claude finish (and
    /// notify) while the app is away.
    func requestPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    private func onHook(_ event: [String: Any]) {
        let message: String
        switch event["hook_event_name"] as? String {
        case "UserPromptSubmit":
            turnStarted = Date()
            KeepAlive.shared.claudeTurn(running: true)
            return
        case "Stop":
            KeepAlive.shared.claudeTurn(running: false)
            let took = Date().timeIntervalSince(turnStarted)
            turnStarted = .distantFuture
            if took < 60 { return }
            message = "Claude has finished"
        default:
            if event["notification_type"] as? String == "idle_prompt" { return }
            guard let text = event["message"] as? String, !text.trimmingCharacters(in: .whitespaces).isEmpty else { return }
            message = text
        }
        if UIApplication.shared.applicationState == .active { return }   // on screen: Claude's own UI says it
        let content = UNMutableNotificationContent()
        content.title = "Claude Code"   // fixed: the sandbox writes these events, and could pose as another app
        content.body = String(message.prefix(200))
        content.sound = .default
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "claude", content: content, trigger: nil))
    }

    /// Back in the app: Claude's alert has done its job.
    func clear() {
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["claude"])
    }
}

/// Installs the latest Claude Code, when cinder-setup asks (after the user agreed to it; see there).
/// The official linux-arm64-musl build of npm's current release is downloaded, what `npm install`
/// would fetch on a laptop, and checked against the sha512 npm publishes for it. Its `claude` is
/// handed to the guest as /usr/local/dl/claude-installer, whose own `claude install` sets up the
/// native install in /root. From there Claude Code keeps itself up to date. As on Android.
final class ClaudeInstaller: NSObject, URLSessionDownloadDelegate {
    static let shared = ClaudeInstaller()
    private static let npmLatest = URL(string: "https://registry.npmjs.org/@anthropic-ai/claude-code-linux-arm64-musl/latest")!

    private var watcher: DirectoryWatcher?
    private var running = false
    private var version = ""
    private var expected = ""
    private var nextReport: Int64 = 0
    private var finished: ((Result<URL, Error>) -> Void)?
    private lazy var session = URLSession(configuration: .default, delegate: self, delegateQueue: nil)
    private var dl: URL { Sandbox.shared.downloads }

    func start() {
        guard watcher == nil else { return }
        watcher = DirectoryWatcher(dl) { [weak self] in
            guard let self else { return }
            let request = self.dl.appendingPathComponent("request")
            guard FileManager.default.fileExists(atPath: request.path) else { return }
            try? FileManager.default.removeItem(at: request)   // even while downloading: one download answers it
            if !self.running { self.install() }
        }
    }

    private func install() {
        let installer = dl.appendingPathComponent("claude-installer")
        if FileManager.default.fileExists(atPath: installer.path) { return }   // downloaded already
        running = true
        // A minute or so more if the user leaves mid-download; Keep running in background does the rest.
        let task = UIApplication.shared.beginBackgroundTask(withName: "Claude Code download")
        func done(_ error: Error?) {
            if let error { try? "Downloading Claude Code failed: \(error.localizedDescription)".write(to: dl.appendingPathComponent("error"), atomically: true, encoding: .utf8) }
            running = false
            UIApplication.shared.endBackgroundTask(task)
        }
        report("Looking up the latest Claude Code…")
        session.dataTask(with: Self.npmLatest) { data, _, error in
            DispatchQueue.main.async {
                guard let data, let meta = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
                      let version = meta["version"] as? String, let dist = meta["dist"] as? [String: Any],
                      let integrity = dist["integrity"] as? String, integrity.hasPrefix("sha512-"),
                      let tarball = (dist["tarball"] as? String).flatMap(URL.init(string:)), tarball.scheme == "https"
                else { return done(error ?? InstallError("npm's answer wasn't what was expected")) }
                self.version = version
                self.expected = String(integrity.dropFirst("sha512-".count))
                self.nextReport = 0
                self.finished = { result in
                    // Hashing and unpacking 240 MB: off the main queue.
                    DispatchQueue.global(qos: .userInitiated).async {
                        let error: Error?
                        do { try self.unpack(try result.get(), to: installer); error = nil } catch let e { error = e }
                        DispatchQueue.main.async { done(error) }
                    }
                }
                self.report("Downloading Claude Code \(version) (about 240 MB)…")
                self.session.downloadTask(with: tarball).resume()
            }
        }.resume()
    }

    /// Checks the archive's sha512 and copies `package/claude` out of it, to `installer` last
    /// (cinder-setup waits for that name).
    private func unpack(_ archive: URL, to installer: URL) throws {
        defer { try? FileManager.default.removeItem(at: archive) }
        report("Checking the download…")
        guard try sha512(archive) == expected else { throw InstallError("the download failed verification (sha512 mismatch)") }
        let part = dl.appendingPathComponent(".claude-installer.part")
        try? FileManager.default.removeItem(at: part)
        try CinderKernel.extractEntry("package/claude", fromArchive: archive.path, to: part.path)
        try FileManager.default.moveItem(at: part, to: installer)
    }

    private func sha512(_ file: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: file)
        defer { try? handle.close() }
        var hash = SHA512()
        while let chunk = try handle.read(upToCount: 1 << 20), !chunk.isEmpty { hash.update(data: chunk) }
        return Data(hash.finalize()).base64EncodedString()
    }

    /// What cinder-setup shows while it waits.
    private func report(_ line: String) {
        try? line.write(to: dl.appendingPathComponent("progress"), atomically: true, encoding: .utf8)
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData _: Int64,
                    totalBytesWritten: Int64, totalBytesExpectedToWrite _: Int64) {
        guard totalBytesWritten >= nextReport else { return }
        nextReport = totalBytesWritten + (5 << 20)
        report("Downloading Claude Code \(version): \(totalBytesWritten >> 20) MB of about 240")
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        // The file at `location` is gone once this returns.
        let kept = dl.appendingPathComponent(".claude.tgz")
        try? FileManager.default.removeItem(at: kept)
        let code = (downloadTask.response as? HTTPURLResponse)?.statusCode ?? 0
        if code != 200 { finished?(.failure(InstallError("npm answered HTTP \(code)"))); finished = nil; return }
        do {
            try FileManager.default.moveItem(at: location, to: kept)
            finished?(.success(kept))
        } catch {
            finished?(.failure(error))
        }
        finished = nil
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error, task is URLSessionDownloadTask else { return }
        finished?(.failure(error))
        finished = nil
    }
}

struct InstallError: LocalizedError {
    let errorDescription: String?
    init(_ message: String) { errorDescription = message }
}
