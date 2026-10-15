import CoreLocation
import UIKit

/// ⋮ → Keep running in background. iOS suspends an app soon after it leaves the screen, and with
/// it the emulator and Claude mid-task. An app receiving location updates keeps running, so while
/// this is on and a session runs, Cinder Lite asks for them: the coarsest accuracy, nothing kept or
/// sent anywhere, and iOS shows its location indicator meanwhile. Off by default.
final class KeepAlive: NSObject, CLLocationManagerDelegate {
    static let shared = KeepAlive()
    private static let key = "keepAlive"
    private let manager = CLLocationManager()
    private var wanted = false   // a session is running

    var enabled: Bool {
        get { UserDefaults.standard.bool(forKey: Self.key) }
        set {
            UserDefaults.standard.set(newValue, forKey: Self.key)
            if newValue && manager.authorizationStatus == .notDetermined { manager.requestWhenInUseAuthorization() }
            update()
        }
    }

    /// Whether iOS refused location to Cinder Lite (Settings → Cinder Lite → Location to change it).
    var denied: Bool { [.denied, .restricted].contains(manager.authorizationStatus) }

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

    private func update() {
        let authorized = [.authorizedWhenInUse, .authorizedAlways].contains(manager.authorizationStatus)
        // For the sandbox: `phone start` refuses without it (/root/.cinder/keep-alive).
        let flag = Sandbox.shared.requests.appendingPathComponent("keep-alive")
        if enabled && authorized { FileManager.default.createFile(atPath: flag.path, contents: Data("on\n".utf8)) }
        else { try? FileManager.default.removeItem(at: flag) }
        if enabled && wanted && authorized {
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
