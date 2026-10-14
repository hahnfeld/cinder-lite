package app.cinder

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.util.Log
import org.json.JSONObject
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import java.io.DataInputStream
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Optional adb-shell access for the sandbox through the Shizuku app (⋮ → Shizuku, off by default).
 *
 * The sandbox's `rish` can't use Shizuku itself: Shizuku's own client runs Android's Java runtime,
 * which can't start inside proot. So `rish` connects here, over the abstract socket "cinder-rish",
 * and this relays the command to [ShellService], which Shizuku runs with adb-shell privileges.
 *
 * Protocol: the client sends one JSON line {"cmd", "interactive"} with its stdout and stderr fds
 * attached (SCM_RIGHTS), then frames — 'D' + length + stdin bytes, 'E' (stdin ended), 'K' (killed).
 * The reply is one line: "exit N" or "error <message>".
 *
 * The service gets pipes, never the client's own fds: SELinux won't let the shell domain touch the
 * app's terminal (a /dev/pts device), so this process copies the output across.
 */
object ShizukuBridge {
    private const val TAG = "cinder-rish"
    private const val PREF = "shizuku"
    private const val REQUEST = 7

    private var server: LocalServerSocket? = null
    @Volatile private var service: IShellService? = null
    private var connection: ServiceConnection? = null
    private val main = Handler(Looper.getMainLooper())

    fun isEnabled(ctx: Context) = ctx.getSharedPreferences("cinder", Context.MODE_PRIVATE).getBoolean(PREF, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences("cinder", Context.MODE_PRIVATE).edit().putBoolean(PREF, on).apply()
        if (on) start(ctx) else stop(ctx)
    }

    /** Shizuku's manager app, or Sui (root), which provides the same API without it. */
    fun isInstalled(ctx: Context) = Shizuku.pingBinder() || runCatching {
        ctx.packageManager.getPackageInfo(ShizukuProvider.MANAGER_APPLICATION_ID, 0); true
    }.getOrDefault(false)

    fun isRunning() = Shizuku.pingBinder()

    fun hasPermission() = isRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    /** Shows Shizuku's own permission dialog; [onResult] gets whether it was granted. */
    fun requestPermission(onResult: (Boolean) -> Unit) {
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != REQUEST) return
                Shizuku.removeRequestPermissionResultListener(this)
                onResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        Shizuku.requestPermission(REQUEST)
    }

    @Synchronized
    fun start(ctx: Context) {
        if (server != null || !isEnabled(ctx)) return
        val app = ctx.applicationContext
        val socket = runCatching { LocalServerSocket(TAG) }
            .getOrElse { Log.e(TAG, "can't listen", it); return }
        server = socket
        thread(name = "rish-accept") {
            while (server === socket) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                if (server !== socket) { client.close(); break }
                thread(name = "rish-client") { serve(app, client) }
            }
        }
    }

    @Synchronized
    fun stop(ctx: Context) {
        val socket = server ?: return
        server = null
        // accept() doesn't notice close(); a throwaway connection wakes it so its thread can exit.
        runCatching { LocalSocket().connect(LocalSocketAddress(TAG)) }
        runCatching { socket.close() }
        connection?.let { conn -> runCatching { Shizuku.unbindUserService(serviceArgs(ctx), conn, true) } }
        connection = null
        service = null
    }

    private fun serve(ctx: Context, client: LocalSocket) {
        try {
            relay(ctx, client)
        } catch (e: Exception) {
            Log.w(TAG, "rish request failed", e)
        } finally {
            runCatching { client.close() }
        }
    }

    private fun relay(ctx: Context, client: LocalSocket) {
        var fds: Array<java.io.FileDescriptor>? = null
        try {
            relayCommand(ctx, client) { fds = it }
        } finally {
            // Our copies of the client's stdout/stderr. If they stay open, whatever reads the other
            // end of a pipe (rish … | head) never sees EOF and waits forever.
            fds?.forEach { runCatching { Os.close(it) } }
        }
    }

    private fun relayCommand(ctx: Context, client: LocalSocket, received: (Array<java.io.FileDescriptor>) -> Unit) {
        val out = client.outputStream
        fun reply(line: String) { runCatching { out.write("$line\n".toByteArray()); out.flush() } }

        // Abstract sockets are reachable by every app on the device; only our own sandbox (same uid)
        // may run commands with adb-shell privileges.
        if (client.peerCredentials.uid != Process.myUid()) return

        val input = DataInputStream(client.inputStream)
        val header = JSONObject(readLine(input))
        val fds = client.ancillaryFileDescriptors?.also(received)
        if (fds == null || fds.size < 2) { reply("error rish sent no output streams"); return }

        val shell = try { connect(ctx) } catch (e: Exception) { reply("error ${e.message}"); return }
        val stdin = ParcelFileDescriptor.createPipe()
        val stdout = ParcelFileDescriptor.createPipe()
        val stderr = ParcelFileDescriptor.createPipe()
        val id = try {
            shell.start(header.optString("cmd"), header.optBoolean("interactive"), stdin[0], stdout[1], stderr[1])
        } catch (e: Exception) {
            listOf(stdin[1], stdout[0], stderr[0]).forEach { runCatching { it.close() } }
            reply("error couldn't start the command: ${e.message}")
            return
        } finally {
            listOf(stdin[0], stdout[1], stderr[1]).forEach { it.close() }   // the service has its own copies
        }
        val output = listOf(copy(stdout[0], fds[0]), copy(stderr[0], fds[1]))

        // Feed stdin from the client's frames. 'K' or the client vanishing (Ctrl-C) kills the command.
        thread(name = "rish-stdin") {
            val sink = FileOutputStream(stdin[1].fileDescriptor)
            var running = true
            try {
                while (running) {
                    // Every frame is a type byte, a 4-byte length (0 for E and K), then that many bytes.
                    val kind = input.readByte().toInt().toChar()
                    val data = ByteArray(input.readInt()).also { input.readFully(it) }
                    when (kind) {
                        'D' -> sink.write(data)
                        'E' -> { runCatching { stdin[1].close() }; running = false }
                        'K' -> { runCatching { shell.kill(id) }; running = false }
                    }
                }
                input.read()   // after E: only returns once the client goes away
                runCatching { shell.kill(id) }
            } catch (_: Exception) {
                runCatching { shell.kill(id) }
            } finally {
                runCatching { stdin[1].close() }
            }
        }

        val code = runCatching { shell.waitFor(id) }.getOrNull()
        // All output shown before the client sees the exit code, and before relay() closes its fds.
        // If the service died, its pipe ends died with it, so these still finish.
        output.forEach { it.join() }
        reply(if (code == null) "error lost the Shizuku service" else "exit $code")
    }

    /** The bound [ShellService], binding it (and asking for Shizuku permission) if needed. */
    private fun connect(ctx: Context): IShellService {
        // Shizuku hands its connection to apps when their process starts, so if it was started while
        // Cinder Lite was already running, the app has to be reopened to pick it up.
        if (!isRunning()) error("Shizuku isn't running. Start it in the Shizuku app. If it's already running, " +
            "reopen Cinder Lite (⋮ → Stop, then open it again).")
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            val answered = CountDownLatch(1)
            var granted = false
            requestPermission { granted = it; answered.countDown() }
            answered.await(60, TimeUnit.SECONDS)
            if (!granted) error("Shizuku permission wasn't granted. Allow Cinder Lite in the Shizuku app.")
        }
        service?.takeIf { it.asBinder().isBinderAlive }?.let { return it }

        val bound = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder != null && binder.pingBinder()) service = IShellService.Stub.asInterface(binder)
                bound.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) { service = null }
        }
        connection = conn
        main.post { runCatching { Shizuku.bindUserService(serviceArgs(ctx), conn) }.onFailure { bound.countDown() } }
        bound.await(20, TimeUnit.SECONDS)
        return service ?: error("couldn't start the Shizuku service")
    }

    private fun serviceArgs(ctx: Context): Shizuku.UserServiceArgs {
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt() }
            .getOrDefault(1)
        return Shizuku.UserServiceArgs(ComponentName(ctx.packageName, ShellService::class.java.name))
            .daemon(false)
            .processNameSuffix("rish")
            .debuggable(false)
            .version(version)
    }

    /** Copies a pipe from the service to one of the client's fds (its terminal, a pipe, a file). */
    private fun copy(from: ParcelFileDescriptor, to: java.io.FileDescriptor) = thread(name = "rish-output") {
        runCatching {
            ParcelFileDescriptor.AutoCloseInputStream(from).use { input ->
                val output = FileOutputStream(to)
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                }
            }
        }
    }

    private fun readLine(input: DataInputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            bytes.write(b)
        }
        return bytes.toString(Charsets.UTF_8.name())
    }
}
