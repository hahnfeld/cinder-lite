package app.cinder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.FileObserver
import android.os.IBinder
import android.os.PowerManager
import org.json.JSONObject
import java.io.File
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
 * run: xdg-open's URLs (OAuth login), termux-install's ask for the full set of Android libraries,
 * and Claude Code's own notifications (from the hooks in assets/claude-settings.json), which become phone
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
                    "open-request" -> openUrl(take(File(dir, path)).trim())
                    "notify-request" -> runCatching { JSONObject(take(File(dir, path))) }.getOrNull()?.let(::onHook)
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
    }

    /** Reads a request file and removes it. */
    private fun take(file: File) = runCatching { file.readText() }.getOrDefault("").also { file.delete() }

    /** Only web pages: anything in the sandbox can call xdg-open, and other schemes reach payment,
     *  SMS and other apps' deep links. */
    private fun openUrl(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" && uri.scheme != "http") return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
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
                .setContentTitle(event.optString("title").ifBlank { "Claude Code" })
                .setContentText(message)
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
