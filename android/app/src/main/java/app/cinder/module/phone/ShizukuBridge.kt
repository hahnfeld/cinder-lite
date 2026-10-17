package app.cinder.module.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.system.Os
import android.util.Log
import android.widget.Toast
import app.cinder.MainActivity
import app.cinder.module.Modules
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * adb-shell access for the sandbox through the Shizuku app, while Phone Use is on.
 *
 * The sandbox's `rish` can't use Shizuku itself: Shizuku's own client runs Android's Java runtime,
 * which can't start inside proot. So `rish` connects here, over a Unix socket in the sandbox's
 * /usr/local/libexec, and this relays the command to [ShellService], which Shizuku runs with
 * adb-shell privileges. The socket lives in app-private storage, so no other app can reach it or put
 * one of its own in its place.
 *
 * Protocol: the client sends one JSON line {"cmd", "interactive"} with its stdout and stderr fds
 * attached (SCM_RIGHTS), then frames — 'D' + length + stdin bytes, 'E' (stdin ended), 'K' (killed).
 * The reply is one line: "exit N" or "error <message>". A {"phone": [op, args…]} line instead asks
 * for a phone screen operation (`phone` in the sandbox), which the app handles itself.
 *
 * The service gets pipes, never the client's own fds: SELinux won't let the shell domain touch the
 * app's terminal (a /dev/pts device), so this process copies the output across.
 */
object ShizukuBridge {
    private const val TAG = "cinder-rish"
    private const val REQUEST = 7
    private const val NO_FOCUS = "(no text field had focus, so the text may have gone nowhere: click the field first)\n"
    private const val NO_ACCESSIBILITY = "Claude can't read the phone screen: \"Cinder Lite phone screen\" is off in " +
        "Android's Accessibility settings. The user has been told how to turn it on (phone setup opens the page). " +
        "Until then use phone shot and phone tap."

    @Volatile private var server: LocalServerSocket? = null
    private var listening: LocalSocket? = null   // the bound socket [server] listens on
    @Volatile private var service: IShellService? = null
    private var connection: ServiceConnection? = null
    private val binding = Any()   // one connect() at a time (not this object's lock: start/stop run on main)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var reminded = 0L

    /** Shizuku's manager app, or Sui (root), which provides the same API without it. */
    fun isInstalled(ctx: Context) = Shizuku.pingBinder() || runCatching {
        ctx.packageManager.getPackageInfo(ShizukuProvider.MANAGER_APPLICATION_ID, 0); true
    }.getOrDefault(false)

    fun isRunning() = Shizuku.pingBinder()

    fun hasPermission() = isRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    /** Shows Shizuku's own permission dialog; [onResult] gets whether it was granted. Returns its listener. */
    fun requestPermission(onResult: (Boolean) -> Unit): Shizuku.OnRequestPermissionResultListener {
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != REQUEST) return
                Shizuku.removeRequestPermissionResultListener(this)
                onResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        Shizuku.requestPermission(REQUEST)
        return listener
    }

    @Synchronized
    fun start(ctx: Context) {
        if (server != null || !Modules.isOn(ctx, PhoneUse)) return
        val app = ctx.applicationContext
        val path = socketFile(app).apply { parentFile?.mkdirs(); delete() }
        val bound = LocalSocket(LocalSocket.SOCKET_STREAM)
        val socket = runCatching {
            bound.bind(LocalSocketAddress(path.path, LocalSocketAddress.Namespace.FILESYSTEM))
            LocalServerSocket(bound.fileDescriptor)
        }.getOrElse { Log.e(TAG, "can't listen", it); runCatching { bound.close() }; return }
        listening = bound
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
        PhoneScreenActivity.current?.close()
        server?.let { socket ->
            server = null
            // accept() doesn't notice close(); a throwaway connection wakes it so its thread can exit.
            val path = socketFile(ctx).path
            runCatching { LocalSocket().use { it.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM)) } }
            runCatching { socket.close() }
            runCatching { listening?.close() }
            listening = null
            socketFile(ctx).delete()
        }
        // Unbound even when the socket was already gone: a phone action that waited out the toggle
        // must not leave a service behind.
        connection?.let { conn -> runCatching { Shizuku.unbindUserService(serviceArgs(ctx), conn, true) } }
        connection = null
        service = null
    }

    private fun serve(ctx: Context, client: LocalSocket) {
        var fds: Array<FileDescriptor>? = null
        try {
            // Only our own sandbox (same uid) may run commands with adb-shell privileges. The socket's
            // directory already keeps other apps out; this holds even if that ever changes.
            if (client.peerCredentials.uid != Process.myUid()) return
            val input = DataInputStream(client.inputStream)
            val header = JSONObject(readLine(input))
            fds = client.ancillaryFileDescriptors
            relay(ctx, client, input, header, fds)
        } catch (e: Exception) {
            Log.w(TAG, "rish request failed", e)
        } finally {
            // Our copies of the client's stdout/stderr. If they stay open, whatever reads the other
            // end of a pipe (rish … | head) never sees EOF and waits forever.
            fds?.forEach { runCatching { Os.close(it) } }
            runCatching { client.close() }
        }
    }

    private fun relay(ctx: Context, client: LocalSocket, input: DataInputStream, header: JSONObject, fds: Array<FileDescriptor>?) {
        val out = client.outputStream
        fun reply(line: String) { runCatching { out.write("$line\n".toByteArray()); out.flush() } }

        if (fds == null || fds.size < 2) return reply("error rish sent no output streams")
        header.optJSONArray("phone")?.let { op ->
            return reply(try { phone(ctx, op, FileOutputStream(fds[0])); "exit 0" } catch (e: Exception) { "error ${why(e)}" })
        }

        val shell = try { connect(ctx) } catch (e: Exception) { return reply("error ${why(e)}") }
        val stdin = ParcelFileDescriptor.createPipe()
        val stdout = ParcelFileDescriptor.createPipe()
        val stderr = ParcelFileDescriptor.createPipe()
        val id = try {
            shell.start(header.optString("cmd"), header.optBoolean("interactive"), stdin[0], stdout[1], stderr[1])
        } catch (e: Exception) {
            listOf(stdin[1], stdout[0], stderr[0]).forEach { runCatching { it.close() } }
            reply("error couldn't start the command: ${why(e)}")
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
                    val data = ByteArray(input.readInt().also { require(it in 0..(1 shl 20)) }).also { input.readFully(it) }
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
        // All output shown before the client sees the exit code, and before serve() closes its fds.
        // If the service died, its pipe ends died with it, so these still finish.
        output.forEach { it.join() }
        reply(if (code == null) "error lost the Shizuku service" else "exit $code")
    }

    /**
     * A phone screen operation: `phone start|stop|shot|setup|ui|apps|info`, or an action, which then
     * prints the screen it leads to: `click|type|scroll|hold|find|read` through [PhoneAccessibility],
     * `tap|swipe|key|text|doubletap` as input events, `open|close|link` through the shell. Output goes
     * to [out], the client's stdout. Arguments are checked before anything waits.
     */
    private fun phone(ctx: Context, op: JSONArray, out: FileOutputStream) {
        fun arg(i: Int) = op.optString(i).ifEmpty { error("missing an argument; see: phone help") }
        fun number(i: Int) = arg(i).toFloatOrNull() ?: error("'${arg(i)}' isn't a number; see: phone help")
        fun live() = PhoneScreenActivity.current?.takeIf { it.displayId >= 0 }
        fun screen() = (live() ?: error("the phone screen isn't open. Run: phone start"))
            .also { check(!it.stopped) { "the phone is asleep; the phone screen resumes when its screen is back on" } }
        fun a11y() = PhoneAccessibility.instance ?: run { remind(ctx); error(NO_ACCESSIBILITY) }
        fun print(text: String) { out.write(text.toByteArray()); out.flush() }
        /** A point given in display pixels, refused off the phone screen, where a touch would go nowhere. */
        fun point(i: Int): Pair<Float, Float> {
            val x = number(i); val y = number(i + 1)
            val spec = screen().spec
            check(x >= 0 && y >= 0 && x < spec.width && y < spec.height) { "$x,$y is off the phone screen (${spec.width}x${spec.height})" }
            return x to y
        }
        /** Does [action] on the phone screen, then prints the screen it leads to, if Claude can read it. */
        fun act(quiet: Long = 300, longest: Long = 2000, action: (PhoneScreenActivity) -> Unit) {
            val screen = screen()
            val a11y = PhoneAccessibility.instance
            try {
                if (screen.held) {
                    // The user has taken the phone screen over: wait for it back, then let Claude decide again.
                    check(until(90_000) { !screen.held }) { "the user is using the phone screen (it's full size); try again later" }
                    error("the user used the phone screen and has given it back; nothing was done. It may have changed")
                }
                if (a11y == null) action(screen) else print(a11y.after(screen.displayId, quiet, longest) { action(screen) })
            } catch (e: Exception) {
                // Refused, failed or held: show the screen as it is now, to pick again from.
                if (a11y != null) live()?.let { runCatching { print(a11y.ui(it.displayId)) } }
                throw e
            }
        }
        when (op.optString(0)) {
            "start" -> {
                val landscape = when (op.optString(1)) {
                    "", "portrait" -> false
                    "landscape" -> true
                    else -> error("phone start [portrait|landscape]")
                }
                // A different shape is a new display: the old one closes, with its apps.
                PhoneScreenActivity.current?.takeIf { it.spec.landscape != landscape }?.let { old ->
                    val id = old.displayId
                    main.post(old::close)
                    // Its apps are gone before the new display opens, so none moves across.
                    if (id >= 0) runCatching { connect(ctx).releaseDisplay(id) }
                    until(5_000) { PhoneScreenActivity.current == null }
                    print("closed the ${if (landscape) "portrait" else "landscape"} phone screen and its apps\n")
                }
                val screen = live() ?: run {
                    connect(ctx)
                    PhoneScreenActivity.failure = null
                    ctx.startActivity(Intent(ctx, PhoneScreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(PhoneScreenActivity.EXTRA_FROM_TERMINAL, MainActivity.visible)
                        .putExtra(PhoneScreenActivity.EXTRA_LANDSCAPE, landscape))
                    until(10_000) { PhoneScreenActivity.failure?.let { error(it) }; live() != null }
                    live() ?: error("Android didn't open the phone screen. Open Cinder Lite and try again.")
                }
                print("phone screen open: display ${screen.displayId}, ${screen.spec.width}x${screen.spec.height}\n")
                if (PhoneAccessibility.instance == null) { remind(ctx); print("Note: $NO_ACCESSIBILITY\n") }
            }
            "stop" -> PhoneScreenActivity.current?.let { main.post(it::close); print("closed the phone screen and its apps\n") }
                ?: print("the phone screen wasn't open\n")
            "shot" -> out.write(screen().shot())
            "setup" -> {
                PhoneAccessibility.openSettings(ctx)
                print("Opened Accessibility settings on the phone. Turn on \"Cinder Lite phone screen\". If " +
                    "Android says it's a restricted setting: App info → ⋮ → Allow restricted settings, then try again.\n")
            }
            "ui" -> { val id = screen().displayId; print(a11y().ui(id)) }
            "apps" -> print(apps(ctx, op.optString(1)).joinToString("") { (label, pkg) -> "$label ($pkg)\n" }.ifEmpty { "(none found)\n" })
            "info" -> {
                val screen = live()
                print("${Build.MANUFACTURER} ${Build.MODEL}: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                    (screen?.spec?.let { "phone screen: ${it.width}x${it.height} pixels, ${it.dpi} dpi${if (it.landscape) ", landscape" else ""}\n" }
                        ?: "phone screen: not open (phone start opens it)\n") +
                    (screen?.let { if (it.stopped) "asleep: phone control stops working until the phone's screen is back on\n"
                        else "awake: phone control works\n" } ?: "") +
                    "Accessibility: ${if (PhoneAccessibility.instance != null) "on" else "off (phone setup)"}\n")
            }
            "click" -> { val n = number(1).toInt(); val named = arg(2); act { s -> a11y().click(n, named) { x, y -> s.tap(x, y) } } }
            "type" -> {
                val n = number(1).toInt(); val named = arg(2)
                val text = if (op.length() > 3) op.optString(3) else error("missing the text to put in it ('' empties it); see: phone help")
                act { a11y().type(n, named, text) }
            }
            "scroll" -> { val n = number(1).toInt(); val direction = arg(2); act { a11y().scroll(n, direction) } }
            "hold" -> {
                val n = number(1).toInt(); val named = arg(2)
                act { s -> a11y().hold(n, named) { x, y -> s.swipe(x, y, x, y, 800) } }
            }
            "doubletap" -> {
                val n = number(1).toInt(); val named = arg(2)
                act { s -> val (x, y) = a11y().center(n, named); s.tap(x, y); Thread.sleep(80); s.tap(x, y) }
            }
            "read" -> {
                val words = (1 until op.length()).map(op::optString)
                val fresh = "fresh" in words
                val pages = words.firstOrNull { it != "fresh" }?.let { (it.toIntOrNull() ?: error("'$it' isn't a number; see: phone help")).coerceIn(1, 9) } ?: 4
                act { print(a11y().read(it.displayId, pages, fresh)) }
            }
            "find" -> {
                val text = arg(1)
                act { a11y().find(it.displayId, text) }
                a11y().match(text)?.let { print("found: element $it\n") }
            }
            "tap" -> { val (x, y) = point(1); act { it.tap(x, y) } }
            "swipe" -> {
                val (x1, y1) = point(1); val (x2, y2) = point(3)
                val ms = if (op.length() > 5) number(5).toLong().coerceIn(0, 5000) else 300
                act { it.swipe(x1, y1, x2, y2, ms) }
            }
            "key" -> { val code = PhoneScreenActivity.keyCode(arg(1)); act { it.key(code) } }
            "text" -> {
                val text = arg(1)
                var unfocused = false
                act { s ->
                    val a11y = PhoneAccessibility.instance
                    unfocused = a11y != null && a11y.focusedField(s.displayId) == null
                    // Characters no key types (emoji, other scripts) go straight into the focused field.
                    if (!s.text(text)) (a11y ?: error("phone text types keyboard characters only, without Accessibility on " +
                        "(phone setup); use phone type N 'name' 'text'")).insert(s.displayId, text)
                }
                if (unfocused) print(NO_FOCUS)
            }
            "open" -> {
                val pkg = packageFor(ctx, arg(1))
                check(pkg != ctx.packageName) { "that's Cinder Lite itself, which stays on the user's screen" }
                act(quiet = 600, longest = 5000) { open(ctx, it.displayId, pkg) }
            }
            "close" -> { val pkg = packageFor(ctx, arg(1)); act { close(ctx, it.displayId, pkg) } }
            "link" -> {
                val url = arg(1)
                val scheme = SCHEME.find(url)?.groupValues?.get(1)?.lowercase() ?: error("'$url' isn't a URL (it needs a scheme, like https:)")
                check(scheme !in BLOCKED_SCHEMES) { "phone link doesn't open $scheme: links (calls, texts); ask the user to do that, or go through the app" }
                // It goes into a shell command, in single quotes.
                check(url.none { it == '\'' || it == '\\' || it.isWhitespace() || it.isISOControl() }) { "encode spaces and quotes in the URL (%20, %27)" }
                act(quiet = 600, longest = 5000) { s ->
                    val output = sh(ctx, "am start --display ${s.displayId} -a android.intent.action.VIEW -d '$url'")
                    check(output.lines().none { it.startsWith("Error") }) { output.trim() }
                }
            }
            else -> error("unknown phone command '${op.optString(0)}'; see: phone help")
        }
    }

    /** The socket `rish` connects to: the sandbox's /usr/local/libexec/rish.sock, which nothing there cleans up. */
    private fun socketFile(ctx: Context) = File(ctx.filesDir, "libexec/rish.sock")

    private val COMPONENT = Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")
    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
    private val BLOCKED_SCHEMES = setOf("tel", "sms", "smsto", "mms", "mmsto", "voicemail")
    private val ROOT_TASK = Regex("^[A-Za-z]* id=([0-9]+) .* displayId=([0-9]+) ")
    private val TASK = Regex("taskId=[0-9]+: ([A-Za-z0-9_.]+)/")

    /** A failure for the client to show. Shizuku dying mid-call has no message of its own. */
    private fun why(e: Exception) = if (e is RemoteException) "lost the Shizuku service" else e.message ?: e.toString()

    /** Polls [done] until it's true or [ms] pass; returns whether it came true. */
    private fun until(ms: Long, done: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + ms
        while (!done()) {
            if (SystemClock.uptimeMillis() > end) return false
            Thread.sleep(100)
        }
        return true
    }

    /**
     * Launches [pkg]'s app on the phone screen. Only the shell may start activities there. Any
     * package is visible to this app because it targets SDK 28, before package visibility filtering.
     */
    private fun open(ctx: Context, displayId: Int, pkg: String) {
        // The component goes into a shell command, and an app's manifest names its own activities.
        val component = ctx.packageManager.getLaunchIntentForPackage(pkg)?.component?.flattenToShortString()
            ?.takeIf(COMPONENT::matches)
            ?: error("no app to open for '$pkg' (phone apps lists them)")
        val output = sh(ctx, "am start --display $displayId -n '$component'")
        check(output.lines().none { it.startsWith("Error") }) { output.trim() }
    }

    /** The apps with a launcher icon, as (name, package), sorted by name; those whose either contains [name]. */
    private fun apps(ctx: Context, name: String = ""): List<Pair<String, String>> {
        val pm = ctx.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString().filterNot(Char::isISOControl) to it.activityInfo.packageName }
            .filter { (label, pkg) -> label.contains(name, true) || pkg.contains(name, true) }
            .distinct()
            .sortedBy { it.first.lowercase() }
    }

    /** An app's package, from the package itself or the app's name: exactly, else a part only one app's name has. */
    private fun packageFor(ctx: Context, target: String): String {
        if (ctx.packageManager.getLaunchIntentForPackage(target) != null) return target
        val apps = apps(ctx)
        val near = apps.filter { it.first.contains(target.trim(), true) }
        return (apps.firstOrNull { it.first.equals(target.trim(), true) } ?: near.singleOrNull())?.second
            ?: error(if (near.isEmpty()) "no app is named '$target' (phone apps lists them)"
                else "several apps' names have '$target' in them: ${near.take(5).joinToString { "${it.first} (${it.second})" }}; name one exactly")
    }

    /** Closes [pkg] on the phone screen: removes its tasks there, as swiping it away in Recents does. */
    private fun close(ctx: Context, displayId: Int, pkg: String) {
        var root: String? = null   // the root task being listed, if it's on the phone screen
        val ids = sh(ctx, "am stack list").lines().mapNotNull { line ->
            val task = ROOT_TASK.find(line)
            if (task != null) { root = task.groupValues[1].takeIf { task.groupValues[2] == "$displayId" }; null }
            else root?.takeIf { TASK.find(line)?.groupValues?.get(1) == pkg }
        }.distinct()
        check(ids.isNotEmpty()) { "$pkg isn't open on the phone screen" }
        ids.forEach { sh(ctx, "am stack remove $it") }
    }

    /** Runs [command] as the shell user; returns its output, stdout and stderr together. */
    private fun sh(ctx: Context, command: String): String {
        val shell = connect(ctx)
        val (read, write) = ParcelFileDescriptor.createPipe()
        return ParcelFileDescriptor.AutoCloseInputStream(read).use { output ->
            val id = ParcelFileDescriptor.open(File("/dev/null"), ParcelFileDescriptor.MODE_READ_ONLY).use { none ->
                write.use { shell.start(command, false, none, it, it) }
            }
            String(output.readBytes()).also { shell.waitFor(id) }
        }
    }

    /** Tells the user, on the phone, what to turn on for Claude to read the phone screen; once a minute at most. */
    private fun remind(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - reminded < 60_000) return
        reminded = now
        // A toast shows two lines at most.
        val text = "Claude needs Settings → Accessibility → Cinder Lite phone screen turned on"
        main.post { Toast.makeText(ctx, text, Toast.LENGTH_LONG).show() }
    }

    /** The bound [ShellService] if it's already connected, for the phone screen window. */
    fun connected(): IShellService? = service?.takeIf { it.asBinder().isBinderAlive }

    /** The bound [ShellService], binding it (and asking for Shizuku permission) if needed. */
    private fun connect(ctx: Context): IShellService {
        synchronized(binding) {
            check(server != null) { "Phone Use is off. Turn it on in Cinder Lite: ⋮ → Modules → Phone Use" }
            // Shizuku hands its connection to an app when the app comes to the front, so after Shizuku
            // starts, Cinder Lite picks it up once the user switches away and back.
            if (!isRunning()) error("Shizuku isn't running. Start it in the Shizuku app. If it's already running, " +
                "switch to another app and back to Cinder Lite.")
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                val answered = CountDownLatch(1)
                var granted = false
                val listener = requestPermission { granted = it; answered.countDown() }
                if (!answered.await(60, TimeUnit.SECONDS)) Shizuku.removeRequestPermissionResultListener(listener)
                if (!granted) error("Shizuku permission wasn't granted. Allow Cinder Lite in the Shizuku app.")
            }
            connected()?.let { return it }

            val bound = CountDownLatch(1)
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    if (binder != null && binder.pingBinder()) service = IShellService.Stub.asInterface(binder)
                    bound.countDown()
                }
                override fun onServiceDisconnected(name: ComponentName?) { service = null }
            }
            connection = conn
            main.post {
                // On main, where stop() runs: support turned off since the check above binds nothing.
                if (server == null) bound.countDown()
                else runCatching { Shizuku.bindUserService(serviceArgs(ctx), conn) }.onFailure { bound.countDown() }
            }
            bound.await(20, TimeUnit.SECONDS)
            return service ?: error("couldn't start the Shizuku service")
        }
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
    private fun copy(from: ParcelFileDescriptor, to: FileDescriptor) = thread(name = "rish-output") {
        // FileOutputStream is unbuffered, so each chunk reaches the client as soon as it's read.
        runCatching { ParcelFileDescriptor.AutoCloseInputStream(from).use { it.copyTo(FileOutputStream(to)) } }
    }

    private fun readLine(input: DataInputStream): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            bytes.write(b)
        }
        return bytes.toString(Charsets.UTF_8.name())
    }
}
