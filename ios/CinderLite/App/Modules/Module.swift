import Foundation

/// ⋮'s items, in order: title, checked (nil when it isn't a toggle), action.
typealias MenuItem = (title: String, checked: Bool?, run: () -> Void)

/// A part of Cinder Lite that's off until the user turns it on (⋮ → Modules), so the base app stays a
/// plain Claude Code runner. The same design as the Android app's `app.cinder.module.Module`, member
/// for member, so a module reads the same on both; iOS has Phone Use only, so far.
///
/// A module is this class plus its sandbox files, `Guest/modules/<id>/`: `bin/` (helpers, on PATH
/// only while it's on) and `brief.md` (its part of Claude's brief, likewise). The base never names a
/// module; it asks the registry (`Modules`).
protocol CinderModule: AnyObject {
    /// Also its folder under Guest/modules, and how it's stored in the enabled set.
    var id: String { get }
    var title: String { get }
    /// A few words for the brief's list of modules, e.g. "using apps on this iPhone".
    var blurb: String { get }

    /// Added to every session's environment while it's on (Android: the prelude).
    var environment: [String] { get }

    /// Request files in /root/.cinder it handles, while it's on; `handle` gets their content.
    var requests: [String] { get }
    func handle(request: String, data: Data)

    /// Its items in the main ⋮ menu, while it's on.
    func menuItems(_ controller: TerminalController) -> [MenuItem]

    /// Installed the first time it's turned on, in the Shell tab: Alpine packages, then `install`
    /// (shell commands), then its installed stamp. `download` is the rough size, for the ask.
    var packages: [String] { get }
    var install: String? { get }
    var download: String? { get }

    /// URL schemes /dev/url may open while it's on, beyond https. iOS can't switch off parts of a
    /// signed app, so this (and `apply`) is what Android's disabled components are here.
    var urlSchemes: [String] { get }

    /// Called at launch and whenever it's turned on or off, to set up or undo its effect on the app.
    func apply(on: Bool)
    /// ⋮ → Stop, and turning it off: whatever it has running.
    func stop()
    /// Whether this install already uses it, for turning it on once after the update that
    /// introduced modules.
    func isInUse() -> Bool
}

extension CinderModule {
    var environment: [String] { [] }
    var requests: [String] { [] }
    func handle(request: String, data: Data) {}
    func menuItems(_ controller: TerminalController) -> [MenuItem] { [] }
    var packages: [String] { [] }
    var install: String? { nil }
    var download: String? { nil }
    var urlSchemes: [String] { [] }
    func apply(on: Bool) {}
    func stop() {}
    func isInUse() -> Bool { false }
}

/// Every module, which are on, and what that changes. As on Android.
enum Modules {
    /// In ⋮ → Modules order. Android has Phone Use, Network Monitor and App Development; the iOS app
    /// has only the first so far, and a module it doesn't have isn't listed.
    static let all: [CinderModule] = [PhoneUse()]

    private static let key = "modules"
    private static var ids: Set<String> {
        get { Set(UserDefaults.standard.stringArray(forKey: key) ?? []) }
        set { UserDefaults.standard.set(all.map(\.id).filter(newValue.contains), forKey: key) }
    }

    static func isOn(_ module: CinderModule) -> Bool { ids.contains(module.id) }
    static var enabled: [CinderModule] { all.filter(isOn) }

    /// Before anything reads the enabled set: the first launch after modules arrived turns on the
    /// ones this install already uses, so an update changes nothing for them. Once, ever.
    static func migrate() {
        guard UserDefaults.standard.object(forKey: key) == nil else { return }
        ids = Set(all.filter { $0.isInUse() }.map(\.id))
    }

    /// At launch, once the sandbox is unpacked: each module's effect on the app, and its requests.
    /// One that was installed into a root since reinstalled (its stamp went with it) is turned off,
    /// to be installed again when the user next turns it on.
    static func start() {
        let lost = enabled.filter { !isInstalled($0) && !($0.packages.isEmpty && $0.install == nil) }
        if !lost.isEmpty {
            ids.subtract(lost.map(\.id))
            try? Sandbox.shared.writeHelpers()   // bootstrap wrote them with these still on
        }
        for module in enabled where !isInstalled(module) { markInstalled(module) }
        publish()
        for module in all { module.apply(on: isOn(module)) }
        ModuleRequests.shared.start()
    }

    /// Turns `module` on or off for the app and the sandbox. Installing it, the first time, is the
    /// caller's to do first (TerminalController.setModule); sessions see the change when they start.
    static func set(_ module: CinderModule, on: Bool) {
        if on { ids.insert(module.id) } else { module.stop(); ids.remove(module.id) }
        publish()
        module.apply(on: on)
        try? Sandbox.shared.writeHelpers()
    }

    /// Its installed stamp, outside the root (whose files only the emulator may create) but cleared
    /// with it by Reinstall sandbox, so a reinstalled sandbox installs it afresh.
    private static func stamp(_ module: CinderModule) -> URL { Sandbox.shared.installed.appendingPathComponent(module.id) }
    static func isInstalled(_ module: CinderModule) -> Bool { FileManager.default.fileExists(atPath: stamp(module).path) }
    static func markInstalled(_ module: CinderModule, _ installed: Bool = true) {
        let file = stamp(module)
        if installed {
            try? FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
            FileManager.default.createFile(atPath: file.path, contents: nil)
        } else {
            try? FileManager.default.removeItem(at: file)
        }
    }

    /// For URLDevice.m, which reads it from the defaults (the sandbox can't write those).
    private static func publish() {
        UserDefaults.standard.set(enabled.flatMap(\.urlSchemes), forKey: "urlSchemes")
    }

    /// The brief's line about modules, before the enabled ones' own parts.
    static var briefLine: String {
        let list = all.map { "\($0.title) (\($0.blurb))" }.joined(separator: ", ")
        return "- Optional modules the user can turn on in ⋮ → Modules: \(list). Each is off unless described " +
            "below; when a task needs one that's off, tell the user where to turn it on rather than working around it."
    }
}
