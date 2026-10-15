import AVFoundation
import Speech
import UIKit

/// The 🎤 key: voice typing through iOS's speech recognizer, in a small sheet. What it hears is
/// shown as it goes; Insert pastes it into the terminal that's showing. `onDone` gets the text, or
/// nil when the sheet closes without it (Cancel, or swiped away).
final class VoiceController: UIViewController {
    private let onDone: (String?) -> Void
    private var inserted = false
    private let text = UILabel()
    private let recognizer = SFSpeechRecognizer()
    private let audio = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var heard = ""

    init(onDone: @escaping (String?) -> Void) {
        self.onDone = onDone
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = Colors.bar
        text.text = "Listening…"
        text.textColor = .lightGray
        text.numberOfLines = 0
        text.font = .preferredFont(forTextStyle: .body)
        let cancel = UIButton(type: .system, primaryAction: UIAction(title: "Cancel") { [weak self] _ in self?.close(insert: false) })
        let insert = UIButton(type: .system, primaryAction: UIAction(title: "Insert") { [weak self] _ in self?.close(insert: true) })
        insert.titleLabel?.font = .boldSystemFont(ofSize: 17)
        for b in [cancel, insert] { b.tintColor = Colors.accent }
        let buttons = UIStackView(arrangedSubviews: [cancel, UIView(), insert])
        let stack = UIStackView(arrangedSubviews: [text, buttons])
        stack.axis = .vertical
        stack.spacing = 16
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: view.layoutMarginsGuide.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: view.layoutMarginsGuide.trailingAnchor),
            stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 20),
        ])
        authorize()
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        stop()
        onDone(inserted && !heard.isEmpty ? heard : nil)
    }

    private func authorize() {
        SFSpeechRecognizer.requestAuthorization { status in
            DispatchQueue.main.async {
                guard status == .authorized else { return self.fail("Speech recognition is off for Cinder Lite (Settings → Cinder Lite).") }
                let answer = { (granted: Bool) in
                    DispatchQueue.main.async {
                        if granted { self.start() } else { self.fail("The microphone is off for Cinder Lite (Settings → Cinder Lite).") }
                    }
                }
                if #available(iOS 17, *) {
                    AVAudioApplication.requestRecordPermission(completionHandler: answer)
                } else {
                    AVAudioSession.sharedInstance().requestRecordPermission(answer)
                }
            }
        }
    }

    private func start() {
        guard let recognizer, recognizer.isAvailable else { return fail("Speech recognition isn't available right now.") }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.record, mode: .measurement, options: .duckOthers)
            try session.setActive(true, options: .notifyOthersOnDeactivation)
            let request = SFSpeechAudioBufferRecognitionRequest()
            request.shouldReportPartialResults = true
            self.request = request
            let input = audio.inputNode
            input.installTap(onBus: 0, bufferSize: 1024, format: input.outputFormat(forBus: 0)) { buffer, _ in request.append(buffer) }
            audio.prepare()
            try audio.start()
            task = recognizer.recognitionTask(with: request) { [weak self] result, error in
                DispatchQueue.main.async {
                    guard let self else { return }
                    if let result {
                        self.heard = result.bestTranscription.formattedString
                        self.text.text = self.heard
                        self.text.textColor = .white
                    }
                    if error != nil && self.heard.isEmpty { self.stop() }
                }
            }
        } catch {
            fail("Couldn't start listening: \(error.localizedDescription)")
        }
    }

    private func stop() {
        if audio.isRunning {
            audio.stop()
            audio.inputNode.removeTap(onBus: 0)
        }
        request?.endAudio()
        task?.cancel()
        task = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func fail(_ message: String) {
        text.text = message
        text.textColor = .lightGray
    }

    private func close(insert: Bool) {
        inserted = insert
        dismiss(animated: true)
    }
}
