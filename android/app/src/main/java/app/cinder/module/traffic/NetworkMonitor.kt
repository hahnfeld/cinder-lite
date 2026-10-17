package app.cinder.module.traffic

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import app.cinder.Sandbox
import app.cinder.Step
import app.cinder.module.Module
import java.io.File

/**
 * Network Monitor: what apps on the phone send and receive, through a VPN into mitmproxy in the
 * sandbox ([TrafficVpn]), read by the `traffic` helper. Its checklist installs mitmproxy, makes its
 * certificate, and asks the user to trust it (for HTTPS) and for Android's VPN consent. From then on
 * `traffic start` and `traffic stop`, Claude's or the shell's, run captures.
 */
object NetworkMonitor : Module {
    override val id = "traffic"
    override val title = "Network Monitor"
    override val blurb = "see what apps on the phone send and receive over the network"
    override val components = listOf(TrafficVpn::class.java)
    override val requests = setOf("traffic-request")

    private val PACKAGE = Regex("[A-Za-z]\\w*(\\.[A-Za-z_]\\w*)+")
    /** Its startActivityForResult code for Android's VPN consent (modules' codes are 100+). */
    private const val VPN = 100

    override val summary = "Claude captures traffic with traffic start and traffic stop (so can you, in the " +
        "Shell). HTTPS is readable only from apps that accept certificates you add, like Chrome and apps you build. " +
        "Most apps refuse it: their first connection fails, then Cinder Lite lets that host through unread, so you " +
        "see only which hosts they reach. While a capture runs, Android shows a key icon and calls and other UDP " +
        "traffic don't get through."

    override fun steps(ctx: Context, sandbox: Sandbox) = listOf(
        Step.work("Install mitmproxy", "About 50 MB, once.", download = true, done = { hasMitmproxy(sandbox) }) {
            sandbox.run("/sbin/apk add --no-cache mitmproxy", asRoot = true, log = it)
        },
        // The certificate mitmproxy would make, but named Cinder Lite, so Settings says whose it is.
        Step.work("Make Cinder Lite's certificate", done = { caCert(sandbox).exists() }) {
            sandbox.run("python3 -c 'import pathlib; from mitmproxy.certs import CertStore; CertStore.create_store(" +
                "pathlib.Path(\"/root/.mitmproxy\"), \"mitmproxy\", 2048, \"Cinder Lite\", \"Cinder Lite\")'", log = it)
        },
        // Since Android 11 no app can install a certificate or open that screen: it waits in Downloads.
        Step.ask("Trust the certificate, to read HTTPS", "Open Settings", "In Settings, search for \"CA certificate\", " +
            "tap it, then Install anyway, and pick \"${TrafficVpn.CERT_NAME}\" in Downloads. Android asks for a screen " +
            "lock first if the phone has none. Only apps that accept certificates you add (Chrome, apps you build) can " +
            "be read; for the rest you see which hosts they reach. Remove it when you're done: Settings → Trusted " +
            "credentials → User.", optional = true, done = { TrafficVpn.trusted(caCert(sandbox)) }) {
            TrafficVpn.saveToDownloads(it, caCert(sandbox))
            runCatching { it.startActivity(Intent("com.android.settings.action.SETTINGS_SEARCH")) }
                .recoverCatching { _ -> it.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
        },
        Step.ask("Allow the VPN", "Allow", "Android asks once. Captures then start without asking.",
            done = { VpnService.prepare(ctx) == null }) { activity ->
            @Suppress("DEPRECATION")
            VpnService.prepare(activity)?.let { activity.startActivityForResult(it, VPN) }
        },
    )

    private fun hasMitmproxy(sandbox: Sandbox) = "P:mitmproxy" in sandbox.installedPackages

    /** The certificate mitmproxy signs with: made once, kept with its key in /root/.mitmproxy. */
    fun caCert(sandbox: Sandbox) = File(sandbox.home, ".mitmproxy/mitmproxy-ca-cert.pem")

    override fun downloads(sandbox: Sandbox) =
        if (hasMitmproxy(sandbox) || File(sandbox.home, ".mitmproxy").exists())
            "mitmproxy (about 50 MB), its certificate and what it captured" else null

    override fun deleteDownloads(ctx: Context, sandbox: Sandbox, log: (String) -> Unit) {
        if (hasMitmproxy(sandbox)) sandbox.run("/sbin/apk del mitmproxy", asRoot = true, log = log)
        sandbox.deleteTree(File(sandbox.home, ".mitmproxy"))
        sandbox.deleteTree(File(sandbox.tmp, "cinder-traffic"))
        log("If you trusted Cinder Lite's certificate, remove it too: Settings → search Trusted credentials → " +
            "User → Cinder Lite → Uninstall. Its key is gone, so it can't be used, but there's no reason to keep it.")
    }

    override fun start(ctx: Context) = TrafficVpn.forgetStale(ctx)

    override fun stop(ctx: Context) = TrafficVpn.stop(ctx)

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
