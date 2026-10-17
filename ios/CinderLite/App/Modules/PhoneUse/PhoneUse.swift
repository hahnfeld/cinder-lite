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

    /// Keep running in background (`phone` needs Cinder Lite awake while Claude works another app),
    /// iCTRL, and its runner: what only the user can do. `phone setup` says the same in the sandbox.
    func steps() -> [Step] {
        [
            Step("Keep running in background", "Claude works other apps while Cinder Lite is in the background. iOS " +
                 "keeps it awake with location updates (the coarsest kind, never kept or sent anywhere) and shows " +
                 "the location indicator meanwhile. If location is off for Cinder Lite, this opens Settings.",
                 .ask("Turn on", {
                     if KeepAlive.shared.denied, let settings = URL(string: UIApplication.openSettingsURLString) {
                         UIApplication.shared.open(settings)
                     } else {
                         KeepAlive.shared.turnOn()
                     }
                 }), done: { KeepAlive.shared.allowed }),
            Step("Install iCTRL", "Claude uses apps on this iPhone through iCTRL, a separate open-source app that " +
                 "drives the screen with Apple's UI-testing tools. Setting it up takes a Mac or a sideloading app " +
                 "with developer tools.",
                 .ask("Open install guide", { UIApplication.shared.open(Self.readme) }), done: { Self.hasICTRL }),
            Step("Start iCTRL's runner", "Claude's phone start starts it too. If that fails, start it in the iCTRL " +
                 "app (after a reboot, iCTRL first mounts its developer disk image again).",
                 .ask("Open iCTRL", { UIApplication.shared.open(URL(string: "ictrl://")!) }),
                 optional: true, done: { [weak self] in self?.runnerAnswers() ?? false }),
        ]
    }

    var summary: String? {
        "Ask Claude to do something on the phone. It runs phone start; the first time, iCTRL asks whether to " +
        "allow \"Cinder Lite\": tap Allow."
    }

    private static let readme = URL(string: "https://github.com/hahnfeld/cinder-lite/blob/main/ios/README.md#phone-use-claude-uses-apps-on-your-iphone")!
    private static var hasICTRL: Bool { URL(string: "ictrl://").map(UIApplication.shared.canOpenURL) ?? false }

    /// Whether iCTRL's runner listens on 127.0.0.1:8200, as `phone` reaches it: the last answer, asking
    /// again in the background (the checklist asks every second, on the main thread).
    private var runnerUp = false
    private var asking = false
    private func runnerAnswers() -> Bool {
        guard !asking else { return runnerUp }
        asking = true
        var request = URLRequest(url: URL(string: "http://127.0.0.1:8200/whoami")!)
        request.timeoutInterval = 1
        URLSession.shared.dataTask(with: request) { _, response, _ in
            DispatchQueue.main.async {
                self.runnerUp = response != nil
                self.asking = false
            }
        }.resume()
        return runnerUp
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
