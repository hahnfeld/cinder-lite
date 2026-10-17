import UIKit

/// Phone Use: Claude uses apps on this iPhone with `phone` (Guest/modules/phone), through iCTRL's
/// runner, a separate app the user installs. The Android app's module of the same name does it
/// through Shizuku and a second screen; the commands are the same.
///
/// What it changes in the app while it's on: the sandbox may open `ictrl:` links (`phone start` starts
/// the runner with one), and it gets /root/.cinder/keep-alive whenever Keep running in background is
/// on, since `phone start` refuses without it: Cinder Lite is in the background while Claude works
/// another app. Off, the pairing key `phone` keeps (/root/.cinder/ictrl.json) is deleted too, so the
/// sandbox can't reach iCTRL's runner until the user allows it again.
final class PhoneUse: CinderModule {
    let id = "phone"
    let title = "Phone Use"
    let blurb = "use apps on this iPhone"
    let urlSchemes = ["ictrl"]

    private var observer: NSObjectProtocol?
    private var flag: URL { Sandbox.shared.requests.appendingPathComponent("keep-alive") }

    /// `phone` needs Cinder Lite awake while Claude works another app.
    func turnedOn(_ controller: TerminalController) {
        if !KeepAlive.shared.enabled { controller.setKeepAlive(true) }
    }

    /// Whether iCTRL is there to use, checked when it's turned on: `phone setup` says the same in the sandbox.
    func nextStep() -> String? {
        guard let ictrl = URL(string: "ictrl://"), UIApplication.shared.canOpenURL(ictrl) else {
            return "Claude uses apps on this iPhone through iCTRL, a separate open-source app that drives the screen " +
                "with Apple's UI-testing tools. It isn't installed: setting it up takes a Mac or a sideloading app with " +
                "developer tools (see Cinder Lite's README)."
        }
        if runnerAnswers() { return nil }
        return "iCTRL's runner isn't running. Claude's phone start starts it; if that fails, start it in the iCTRL app " +
            "(after a reboot, iCTRL first mounts its developer disk image again)."
    }

    /// iCTRL's runner listening on 127.0.0.1:8200, as `phone` reaches it. Loopback answers or refuses at
    /// once, so the wait is short even on the main thread.
    private func runnerAnswers() -> Bool {
        var request = URLRequest(url: URL(string: "http://127.0.0.1:8200/whoami")!)
        request.timeoutInterval = 1
        let done = DispatchSemaphore(value: 0)
        var answered = false
        URLSession.shared.dataTask(with: request) { _, response, _ in
            answered = response != nil
            done.signal()
        }.resume()
        _ = done.wait(timeout: .now() + 1.5)
        return answered
    }

    func apply(on: Bool) {
        if let observer { NotificationCenter.default.removeObserver(observer) }
        observer = nil
        guard on else {
            try? FileManager.default.removeItem(at: flag)
            try? FileManager.default.removeItem(at: Sandbox.shared.requests.appendingPathComponent("ictrl.json"))
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
}
