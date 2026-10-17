package app.cinder.module.traffic

import android.app.Activity
import android.content.Context
import android.net.VpnService
import app.cinder.Sandbox
import app.cinder.module.Module
import java.io.File

/**
 * Network Monitor: what apps on the phone send and receive, through a VPN into mitmproxy in the
 * sandbox ([TrafficVpn]), read by the `traffic` helper. Turning it on installs mitmproxy, makes its
 * certificate, and asks the user to trust it (for HTTPS) and for Android's VPN consent ([TrafficFlow]).
 * From then on `traffic start` and `traffic stop`, Claude's or the shell's, run captures.
 */
object NetworkMonitor : Module {
    override val id = "traffic"
    override val title = "Network Monitor"
    override val blurb = "see what apps on the phone send and receive over the network"
    override val components = listOf(TrafficVpn::class.java)
    override val download = "about 50 MB (mitmproxy)"
    override val requests = setOf("traffic-request")

    private val PACKAGE = Regex("[A-Za-z]\\w*(\\.[A-Za-z_]\\w*)+")

    override fun nextStep(ctx: Context) = "Claude captures traffic with traffic start and traffic stop (so can you, in " +
        "the Shell). While a capture runs, Android shows a key icon and calls and other UDP traffic don't get through."

    override fun isInstalled(sandbox: Sandbox) = TrafficFlow.hasMitmproxy(sandbox) && TrafficFlow.caCert(sandbox).exists()

    override fun install(ctx: Context, sandbox: Sandbox, log: (String) -> Unit) {
        if (!TrafficFlow.hasMitmproxy(sandbox)) TrafficFlow.installMitmproxy(sandbox, log)
        log("making Cinder Lite's certificate…")
        TrafficFlow.ensureCa(sandbox, log)
    }

    override fun turnedOn(activity: Activity) = TrafficFlow.turnOn(activity)

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

    override fun start(ctx: Context) = TrafficVpn.forgetStale(ctx)

    override fun stop(ctx: Context) = TrafficVpn.stop(ctx)

    override fun onResume(activity: Activity) = TrafficFlow.resume(activity)

    override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int): Boolean {
        if (requestCode != TrafficFlow.VPN) return false
        TrafficFlow.consented(activity, resultCode == Activity.RESULT_OK)
        return true
    }

    /**
     * `traffic start [app…]` and `traffic stop`. Turning the module on was the user's say-so, so a
     * capture starts at once; what failed goes back to the helper waiting on it ([TrafficVpn.answer]).
     */
    override fun onRequest(ctx: Context, name: String, text: String) {
        val words = text.trim().split(Regex("\\s+"))
        when (words.first()) {
            "stop" -> TrafficVpn.stop(ctx)
            "start" -> {
                if (TrafficVpn.running) return
                if (VpnService.prepare(ctx) != null) return TrafficVpn.answer(ctx, "failed: Android's VPN consent " +
                    "is missing (the user can turn Network Monitor off and on in ⋮ → Modules to give it)")
                // Only apps that can run: watching a turned-off one (Chrome, on a phone that browses with
                // Chrome Beta) would show nothing at all.
                val asked = words.drop(1).filter { it.matches(PACKAGE) }.take(20)
                // Its own traffic is how the capture leaves the phone (mitmproxy's, Claude's): into the VPN, it would loop.
                if (ctx.packageName in asked) return TrafficVpn.answer(ctx, "failed: Cinder Lite's own traffic can't be " +
                    "captured: it's how the capture itself reaches the network")
                val apps = asked.filter { runCatching { ctx.packageManager.getApplicationInfo(it, 0).enabled }.getOrDefault(false) }
                if (asked.isNotEmpty() && apps.isEmpty()) return TrafficVpn.answer(ctx,
                    "failed: none of those apps is on this phone and turned on (phone apps lists them)")
                runCatching { TrafficVpn.start(ctx, apps) }.onFailure { TrafficVpn.answer(ctx, "failed: ${it.message}") }
            }
        }
    }
}
