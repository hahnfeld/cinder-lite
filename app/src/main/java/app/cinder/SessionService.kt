package app.cinder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager

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
 */
class SessionService : Service() {

    private var lock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            MainActivity.stop(applicationContext)
            return START_NOT_STICKY
        }
        startForeground(ID, build())
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        (lock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cinder:session").also {
            it.setReferenceCounted(false)
            lock = it
        }).acquire(WAKE_LOCK_MS)   // never indefinite: a forgotten session must not drain the battery
        // Deliberately not sticky. If Android does kill us the CLI died with the process, so
        // restarting an empty service would only show a notification that lies.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        lock?.let { if (it.isHeld) it.release() }
        lock = null
        super.onDestroy()
    }

    private fun build(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Claude Code session", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SessionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Claude Code is running")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "cinder-session"
        private const val ID = 9002
        private const val ACTION_STOP = "app.cinder.STOP"
        private const val WAKE_LOCK_MS = 60 * 60 * 1000L

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, SessionService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, SessionService::class.java))
        }
    }
}
