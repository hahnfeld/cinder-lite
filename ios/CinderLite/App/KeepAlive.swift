import CoreLocation
import UIKit

/// ⋮ → Keep running in background. iOS suspends an app soon after it leaves the screen, and with
/// it the emulator and Claude mid-task. An app receiving location updates keeps running, so while
/// this is on and a session runs, Cinder Lite asks for them: the coarsest accuracy, nothing kept or
/// sent anywhere, and iOS shows its location indicator meanwhile. Off by default.
///
/// Never indefinitely, as on Android: a forgotten session must not drain the battery. It lapses an
/// hour after the last sign of use (a turn ending, a session starting, the app on screen), or four
/// while a Claude turn runs (an interrupted turn may never say it ended); the next sign renews it.
final class KeepAlive: NSObject, CLLocationManagerDelegate {
    static let shared = KeepAlive()
    private static let key = "keepAlive"
    private let manager = CLLocationManager()
    private var wanted = false   // a session is running
    private var turnRunning = false
    private var lapsed = false
    private var idleTimer: Timer?

    var enabled: Bool {
        get { UserDefaults.standard.bool(forKey: Self.key) }
        set {
            UserDefaults.standard.set(newValue, forKey: Self.key)
            if newValue && manager.authorizationStatus == .notDetermined { manager.requestWhenInUseAuthorization() }
            update()
        }
    }

    /// On, with notifications asked for too (Claude's alerts are how the user hears from it in the
    /// background): ⋮ → Keep running in background, and Phone Use's checklist.
    func turnOn() {
        enabled = true
        Notifier.shared.requestPermission()
    }

    /// Whether iOS refused location to Cinder Lite (Settings → Cinder Lite → Location to change it).
    var denied: Bool { [.denied, .restricted].contains(manager.authorizationStatus) }

    /// On, and iOS lets it work. Posted as `changed` when that changes (Phone Use tells the sandbox).
    var allowed: Bool { enabled && authorized }
    static let changed = Notification.Name("KeepAlive.changed")
    private var wasAllowed = false
    private var authorized: Bool { [.authorizedWhenInUse, .authorizedAlways].contains(manager.authorizationStatus) }

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyThreeKilometers
        manager.distanceFilter = CLLocationDistanceMax
        manager.pausesLocationUpdatesAutomatically = false
        manager.activityType = .other
    }

    /// Called whenever sessions start or end.
    func sessionsRunning(_ running: Bool) {
        wanted = running
        update()
    }

    /// A sign of use: restarts the hour.
    func touch() {
        lapsed = false
        idleTimer?.invalidate()
        idleTimer = Timer.scheduledTimer(withTimeInterval: (turnRunning ? 4 : 1) * 3600, repeats: false) { [weak self] _ in
            self?.lapsed = true
            self?.update()
        }
        update()
    }

    /// Claude Code's hooks: a prompt submitted starts a turn, Stop ends it.
    func claudeTurn(running: Bool) {
        turnRunning = running
        touch()
    }

    private func update() {
        if allowed != wasAllowed {
            wasAllowed = allowed
            NotificationCenter.default.post(name: Self.changed, object: self)
        }
        if enabled && wanted && authorized && !lapsed {
            manager.allowsBackgroundLocationUpdates = true
            manager.showsBackgroundLocationIndicator = true
            manager.startUpdatingLocation()
        } else {
            manager.stopUpdatingLocation()
            if authorized { manager.allowsBackgroundLocationUpdates = false }
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) { update() }
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {}
    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {}
}
