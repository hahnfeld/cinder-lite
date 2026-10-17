import UIKit

enum Colors {
    static let background = UIColor(red: 0x15 / 255.0, green: 0x13 / 255.0, blue: 0x0F / 255.0, alpha: 1)
    static let bar = UIColor(red: 0x1D / 255.0, green: 0x1A / 255.0, blue: 0x15 / 255.0, alpha: 1)
    static let accent = UIColor(red: 0xE0 / 255.0, green: 0x7A / 255.0, blue: 0x3A / 255.0, alpha: 1)
}

/// The two terminals the app keeps, each with its own session: switching keeps both running.
enum Tab {
    case claude, shell

    var title: String { self == .claude ? "Claude" : "Shell" }
    var noun: String { self == .claude ? "Claude Code" : "The shell" }

    var argv: [String] {
        switch self {
        // First-run setup, then Claude Code with Cinder Lite's brief and hooks (Guest/libexec).
        case .claude: return ["/bin/sh", "/usr/local/libexec/cinder-claude"]
        // Not a login shell: Alpine's /etc/profile would reset PATH and drop the sandbox's tools.
        // $SHELL is bash once installed, busybox sh before that, so a shell works for recovery too.
        case .shell: return ["/bin/sh", "-c", "cd /workspace && exec \"$SHELL\""]
        }
    }
}

/// One screen: Claude Code's own TUI in iSH-AOK's terminal view, with a row of the keys a phone
/// keyboard lacks above the keyboard. As on Android: Claude and Shell tabs, ⋮ for the rest.
final class TerminalController: UIViewController {
    private struct Session {
        let terminal: Terminal
        let pid: Int32
        var running = true
    }

    private let terminalView = TerminalView()
    private let status = UILabel()
    /// The checklist on screen over the terminal, if any: setup's, or a module's being turned on.
    private var checklist: Checklist?
    private let checklistView = ChecklistView()
    private var checklistTimer: Timer?
    private var fullChecklist: NSLayoutConstraint!
    private var keys: KeysBar!
    private var sessions: [Tab: Session] = [:]
    private var shown = Tab.claude
    private var booted = false
    private var autocorrect: Autocorrect?
    private static let autocorrectKey = "autocorrect"
    private var autocorrecting: Bool { UserDefaults.standard.object(forKey: Self.autocorrectKey) as? Bool ?? true }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = Colors.background
        keys = KeysBar(controller: self)
        terminalView.translatesAutoresizingMaskIntoConstraints = false
        terminalView.canBecomeFirstResponder = true
        terminalView.inputAccessoryView = keys
        terminalView.controlKey = keys.ctrl   // TerminalView applies (and clears) a sticky ctrl itself
        terminalView.keyboardAppearance = .dark
        view.addSubview(terminalView)
        NSLayoutConstraint.activate([
            terminalView.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor),
            terminalView.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor),
            terminalView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            terminalView.bottomAnchor.constraint(equalTo: view.keyboardLayoutGuide.topAnchor),
        ])
        let pinch = UIPinchGestureRecognizer(target: self, action: #selector(pinched(_:)))
        pinch.delegate = self
        terminalView.addGestureRecognizer(pinch)

        status.textColor = .lightGray
        status.font = .monospacedSystemFont(ofSize: 13, weight: .regular)
        status.numberOfLines = 0
        status.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(status)
        NSLayoutConstraint.activate([
            status.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 12),
            status.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -12),
            status.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 12),
        ])
        checklistView.translatesAutoresizingMaskIntoConstraints = false
        checklistView.isHidden = true
        checklistView.onDetails = { [weak self] in self?.toggleDetails() }
        checklistView.onTap = { [weak self] in self?.refresh() }
        view.addSubview(checklistView)
        // Over the whole screen; with Details open, only as tall as it needs, the terminal below.
        fullChecklist = checklistView.bottomAnchor.constraint(equalTo: view.keyboardLayoutGuide.topAnchor)
        NSLayoutConstraint.activate([
            checklistView.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor),
            checklistView.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor),
            checklistView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            checklistView.bottomAnchor.constraint(lessThanOrEqualTo: view.keyboardLayoutGuide.topAnchor),
            fullChecklist,
        ])
        CinderKernel.sessionExited = { [weak self] pid, status in self?.exited(pid: pid, status: status) }
        start()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        showKeyboard()
    }

    /// At launch, and on every return to the app.
    func showKeyboard() {
        if checklist != nil && !checklistView.showDetails { terminalView.resignFirstResponder(); return }
        if terminalView.terminal != nil && presentedViewController == nil { terminalView.becomeFirstResponder() }
    }

    // MARK: Setup and sessions

    /// Unpacks the sandbox (first launch only), boots it, and starts Claude. Claude Code's own
    /// setup (base tools, and Claude Code once the user agrees to its download) runs in the Claude
    /// tab, from cinder-claude, while setup's checklist shows how far it's got.
    private func start() {
        let setup = setupChecklist()
        if !setup.look().complete { open(setup) }
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                try Sandbox.shared.bootstrap()
                DispatchQueue.main.async {
                    do {
                        try Sandbox.shared.boot()
                        self.booted = true
                        Modules.start()
                        Notifier.shared.start()
                        FileOpener.shared.start(presenter: self)
                        ClaudeInstaller.shared.start()
                        self.launch(.claude)
                        #if DEBUG
                        self.watchDebugInput()
                        #endif
                    } catch {
                        self.failed(error)
                    }
                }
            } catch {
                DispatchQueue.main.async { self.failed(error) }
            }
        }
    }

    private func failed(_ error: Error) {
        close()   // the error shows instead
        status.text = "Couldn't start the sandbox: \(error.localizedDescription)\n\nCheck the free space, then ⋮ → Reinstall sandbox."
    }

    // MARK: Checklists

    /// First-run setup, the same rows as Android's: the app unpacks Alpine and downloads Claude Code
    /// (once the user taps Download); cinder-setup, in the Claude tab, installs it and then the base
    /// tools. Each row is checked against the guest's own files (fakefs keeps them as real files under
    /// root/data).
    private func setupChecklist() -> Checklist {
        let sandbox = Sandbox.shared, fm = FileManager.default
        func file(_ name: String) -> URL { sandbox.downloads.appendingPathComponent(name) }
        func read(_ name: String) -> String? {
            (try? String(contentsOf: file(name), encoding: .utf8))?.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        let request = { _ = fm.createFile(atPath: file("request").path, contents: nil) }
        let installed = { (try? String(contentsOf: sandbox.guestFile("/lib/apk/db/installed"), encoding: .utf8)) ?? "" }

        let claudeCode = Step("Download and install Claude Code", Self.claudeNotice, .download(request),
                              done: { (try? fm.attributesOfItem(atPath: sandbox.guestFile("/root/.local/bin/claude").path)) != nil })
        claudeCode.progress = { fm.fileExists(atPath: file("claude-installer").path) ? "Installing…" : read("progress") }
        let base = Step("Install the base tools", "bash, Python, Node.js, git and the usual command-line tools. In the " +
                        "emulator this takes several minutes; keep Cinder Lite open.", done: {
            let db = installed()
            return ["bash", "python3", "nodejs", "tzdata"].allSatisfy { db.contains("\nP:\($0)\n") }
        })
        let list = Checklist("Setting up Cinder Lite", [Step("Unpack Alpine Linux", done: { sandbox.isBootstrapped }), claudeCode, base])
        // cinder-claude reports a failure in setup-error and waits for Enter; a Claude tab that's gone
        // (⋮ → Stop) has stopped setup too. What happened is in the Claude tab (Details).
        list.failure = { [weak self] in
            read("setup-error") ?? (self?.booted == true && self?.sessions[.claude]?.running != true ? "Setup stopped." : nil)
        }
        list.retry = { [weak self] in
            guard let self else { return }
            for name in ["error", "setup-error"] { try? fm.removeItem(at: file(name)) }
            if !claudeCode.done() { request() }
            if self.sessions[.claude]?.running == true { self.sessions[.claude]?.terminal.sendInput(Data("\r".utf8)) }
            else { self.launch(.claude) }
        }
        return list
    }

    /// Shown before Claude Code is downloaded; the Android app says the same. Claude Code isn't Cinder
    /// Lite's to hand out: it's Anthropic's, under its own license. So its download is the user's call,
    /// made by a tap, never automatic.
    private static let claudeNotice = "Cinder Lite runs Claude Code, but doesn't include it. Claude Code is " +
        "Anthropic's software, distributed separately under its own license (© Anthropic PBC, all rights " +
        "reserved). Its use is subject to Anthropic's legal agreements: " +
        "https://code.claude.com/docs/en/legal-and-compliance\n\nTap Download to get the latest " +
        "version from npm (about 240 MB) and install it here. By downloading it, you accept Anthropic's terms for it."

    /// Shows `list` over the terminal and keeps looking at it, every second, until it's closed.
    private func open(_ list: Checklist) {
        checklist = list
        checklistView.showDetails = false
        fullChecklist.isActive = true
        checklistView.isHidden = false
        terminalView.resignFirstResponder()
        checklistTimer?.invalidate()
        checklistTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.refresh() }
        refresh()
    }

    private func close() {
        checklist = nil
        checklistTimer?.invalidate()
        checklistTimer = nil
        checklistView.isHidden = true
        showKeyboard()
    }

    private func toggleDetails() {
        checklistView.showDetails.toggle()
        fullChecklist.isActive = !checklistView.showDetails
        refresh()
        showKeyboard()
    }

    /// Draws the checklist as it now stands. Setup's closes once it's done (Claude Code starts in the
    /// Claude tab); a module's stays, with its buttons, until the user leaves it.
    private func refresh() {
        guard let list = checklist else { return }
        let look = list.look()
        if list.module == nil && look.complete { return close() }
        checklistView.show(list, look, actions: checklistActions(list, look))
    }

    /// A module's list's buttons at the foot, as on Android; setup's has none.
    private func checklistActions(_ list: Checklist, _ look: Checklist.Look) -> [(String, () -> Void)] {
        guard let module = list.module else { return [] }
        // Finished: just Done. Unfinished: leave it for later, or don't want it after all.
        if look.complete { return [("Done", { [weak self] in self?.close(); self?.offerRestart(module) })] }
        return [("Turn off", { [weak self] in self?.setModule(module, on: false) }), ("Later", { [weak self] in self?.close() })]
    }

    /// Setup's checklist is showing: until it's done, the rest of the app waits.
    private var settingUp: Bool { checklist != nil && checklist?.module == nil }

    /// Starts `tab` in a fresh session (ending its old one, if any) and shows it.
    func launch(_ tab: Tab) {
        guard booted else { return }
        if let old = sessions[tab] {
            sessions[tab] = nil   // its exit, when it comes, is no longer news
            if old.running {
                CinderKernel.signalSession(old.pid, signal: SIGKILL)
                old.terminal.destroy()   // a finished one was already (see exited)
            }
        }
        autocorrect?.forget()
        var pid: Int32 = 0
        do {
            let terminal = try CinderKernel.startSession(tab.argv, environment: Sandbox.shared.environment, pid: &pid)
            terminal.inputFilter = { [weak self] input in self?.filter(input, for: tab) ?? input }
            sessions[tab] = Session(terminal: terminal, pid: pid)
        } catch {
            status.text = "Can't start \(tab.noun): \(error.localizedDescription). Try ⋮ → Reinstall sandbox."
            return
        }
        show(tab)
        KeepAlive.shared.sessionsRunning(true)
        KeepAlive.shared.touch()
    }

    /// Switches the screen to `tab`, starting its session the first time. The other keeps running.
    func show(_ tab: Tab) {
        if checklist?.module != nil { close() }   // a module's list: as if Later
        autocorrect?.forget()
        shown = tab
        guard let session = sessions[tab] else { return launch(tab) }
        terminalView.terminal = session.terminal
        showKeyboard()
    }

    /// Ends both sessions, and whatever any module has running (⋮ → Stop); Enter starts one again.
    func stopAll() {
        for (_, s) in sessions where s.running { CinderKernel.signalSession(s.pid, signal: SIGKILL) }
        for module in Modules.all { module.stop() }
    }

    /// A session's leader exited. Its screen starts over (scrollback is kept) with normal terminal
    /// state and a plain notice, as on Android: Claude's TUI may have left anything on it.
    private func exited(pid: Int32, status: Int32) {
        guard let tab = sessions.first(where: { $0.value.pid == pid })?.key else { return }
        sessions[tab]?.running = false
        KeepAlive.shared.sessionsRunning(sessions.values.contains { $0.running })
        let signal = status & 0x7f, code = (status >> 8) & 0xff
        let how = signal != 0 ? "was stopped" : code == 0 ? "exited" : "exited with code \(code)"
        let notice = "\u{1b}[?1049l\u{1b}[0m\u{1b}[?25h\u{1b}[2J\u{1b}[H\(tab.noun) \(how). Press Enter to start it again.\r\n"
        let bytes = Array(notice.utf8)
        guard let terminal = sessions[tab]?.terminal else { return }
        _ = terminal.sendOutput(bytes, length: Int32(bytes.count))
        // The emulator finds terminals by pty number, and the next session may get this one's: let
        // go of it now, so that session gets a terminal of its own. This one stays on screen.
        terminal.destroy()
    }

    // MARK: Input

    /// Every keyboard input to a terminal comes through here first: a finished session restarts on
    /// Enter; Claude's prompt gets autocorrect (⋮ → Autocorrect, on by default), the shell never does.
    private func filter(_ input: Data, for tab: Tab) -> Data? {
        guard let session = sessions[tab] else { return nil }
        if !session.running {
            if input.contains(13) { DispatchQueue.main.async { self.launch(tab) } }
            return nil
        }
        guard tab == .claude, autocorrecting, let text = String(data: input, encoding: .utf8) else { return input }
        let fixer = autocorrect ?? Autocorrect()
        autocorrect = fixer
        return Data(fixer.filter(text).utf8)
    }

    /// The keys bar's keys: the word being typed is unknown after any of them.
    func send(_ text: String) {
        autocorrect?.forget()
        sessions[shown]?.terminal.sendRawInput(Data(text.utf8))
    }

    func arrow(_ direction: Character) {
        guard let terminal = sessions[shown]?.terminal, let ascii = direction.asciiValue else { return }
        send(terminal.arrow(CChar(ascii)))
    }

    func paste(_ text: String) {
        autocorrect?.forget()
        guard let terminal = sessions[shown]?.terminal else { return }
        guard let sequence = ISHPasteSequence(text, terminal.bracketedPasteEnabled, false) else { return }
        terminal.sendRawInput(Data(sequence.utf8))
    }

    #if DEBUG
    /// Simulator testing, where nothing can type or tap. Files written to Library/Cinder/debug/ (in the
    /// app's container, on the Mac): `type` is typed into the terminal that's showing, as the
    /// keyboard would (through autocorrect and Enter-to-restart); `menu` taps the button it names in the
    /// dialog showing or the checklist showing, or else picks the ⋮ item it names.
    private var debugWatcher: DirectoryWatcher?
    private func watchDebugInput() {
        let dir = Sandbox.shared.root.deletingLastPathComponent().appendingPathComponent("debug")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let typed = dir.appendingPathComponent("type"), picked = dir.appendingPathComponent("menu")
        debugWatcher = DirectoryWatcher(dir) { [weak self] in
            guard let self else { return }
            if FileManager.default.fileExists(atPath: typed.path), let data = take(typed) {
                self.sessions[self.shown]?.terminal.sendInput(data)
            }
            if FileManager.default.fileExists(atPath: picked.path), let data = take(picked),
               let title = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines) {
                // A button of the dialog showing, if there is one (UIKit has no public way to tap it).
                if let alert = self.presentedViewController as? UIAlertController,
                   let action = alert.actions.first(where: { $0.title == title }),
                   let handler = action.value(forKey: "handler") {
                    typealias Handler = @convention(block) (UIAlertAction) -> Void
                    let run = unsafeBitCast(handler as AnyObject, to: Handler.self)
                    alert.dismiss(animated: false) { run(action) }
                } else if let list = self.checklist, case let look = list.look(),
                          let button = (list.steps.indices.flatMap { list.row($0, look).buttons } +
                                        self.checklistActions(list, look)).first(where: { $0.0 == title }) {
                    button.1()   // a checklist's button
                    self.refresh()
                } else if let item = (self.menuItems() + self.moduleItems()).first(where: { $0.title == title }) {
                    item.run()
                }
            }
        }
    }
    #endif

    @objc private func pinched(_ pinch: UIPinchGestureRecognizer) {
        guard pinch.state == .changed, pinch.scale > 1.15 || pinch.scale < 0.87 else { return }
        let prefs = UserPreferences.shared()
        let size = prefs.fontSize.intValue + (pinch.scale > 1 ? 1 : -1)
        prefs.fontSize = NSNumber(value: min(24, max(8, size)))
        terminalView.updateStyle()
        pinch.scale = 1
    }

    // MARK: ⋮

    /// ⋮'s items, in order; `menu` puts Modules before the last.
    private func menuItems() -> [MenuItem] {
        let other: Tab = shown == .claude ? .shell : .claude
        return [
            (other.title, nil, { [weak self] in self?.show(other) }),
            ("Restart \(shown.title)", nil, { [weak self] in self.map { $0.launch($0.shown) } }),
            ("Stop", nil, { [weak self] in self?.stopAll() }),
            ("Autocorrect", autocorrecting, { [weak self] in
                guard let self else { return }
                self.autocorrect?.forget()
                UserDefaults.standard.set(!self.autocorrecting, forKey: Self.autocorrectKey)
            }),
            ("Keep running in background", KeepAlive.shared.enabled, { [weak self] in self?.setKeepAlive(!KeepAlive.shared.enabled) }),
            ("Reinstall sandbox", nil, { [weak self] in self?.reinstall() }),
        ]
    }

    /// ⋮ → Modules: every module, checked once it's on and its checklist is done. Until then a tap
    /// opens the checklist again.
    private func moduleItems() -> [MenuItem] {
        Modules.all.map { module in
            let ready = Modules.isOn(module) && module.isReady
            return (module.title, ready, { [weak self] in self?.setModule(module, on: !ready) })
        }
    }

    func menu() -> UIMenu {
        func action(_ item: MenuItem, _ attributes: UIMenuElement.Attributes = []) -> UIAction {
            UIAction(title: item.title, attributes: attributes, state: item.checked == true ? .on : .off) { _ in item.run() }
        }
        let items = menuItems()
        // While setup's checklist shows, only what can't get in its way: Stop, and starting over.
        if settingUp { return UIMenu(children: items.filter { $0.title == "Stop" || $0.title == "Reinstall sandbox" }.map { action($0) }) }
        return UIMenu(children: items.dropLast().map { action($0) } + [
            UIMenu(title: "Modules", children: moduleItems().map { action($0) }),
            action(items[items.count - 1], .destructive),   // Reinstall sandbox
        ])
    }

    /// ⋮ → Modules → a module: on, with its checklist (as on Android); off once the user says so.
    /// Either way Claude learns of it when it next starts: its brief is read at launch.
    private func setModule(_ module: CinderModule, on: Bool) {
        guard !settingUp else { return }
        guard !on else {
            Modules.set(module, on: true)
            return open(Checklist("Turning on \(module.title)", module.steps(), module: module))
        }
        confirm("Turn off \(module.title)?", "Claude stops seeing its commands when it next starts.", "Turn off") { [weak self] off in
            guard off, let self else { return }
            Modules.set(module, on: false)
            if self.checklist?.module === module { self.close() }
            self.offerRestart(module)
        }
    }

    /// Claude reads its brief when it starts, so it learns of a module turned on (its list done) or off
    /// then. The same question either way, as on Android.
    private func offerRestart(_ module: CinderModule) {
        confirm("\(module.title) is \(Modules.isOn(module) ? "on" : "off")", "Claude finds out when it next starts. " +
                "Restart Claude now? That ends this conversation; claude --continue picks it up again.",
                "Restart Claude", cancel: "Later") { [weak self] restart in
            if restart { self?.launch(.claude) }
        }
    }

    func setKeepAlive(_ on: Bool) {
        let keepAlive = KeepAlive.shared
        guard on else { keepAlive.enabled = false; return }
        keepAlive.turnOn()
        if keepAlive.denied {
            alert("Location is off", "Cinder Lite stays awake in the background by receiving location updates (the " +
                  "coarsest kind, never stored or sent anywhere). Allow location for Cinder Lite in Settings → " +
                  "Cinder Lite → Location, then come back.")
        } else {
            alert("Keep running in background", "While a session runs, Cinder Lite now keeps going when you leave " +
                  "it, so Claude can finish a task (or, with Phone Use, work other apps). iOS shows the location " +
                  "indicator meanwhile; Cinder Lite only asks for location to stay awake, and doesn't keep or " +
                  "send it. It uses more battery: turn it off here when you don't need it.")
        }
    }

    private func reinstall() {
        let sheet = UIAlertController(title: "Reinstall sandbox?",
                                      message: "Everything installed in the sandbox is removed, the latest Claude Code is " +
                                      "installed again, and every module turns off. Your login, settings and /workspace are " +
                                      "kept. Cinder Lite closes; open it again to set up.", preferredStyle: .alert)
        sheet.addAction(UIAlertAction(title: "Cancel", style: .cancel) { [weak self] _ in self?.showKeyboard() })
        sheet.addAction(UIAlertAction(title: "Reinstall", style: .destructive) { _ in
            for module in Modules.enabled { Modules.set(module, on: false) }   // a clean start, as on Android
            Sandbox.shared.requestReset()
            exit(0)   // the emulator holds the old root until the process ends
        })
        present(sheet, animated: true)
    }

    func listen() {
        autocorrect?.forget()
        let voice = VoiceController { [weak self] text in
            if let text { self?.paste(text) }
            self?.showKeyboard()
        }
        if let sheet = voice.sheetPresentationController {
            sheet.detents = [.medium()]
            sheet.prefersGrabberVisible = true
        }
        present(voice, animated: true)
    }

    func alert(_ title: String, _ message: String) {
        let a = UIAlertController(title: title, message: message, preferredStyle: .alert)
        a.addAction(UIAlertAction(title: "OK", style: .default) { [weak self] _ in self?.showKeyboard() })
        present(a, animated: true)
    }

    /// A yes-or-no question; `answer` gets which.
    private func confirm(_ title: String, _ message: String, _ yes: String, cancel: String = "Cancel",
                         answer: @escaping (Bool) -> Void) {
        let a = UIAlertController(title: title, message: message, preferredStyle: .alert)
        a.addAction(UIAlertAction(title: cancel, style: .cancel) { [weak self] _ in self?.showKeyboard(); answer(false) })
        a.addAction(UIAlertAction(title: yes, style: .default) { [weak self] _ in self?.showKeyboard(); answer(true) })
        present(a, animated: true)
    }
}

extension TerminalController: UIGestureRecognizerDelegate {
    func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }
}

/// The row of keys a phone keyboard lacks, above the keyboard: esc tab ⇧tab ctrl ← ↑ ↓ → 🎤 ⋮.
final class KeysBar: UIInputView {
    let ctrl = UIButton(type: .custom)

    init(controller: TerminalController) {
        super.init(frame: CGRect(x: 0, y: 0, width: 0, height: 44), inputViewStyle: .default)
        allowsSelfSizing = true
        backgroundColor = Colors.bar
        weak let c = controller
        func key(_ label: String, _ button: UIButton = UIButton(type: .custom), action: @escaping () -> Void) -> UIButton {
            button.setTitle(label, for: .normal)
            button.setTitleColor(.lightGray, for: .normal)
            button.setTitleColor(.white, for: .highlighted)
            button.titleLabel?.font = .systemFont(ofSize: 17)
            button.titleLabel?.adjustsFontSizeToFitWidth = true   // ⇧tab, on a narrow phone
            button.titleLabel?.minimumScaleFactor = 0.6
            button.addAction(UIAction { _ in action() }, for: .touchUpInside)
            return button
        }
        let menu = key("⋮") {}
        menu.menu = UIMenu(children: [UIDeferredMenuElement.uncached { done in done(c?.menu().children ?? []) }])
        menu.showsMenuAsPrimaryAction = true
        menu.preferredMenuElementOrder = .fixed   // as listed, the other tab first, as on Android
        ctrl.setTitleColor(Colors.accent, for: .selected)
        let row = UIStackView(arrangedSubviews: [
            key("esc") { c?.send("\u{1b}") },
            key("tab") { c?.send("\t") },
            key("⇧tab") { c?.send("\u{1b}[Z") },   // Claude Code's mode switch
            key("ctrl", ctrl) { [ctrl] in ctrl.isSelected.toggle() },   // sticky: applies to the next key typed
            key("←") { c?.arrow("D") },
            key("↑") { c?.arrow("A") },
            key("↓") { c?.arrow("B") },
            key("→") { c?.arrow("C") },
            key("🎤") { c?.listen() },
            menu,
        ])
        row.distribution = .fillEqually
        row.translatesAutoresizingMaskIntoConstraints = false
        addSubview(row)
        NSLayoutConstraint.activate([
            row.leadingAnchor.constraint(equalTo: safeAreaLayoutGuide.leadingAnchor),
            row.trailingAnchor.constraint(equalTo: safeAreaLayoutGuide.trailingAnchor),
            row.topAnchor.constraint(equalTo: topAnchor),
            row.heightAnchor.constraint(equalToConstant: 44),
            row.bottomAnchor.constraint(equalTo: bottomAnchor),
        ])
    }

    required init?(coder: NSCoder) { fatalError() }
}
