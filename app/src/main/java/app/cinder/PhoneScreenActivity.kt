package app.cinder

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Rational
import android.view.Display
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The phone screen: a virtual display that Claude drives (`phone` in the sandbox), shown in a
 * picture-in-picture window. [ShellService] creates the display with adb-shell privileges and it
 * renders straight into this window's SurfaceView; the display lives exactly as long as the window.
 */
class PhoneScreenActivity : Activity(), SurfaceHolder.Callback {

    /** The display's size: the phone's own, scaled down so its long edge is [LONG_EDGE]. */
    class Spec(val width: Int, val height: Int, val dpi: Int) {
        val aspect get() = Rational(width, height)
    }

    private val spec by lazy { spec(this) }
    private var shell: IShellService? = null
    private lateinit var view: SurfaceView
    @Volatile var displayId = -1
        private set
    private var entered = false
    private var returned = false
    private var stopped = false
    private val died = IBinder.DeathRecipient { runOnUiThread { finish() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shell = ShizukuBridge.connected()
        if (shell == null) { failure = "lost the Shizuku service"; finish(); return }
        shell!!.asBinder().linkToDeath(died, 0)
        view = SurfaceView(this)
        // A buffer the display's size, whatever size the window is, so screenshots are full size
        // and the display never has to change resolution as the window is resized.
        view.holder.setFixedSize(spec.width, spec.height)
        view.holder.addCallback(this)
        setContentView(view)
        current = this
    }

    override fun onResume() {
        super.onResume()
        // Into the PiP window once; after that the user may expand it, and Back shrinks it again.
        if (!entered) { entered = true; if (!isInPictureInPictureMode) enterPictureInPictureMode(params()) }
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        // The PiP window can't take keyboard focus, but Android leaves focus on it; hand it back to
        // the terminal the first time, so the user (and the keyboard) carry on where they were.
        if (!inPip && stopped) dismissed()
        if (inPip && !returned) {
            returned = true
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        enterPictureInPictureMode(params())
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
        // Leaving the expanded phone screen shrinks it back into PiP (Android 12+ does this itself).
        if (Build.VERSION.SDK_INT < 31) enterPictureInPictureMode(params())
    }

    override fun onStart() {
        super.onStart()
        stopped = false
    }

    override fun onStop() {
        super.onStop()
        stopped = true
        if (entered && !isInPictureInPictureMode) dismissed()
    }

    /**
     * The user dismissed the window (its ✕ or a swipe away): Android stops it and takes it out of
     * PiP, in either order, so close the phone screen too. In PiP it also stops when the screen turns
     * off, and expanding it leaves PiP while started; neither closes it.
     */
    private fun dismissed() {
        release()
        finish()
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    /** Closes the display (and the apps on it). Safe to call twice. */
    fun release() {
        if (current === this) current = null
        val shell = shell ?: return
        this.shell = null
        runCatching { shell.asBinder().unlinkToDeath(died, 0) }
        // Closing the display's apps takes a moment; not on the main thread.
        val id = displayId
        if (id >= 0) thread { runCatching { shell.releaseDisplay(id) } }
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

    private fun params() = PictureInPictureParams.Builder().setAspectRatio(spec.aspect).apply {
        if (Build.VERSION.SDK_INT >= 31) setAutoEnterEnabled(true).setSeamlessResizeEnabled(false)
    }.build()

    companion object {
        private const val LONG_EDGE = 1400
        private const val MAX_ASPECT = 2.35   // PiP windows can't be narrower than 1 : 2.39

        @Volatile var current: PhoneScreenActivity? = null
            private set
        /** Why the last start failed, for the bridge to report. */
        @Volatile var failure: String? = null

        /**
         * Portrait, the phone's own aspect ratio and dp size (so apps lay out as they do on the
         * phone), at a resolution small enough that Claude sees screenshots unscaled: then
         * screenshot pixels, element bounds and tap coordinates are all the same numbers.
         */
        @Suppress("DEPRECATION")
        fun spec(ctx: Context): Spec {
            val metrics = DisplayMetrics()
            ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
            val short = minOf(metrics.widthPixels, metrics.heightPixels).toDouble()
            val long = minOf(maxOf(metrics.widthPixels, metrics.heightPixels).toDouble(), short * MAX_ASPECT)
            val scale = LONG_EDGE / long
            return Spec((short * scale).toInt() / 2 * 2, LONG_EDGE, (metrics.densityDpi * scale).toInt())
        }
    }
}
