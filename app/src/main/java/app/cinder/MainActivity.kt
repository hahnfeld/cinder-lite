package app.cinder

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * One screen: Claude Code's own TUI in a Termux terminal view, with a row of the keys a phone
 * keyboard lacks. While the sandbox is being set up, a plain log stands in for the terminal.
 *
 * Two terminals — Claude Code and a shell — each keep their own session; ⋮ switches between them
 * without stopping either. Sessions and setup state live in the companion object, not the activity:
 * the foreground service keeps the process (and so them) alive while the activity comes and goes. Background
 * threads reach whichever activity is current through [ui].
 */
class MainActivity : Activity(), TerminalViewClient, TerminalSessionClient {

    private lateinit var sandbox: Sandbox
    private lateinit var terminal: TerminalView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var ctrlKey: Button
    private var ctrl = false
    private var fontSize = 0
    private var watcher: FileObserver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = this
        sandbox = Sandbox(applicationContext)
        fontSize = sp(11f)

        terminal = TerminalView(this, null).apply {
            setTerminalViewClient(this@MainActivity)
            setTextSize(fontSize)
            setTypeface(Typeface.MONOSPACE)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(Color.LTGRAY)
            setPadding(sp(8f), sp(8f), sp(8f), sp(8f))
        }
        logScroll = ScrollView(this).apply { addView(logView); visibility = View.GONE }

        val screen = FrameLayout(this).apply {
            addView(terminal)
            addView(logScroll)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(screen, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(keysRow(), LinearLayout.LayoutParams(-1, -2))
        })

        watchRequests()

        val live = current
        when {
            busy.get() -> showLog()                // setup running from an earlier activity
            live != null -> attach(live)
            checkSelfPermission(STORAGE) != PackageManager.PERMISSION_GRANTED ->
                requestPermissions(arrayOf(STORAGE), 1)   // boots from onRequestPermissionsResult
            else -> boot()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (sessions.isEmpty()) boot()
    }

    override fun onDestroy() {
        watcher?.stopWatching()
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
     * Gets the sandbox ready — unpacking the bundled runtime, installing the latest Claude Code,
     * installing the base tools — and then starts Claude. Each step is skipped when already done, so on an
     * installed device this goes straight to launching. Only one setup runs at a time.
     */
    private fun boot(reset: Boolean = false) {
        if (!busy.compareAndSet(false, true)) return
        val sandbox = sandbox
        val app = applicationContext
        if (reset || !sandbox.ready) {
            setupLog.setLength(0)
            showLog()
        }
        SessionService.start(app)   // keep the process alive through a long first download
        thread {
            try {
                if (reset) { log("resetting the sandbox…"); sandbox.reset() }
                sandbox.bootstrap(::log)
                if (!sandbox.hasClaude) sandbox.installClaude(::log)
                if (!sandbox.hasBaseTools) sandbox.installBaseTools(::log)
                launch(sandbox, Tab.Claude)
            } catch (e: Exception) {
                Log.e(TAG, "setup failed", e)
                log("failed: ${e.message}")
                log("check the connection and free space, then ⋮ → Reinstall sandbox")
                SessionService.stop(app)
            } finally {
                busy.set(false)
            }
        }
    }

    /** (Re)starts [tab]'s session and shows it; ignored while setup is running. */
    private fun restart(tab: Tab) {
        if (busy.get()) return
        val sandbox = sandbox
        thread { launch(sandbox, tab) }
    }

    /** Switches the screen to [tab], starting its session the first time. The other keeps running. */
    private fun show(tab: Tab) {
        shown = tab
        sessions[tab]?.let { attach(it) } ?: restart(tab)
    }

    private fun attach(s: TerminalSession) {
        logScroll.visibility = View.GONE
        terminal.visibility = View.VISIBLE
        sessions.values.forEach { it.updateTerminalSessionClient(this) }
        terminal.attachSession(s)
        showKeyboard()
    }

    private fun showLog() {
        logView.text = setupLog
        terminal.visibility = View.INVISIBLE
        logScroll.visibility = View.VISIBLE
    }

    /**
     * Watches home/.cinder for requests the sandbox's helper scripts leave there: xdg-open's URLs
     * (OAuth login) and termux-install's ask for the full set of Android libraries.
     */
    private fun watchRequests() {
        val dir = File(sandbox.home, ".cinder").apply { mkdirs() }
        watcher = object : FileObserver(dir, MOVED_TO or CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                when (path) {
                    "open-request" -> {
                        val file = File(dir, path)
                        val url = runCatching { file.readText().trim() }.getOrDefault("")
                        file.delete()
                        val uri = Uri.parse(url)
                        // Only web pages: anything in the sandbox can call xdg-open, and other
                        // schemes reach payment, SMS, and other apps' deep links.
                        if (uri.scheme == "https" || uri.scheme == "http") runOnUiThread {
                            runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                        }
                    }
                    "stage-request" -> {
                        File(dir, path).delete()
                        thread {
                            runCatching { sandbox.stageAndroidRuntime(full = true) }
                                .onFailure { Log.e(TAG, "staging Android libraries failed", it) }
                            File(dir, "stage-done").writeText("")
                        }
                    }
                }
            }
        }.apply { startWatching() }
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
            setPadding(0, sp(10f), 0, sp(10f))   // nine keys share a phone's width; keep the row short
            maxLines = 1
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setOnClickListener(onClick)
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
        key("⋮") { menu(it) }
    }

    private fun setCtrl(on: Boolean) {
        ctrl = on
        ctrlKey.setTextColor(if (on) Color.rgb(0xE0, 0x7A, 0x3A) else Color.LTGRAY)
    }

    private fun menu(anchor: View) = PopupMenu(this, anchor).apply {
        val other = if (shown == Tab.Claude) Tab.Shell else Tab.Claude
        menu.add(other.title).setOnMenuItemClickListener { show(other); true }
        menu.add("Restart ${shown.title}").setOnMenuItemClickListener { restart(shown); true }
        menu.add("Stop").setOnMenuItemClickListener { stop(applicationContext); true }
        menu.add("Reinstall sandbox").setOnMenuItemClickListener {
            if (!busy.get()) { finishAll(); boot(reset = true) }
            true
        }
    }.show()

    private fun showKeyboard() {
        terminal.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(terminal, 0)
    }

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
        if (keyCode != KeyEvent.KEYCODE_ENTER || session.isRunning) return false
        restart(tabOf(session) ?: return false)
        return true
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
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession) = false
    override fun onEmulatorSet() {}

    // ---------------- TerminalSessionClient ----------------

    override fun onTextChanged(changedSession: TerminalSession) {
        if (changedSession === current) terminal.onScreenUpdated()
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        val tab = tabOf(finishedSession) ?: return   // a session that was already replaced
        if (sessions.values.none { it.isRunning }) SessionService.stop(applicationContext)
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
        if (text.isNotEmpty()) session.emulator?.paste(text)
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
        private const val STORAGE = Manifest.permission.WRITE_EXTERNAL_STORAGE
        // Claude Code can't verify who owns its default messaging-socket directory under proot (its
        // uid reads as the kernel overflow uid), so it turns cross-session messaging off and warns.
        // An explicit socket path (one per process, like its default) skips that check.
        private const val CLAUDE =
            "mkdir -p -m 700 /tmp/cc-socks && exec /root/.local/bin/claude --messaging-socket-path /tmp/cc-socks/\$\$.sock " +
                "--append-system-prompt \"\$(cat /usr/local/libexec/cinder-brief.md)\""
        // Not a login shell: Alpine's /etc/profile would reset PATH and drop the sandbox's tools.
        // $SHELL is bash once installed, busybox sh before that — so a shell works for recovery too.
        private const val SHELL = "exec \$SHELL"

        /** The two terminals the app keeps, each with its own session: switching keeps both alive. */
        private enum class Tab(val title: String, val noun: String, val command: String) {
            Claude("Claude", "Claude Code", CLAUDE),
            Shell("Shell", "The shell", SHELL)
        }

        private val main = Handler(Looper.getMainLooper())
        private val busy = AtomicBoolean(false)
        private val setupLog = StringBuilder()

        // Outlive the activity; touched on the main thread only.
        private val sessions = mutableMapOf<Tab, TerminalSession>()
        private var shown = Tab.Claude
        private val current get() = sessions[shown]
        private var ui: MainActivity? = null

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
            main.post {
                // Without an activity there's no client to give the session; the next onCreate
                // finds no session and boots, which goes straight to launching.
                val activity = ui ?: return@post
                sessions[tab]?.finishIfRunning()
                val s = TerminalSession(argv[0], sandbox.workspace.absolutePath, argv, sandbox.env(), 2000, activity)
                sessions[tab] = s
                shown = tab
                activity.attach(s)
                SessionService.start(activity.applicationContext)
            }
        }

        /** Ends both sessions, the keep-alive service and the app: ⋮ → Stop, or the notification's Stop. */
        fun stop(ctx: Context) {
            main.post {
                finishAll()
                SessionService.stop(ctx)
                ui?.finishAndRemoveTask()
            }
        }
    }
}
