package app.cinder

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Rational
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The phone screen: a virtual display that Claude drives (`phone` in the sandbox), shown in a
 * picture-in-picture window. [ShellService] creates the display with adb-shell privileges and it
 * renders straight into this window's SurfaceView; the display lives exactly as long as the window.
 * Expanded to full size, it's the user's: their touches go to the display, and Claude waits.
 */
class PhoneScreenActivity : Activity(), SurfaceHolder.Callback {

    /** The display's size: the phone's own, scaled down so its long edge is [LONG_EDGE]. */
    class Spec(val width: Int, val height: Int, val dpi: Int) {
        val landscape get() = width > height
    }

    val spec by lazy { spec(this, intent.getBooleanExtra(EXTRA_LANDSCAPE, false)) }
    @Volatile private var shell: IShellService? = null
    private lateinit var view: SurfaceView
    @Volatile var displayId = -1
        private set
    /** The user has the window full size, to use the phone screen themselves. */
    @Volatile var held = false
        private set
    /** Stopped while open: the phone is asleep, and the display's apps are paused with it. */
    @Volatile var stopped = false
        private set
    private var entered = false
    private var returned = false
    private val died = IBinder.DeathRecipient { runOnUiThread { finish() } }

    @SuppressLint("ClickableViewAccessibility")   // the touches are the phone screen's, not a click here
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shell = ShizukuBridge.connected() ?: run { failure = "lost the Shizuku service"; finish(); return }
        shell.asBinder().linkToDeath(died, 0)
        this.shell = shell
        view = SurfaceView(this)
        // A buffer the display's size, whatever size the window is, so screenshots are full size
        // and the display never has to change resolution as the window is resized.
        view.holder.setFixedSize(spec.width, spec.height)
        view.holder.addCallback(this)
        view.setOnTouchListener { v, event -> held && forward(v, event) }
        setContentView(view)
        current = this
    }

    override fun onResume() {
        super.onResume()
        // Into the PiP window once; after that the user may expand it (taking it over) and leave to
        // shrink it again. Android refuses (or, without PiP support, throws) when PiP is unavailable.
        if (entered) return
        entered = true
        if (!isInPictureInPictureMode && !runCatching { enterPictureInPictureMode(params()) }.getOrDefault(false)) {
            failure = "Android won't show the phone screen in picture-in-picture: this phone doesn't support it, or " +
                "it's off for Cinder Lite (Settings → Apps → Special app access → Picture-in-picture)"
            close()
        }
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        held = !inPip && !stopped
        if (!inPip && stopped) close()   // dismissed: see onStop
        // The PiP window can't take keyboard focus, but Android leaves focus on it; hand it back to
        // the terminal the first time, so the user (and the keyboard) carry on where they were. Not
        // when they were in another app: that would pull them out of it.
        if (inPip && !returned) {
            returned = true
            if (intent.getBooleanExtra(EXTRA_FROM_TERMINAL, false)) {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Full size, Back is the phone screen's own; leaving (Home, switching apps) shrinks it.
        runCatching { key(KeyEvent.KEYCODE_BACK) }
    }

    /** The user's touch on the full-size window, scaled to the display and sent to it. */
    private fun forward(v: View, event: MotionEvent): Boolean {
        val copy = MotionEvent.obtain(event)
        copy.transform(Matrix().apply { setScale(spec.width.toFloat() / v.width, spec.height.toFloat() / v.height) })
        runCatching { inject(copy) }
        copy.recycle()
        return true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val shell = shell ?: return
        runCatching {
            if (displayId < 0) displayId = shell.createDisplay(spec.width, spec.height, spec.dpi, holder.surface)
            else shell.setSurface(displayId, holder.surface)
        }.onFailure { failure = "couldn't create the phone screen: ${it.message}"; finish() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (displayId >= 0) runCatching { shell?.setSurface(displayId, null) }
    }

    override fun onUserLeaveHint() {
        // Leaving the expanded phone screen (Home, switching apps) shrinks it back into PiP.
        if (!isInPictureInPictureMode) runCatching { enterPictureInPictureMode(params()) }
    }

    override fun onStart() {
        super.onStart()
        stopped = false
    }

    /**
     * The user dismissed the window (its ✕ or a swipe away): Android stops it and takes it out of
     * PiP, in either order, so close the phone screen too. It also stops when the screen turns off,
     * in PiP or full size, and expanding it leaves PiP while started; neither closes it.
     */
    override fun onStop() {
        super.onStop()
        stopped = true
        if (entered && !isInPictureInPictureMode && getSystemService(PowerManager::class.java).isInteractive) close()
    }

    /** Closes the window, the display and the apps on it. */
    fun close() {
        release()
        finish()
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    /** Closes the display (and the apps on it). Safe to call twice. */
    private fun release() {
        if (current === this) current = null
        held = false   // nothing left to wait for
        val shell = shell ?: return
        this.shell = null
        runCatching { shell.asBinder().unlinkToDeath(died, 0) }
        // Closing the display's apps takes a moment; not on the main thread.
        val id = displayId
        if (id >= 0) thread { runCatching { shell.releaseDisplay(id) } }
    }

    fun tap(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        touch(now, now, MotionEvent.ACTION_DOWN, x, y)
        touch(now, now, MotionEvent.ACTION_UP, x, y)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        val down = SystemClock.uptimeMillis()
        var x = x1
        var y = y1
        touch(down, down, MotionEvent.ACTION_DOWN, x, y)
        try {
            val steps = maxOf(1, (ms / 16).toInt())   // a move per frame
            for (i in 1..steps) {
                Thread.sleep(ms / steps)
                x = x1 + (x2 - x1) * i / steps
                y = y1 + (y2 - y1) * i / steps
                touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, y)
            }
        } catch (e: Exception) {
            // Never leave a finger down on the display.
            runCatching { touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, x, y) }
            throw e
        }
        touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x2, y2)
    }

    /** Presses a key, a KeyEvent code ([keyCode] finds one by name). */
    fun key(code: Int) {
        val now = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            inject(KeyEvent(now, now, action, code, 0).apply { source = InputDevice.SOURCE_KEYBOARD })
        }
    }

    /** Types [text] into the focused field, key by key. */
    fun text(text: String) {
        val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
            ?: error("phone text only types keyboard characters. Use phone type N 'name' 'text' instead")
        events.forEach { inject(it.apply { source = InputDevice.SOURCE_KEYBOARD }) }
    }

    private fun touch(down: Long, time: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, time, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try { inject(event) } finally { event.recycle() }
    }

    private fun inject(event: InputEvent) {
        val shell = shell ?: error("the phone screen is closing")
        check(shell.inject(displayId, event)) { "Android refused input to the phone screen" }
    }

    /** A PNG of the phone screen, at the display's own size. */
    fun shot(): ByteArray {
        val bitmap = Bitmap.createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        var result = -1
        PixelCopy.request(view, bitmap, { result = it; done.countDown() }, Handler(Looper.getMainLooper()))
        done.await(5, TimeUnit.SECONDS)
        check(result == PixelCopy.SUCCESS) { "couldn't capture the phone screen (PixelCopy $result)" }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun params() = PictureInPictureParams.Builder().setAspectRatio(Rational(spec.width, spec.height)).apply {
        if (Build.VERSION.SDK_INT >= 31) setSeamlessResizeEnabled(false)
    }.build()

    companion object {
        private const val LONG_EDGE = 1400
        private const val MAX_ASPECT = 2.35   // PiP windows can't be narrower than 1 : 2.39

        @Volatile var current: PhoneScreenActivity? = null
            private set
        /** Why the last start failed, for the bridge to report. */
        @Volatile var failure: String? = null
        /** Set when `phone start` comes from the terminal on screen, to give it the focus back. */
        const val EXTRA_FROM_TERMINAL = "from-terminal"
        /** Set for a landscape phone screen (`phone start landscape`), for apps that only run that way. */
        const val EXTRA_LANDSCAPE = "landscape"

        /**
         * A key by name: `back`, `enter`, `KEYCODE_TAB` (the prefix and case are optional), or a number.
         * Only keys an app handles: Android acts on power, volume, media and the like phone-wide,
         * whichever display they're sent to.
         */
        fun keyCode(name: String): Int {
            val code = KeyEvent.keyCodeFromString(name.uppercase())
            require(code != KeyEvent.KEYCODE_UNKNOWN) { "unknown key '$name'" }
            require(code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 || code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ||
                code in APP_KEYS) { "phone key only sends keys an app handles, not '$name'" }
            return code
        }

        private val APP_KEYS = setOf(
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SEARCH,
            KeyEvent.KEYCODE_COMMA, KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_EQUALS,
            KeyEvent.KEYCODE_SLASH, KeyEvent.KEYCODE_SEMICOLON, KeyEvent.KEYCODE_APOSTROPHE, KeyEvent.KEYCODE_AT,
        )

        /**
         * The phone's own aspect ratio and dp size (so apps lay out as they do on the phone), portrait
         * or [landscape], at a resolution small enough that Claude sees screenshots unscaled: then
         * screenshot pixels, element bounds and tap coordinates are all the same numbers. A virtual
         * display doesn't turn for apps that only run in landscape, so it's made that shape instead.
         */
        @Suppress("DEPRECATION")
        fun spec(ctx: Context, landscape: Boolean): Spec {
            val metrics = DisplayMetrics()
            ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
            val short = minOf(metrics.widthPixels, metrics.heightPixels).toDouble()
            val long = minOf(maxOf(metrics.widthPixels, metrics.heightPixels).toDouble(), short * MAX_ASPECT)
            val scale = LONG_EDGE / long
            val across = (short * scale).toInt() / 2 * 2
            val dpi = (metrics.densityDpi * scale).toInt()
            return if (landscape) Spec(LONG_EDGE, across, dpi) else Spec(across, LONG_EDGE, dpi)
        }
    }
}
