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
    override val download = "about 90 MB (mitmproxy, apk-mitm and a Java runtime)"
    override val requests = setOf("traffic-request")

    // apk-mitm runs apktool and uber-apk-signer, both Java, under proot. A JVM there needs the same
    // coaxing App Development gives it (no CDS, no perf data, serial GC, a bounded heap); kept in
    // step with that module's prelude. These apply only while Network Monitor is on, and if App
    // Development is on too, its identical lines simply come later and win.
    override val path = listOf("/usr/lib/jvm/default-jvm/bin")
    override val prelude =
        "export JAVA_HOME=/usr/lib/jvm/default-jvm; " +
        "export JAVA_TOOL_OPTIONS=\"-XX:-UsePerfData -Xshare:off -XX:+UseSerialGC " +
        "-XX:CompressedClassSpaceSize=64m -Xms16m -Xmx512m -Djava.io.tmpdir=/tmp\"; "

    private val PACKAGE = Regex("[A-Za-z]\\w*(\\.[A-Za-z_]\\w*)+")

    override fun nextStep(ctx: Context) = "Claude captures traffic with traffic start and traffic stop (so can you, in " +
        "the Shell). While a capture runs, Android shows a key icon and calls and other UDP traffic don't get through."

    override fun isInstalled(sandbox: Sandbox) =
        TrafficFlow.hasMitmproxy(sandbox) && TrafficFlow.caCert(sandbox).exists() && apkMitmInstalled(sandbox)

    override fun install(ctx: Context, sandbox: Sandbox, log: (String) -> Unit) {
        if (!TrafficFlow.hasMitmproxy(sandbox)) TrafficFlow.installMitmproxy(sandbox, log)
        log("making Cinder Lite's certificate…")
        TrafficFlow.ensureCa(sandbox, log)
        installApkMitm(sandbox, log)
    }

    // ---- apk-mitm: patches an APK to accept user certificates, so its HTTPS can be read ----
    // https://github.com/shroudedcode/apk-mitm — an npm CLI that rewrites an app's network-security
    // config and strips common certificate pinning, then re-signs it with uber-apk-signer.

    private fun apkMitmBin(sandbox: Sandbox) = File(sandbox.home, ".npm-global/bin/apk-mitm")
    private fun hasJava(sandbox: Sandbox) =
        sandbox.installedPackages.any { it == "P:openjdk17-jre-headless" || it == "P:openjdk17-jdk" }
    private fun apkMitmInstalled(sandbox: Sandbox) = apkMitmBin(sandbox).exists() && hasJava(sandbox)

    private fun installApkMitm(sandbox: Sandbox, log: (String) -> Unit) {
        if (!hasJava(sandbox)) {
            log("installing a Java runtime for apk-mitm…")
            sandbox.run("/sbin/apk add --no-cache openjdk17-jre-headless", asRoot = true, log = log)
        }
        log("installing apk-mitm…")
        sandbox.run("npm install -g apk-mitm", log = log)
        check(apkMitmBin(sandbox).exists()) { "apk-mitm didn't install (see above)" }
        // apktool and uber-apk-signer download themselves the first time apk-mitm runs.
    }

    override fun turnedOn(activity: Activity) = TrafficFlow.turnOn(activity)

    override fun downloads(sandbox: Sandbox) =
        if (TrafficFlow.hasMitmproxy(sandbox) || File(sandbox.home, ".mitmproxy").exists() || apkMitmBin(sandbox).exists())
            "mitmproxy, apk-mitm and their downloads (about 90 MB), the certificate and what it captured" else null

    override fun deleteDownloads(ctx: Context, sandbox: Sandbox, log: (String) -> Unit) {
        if (TrafficFlow.hasMitmproxy(sandbox)) sandbox.run("/sbin/apk del mitmproxy", asRoot = true, log = log)
        if (apkMitmBin(sandbox).exists()) {
            log("removing apk-mitm…")
            sandbox.run("npm uninstall -g apk-mitm", log = log)
        }
        sandbox.deleteTree(File(sandbox.home, ".cache/apk-mitm"))   // its apktool/uber-apk-signer downloads
        // The Java runtime apk-mitm needed, unless App Development still wants it.
        if ("P:openjdk17-jre-headless" in sandbox.installedPackages && "P:openjdk17-jdk" !in sandbox.installedPackages)
            sandbox.run("/sbin/apk del openjdk17-jre-headless", asRoot = true, log = log)
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
