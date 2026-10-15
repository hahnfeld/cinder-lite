import UIKit

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        run_at_boot()   // the emulator's once-per-process setup
        return true
    }

    func application(_ application: UIApplication, configurationForConnecting session: UISceneSession,
                     options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        UISceneConfiguration(name: "Default", sessionRole: session.role)
    }
}

final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?
    private let controller = TerminalController()

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession,
               options connectionOptions: UIScene.ConnectionOptions) {
        guard let scene = scene as? UIWindowScene else { return }
        let window = UIWindow(windowScene: scene)
        window.rootViewController = controller
        window.overrideUserInterfaceStyle = .dark
        window.makeKeyAndVisible()
        self.window = window
    }

    /// Back in the app: the keyboard comes back up, and Claude's alert has done its job.
    func sceneDidBecomeActive(_ scene: UIScene) {
        Notifier.shared.visible = true
        Notifier.shared.clear()
        controller.showKeyboard()
    }

    /// Claude's alerts only become notifications while the app isn't on screen.
    func sceneWillResignActive(_ scene: UIScene) {
        Notifier.shared.visible = false
    }
}
