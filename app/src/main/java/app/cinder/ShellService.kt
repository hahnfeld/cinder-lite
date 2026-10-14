package app.cinder

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.ParcelFileDescriptor
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

    private class Job(val process: Process, val output: List<Thread>)

    private val jobs = ConcurrentHashMap<Int, Job>()
    private val ids = AtomicInteger()

    override fun start(
        command: String,
        interactive: Boolean,
        stdin: ParcelFileDescriptor,
        stdout: ParcelFileDescriptor,
        stderr: ParcelFileDescriptor
    ): Int {
        val argv = if (interactive) listOf("/system/bin/sh", "-i") else listOf("/system/bin/sh", "-c", command)
        val process = ProcessBuilder(argv).start()
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
        jobs[id]?.process?.destroy()
    }

    @Synchronized   // waits for a releaseDisplay in progress, so its apps are closed before exiting
    override fun destroy() {
        jobs.values.forEach { it.process.destroy() }
        displays.keys.toList().forEach(::releaseDisplay)
        exitProcess(0)
    }

    private val displays = ConcurrentHashMap<Int, VirtualDisplay>()

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
     * scrcpy's --new-display flags (most are hidden, so they're written as numbers), without system
     * decorations (a home screen and taskbar Claude doesn't need) and with STEAL_TOP_FOCUS_DISABLED: a
     * trusted display with its own focus, so the phone screen never takes focus or input from the
     * user's own screen, and closing it closes its apps rather than moving them onto the user's screen.
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
                (1 shl 11) or                // OWN_DISPLAY_GROUP
                (1 shl 12) or                // ALWAYS_UNLOCKED
                (1 shl 13)                   // TOUCH_FEEDBACK_DISABLED
        }
        if (Build.VERSION.SDK_INT >= 34) {
            flags = flags or (1 shl 14) or   // OWN_FOCUS
                (1 shl 15) or                // DEVICE_DISPLAY_GROUP
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
