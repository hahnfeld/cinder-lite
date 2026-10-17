package app.cinder

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.cinder.module.Module
import app.cinder.module.Modules
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** The app's settings: autocorrect, which modules are on. */
internal fun prefs(ctx: Context) = ctx.getSharedPreferences("cinder", Context.MODE_PRIVATE)

/**
 * One screen: Claude Code's own TUI in a Termux terminal view, with a row of the keys a phone
 * keyboard lacks. While the sandbox is being set up, or a module turned on, its checklist stands in
 * for the terminal ([Checklist]).
 *
 * Two terminals — Claude Code and a shell — each keep their own session; ⋮ switches between them
 * without stopping either. Sessions and setup state live in the companion object, not the
 * activity: the foreground service keeps the process (and so them) alive while the activity comes
 * and goes. Background threads reach whichever activity is current through [ui].
 */
class MainActivity : Activity(), TerminalViewClient, TerminalSessionClient {

    private lateinit var sandbox: Sandbox
    private lateinit var terminal: TerminalView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var setupPane: LinearLayout
    private lateinit var heading: TextView
    private lateinit var rows: LinearLayout
    private lateinit var details: Button
    private lateinit var footer: LinearLayout
    private lateinit var ctrlKey: Button
    private var ctrl = false
    private var fontSize = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = this
        sandbox = Sandbox(applicationContext)
        fontSize = sp(11f)

        terminal = TerminalView(this, null).apply {
            setTerminalViewClient(this@MainActivity)
            setTextSize(fontSize)
            setTypeface(terminalTypeface())
            isFocusable = true
            isFocusableInTouchMode = true
        }
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(Color.LTGRAY)
            setPadding(sp(8f), sp(8f), sp(8f), sp(8f))
        }
        // The checklist (title, rows, Details), its output under Details, and its own buttons at the foot.
        heading = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, sp(8f))
        }
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        details = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            isAllCaps = false
            setTextColor(Color.LTGRAY)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setOnClickListener { showDetails = !showDetails; showChecklist() }
        }
        logScroll = ScrollView(this).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(sp(12f), sp(12f), sp(12f), 0)
                addView(heading)
                addView(rows)
                addView(details)
                addView(logView)
            })
        }
        footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        setupPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(logScroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(footer, LinearLayout.LayoutParams(-1, -2))
        }

        val screen = FrameLayout(this).apply {
            addView(terminal)
            addView(setupPane)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(screen, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(keysRow(), LinearLayout.LayoutParams(-1, -2))
        })

        val live = current
        when {
            checklist != null -> showChecklist()   // from an earlier activity
            busy.get() -> showLog()
            live != null -> attach(live)
            checkSelfPermission(STORAGE) != PackageManager.PERMISSION_GRANTED ->
                requestPermissions(arrayOf(STORAGE), 1)   // boots from onRequestPermissionsResult
            else -> boot()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (sessions.isEmpty() && checklist == null) boot()
    }

    override fun onResume() {
        super.onResume()
        visible = true
        // Back in the app: Claude's alert has done its job.
        SessionService.clearAlert(this)
        checklist?.advance()   // back from Settings or another app, where a step may have been done
    }

    override fun onPause() {
        visible = false
        super.onPause()
    }

    override fun onDestroy() {
        if (ui === this) ui = null
        super.onDestroy()
    }

    /** Keep the session (and the activity) around; back just leaves the app. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    // ---------------- setup + launch ----------------

    /**
     * Gets the sandbox ready and starts Claude. Its checklist unpacks the bundled runtime and installs
     * the latest Claude Code and the base tools; what's done already is ticked, so on an installed
     * device this goes straight to launching.
     */
    private fun boot() {
        val sandbox = sandbox
        val app = applicationContext
        SessionService.start(app)   // keep the process alive through a long first download
        if (sandbox.ready) return launchClaude(sandbox, app)
        open(checklist("Setting up Cinder Lite", listOf(
            Step.work("Unpack Alpine Linux", done = { sandbox.isUnpacked }) { sandbox.bootstrap(it) },
            Step.work("Download and install Claude Code", CLAUDE_NOTICE, download = true,
                done = { sandbox.hasClaude }) { sandbox.installClaude(it) },
            Step.work("Install the base tools", BASE_TOOLS, done = { sandbox.hasBaseTools }) { sandbox.installBaseTools(it) },
        )) { launchClaude(sandbox, app) })
    }

    /** Setup's done: Claude starts. */
    private fun launchClaude(sandbox: Sandbox, app: Context) {
        checklist = null
        if (!busy.compareAndSet(false, true)) return
        thread {
            try {
                sandbox.bootstrap(::log)
                launch(sandbox, Tab.Claude)
            } catch (e: Exception) {
                Log.e(TAG, "setup failed", e)
                log("failed: ${e.message}")
                log("check the free space, then ⋮ → Reinstall sandbox")
                main.post { ui?.showLog() }
            } finally {
                busy.set(false)
            }
        }
    }

    /** (Re)starts [tab]'s session and shows it; ignored while setup is running. */
    private fun restart(tab: Tab) {
        if (working || settingUp) return
        checklist = null   // a module's list: as if Later
        val sandbox = sandbox
        thread { launch(sandbox, tab) }
    }

    /** Switches the screen to [tab], starting its session the first time. The other keeps running. */
    private fun show(tab: Tab) {
        if (settingUp) return
        checklist = null   // a module's list: as if Later
        autocorrect?.forget()
        shown = tab
        sessions[tab]?.let { attach(it) } ?: restart(tab)
    }

    private fun attach(s: TerminalSession) {
        setupPane.visibility = View.GONE
        terminal.visibility = View.VISIBLE
        sessions.values.forEach { it.updateTerminalSessionClient(this) }
        terminal.attachSession(s)
        if (hasWindowFocus()) showKeyboard()
    }

    /**
     * Bring the keyboard up whenever the window gets focus with a terminal showing: at launch, and
     * on every return to the app, where Android won't restore it by itself. Asking any earlier
     * (before focus) makes Android defer it to the first touch and slide it up mid-tap, moving
     * the key row so that tap misses.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && terminal.visibility == View.VISIBLE) showKeyboard()
    }

    /** Setup's output on its own, for what has no checklist: a reset, deleting a module's files, a failed start. */
    private fun showLog() {
        heading.visibility = View.GONE
        rows.visibility = View.GONE
        details.visibility = View.GONE
        footer.removeAllViews()
        logView.text = setupLog
        logView.visibility = View.VISIBLE
        terminal.visibility = View.INVISIBLE
        setupPane.visibility = View.VISIBLE
    }

    /** A checklist that logs to setup's log, and waits while a reset or a delete is under way. */
    private fun checklist(title: String, steps: List<Step>, module: Module? = null, finished: () -> Unit = {}) =
        Checklist(title, steps, module, module?.summary, ::log, paused = { busy.get() }, finished = finished)

    /** Shows [list] in place of the terminal and gets it going. */
    private fun open(list: Checklist) {
        setupLog.setLength(0)
        showDetails = false
        checklist = list
        list.changed = { ui?.showChecklist() }
        showChecklist()
        list.advance()
    }

    /** Draws the current checklist, with Details and the buttons for where it's at. */
    private fun showChecklist() {
        val list = checklist ?: return
        val module = list.module
        heading.text = if (module != null && list.complete) "${module.title} is on" else list.title
        heading.visibility = View.VISIBLE
        rows.visibility = View.VISIBLE
        details.visibility = View.VISIBLE
        list.render(this, rows, ::sp)
        details.text = if (showDetails) "▾ Details" else "▸ Details"
        logView.text = setupLog
        logView.visibility = if (showDetails) View.VISIBLE else View.GONE
        footer.removeAllViews()
        fun button(label: String, onClick: () -> Unit) = footer.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = label
            isAllCaps = false
            setTextColor(Checklist.ORANGE)
            setOnClickListener { onClick() }
        })
        // Finished: just Done. Unfinished: leave it for later, or don't want it after all.
        if (module != null && list.running == null) {
            if (list.complete) button("Done") { back(); offerRestart(module) }
            else { button("Turn off") { turnOff(module) }; button("Later") { back() } }
        }
        terminal.visibility = View.INVISIBLE
        setupPane.visibility = View.VISIBLE
    }

    // ---------------- extra keys ----------------

    private fun keysRow() = LinearLayout(this).apply {
        setBackgroundColor(Color.rgb(0x1D, 0x1A, 0x15))
        fun key(label: String, onClick: (View) -> Unit) = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = label
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(0, sp(10f), 0, sp(10f))   // ten keys share a phone's width; keep the row short
            maxLines = 1
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setOnClickListener { autocorrect?.forget(); onClick(it) }   // the word being typed is unknown after any of these
        }.also { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }

        fun send(keyCode: Int, mod: Int = 0) = { _: View ->
            // handleKeyCode dereferences the emulator, which exists only once a session is attached
            // and laid out — not during setup.
            if (terminal.currentSession?.emulator != null) terminal.handleKeyCode(keyCode, mod)
        }
        key("esc", send(KeyEvent.KEYCODE_ESCAPE))
        key("tab", send(KeyEvent.KEYCODE_TAB))
        key("⇧tab", send(KeyEvent.KEYCODE_TAB, KeyHandler.KEYMOD_SHIFT))   // Claude Code's mode switch
        ctrlKey = key("ctrl") { setCtrl(!ctrl) }
        key("←", send(KeyEvent.KEYCODE_DPAD_LEFT))
        key("↑", send(KeyEvent.KEYCODE_DPAD_UP))
        key("↓", send(KeyEvent.KEYCODE_DPAD_DOWN))
        key("→", send(KeyEvent.KEYCODE_DPAD_RIGHT))
        key("🎤") { listen() }
        key("⋮") { menu(it) }
    }

    private fun setCtrl(on: Boolean) {
        ctrl = on
        ctrlKey.setTextColor(if (on) Color.rgb(0xE0, 0x7A, 0x3A) else Color.LTGRAY)
    }

    private fun menu(anchor: View) = PopupMenu(this, anchor).apply {
        // While setup's checklist shows, only what can't get in its way: Stop, and starting over.
        if (settingUp) {
            menu.add("Stop").setOnMenuItemClickListener { stop(applicationContext); true }
            menu.add("Reinstall sandbox").setOnMenuItemClickListener { confirmReinstall(); true }
            return@apply
        }
        val other = if (shown == Tab.Claude) Tab.Shell else Tab.Claude
        menu.add(other.title).setOnMenuItemClickListener { show(other); true }
        menu.add("Restart ${shown.title}").setOnMenuItemClickListener { restart(shown); true }
        menu.add("Stop").setOnMenuItemClickListener { stop(applicationContext); true }
        menu.add("Autocorrect").setCheckable(true).setChecked(autocorrecting(this@MainActivity))
            .setOnMenuItemClickListener {
                autocorrect?.forget()
                prefs(this@MainActivity).edit().putBoolean(AUTOCORRECT, !autocorrecting(this@MainActivity)).apply()
                true
            }
        val on = Modules.enabled(this@MainActivity)
        val modules = menu.addSubMenu("Modules")
        for (module in Modules.all) {
            // Checked once it's on and its checklist is done; until then a tap reopens the checklist.
            val ready = module in on && module.isReady(this@MainActivity, sandbox)
            modules.add(module.title).setCheckable(true).setChecked(ready)
                .setOnMenuItemClickListener { if (ready) turnOff(module) else turnOn(module); true }
        }
        menu.add("Reinstall sandbox").setOnMenuItemClickListener { confirmReinstall(); true }
    }.show()

    /** ⋮ → Reinstall sandbox, once the user agrees: it deletes a lot, and one mis-tap shouldn't. */
    private fun confirmReinstall() {
        if (working) return
        AlertDialog.Builder(this)
            .setTitle("Reinstall sandbox?")
            .setMessage("Both sessions end, everything installed in the sandbox is deleted (packages, Claude Code " +
                "itself, which downloads again, and what modules downloaded), and every module turns off. Your " +
                "login, settings and /workspace are kept.")
            .setPositiveButton("Reinstall") { _, _ -> reinstall() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Back to a fresh sandbox (see [Sandbox.reset]), then setup's checklist. */
    private fun reinstall() {
        if (working || !busy.compareAndSet(false, true)) return
        checklist = null
        finishAll()
        // A clean start: every module off, until it's turned on again (and its checklist run). The iOS app does the same.
        val app = applicationContext
        for (module in Modules.enabled(app)) Modules.set(app, module, false)
        setupLog.setLength(0)
        log("resetting the sandbox…")
        showLog()
        val sandbox = sandbox
        thread {
            try { sandbox.reset() } finally { busy.set(false) }
            main.post { ui?.boot() }
        }
    }

    // ---------------- modules (see module/Module.kt) ----------------

    /**
     * ⋮ → Modules, unchecked (or on with its checklist unfinished): on, and its checklist, which
     * installs what it needs and asks for what only the user can grant. Done rows are ticked.
     */
    private fun turnOn(module: Module) {
        if (working || settingUp) return
        val app = applicationContext
        Modules.set(app, module, true)
        SessionService.start(app)   // keep the process alive through a download
        open(checklist("Turning on ${module.title}", module.steps(app, sandbox), module))
    }

    /**
     * Claude reads its brief when it starts, so it learns of a module turned on (its list done) or off
     * then. The same question either way, as on iOS.
     */
    private fun offerRestart(module: Module) {
        AlertDialog.Builder(this)
            .setTitle("${module.title} is ${if (Modules.isOn(this, module)) "on" else "off"}")
            .setMessage("Claude finds out when it next starts. Restart Claude now? That ends this conversation; " +
                "claude --continue picks it up again.")
            .setPositiveButton("Restart Claude") { _, _ -> restart(Tab.Claude) }
            .setNegativeButton("Later", null)
            .show()
    }

    /** Back from a checklist or setup's log to the terminal that was showing. */
    private fun back() {
        checklist = null
        current?.let(::attach) ?: show(shown)
    }

    /** ⋮ → Modules, unchecked, or its checklist's Turn off: off, and if the user says so, its downloads deleted too. */
    private fun turnOff(module: Module) {
        if (working || settingUp) return
        val downloads = module.downloads(sandbox)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Turn off ${module.title}?")
            .setMessage("Claude stops seeing its commands when it next starts." +
                (downloads?.let { "\n\nAlso delete $it? Turning it on again downloads them again." } ?: ""))
            .setNegativeButton("Cancel", null)
        if (downloads == null) dialog.setPositiveButton("Turn off") { _, _ -> switchOff(module, delete = false) }
        else dialog.setPositiveButton("Turn off, keep files") { _, _ -> switchOff(module, delete = false) }
            .setNeutralButton("Turn off and delete") { _, _ -> switchOff(module, delete = true) }
        dialog.show()
    }

    private fun switchOff(module: Module, delete: Boolean) {
        if (!busy.compareAndSet(false, true)) return
        val sandbox = sandbox
        val app = applicationContext
        Modules.set(app, module, false)
        if (checklist?.module === module) back()
        if (!delete) {
            busy.set(false)
            return offerRestart(module)
        }
        setupLog.setLength(0)
        log("turning off ${module.title} and deleting its files…")
        showLog()
        thread {
            try {
                module.deleteDownloads(app, sandbox, ::log)
                log("done")
                main.post { ui?.run { back(); offerRestart(module) } }
            } catch (e: Exception) {
                Log.e(TAG, "couldn't delete ${module.title}'s files", e)
                log("failed: ${e.message}")
                log("⋮ → ${shown.title} goes back to the terminal.")
            } finally {
                busy.set(false)
            }
        }
    }

    /**
     * Voice typing through the phone's own speech recognizer (no microphone permission here; the
     * recognizer app handles that). What it hears is pasted into the terminal that's showing.
     */
    private fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        runCatching { startActivityForResult(intent, VOICE) }
            .onFailure { Toast.makeText(this, "No speech recognizer on this phone", Toast.LENGTH_SHORT).show() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != VOICE || resultCode != RESULT_OK) return
        val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
        autocorrect?.forget()
        current?.emulator?.paste(text)
    }

    private fun showKeyboard() {
        terminal.requestFocus()
        // Not InputMethodManager.showSoftInput: Android drops that when it arrives before the window
        // is ready for the keyboard, as it can at launch. This waits until it is.
        window.insetsController?.show(WindowInsets.Type.ime())
    }

    /**
     * Android's monospace font, then a 70 KB cut of Noto Sans Symbols 2 for symbols (Claude Code's
     * ⏵⏵ mode marker among them, which no system font has), then the system's usual fallbacks
     * (emoji, CJK…). Plain monospace if the system font isn't at AOSP's path.
     */
    private fun terminalTypeface(): Typeface = runCatching {
        fun family(font: Font) = FontFamily.Builder(font).build()
        Typeface.CustomFallbackBuilder(family(Font.Builder(File("/system/fonts/DroidSansMono.ttf")).build()))
            .addCustomFallback(family(Font.Builder(assets, "fonts/symbols.ttf").build()))
            .setSystemFallback("monospace")
            .build()
    }.getOrDefault(Typeface.MONOSPACE)

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics).toInt()

    // ---------------- TerminalViewClient ----------------

    override fun onScale(scale: Float): Float {
        if (scale in 0.9f..1.1f) return scale
        fontSize = (fontSize + if (scale > 1f) 2 else -2).coerceIn(sp(6f), sp(24f))
        terminal.setTextSize(fontSize)
        return 1f
    }

    override fun onSingleTapUp(e: MotionEvent) = showKeyboard()

    /** A finished session says "Press Enter to start it again". */
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER && !session.isRunning) {
            restart(tabOf(session) ?: return false)
            return true
        }
        val fix = corrector(session) ?: return false
        return when (keyCode) {
            KeyEvent.KEYCODE_DEL -> fix.backspace()
            // Alt/Shift+Enter is a newline in Claude Code, ctrl a different key: only a plain Enter waits.
            KeyEvent.KEYCODE_ENTER -> if (e.hasNoModifiers() && !ctrl) fix.typed('\r'.code) else { fix.forget(); false }
            else -> { if (e.unicodeChar == 0) fix.forget(); false }   // characters arrive in onCodePoint
        }
    }

    /** Sticky: one press of the ctrl key applies to the next key typed. */
    override fun readControlKey(): Boolean = ctrl.also { if (it) setCtrl(false) }
    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = true   // no autocorrect/composing text in a terminal
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
    override fun onLongPress(event: MotionEvent) = false
    override fun readAltKey() = false
    override fun readShiftKey() = false
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        val fix = corrector(session) ?: return false
        // A keyboard that commits Enter as text arrives as ctrl+M, which is the same CR.
        if (ctrlDown) return if (codePoint == 'm'.code) fix.typed('\r'.code) else { fix.forget(); false }
        return fix.typed(codePoint)
    }

    /** Claude's prompt gets autocorrect (⋮ → Autocorrect, on by default); the shell never does. */
    private fun corrector(session: TerminalSession): Autocorrect? {
        if (session !== sessions[Tab.Claude] || !autocorrecting(this)) return null
        return autocorrect ?: Autocorrect(applicationContext) { sessions[Tab.Claude]?.write(it) }.also { autocorrect = it }
    }
    override fun onEmulatorSet() {}

    // ---------------- TerminalSessionClient ----------------

    override fun onTextChanged(changedSession: TerminalSession) {
        if (changedSession === current) terminal.onScreenUpdated()
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        val tab = tabOf(finishedSession) ?: return   // a session that was already replaced
        // Not while setup runs (Reinstall ends the sessions it then outlasts): it needs the service too.
        if (!working && sessions.values.none { it.isRunning }) SessionService.stop(applicationContext)
        // Termux has just printed "[Process completed … - press Enter]" wherever the cursor was —
        // for Claude's TUI that's mid-screen, on top of its input box. Start the visible screen over
        // (scrollback is kept) with normal terminal state and a plain notice instead.
        val status = finishedSession.exitStatus
        val what = tab.noun
        val how = when {
            status == 0 -> "exited"
            status < 0 -> "was stopped"   // killed by a signal: Stop, Restart, or Android
            else -> "exited with code $status"
        }
        val notice = "\u001b[?1049l\u001b[0m\u001b[?25h\u001b[2J\u001b[H$what $how. Press Enter to start it again.\r\n"
        finishedSession.emulator?.let {
            val bytes = notice.toByteArray()
            it.append(bytes, bytes.size)
            if (finishedSession === current) terminal.onScreenUpdated()
        }
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip ?: return
        val text = clip.getItemAt(0).coerceToText(this).toString()
        if (text.isNotEmpty()) { autocorrect?.forget(); session.emulator?.paste(text) }
    }

    override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}

    // ---------------- logging (both interfaces) ----------------

    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "", e) }

    companion object {
        private const val TAG = "cinder"
        private const val BASE_TOOLS = "bash, Python, Node.js, git and the usual command-line tools."
        private const val STORAGE = Manifest.permission.WRITE_EXTERNAL_STORAGE
        private const val VOICE = 2   // startActivityForResult codes; modules use 100 and up
        // Claude Code can't verify who owns its default messaging-socket directory under proot (its
        // uid reads as the kernel overflow uid), so it turns cross-session messaging off and warns.
        // An explicit socket path (one per process, like its default) skips that check.
        // --settings adds the hooks that turn Claude's notifications into phone notifications
        // (assets/cinder-settings.json), without touching the user's own settings files.
        private const val CLAUDE =
            "mkdir -p -m 700 /tmp/cc-socks && exec /root/.local/bin/claude --messaging-socket-path /tmp/cc-socks/\$\$.sock " +
                "--settings /usr/local/libexec/cinder-settings.json --append-system-prompt \"\$(cat /usr/local/libexec/cinder-brief.md)\""
        // Not a login shell: Alpine's /etc/profile would reset PATH and drop the sandbox's tools.
        // $SHELL is bash once installed, busybox sh before that — so a shell works for recovery too.
        private const val SHELL = "exec \$SHELL"

        /**
         * Shown before Claude Code is downloaded; the iOS app says the same. Claude Code isn't Cinder
         * Lite's to hand out: it's Anthropic's, under its own license. So its download is the user's
         * call, made by a tap, never automatic.
         */
        private const val CLAUDE_NOTICE = "Cinder Lite runs Claude Code, but doesn't include it. Claude Code is " +
            "Anthropic's software, distributed separately under its own license (© Anthropic PBC, all rights " +
            "reserved). Its use is subject to Anthropic's legal agreements: " +
            "https://code.claude.com/docs/en/legal-and-compliance\n\nTap Download to get the latest " +
            "version from npm (about 240 MB) and install it here. By downloading it, you accept Anthropic's terms for it."

        /** The two terminals the app keeps, each with its own session: switching keeps both alive. */
        private enum class Tab(val title: String, val noun: String, val command: String) {
            Claude("Claude", "Claude Code", CLAUDE),
            Shell("Shell", "The shell", SHELL)
        }

        private val main = Handler(Looper.getMainLooper())
        private val busy = AtomicBoolean(false)
        private val setupLog = StringBuilder()
        /** The checklist on screen in place of the terminal, if any; touched on main only. */
        private var checklist: Checklist? = null
        private var showDetails = false
        /** Setup, a reset or a checklist's step is running: nothing else may start meanwhile. */
        private val working get() = busy.get() || checklist?.running != null
        /** Setup's checklist is showing: until it's done, the rest of the app waits. */
        private val settingUp get() = checklist?.let { it.module == null } == true

        // Outlive the activity; touched on the main thread only.
        private val sessions = mutableMapOf<Tab, TerminalSession>()
        private var autocorrect: Autocorrect? = null
        private const val AUTOCORRECT = "autocorrect"
        private fun autocorrecting(ctx: Context) = prefs(ctx).getBoolean(AUTOCORRECT, true)
        private var shown = Tab.Claude
        private val current get() = sessions[shown]
        private var ui: MainActivity? = null

        /** Whether the app is on screen; Claude's alerts only become phone notifications when not. */
        @Volatile var visible = false

        private fun tabOf(s: TerminalSession) = sessions.entries.find { it.value === s }?.key
        private fun finishAll() = sessions.values.forEach { it.finishIfRunning() }

        private fun log(line: String) {
            main.post {
                setupLog.append(line).append('\n')
                ui?.run {
                    logView.text = setupLog
                    logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }

        /** Something a checklist waits on has happened elsewhere (a Shizuku prompt's answer): look again. */
        fun recheck() = main.post { checklist?.advance() }

        /** Starts [tab] in a fresh session (replacing its old one, if any) and shows it. Call off main. */
        private fun launch(sandbox: Sandbox, tab: Tab) {
            // Touches files and runs getprop, hence off the main thread.
            val argv = try {
                sandbox.prootCommand(tab.command)
            } catch (e: Exception) {
                log("can't start: ${e.message} — try ⋮ → Reinstall sandbox")
                main.post { ui?.showLog() }
                return
            }
            if (tab == Tab.Claude) sandbox.updateClaude()
            main.post {
                // Without an activity there's no client to give the session; the next onCreate
                // finds no session and boots, which goes straight to launching.
                val activity = ui ?: return@post
                sessions[tab]?.finishIfRunning()
                autocorrect?.forget()
                val env = sandbox.env().map { "${it.key}=${it.value}" }.toTypedArray()
                val s = TerminalSession(argv[0], sandbox.workspace.absolutePath, argv, env, 2000, activity)
                sessions[tab] = s
                shown = tab
                activity.attach(s)
                SessionService.start(activity.applicationContext)
            }
        }

        /**
         * Ends both sessions, whatever modules are running (the phone screen, watching traffic), the
         * keep-alive service and the app: ⋮ → Stop, or the notification's Stop.
         */
        fun stop(ctx: Context) {
            main.post {
                finishAll()
                Modules.all.forEach { runCatching { it.stop(ctx) } }
                SessionService.stop(ctx)
                ui?.finishAndRemoveTask()
            }
        }
    }
}
