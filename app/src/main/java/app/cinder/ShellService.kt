package app.cinder

import android.os.ParcelFileDescriptor
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * The Shizuku user service behind the sandbox's `rish`: Shizuku runs this class in its own process
 * with adb-shell privileges (uid 2000), and ShizukuBridge talks to it over binder. Each command's
 * output goes straight to the caller's stdout/stderr fds; stdin arrives through a pipe the bridge
 * feeds.
 */
class ShellService : IShellService.Stub() {

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

    override fun destroy() {
        jobs.values.forEach { it.process.destroy() }
        exitProcess(0)
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
