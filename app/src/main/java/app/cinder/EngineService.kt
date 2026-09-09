package app.cinder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps a running turn alive while the app is in the background.
 *
 * The CLI is a child process of *this* app's process, so its fate is the app's fate: once the
 * activity stops, Android is free to freeze the process (the cached-app freezer) or reclaim it
 * outright, and a turn that was mid-flight simply stops. Nothing here runs the CLI — the point
 * is that a started foreground service raises the whole process's importance, which is what
 * keeps the child alive.
 *
 * The wake lock is a separate problem from the same family: importance stops the process being
 * killed, but Doze can still suspend the CPU out from under it on an idle screen-off phone.
 *
 * Android 15 caps dataSync foreground services at six hours a day. That's far past any single
 * turn, and the service only runs while one is actually in flight.
 */
class EngineService : Service() {

    private var lock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val what = intent?.getStringExtra(EXTRA_TEXT) ?: "working…"
        startForeground(ID, build(what))
        if (lock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cinder:turn").apply {
                setReferenceCounted(false)
                // Never indefinite: a wedged turn must not hold the CPU awake forever.
                acquire(60 * 60 * 1000L)
            }
        }
        // Deliberately not sticky. If Android does kill us mid-turn the CLI died with the
        // process, so restarting an empty service would only show a notification that lies.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        lock?.let { if (it.isHeld) it.release() }
        lock = null
        super.onDestroy()
    }

    private fun build(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Running turns", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Claude is working")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "cinder-turn"
        private const val ID = 9002
        private const val EXTRA_TEXT = "text"
        const val STOP = "app.cinder.STOP_ENGINE"

        fun start(ctx: Context, text: String) = runCatching {
            val i = Intent(ctx, EngineService::class.java).putExtra(EXTRA_TEXT, text)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) = runCatching {
            ctx.startService(Intent(ctx, EngineService::class.java).setAction(STOP))
        }
    }
}
