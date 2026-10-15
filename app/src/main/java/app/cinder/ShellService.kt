package app.cinder

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.input.InputManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import android.system.OsConstants
import android.view.InputEvent
import android.view.Surface
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * The Shizuku user service behind the sandbox's `rish` and the phone screen: Shizuku runs this
 * class in its own process with adb-shell privileges (uid 2000), and ShizukuBridge talks to it over
 * binder. Each command's output goes to pipes the bridge relays; stdin arrives through a pipe the
 * bridge feeds.
 *
 * Shizuku passes a [context] (this app's, opened in Shizuku's process) when it has this constructor.
 */
class ShellService(private val context: Context?) : IShellService.Stub() {

    constructor() : this(null)

    private class Job(val process: Process, val output: List<Thread>) {
        /** The shell's pid, which `setsid` makes its process group's id too (0 if unreadable). */
        val group = runCatching { process.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(process) }
            .getOrDefault(0)

        /** Ends the shell and everything it started: Ctrl-C on `rish -c 'logcat | grep x'` ends both sides. */
        fun end() {
            if (group > 0) runCatching { Os.kill(-group, OsConstants.SIGTERM) }
            process.destroy()
        }
    }

    private val jobs = ConcurrentHashMap<Int, Job>()
    private val ids = AtomicInteger()
    private val displays = ConcurrentHashMap<Int, VirtualDisplay>()

    override fun start(
        command: String,
        interactive: Boolean,
        stdin: ParcelFileDescriptor,
        stdout: ParcelFileDescriptor,
        stderr: ParcelFileDescriptor
    ): Int {
        // Each command in a process group of its own, so kill() reaches all of it.
        val shell = if (interactive) listOf("/system/bin/sh", "-i") else listOf("/system/bin/sh", "-c", command)
        val process = ProcessBuilder(listOf("/system/bin/setsid") + shell).start()
        pump(ParcelFileDescriptor.AutoCloseInputStream(stdin), process.outputStream)
        val output = listOf(
            pump(process.inputStream, ParcelFileDescriptor.AutoCloseOutputStream(stdout)),
            pump(process.errorStream, ParcelFileDescriptor.AutoCloseOutputStream(stderr))
        )
        return ids.incrementAndGet().also { jobs[it] = Job(process, output) }
    }

    override fun waitFor(id: Int): Int {
        val job = jobs[id] ?: return -1
        val code = job.process.waitFor()
        job.output.forEach { it.join() }   // all output written before the caller sees the exit code
        jobs.remove(id)
        return code
    }

    override fun kill(id: Int) {
        jobs[id]?.end()
    }

    @Synchronized   // waits for a releaseDisplay in progress, so its apps are closed before exiting
    override fun destroy() {
        jobs.values.forEach { it.end() }
        displays.keys.toList().forEach(::releaseDisplay)
        exitProcess(0)
    }

    override fun createDisplay(width: Int, height: Int, dpi: Int, surface: Surface): Int {
        // createVirtualDisplay checks that the caller's package matches its uid, here the shell's.
        val shell = (context ?: error("this Shizuku version is too old for the phone screen"))
            .createPackageContext("com.android.shell", 0)
        val display = shell.getSystemService(DisplayManager::class.java)
            .createVirtualDisplay("Cinder Lite phone screen", width, height, dpi, surface, displayFlags())
        val id = display.display.displayId
        displays[id] = display
        // No keyboard for the phone screen's text fields: Android would otherwise show it on the
        // user's screen, typing into the phone screen. Claude sets text without one. The method is
        // hidden (WindowManager.DISPLAY_IME_POLICY_HIDE = 2), as in scrcpy's --display-ime-policy.
        runCatching {
            val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)!!
            wm.javaClass.getMethod("setDisplayImePolicy", Int::class.java, Int::class.java).invoke(wm, id, 2)
        }
        return id
    }

    override fun setSurface(displayId: Int, surface: Surface?) {
        displays[displayId]?.surface = surface
    }

    /**
     * Sends [event] to the display, the way scrcpy does as the shell: InputManager's hidden
     * injectInputEvent, after the event's hidden setDisplayId.
     */
    private val injector by lazy {
        val manager = runCatching { Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null) }
            .getOrElse { InputManager::class.java.getMethod("getInstance").invoke(null) }!!
        val inject = manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.java)
        val setDisplayId = InputEvent::class.java.getMethod("setDisplayId", Int::class.java)
        return@lazy { event: InputEvent, displayId: Int ->
            setDisplayId.invoke(event, displayId)
            inject.invoke(manager, event, 0) as Boolean   // INJECT_INPUT_EVENT_MODE_ASYNC
        }
    }

    override fun inject(displayId: Int, event: InputEvent) = runCatching { injector(event, displayId) }
        .onFailure { if (!injectFailed) { injectFailed = true; Log.w("cinder-rish", "can't inject input", it) } }
        .getOrDefault(false)
    @Volatile private var injectFailed = false   // logged once: the user's touches would repeat it

    @Synchronized
    override fun releaseDisplay(displayId: Int) {
        val display = displays.remove(displayId) ?: return
        closeApps(displayId)
        display.release()
    }

    /**
     * Removes the apps' tasks from the display while it still exists. DESTROY_CONTENT_ON_REMOVAL
     * alone leaves them behind, hidden, and Android won't remove them once the display is gone.
     */
    private fun closeApps(displayId: Int) {
        runCatching {
            val script = "for id in \$(am stack list | sed -n 's/^RootTask id=\\([0-9]*\\) .* displayId=$displayId .*/\\1/p'); " +
                "do am stack remove \$id; done"
            ProcessBuilder("/system/bin/sh", "-c", script).redirectErrorStream(true).start()
                .apply { inputStream.readBytes(); waitFor() }
        }
    }

    /**
     * scrcpy's --new-display flags (most are hidden, so they're written as numbers), with these
     * changes: no system decorations (a home screen and taskbar Claude doesn't need); no display group
     * of its own (OWN_DISPLAY_GROUP, ALWAYS_UNLOCKED, DEVICE_DISPLAY_GROUP), so the phone screen sleeps
     * and wakes with the phone's own instead of on its own timer; and STEAL_TOP_FOCUS_DISABLED. That
     * makes a trusted display with its own focus, so it never takes focus or input from the user's
     * screen, and closing it closes its apps rather than moving them onto the user's screen.
     */
    private fun displayFlags(): Int {
        var flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            (1 shl 6) or    // SUPPORTS_TOUCH
            (1 shl 7) or    // ROTATES_WITH_CONTENT
            (1 shl 8)       // DESTROY_CONTENT_ON_REMOVAL
        if (Build.VERSION.SDK_INT >= 33) {
            flags = flags or (1 shl 10) or   // TRUSTED
                (1 shl 13)                   // TOUCH_FEEDBACK_DISABLED
        }
        if (Build.VERSION.SDK_INT >= 34) {
            flags = flags or (1 shl 14) or   // OWN_FOCUS
                (1 shl 16)                   // STEAL_TOP_FOCUS_DISABLED: taps there don't take the user's keys
        }
        return flags
    }

    /**
     * Copies [from] to [to] on a thread until either side ends, then closes both. Flushes every
     * chunk: a process's stdin is buffered, and interactive input must arrive line by line.
     */
    private fun pump(from: InputStream, to: OutputStream) = thread {
        runCatching {
            from.use { input ->
                to.use { output ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        output.flush()
                    }
                }
            }
        }
    }
}
