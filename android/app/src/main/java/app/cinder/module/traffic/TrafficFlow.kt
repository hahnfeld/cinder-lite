package app.cinder.module.traffic

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import app.cinder.MainActivity
import app.cinder.Sandbox
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Getting from "watch" to [TrafficVpn] running: mitmproxy and its certificate, the user's trust in
 * that certificate, Android's VPN consent. ⋮ → Watch network traffic starts it, and so does the
 * user's yes to Claude's `traffic start` ([askToWatch]).
 */
object TrafficFlow {
    private const val TAG = "cinder"
    /** MainActivity's request code for Android's VPN consent (modules' codes are 100+). */
    const val VPN = 100
    /** SessionService's ask to watch traffic for Claude: the package names, or none for every app. */
    const val WATCH = "app.cinder.WATCH"
    /** When Claude asked; the ask lapses after [ASK_MS], as long as `traffic start` waits for it. */
    const val ASKED = "app.cinder.ASKED"
    const val ASK_MS = 120_000L
    private const val SETTINGS_SEARCH = "com.android.settings.action.SETTINGS_SEARCH"

    private val main = Handler(Looper.getMainLooper())
    private val preparing = AtomicBoolean(false)   // watch(): installing mitmproxy, making the certificate
    // What's to be watched while the user is in Settings (the certificate) or Android's VPN consent.
    private var pendingWatch: List<String>? = null
    private var pendingVpn: List<String>? = null

    private fun toast(ctx: Context, text: String) = main.post { Toast.makeText(ctx, text, Toast.LENGTH_LONG).show() }

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

    // ---------------- from "watch" to watching ----------------

    /**
     * Claude's `traffic start`, by way of SessionService: the user decides, every time, and only
     * while Claude is still waiting for the answer ([ASK_MS]); a late yes starts nothing.
     */
    fun askToWatch(activity: Activity, intent: Intent) {
        val apps = intent.getStringArrayExtra(WATCH)?.toList() ?: return
        intent.removeExtra(WATCH)
        val asked = intent.getLongExtra(ASKED, 0)
        fun lapsed() = System.currentTimeMillis() - asked > ASK_MS
        if (TrafficVpn.running || activity.isFinishing) return
        if (lapsed()) {
            toast(activity, "Claude's request to watch network traffic has expired")
            return
        }
        // Only apps that can run: watching a turned-off one (Chrome, on a phone that browses with
        // Chrome Beta) would show nothing at all.
        val pm = activity.packageManager
        val installed = apps.filter { runCatching { pm.getApplicationInfo(it, 0).enabled }.getOrDefault(false) }
        if (apps.isNotEmpty() && installed.isEmpty()) {
            toast(activity, "Claude asked to watch apps that aren't on this phone or are turned off: ${apps.joinToString()}")
            TrafficVpn.answer(activity, "failed: none of those apps is on this phone and turned on (phone apps lists them)")
            return
        }
        val what = if (installed.isEmpty()) "every app" else installed.joinToString {
            pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString()
        }
        AlertDialog.Builder(activity)
            .setTitle("Watch network traffic?")
            .setMessage("Claude wants to see what $what on this phone sends and receives over the network, " +
                "until you stop it (Stop in its notification). Passwords, tokens and cookies stay hidden from Claude.")
            .setPositiveButton("Watch") { _, _ ->
                if (lapsed()) toast(activity, "Claude's request to watch network traffic has expired")
                else {
                    TrafficVpn.answer(activity, "accepted")
                    watch(activity, installed)
                }
            }
            .setNegativeButton("Don't") { _, _ -> TrafficVpn.answer(activity, "declined") }
            .setOnCancelListener { TrafficVpn.answer(activity, "declined") }
            .show()
    }

    /**
     * ⋮ → Watch network traffic, or the user's yes to Claude: mitmproxy if it's missing (turning
     * Network Monitor on installed it; Reinstall sandbox takes it away), then the certificate
     * ([trust]), then Android's VPN consent ([consent]); [TrafficVpn] does the rest.
     */
    fun watch(activity: Activity, apps: List<String>) {
        if (MainActivity.settingUp || !preparing.compareAndSet(false, true)) return
        val app = activity.applicationContext
        val sandbox = Sandbox(app)
        thread {
            try {
                if (!hasMitmproxy(sandbox)) {
                    toast(app, "Getting mitmproxy to watch traffic with (once, about a minute)…")
                    installMitmproxy(sandbox) { Log.i(TAG, it) }
                }
                ensureCa(sandbox) { Log.i(TAG, it) }
                main.post { MainActivity.foreground?.let { trust(it, apps) } }
            } catch (e: Exception) {
                Log.e(TAG, "can't watch traffic", e)
                toast(app, "Couldn't set up watching: ${e.message}")
                TrafficVpn.answer(app, "failed: ${e.message}")
            } finally {
                preparing.set(false)
            }
        }
    }

    /**
     * HTTPS is readable only once the user trusts Cinder Lite's certificate in Settings: since
     * Android 11 no app can install one or open that screen, so the certificate waits in Downloads.
     */
    private fun trust(activity: Activity, apps: List<String>) {
        val cert = caCert(Sandbox(activity.applicationContext))
        if (TrafficVpn.trusted(cert)) return consent(activity, apps, decrypt = true)
        runCatching { TrafficVpn.saveToDownloads(activity, cert) }
            .onFailure { Log.e(TAG, "can't save the certificate", it); return consent(activity, apps, decrypt = false) }
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
                pendingWatch = apps
                runCatching { activity.startActivity(Intent(SETTINGS_SEARCH)) }
                    .recoverCatching { activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
            }
            .setNeutralButton("Without HTTPS") { _, _ -> consent(activity, apps, decrypt = false) }
            .setNegativeButton("Cancel") { _, _ -> TrafficVpn.answer(activity, "declined") }
            .setOnCancelListener { TrafficVpn.answer(activity, "declined") }
            .show()
    }

    /** Back from Settings, where the user may have trusted the certificate. */
    fun resume(activity: Activity) {
        pendingWatch?.let { pendingWatch = null; trust(activity, it) }
    }

    /** Android's own VPN consent, the first time only; then [TrafficVpn] starts. */
    private fun consent(activity: Activity, apps: List<String>, decrypt: Boolean) {
        val flag = File(Sandbox(activity.applicationContext).tmp, "cinder-traffic/decrypt").apply { parentFile?.mkdirs() }
        if (decrypt) flag.writeText("") else flag.delete()
        val ask = VpnService.prepare(activity) ?: return TrafficVpn.start(activity, apps)
        pendingVpn = apps
        @Suppress("DEPRECATION")
        activity.startActivityForResult(ask, VPN)
    }

    /** Android's answer to [consent]'s ask. */
    fun consented(activity: Activity, granted: Boolean) {
        if (granted) pendingVpn?.let { TrafficVpn.start(activity, it) } else TrafficVpn.answer(activity, "declined")
        pendingVpn = null
    }
}
