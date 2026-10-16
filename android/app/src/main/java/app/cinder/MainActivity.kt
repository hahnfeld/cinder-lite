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
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * One screen: Claude Code's own TUI in a Termux terminal view, with a row of the keys a phone
 * keyboard lacks. While the sandbox is being set up, a plain log stands in for the terminal.
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
    private lateinit var agree: Button
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
        logScroll = ScrollView(this).apply { addView(logView) }
        // Setup's log, and under it the button that agrees to Claude Code's download (see [askToDownload]).
        agree = Button(this).apply {
            text = "Download Claude Code"
            isAllCaps = false
            setOnClickListener {
                consent?.countDown()
                consent = null
                visibility = View.GONE
                SessionService.start(applicationContext)   // Stop may have ended it while this waited
            }
        }
        setupPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(logScroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(agree, LinearLayout.LayoutParams(-1, -2))
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

    override fun onResume() {
        super.onResume()
        visible = true
        // Back in the app: Claude's alert has done its job.
        SessionService.clearAlert(this)
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
     * Gets the sandbox ready — unpacking the bundled runtime, installing the latest Claude Code,
     * installing the base tools — and then starts Claude. Each step is skipped when already done, so
     * on an installed device this goes straight to launching. Only one setup runs at a time.
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
                if (!sandbox.hasClaude) { askToDownload(); sandbox.installClaude(::log) }
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

    private fun showLog() {
        logView.text = setupLog
        agree.visibility = if (consent != null) View.VISIBLE else View.GONE
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
        menu.add("Shizuku").setCheckable(true).setChecked(ShizukuBridge.isEnabled(this@MainActivity))
            .setOnMenuItemClickListener { toggleShizuku(); true }
        menu.add("Reinstall sandbox").setOnMenuItemClickListener {
            if (!busy.get()) { finishAll(); boot(reset = true) }
            true
        }
    }.show()

    /**
     * Optional adb-shell access for the sandbox (`rish`, `adb shell`) through the Shizuku app.
     * Off by default; most people won't have Shizuku.
     */
    private fun toggleShizuku() {
        val app = applicationContext
        fun say(text: String) = Toast.makeText(app, text, Toast.LENGTH_LONG).show()
        fun on() { say("Shizuku on: rish and adb shell work in the sandbox"); askPhoneAccess() }
        when {
            ShizukuBridge.isEnabled(app) -> {
                ShizukuBridge.setEnabled(app, false)
                say("Shizuku off: rish and adb shell are disabled")
            }
            !ShizukuBridge.isInstalled(app) ->
                say("Shizuku isn't installed. Get it from Google Play or github.com/RikkaApps/Shizuku")
            else -> {
                ShizukuBridge.setEnabled(app, true)
                when {
                    !ShizukuBridge.isRunning() -> say("Shizuku on. Start Shizuku in its app, then come back to " +
                        "Cinder Lite")
                    ShizukuBridge.hasPermission() -> on()
                    else -> ShizukuBridge.requestPermission { granted ->
                        runOnUiThread {
                            if (granted) on() else say("Shizuku on, but permission was denied. rish will ask again")
                        }
                    }
                }
            }
        }
    }

    /**
     * Asks the user to turn on the accessibility service that lets Claude read and tap the phone
     * screen (`phone` in the sandbox), unless it's on already. Android won't let an app turn it on.
     */
    private fun askPhoneAccess() {
        if (PhoneAccessibility.instance != null || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("Phone screen access")
            .setMessage("With Shizuku on, Claude can also use apps on a separate phone screen, in a small " +
                "floating window, while you keep using yours. To read and tap what's on it, Claude needs " +
                "Cinder Lite's accessibility service.\n\nOn the next screen, turn on \"Cinder Lite phone " +
                "screen\". If Android says it's a restricted setting, tap App info here instead, then ⋮ → " +
                "Allow restricted settings, and turn it on in Settings → Accessibility.")
            .setPositiveButton("Open settings") { _, _ -> PhoneAccessibility.openSettings(this) }
            .setNeutralButton("App info") { _, _ -> PhoneAccessibility.openAppInfo(this) }
            .setNegativeButton("Not now", null)
            .show()
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
        private const val STORAGE = Manifest.permission.WRITE_EXTERNAL_STORAGE
        private const val VOICE = 2
        // Claude Code can't verify who owns its default messaging-socket directory under proot (its
        // uid reads as the kernel overflow uid), so it turns cross-session messaging off and warns.
        // An explicit socket path (one per process, like its default) skips that check.
        // --settings adds the hooks that turn Claude's notifications into phone notifications
        // (assets/claude-settings.json), without touching the user's own settings files.
        // Claude Code's own update check is off (Sandbox's prelude), so it updates here: once a day, a
        // minute after starting, in its own session so it outlives this one. The iOS app does the same.
        private const val UPDATE =
            "s=/root/.cache/cinder-update; [ -n \"\$(find \$s -mtime -1 2>/dev/null)\" ] || { mkdir -p /root/.cache && : > \$s; " +
                "setsid sh -c 'sleep 60; exec /root/.local/bin/claude update' </dev/null >\$s.log 2>&1 & }; "
        private const val CLAUDE = UPDATE +
            "mkdir -p -m 700 /tmp/cc-socks && exec /root/.local/bin/claude --messaging-socket-path /tmp/cc-socks/\$\$.sock " +
                "--settings /usr/local/libexec/cinder-settings.json --append-system-prompt \"\$(cat /usr/local/libexec/cinder-brief.md)\""
        // Not a login shell: Alpine's /etc/profile would reset PATH and drop the sandbox's tools.
        // $SHELL is bash once installed, busybox sh before that — so a shell works for recovery too.
        private const val SHELL = "exec \$SHELL"

        /** Shown before Claude Code is downloaded; the iOS app's cinder-setup says the same. */
        private val CLAUDE_NOTICE = listOf(
            "",
            "Cinder Lite runs Claude Code, but doesn't include it. Claude Code is Anthropic's software, " +
                "distributed separately under its own license (© Anthropic PBC, all rights reserved). " +
                "Its use is subject to Anthropic's legal agreements: " +
                "https://code.claude.com/docs/en/legal-and-compliance",
            "",
            "Tap Download Claude Code to get the latest version from npm (about 240 MB) and install it " +
                "here. By downloading it, you accept Anthropic's terms for it.",
        )

        /** The two terminals the app keeps, each with its own session: switching keeps both alive. */
        private enum class Tab(val title: String, val noun: String, val command: String) {
            Claude("Claude", "Claude Code", CLAUDE),
            Shell("Shell", "The shell", SHELL)
        }

        private val main = Handler(Looper.getMainLooper())
        private val busy = AtomicBoolean(false)
        private val setupLog = StringBuilder()
        /** Set while setup waits for the user to agree to Claude Code's download; touched on main only. */
        private var consent: CountDownLatch? = null

        // Outlive the activity; touched on the main thread only.
        private val sessions = mutableMapOf<Tab, TerminalSession>()
        private var autocorrect: Autocorrect? = null
        private const val AUTOCORRECT = "autocorrect"
        private fun prefs(ctx: Context) = ctx.getSharedPreferences("cinder", Context.MODE_PRIVATE)
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

        /**
         * Claude Code isn't Cinder Lite's to hand out: it's Anthropic's, under its own license. So
         * its download is the user's call, made by a tap, never automatic. Blocks setup's thread
         * until then; leaving the app instead just leaves it waiting (it asks again on return).
         */
        private fun askToDownload() {
            CLAUDE_NOTICE.forEach(::log)
            val latch = CountDownLatch(1)
            main.post {
                consent = latch
                ui?.showLog()
            }
            latch.await()
            log("")
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
         * Ends both sessions, the phone screen, the keep-alive service and the app: ⋮ → Stop, or the
         * notification's Stop.
         */
        fun stop(ctx: Context) {
            main.post {
                finishAll()
                PhoneScreenActivity.current?.close()
                SessionService.stop(ctx)
                ui?.finishAndRemoveTask()
            }
        }
    }
}
