import UIKit

/// One row of a `Checklist`. `done` says whether it's done, from the state it leaves in the sandbox or
/// the system, so it's asked again whenever the list is looked at: a list that was interrupted, failed
/// or is opened again starts at its first row that isn't. As on Android (`app.cinder.Step`), a row is
/// work that happens by itself (`wait`: the sandbox's setup, or the app, is doing it), a `download`
/// that starts once the list's one Download tap is given, or something only the user can do (`ask`: a
/// button that opens Settings, a page or a prompt). `optional` rows can be skipped.
final class Step {
    enum Kind {
        case wait
        case download(() -> Void)
        case ask(String, () -> Void)
    }

    let title: String
    let detail: String?
    let kind: Kind
    let optional: Bool
    let done: () -> Bool
    /// What it's doing right now, shown under its title while it runs.
    var progress: () -> String? = { nil }

    init(_ title: String, _ detail: String? = nil, _ kind: Kind = .wait, optional: Bool = false, done: @escaping () -> Bool) {
        self.title = title
        self.detail = detail
        self.kind = kind
        self.optional = optional
        self.done = done
    }
}

/// Setup (`module` nil), or a module being turned on, as a list of `Step`s ticked off in order, shown
/// over the terminal. Nothing here remembers where it got to: every second while it's showing, every row
/// is asked whether it's done, and the first that isn't is the one that's running, or waits with its
/// button. Android's `Checklist` works the same way.
final class Checklist {
    let title: String
    let steps: [Step]
    let module: CinderModule?
    let summary: String?
    /// Why the work stopped, if it did: the current row shows ✗ and Retry, which calls `retry`.
    var failure: () -> String? = { nil }
    var retry: () -> Void = {}
    private var skipped: [Step] = []
    /// The user tapped Download: this list's downloads may run.
    private var agreed = false

    init(_ title: String, _ steps: [Step], module: CinderModule? = nil) {
        self.title = title
        self.steps = steps
        self.module = module
        summary = module?.summary
    }

    /// One look at the list: each row asked once.
    struct Look {
        let done: [Bool]
        let next: Step?
        var complete: Bool { next == nil }
    }

    func look() -> Look {
        let done = steps.map { $0.done() }
        let next = steps.indices.first { i in !done[i] && !skipped.contains { $0 === steps[i] } }
        return Look(done: done, next: next.map { steps[$0] })
    }

    /// A row as `ChecklistView` shows it: its mark (✓ done, – skipped, ✗ failed, → waiting for the user,
    /// ○ not yet, nil running), the line under it, and its buttons. Only the current row has the last two.
    func row(_ i: Int, _ look: Look) -> (mark: String?, detail: String?, buttons: [(String, () -> Void)]) {
        let step = steps[i]
        if look.done[i] { return ("✓", nil, []) }
        if skipped.contains(where: { $0 === step }) { return ("–", nil, []) }
        guard step === look.next else { return ("○", nil, []) }
        let skip: [(String, () -> Void)] = step.optional ? [("Skip", { [weak self] in self?.skipped.append(step) })] : []
        if let failure = failure() {
            return ("✗", failure, [("Retry", { [weak self] in self?.agreed = true; self?.retry() })] + skip)
        }
        switch step.kind {
        case .wait:
            return (nil, step.progress() ?? step.detail, [])
        case .download(let start):
            if agreed || step.progress() != nil { return (nil, step.progress() ?? step.detail, []) }
            return ("→", step.detail, [("Download", { [weak self] in self?.agreed = true; start() })] + skip)
        case .ask(let label, let act):
            return ("→", step.detail, [(label, act)] + skip)
        }
    }

    /// Under every finished module's list; the Android app says the same.
    static let restart = "Claude learns about it when it next starts: Restart Claude ends this conversation, " +
        "and claude --continue picks it back up."
}

/// A checklist on screen: its title, a row per step, a Details toggle (the terminal underneath, where the
/// work prints what it's doing), and the buttons the controller gives it at the foot. Redrawn only when
/// what it shows changes, and at once after any of its buttons, so nothing can be tapped twice.
final class ChecklistView: UIView {
    private let stack = UIStackView()
    private let footer = UIStackView()
    private var shown = ""
    var showDetails = false
    var onDetails: () -> Void = {}
    var onTap: () -> Void = {}

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = Colors.background
        stack.axis = .vertical
        stack.spacing = 12
        stack.translatesAutoresizingMaskIntoConstraints = false
        footer.axis = .horizontal
        footer.spacing = 20
        footer.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)
        addSubview(footer)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 16),
            stack.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -16),
            stack.topAnchor.constraint(equalTo: topAnchor, constant: 12),
            footer.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -16),
            footer.topAnchor.constraint(greaterThanOrEqualTo: stack.bottomAnchor, constant: 12),
            footer.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -8),
        ])
    }

    required init?(coder: NSCoder) { fatalError() }

    /// Draws `list` as `look` found it, with `actions` at the foot.
    func show(_ list: Checklist, _ look: Checklist.Look, actions: [(String, () -> Void)]) {
        let rows = list.steps.indices.map { list.row($0, look) }
        let heading = list.module.map { look.complete ? "\($0.title) is on" : list.title } ?? list.title
        let note = look.complete && list.module != nil ? [list.summary, Checklist.restart].compactMap { $0 }.joined(separator: "\n\n") : nil
        let signature = ([heading, "\(showDetails)", note ?? ""] + actions.map(\.0) + list.steps.indices.map {
            "\(rows[$0].mark ?? "…")\(list.steps[$0].title)\(rows[$0].detail ?? "")\(rows[$0].buttons.map(\.0))"
        }).joined(separator: "|")
        guard signature != shown else { return }
        shown = signature

        stack.arrangedSubviews.forEach { $0.removeFromSuperview() }
        stack.addArrangedSubview(label(heading, size: 20, color: .white, weight: .medium))
        for (i, step) in list.steps.enumerated() {
            let (mark, detail, buttons) = rows[i]
            let markView: UIView
            if let mark {
                let color: UIColor = mark == "✓" ? UIColor(red: 0.49, green: 0.70, blue: 0.26, alpha: 1)
                    : mark == "✗" ? UIColor(red: 0.90, green: 0.33, blue: 0.29, alpha: 1)
                    : mark == "→" ? Colors.accent : .gray
                let view = label(mark, size: 17, color: color)
                view.textAlignment = .center
                markView = view
            } else {
                let spinner = UIActivityIndicatorView(style: .medium)
                spinner.color = .lightGray
                spinner.startAnimating()
                markView = spinner
            }
            markView.widthAnchor.constraint(equalToConstant: 24).isActive = true
            let current = step === look.next
            let text = UIStackView(arrangedSubviews: [label(step.title, size: 16, color: look.done[i] || current ? .white : .gray)])
            text.axis = .vertical
            text.spacing = 4
            text.alignment = .leading
            if let detail { text.addArrangedSubview(label(detail, size: 13, color: .lightGray)) }
            if !buttons.isEmpty {
                let row = UIStackView(arrangedSubviews: buttons.map { button($0.0, filled: true, $0.1) })
                row.spacing = 10
                text.addArrangedSubview(row)
            }
            let line = UIStackView(arrangedSubviews: [markView, text])
            line.spacing = 8
            line.alignment = .top
            stack.addArrangedSubview(line)
        }
        if let note { stack.addArrangedSubview(label(note, size: 13, color: .lightGray)) }
        let details = button(showDetails ? "▾ Details" : "▸ Details", filled: false) { [weak self] in self?.onDetails() }
        details.contentHorizontalAlignment = .leading
        stack.addArrangedSubview(details)

        footer.arrangedSubviews.forEach { $0.removeFromSuperview() }
        for (title, action) in actions { footer.addArrangedSubview(button(title, filled: false, accent: true, action)) }
    }

    private func label(_ text: String, size: CGFloat, color: UIColor, weight: UIFont.Weight = .regular) -> UILabel {
        let label = UILabel()
        label.text = text
        label.numberOfLines = 0
        label.font = .systemFont(ofSize: size, weight: weight)
        label.textColor = color
        return label
    }

    private func button(_ title: String, filled: Bool, accent: Bool = false, _ action: @escaping () -> Void) -> UIButton {
        var config = filled ? UIButton.Configuration.gray() : UIButton.Configuration.plain()
        config.title = title
        config.baseForegroundColor = accent ? Colors.accent : filled ? .white : .lightGray
        if !filled { config.contentInsets = .init(top: 6, leading: 0, bottom: 6, trailing: 0) }
        return UIButton(configuration: config, primaryAction: UIAction { [weak self] _ in action(); self?.onTap() })
    }
}
