package app.cinder.module.traffic

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.Menu
import app.cinder.MainActivity
import app.cinder.Sandbox
import app.cinder.SessionService
import app.cinder.module.Module
import java.io.File

/**
 * Network Monitor: what apps on the phone send and receive, through a VPN into mitmproxy in the
 * sandbox ([TrafficVpn]), read by the `traffic` helper. Turning it on installs mitmproxy and makes
 * its certificate; trusting that certificate (for HTTPS) and Android's VPN consent come the first
 * time it watches ([TrafficFlow]).
 */
object NetworkMonitor : Module {
    override val id = "traffic"
    override val title = "Network Monitor"
    override val blurb = "see what apps on the phone send and receive over the network"
    override val components = listOf(TrafficVpn::class.java)
    override val download = "about 50 MB (mitmproxy)"
    override val nextStep = "Start watching with ⋮ → Watch network traffic, or when Claude asks."
    override val requests = setOf("traffic-request")

    private const val WATCH_ID = 9005
    private val PACKAGE = Regex("[A-Za-z]\\w*(\\.[A-Za-z_]\\w*)+")

    override fun isInstalled(sandbox: Sandbox) = TrafficFlow.hasMitmproxy(sandbox) && TrafficFlow.caCert(sandbox).exists()

    override fun install(ctx: Context, sandbox: Sandbox, log: (String) -> Unit) {
        if (!TrafficFlow.hasMitmproxy(sandbox)) TrafficFlow.installMitmproxy(sandbox, log)
        log("making Cinder Lite's certificate…")
        TrafficFlow.ensureCa(sandbox, log)
    }

    override fun downloads(sandbox: Sandbox) =
        if (TrafficFlow.hasMitmproxy(sandbox) || File(sandbox.home, ".mitmproxy").exists())
            "mitmproxy (about 50 MB), its certificate and what it captured" else null

    override fun deleteDownloads(ctx: Context, sandbox: Sandbox, log: (String) -> Unit) {
        if (TrafficFlow.hasMitmproxy(sandbox)) sandbox.run("/sbin/apk del mitmproxy", asRoot = true, log = log)
        sandbox.deleteTree(File(sandbox.home, ".mitmproxy"))
        sandbox.deleteTree(File(sandbox.tmp, "cinder-traffic"))
        log("If you trusted Cinder Lite's certificate, remove it too: Settings → search Trusted credentials → " +
            "User → Cinder Lite → Uninstall. Its key is gone, so it can't be used, but there's no reason to keep it.")
    }

    /** mitmproxy or its certificate from before modules existed. */
    override fun isInUse(ctx: Context, sandbox: Sandbox) = TrafficFlow.hasMitmproxy(sandbox) || File(sandbox.home, ".mitmproxy").exists()

    override fun start(ctx: Context) = TrafficVpn.forgetStale(ctx)

    override fun stop(ctx: Context) = TrafficVpn.stop(ctx)

    override fun menu(activity: Activity, menu: Menu) {
        menu.add("Watch network traffic").setCheckable(true).setChecked(TrafficVpn.running)
            .setOnMenuItemClickListener {
                if (TrafficVpn.running) TrafficVpn.stop(activity) else TrafficFlow.watch(activity, emptyList())
                true
            }
    }

    override fun onIntent(activity: Activity, intent: Intent) = TrafficFlow.askToWatch(activity, intent)

    override fun onResume(activity: Activity) = TrafficFlow.resume(activity)

    override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int): Boolean {
        if (requestCode != TrafficFlow.VPN) return false
        TrafficFlow.consented(activity, resultCode == Activity.RESULT_OK)
        return true
    }

    /**
     * Claude's `traffic start [app…]` and `traffic stop`. Stopping needs no one's say-so; starting is
     * always the user's call, on screen ([TrafficFlow.askToWatch]), through a notification when the
     * app isn't showing. The ask lapses after [TrafficFlow.ASK_MS], when the helper stops waiting.
     */
    override fun onRequest(ctx: Context, name: String, text: String) {
        val words = text.trim().split(Regex("\\s+"))
        when (words.first()) {
            "stop" -> TrafficVpn.stop(ctx)
            "start" -> {
                val apps = words.drop(1).filter { it.matches(PACKAGE) }.take(20)
                val ask = Intent(ctx, MainActivity::class.java).putExtra(TrafficFlow.WATCH, apps.toTypedArray())
                    .putExtra(TrafficFlow.ASKED, System.currentTimeMillis())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (MainActivity.visible) {
                    runCatching { ctx.startActivity(ask) }
                    return
                }
                // Notifications off: nothing would reach the user, so say so rather than leave Claude waiting.
                val nm = SessionService.alerts(ctx) ?: return TrafficVpn.answer(ctx, "unreachable")
                nm.notify(
                    WATCH_ID, Notification.Builder(ctx, SessionService.ALERT_CHANNEL)
                        .setContentTitle("Claude Code")
                        .setContentText("Claude wants to watch network traffic. Tap to decide")
                        .setSmallIcon(android.R.drawable.stat_notify_chat)
                        .setContentIntent(PendingIntent.getActivity(ctx, 2, ask,
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                        .setAutoCancel(true)
                        .setTimeoutAfter(TrafficFlow.ASK_MS)
                        .build()
                )
            }
        }
    }
}
