import Foundation

/// ⋮'s items, in order: title, checked (nil when it isn't a toggle), action.
typealias MenuItem = (title: String, checked: Bool?, run: () -> Void)

/// A part of Cinder Lite that's off until the user turns it on (⋮ → Modules), so the base app stays a
/// plain Claude Code runner. The same design as the Android app's `app.cinder.module.Module`, with only
/// the members iOS's modules use so far (Android's also has installs, requests and PATH entries, for
/// modules iOS doesn't have); iOS has Phone Use only.
///
/// A module is this class plus its sandbox files, `Guest/modules/<id>/`: `bin/` (helpers, on PATH
/// only while it's on) and `brief.md` (its part of Claude's brief, likewise). The base never names a
/// module; it asks the registry (`Modules`).
protocol CinderModule: AnyObject {
    /// Also its folder under Guest/modules, and how it's stored in the enabled set.
    var id: String { get }
    var title: String { get }
    /// What it lets Claude do, in a few words, for the brief's list of modules.
    var blurb: String { get }

    /// URL schemes the sandbox may open while it's on, beyond https (/dev/url, and links tapped in the
    /// terminal). iOS can't switch off parts of a signed app, so this (and `apply`) is what Android's
    /// disabled components are here.
    var urlSchemes: [String] { get }

    /// Called at launch and whenever it's turned on or off, to set up or undo its effect on the app.
    func apply(on: Bool)
    /// What the user still has to do outside Cinder Lite once it's on, for the "it's on" dialog; nil
    /// if nothing.
    func nextStep() -> String?
    /// It's just been turned on: turns on, or asks the user for, whatever else it needs, so everything
    /// it does works from now on. After the "it's on" dialog.
    func turnedOn(_ controller: TerminalController)
    /// ⋮ → Stop (asked of every module, on or off), and turning it off: whatever it has running.
    func stop()
}

extension CinderModule {
    var urlSchemes: [String] { [] }
    func apply(on: Bool) {}
    func nextStep() -> String? { nil }
    func turnedOn(_ controller: TerminalController) {}
    func stop() {}
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

    /// At launch, once the sandbox is unpacked: each module's effect on the app.
    static func start() {
        publish()
        for module in all { module.apply(on: isOn(module)) }
    }

    /// Turns `module` on or off for the app and the sandbox; sessions see the change when they start.
    static func set(_ module: CinderModule, on: Bool) {
        if on { ids.insert(module.id) } else { module.stop(); ids.remove(module.id) }
        publish()
        module.apply(on: on)
        Sandbox.shared.writeHelpers()
    }

    /// For URLDevice.m, which reads it from the defaults (the sandbox can't write those).
    private static func publish() {
        UserDefaults.standard.set(enabled.flatMap(\.urlSchemes), forKey: "urlSchemes")
    }

    /// The brief's line about modules, before the enabled ones' own parts. Android's says the same.
    static var briefLine: String {
        let list = all.map { "\($0.title) (\(isOn($0) ? "on" : "off")): \($0.blurb)" }.joined(separator: "; ")
        return "- Cinder Lite has optional modules, which the user turns on in its ⋮ menu → Modules: \(list). " +
            "If a task needs one that's off, tell the user how to turn it on rather than working around it."
    }
}
