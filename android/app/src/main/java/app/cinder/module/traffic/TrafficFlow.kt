package app.cinder.module.traffic

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import app.cinder.Sandbox
import app.cinder.module.Modules
import java.io.File

/**
 * Getting Network Monitor ready when it's checked: mitmproxy and its certificate (the install), then
 * the user's trust in that certificate and Android's VPN consent ([turnOn]). Then `traffic start`
 * and `traffic stop` run captures ([NetworkMonitor.onRequest], [TrafficVpn]) with no more asking.
 */
object TrafficFlow {
    /** MainActivity's request code for Android's VPN consent (modules' codes are 100+). */
    const val VPN = 100
    private const val SETTINGS_SEARCH = "com.android.settings.action.SETTINGS_SEARCH"

    private val main = Handler(Looper.getMainLooper())
    // Waiting on the user, who's in Settings trusting the certificate.
    private var pendingTrust = false

    private fun toast(ctx: Context, text: String) { main.post { Toast.makeText(ctx, text, Toast.LENGTH_LONG).show() } }

    // ---------------- mitmproxy and its certificate ----------------

    fun hasMitmproxy(sandbox: Sandbox) = "P:mitmproxy" in sandbox.installedPackages

    fun installMitmproxy(sandbox: Sandbox, log: (String) -> Unit) {
        sandbox.run("/sbin/apk add --no-cache mitmproxy", asRoot = true, log = log)
        check(hasMitmproxy(sandbox)) { "mitmproxy didn't install" }
    }

    /** The certificate mitmproxy signs with: made once, kept with its key in /root/.mitmproxy. */
    fun caCert(sandbox: Sandbox) = File(sandbox.home, ".mitmproxy/mitmproxy-ca-cert.pem")

    /** Makes [caCert] the way mitmproxy would, but named Cinder Lite, so Settings says whose it is. */
    fun ensureCa(sandbox: Sandbox, log: (String) -> Unit) {
        if (caCert(sandbox).exists()) return
        sandbox.run("python3 -c 'import pathlib; from mitmproxy.certs import CertStore; " +
            "CertStore.create_store(pathlib.Path(\"/root/.mitmproxy\"), \"mitmproxy\", 2048, \"Cinder Lite\", \"Cinder Lite\")'", log = log)
        check(caCert(sandbox).exists()) { "couldn't make the certificate" }
    }

    // ---------------- checked: the user's say-so ----------------

    /** Network Monitor was just turned on (and installed): the certificate, then the VPN consent. */
    fun turnOn(activity: Activity) = trust(activity)

    /** The user backed out of the asks, so it couldn't capture: back off, as if they'd never checked it. */
    private fun backOut(ctx: Context) {
        Modules.set(ctx, NetworkMonitor, false)
        toast(ctx, "Network Monitor is off")
    }

    /**
     * HTTPS is readable only once the user trusts Cinder Lite's certificate in Settings: since
     * Android 11 no app can install one or open that screen, so the certificate waits in Downloads.
     */
    private fun trust(activity: Activity) {
        val cert = caCert(Sandbox(activity.applicationContext))
        if (TrafficVpn.trusted(cert)) return consent(activity)
        runCatching { TrafficVpn.saveToDownloads(activity, cert) }.onFailure { return consent(activity) }
        AlertDialog.Builder(activity)
            .setTitle("Read HTTPS too?")
            .setMessage("To read what apps send over HTTPS, Android needs you to trust Cinder Lite's certificate, once:\n\n" +
                "1. Tap Open Settings and search for \"CA certificate\"\n" +
                "2. Tap it, then Install anyway\n" +
                "3. Pick \"${TrafficVpn.CERT_NAME}\" in Downloads, then come back here\n\n" +
                "Android asks for a screen lock first if the phone has none. Only apps that accept certificates " +
                "you add (Chrome, apps you build) can be read; for the rest you see which hosts they reach.\n\n" +
                "Remove it when you're done: Settings → Trusted credentials → User.")
            .setPositiveButton("Open Settings") { _, _ ->
                pendingTrust = true
                runCatching { activity.startActivity(Intent(SETTINGS_SEARCH)) }
                    .recoverCatching { activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
            }
            .setNeutralButton("Without HTTPS") { _, _ -> consent(activity) }
            .setNegativeButton("Cancel") { _, _ -> backOut(activity) }
            .setOnCancelListener { backOut(activity) }
            .show()
    }

    /** Back from Settings, where the user may have trusted the certificate. */
    fun resume(activity: Activity) {
        if (pendingTrust) { pendingTrust = false; trust(activity) }
    }

    /** Android's own VPN consent, the first time only. */
    private fun consent(activity: Activity) {
        val ask = VpnService.prepare(activity) ?: return ready(activity)
        @Suppress("DEPRECATION")
        activity.startActivityForResult(ask, VPN)
    }

    /** Android's answer to [consent]'s ask. */
    fun consented(activity: Activity, granted: Boolean) {
        if (granted) ready(activity) else backOut(activity)
    }

    private fun ready(ctx: Context) = toast(ctx, "Network Monitor is ready: Claude starts a capture with traffic start")
}
