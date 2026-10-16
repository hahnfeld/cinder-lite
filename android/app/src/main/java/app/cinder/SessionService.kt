package app.cinder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.FileObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.concurrent.thread

/**
 * Keeps Claude Code (and a first-run download) alive while the app is in the background.
 *
 * The CLI is a child process of *this* app's process, so its fate is the app's fate: once the
 * activity stops, Android is free to freeze the process (the cached-app freezer) or reclaim it
 * outright. Nothing here runs the CLI — a started foreground service raises the whole process's
 * importance, which is what keeps the child alive.
 *
 * The wake lock is a separate problem from the same family: importance stops the process being
 * killed, but Doze can still suspend the CPU out from under it on an idle screen-off phone. It's
 * bounded, and renewed each time a session (re)starts.
 *
 * It also handles the requests the sandbox's helper scripts leave in home/.cinder while sessions
 * run: xdg-open's URLs (OAuth login) and files, termux-install's ask for the full set of Android libraries,
 * traffic's start and stop, and Claude Code's own notifications (from the hooks in assets/claude-settings.json), which become phone
 * notifications while the app isn't on screen. One watcher for all three: Android delivers a
 * directory's events to only one FileObserver per process.
 */
class SessionService : Service() {

    private var lock: PowerManager.WakeLock? = null
    private var watcher: FileObserver? = null
    @Volatile private var turnStarted = Long.MAX_VALUE   // unknown start: never counts as long

    override fun onCreate() {
        super.onCreate()
        val dir = File(filesDir, "home/.cinder").apply { mkdirs() }
        watcher = object : FileObserver(dir, MOVED_TO or CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                when (path) {
                    "open-request" -> take(File(dir, path)).trim().let { if (it.startsWith("/")) openFile(it) else openUrl(it) }
                    "notify-request" -> runCatching { JSONObject(take(File(dir, path))) }.getOrNull()?.let(::onHook)
                    "traffic-request" -> onTraffic(take(File(dir, path)).trim().split(Regex("\\s+")))
                    "stage-request" -> {
                        File(dir, path).delete()
                        thread {
                            runCatching { Sandbox(applicationContext).stageAndroidRuntime(full = true) }
                            File(dir, "stage-done").writeText("")
                        }
                    }
                }
            }
        }.apply { startWatching() }
        ShizukuBridge.start(this)   // no-op unless turned on in ⋮ → Shizuku
        TrafficVpn.forgetStale(this)
    }

    /**
     * Reads a request file and removes it. Anything in the sandbox can write these, so only a small
     * regular file is read: not a link (to /dev/zero, say), a pipe that would block, or a huge file.
     */
    private fun take(file: File) = runCatching {
        val path = file.toPath()
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 1 shl 16) ""
        else Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { String(it.readBytes()) }
    }.getOrDefault("").also { file.delete() }

    /** Only web pages, in a browser: anything in the sandbox can call xdg-open, and other schemes
     *  (or an https link another app claims) reach payment, SMS and other apps' deep links. */
    private fun openUrl(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "https") return
        val view = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // The default browser, by package: a bare https intent goes to whichever app claims the link.
        packageManager.resolveActivity(Intent(view).setData(Uri.parse("https://example.com")), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName?.takeIf { it != "android" }?.let(view::setPackage)
        runCatching { startActivity(view) }
    }

    /**
     * A sandbox file, in the app the user picks: never one chosen for them, since anything in the
     * sandbox can call xdg-open. Hidden files (logins, keys) and APKs (installing is the user's
     * call, from their Download folder) aren't handed out.
     */
    private fun openFile(guest: String) {
        if (guest.split('/').any { it.startsWith(".") } || guest.endsWith(".apk", ignoreCase = true)) return
        val file = Sandbox(applicationContext).hostFile(guest)
        if (!SharedFiles.shareable(this, file)) return
        val type = SharedFiles.type(file)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(SharedFiles.uri(this, file), type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Text no app claims by its own type (Markdown, say) still opens as plain text, in the browser at least.
        if (type.startsWith("text/") && packageManager.queryIntentActivities(view, 0).isEmpty()) view.setDataAndType(view.data, "text/plain")
        // With no app for it, Android's chooser shows nothing at all: say so instead.
        if (packageManager.queryIntentActivities(view, 0).isEmpty()) {
            Handler(Looper.getMainLooper()).post { Toast.makeText(this, "No app on this phone opens ${file.name}", Toast.LENGTH_LONG).show() }
            return
        }
        runCatching { startActivity(Intent.createChooser(view, "Open ${file.name} with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /**
     * Claude's `traffic start [app…]` and `traffic stop`. Stopping needs no one's say-so; starting is
     * always the user's call, on screen (MainActivity's dialog), through a notification when the
     * app isn't showing. The ask lapses after [MainActivity.ASK_MS], when the helper stops waiting.
     */
    private fun onTraffic(words: List<String>) {
        when (words.first()) {
            "stop" -> TrafficVpn.stop(this)
            "start" -> {
                val apps = words.drop(1).filter { it.matches(PACKAGE) }.take(20)
                val ask = Intent(this, MainActivity::class.java).putExtra(MainActivity.WATCH, apps.toTypedArray())
                    .putExtra(MainActivity.ASKED, System.currentTimeMillis())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (MainActivity.visible) {
                    runCatching { startActivity(ask) }
                    return
                }
                val nm = getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(
                    NotificationChannel(ALERT_CHANNEL, "Claude Code alerts", NotificationManager.IMPORTANCE_HIGH)
                )
                // Notifications off: nothing would reach the user, so say so rather than leave Claude waiting.
                if (!nm.areNotificationsEnabled() || nm.getNotificationChannel(ALERT_CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) {
                    TrafficVpn.answer(this, "unreachable")
                    return
                }
                nm.notify(
                    WATCH_ID, Notification.Builder(this, ALERT_CHANNEL)
                        .setContentTitle("Claude Code")
                        .setContentText("Claude wants to watch network traffic. Tap to decide")
                        .setSmallIcon(android.R.drawable.stat_notify_chat)
                        .setContentIntent(PendingIntent.getActivity(this, 2, ask,
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                        .setAutoCancel(true)
                        .setTimeoutAfter(MainActivity.ASK_MS)
                        .build()
                )
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            MainActivity.stop(applicationContext)
            return START_NOT_STICKY
        }
        startForeground(ID, build())
        val pm = getSystemService(PowerManager::class.java)
        (lock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cinder:session").also {
            it.setReferenceCounted(false)
            lock = it
        }).acquire(WAKE_LOCK_MS)   // never indefinite: a forgotten session must not drain the battery
        // Deliberately not sticky. If Android does kill us the CLI died with the process, so
        // restarting an empty service would only show a notification that lies.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watcher?.stopWatching()
        lock?.let { if (it.isHeld) it.release() }
        lock = null
        super.onDestroy()
    }

    /**
     * A Claude Code hook event. UserPromptSubmit marks when a turn starts; Stop (the turn is done)
     * notifies only for turns that ran a minute or more; Notification does when Claude needs
     * something (permission, a question) — but not its idle "waiting for your input" reminder, which
     * would ping after every reply. Nothing is posted while the app is on screen.
     */
    private fun onHook(event: JSONObject) {
        val now = System.currentTimeMillis()
        val message = when (event.optString("hook_event_name")) {
            "UserPromptSubmit" -> { turnStarted = now; return }
            "Stop" -> {
                val took = now - turnStarted
                turnStarted = Long.MAX_VALUE
                if (took < LONG_TURN_MS) return
                "Claude has finished"
            }
            else -> {
                if (event.optString("notification_type") == "idle_prompt") return
                event.optString("message").ifBlank { return }
            }
        }
        if (MainActivity.visible) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL, "Claude Code alerts", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.notify(
            ALERT_ID, Notification.Builder(this, ALERT_CHANNEL)
                .setContentTitle("Claude Code")   // fixed: the sandbox writes these events, and could pose as another app
                .setContentText(message.take(200))
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build()
        )
    }

    private fun openApp() =
        PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun build(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Claude Code session", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SessionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Claude Code is running")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(openApp())
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "cinder-session"
        private const val ID = 9002
        private const val ACTION_STOP = "app.cinder.STOP"
        private const val WAKE_LOCK_MS = 60 * 60 * 1000L
        private const val ALERT_CHANNEL = "cinder-alerts"
        private const val ALERT_ID = 9003
        private const val LONG_TURN_MS = 60_000L
        private const val WATCH_ID = 9005
        private val PACKAGE = Regex("[A-Za-z]\\w*(\\.[A-Za-z_]\\w*)+")

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, SessionService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, SessionService::class.java))
        }

        fun clearAlert(ctx: Context) {
            ctx.getSystemService(NotificationManager::class.java).cancel(ALERT_ID)
        }
    }
}
