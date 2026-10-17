import Foundation

/// Phone Use: Claude uses apps on this iPhone with `phone` (Guest/modules/phone), through iCTRL's
/// runner, a separate app the user installs. The Android app's module of the same name does it
/// through Shizuku and a second screen; the commands are the same.
///
/// What it changes in the app while it's on: /dev/url may open `ictrl:` links (`phone start` starts
/// the runner with one), and the sandbox gets /root/.cinder/keep-alive whenever Keep running in
/// background is on, since `phone start` refuses without it: Cinder Lite is in the background
/// while Claude works another app.
final class PhoneUse: CinderModule {
    let id = "phone"
    let title = "Phone Use"
    let blurb = "using apps on this iPhone"
    let urlSchemes = ["ictrl"]

    private var observer: NSObjectProtocol?
    private var flag: URL { Sandbox.shared.requests.appendingPathComponent("keep-alive") }

    func menuItems(_ controller: TerminalController) -> [MenuItem] {
        [("Phone control…", nil, { [weak controller] in
            controller?.alert("Phone control", "Claude can use apps on this iPhone through iCTRL, a separate open-source " +
                              "app that drives the screen with Apple's UI-testing tools. Setting it up takes a Mac or a " +
                              "sideloading app with developer tools; see the README. Once its runner is going, Claude uses " +
                              "`phone` in the sandbox, and Keep running in background must be on: Cinder Lite is behind the " +
                              "app being used.")
        })]
    }

    func apply(on: Bool) {
        if let observer { NotificationCenter.default.removeObserver(observer) }
        observer = nil
        guard on else {
            try? FileManager.default.removeItem(at: flag)
            return
        }
        observer = NotificationCenter.default.addObserver(forName: KeepAlive.changed, object: nil, queue: .main) { [weak self] _ in
            self?.writeFlag()
        }
        writeFlag()
    }

    private func writeFlag() {
        if KeepAlive.shared.allowed { FileManager.default.createFile(atPath: flag.path, contents: Data("on\n".utf8)) }
        else { try? FileManager.default.removeItem(at: flag) }
    }

    /// Paired with iCTRL before modules arrived: its key outlives a reinstall, as `phone` keeps it.
    func isInUse() -> Bool {
        FileManager.default.fileExists(atPath: Sandbox.shared.requests.appendingPathComponent("ictrl.json").path)
    }
}
