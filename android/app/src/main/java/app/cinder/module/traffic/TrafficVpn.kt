package app.cinder.module.traffic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.widget.Toast
import app.cinder.MainActivity
import app.cinder.Sandbox
import hev.htproxy.TProxyService
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import kotlin.concurrent.thread

/**
 * A capture of the phone's network traffic: Claude's (or the shell's) `traffic start`, while Network
 * Monitor is on. Turning the module on got Android's VPN consent; Android shows a key icon while
 * this runs, and it adds a notification with Stop.
 *
 * Android's VPN hands this app the packets of the apps being watched. hev-socks5-tunnel (an in-app
 * library) turns them back into connections and hands those to mitmproxy's SOCKS5 port in the
 * sandbox, which records them under /tmp/cinder-traffic for the `traffic` helper (see
 * assets/modules/traffic/libexec/cinder-traffic.py). This app's own traffic — the sandbox's, and so mitmproxy's way out —
 * stays outside the VPN, or it would loop.
 *
 * DNS is answered inside the tunnel (hev's mapdns), so mitmproxy sees hostnames, not addresses. Other
 * UDP isn't relayed (mitmproxy's SOCKS5 is TCP only): QUIC falls back to TCP, calls and games fail.
 * With nothing behind the tunnel the phone would be offline, so mitmproxy exiting stops it all.
 */
class TrafficVpn : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var proxy: Process? = null
    @Volatile private var stopping = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        running = true
        stopping = false
        val apps = intent?.getStringArrayExtra(EXTRA_APPS).orEmpty().toList()
        startForeground(ID, build("Starting…"))
        thread {
            try {
                start(apps)
            } catch (e: Exception) {
                Log.e(TAG, "traffic watching failed", e)
                failed("Couldn't watch network traffic: ${e.message}")
            }
        }
        return START_NOT_STICKY
    }

    /** It stopped on its own: the user hears why, and so does a `traffic start` still waiting. */
    private fun failed(why: String) {
        shutdown()
        answer(this, "failed: $why")
        say(why)
    }

    private fun start(apps: List<String>) {
        val sandbox = Sandbox(applicationContext)
        val dir = File(sandbox.tmp, "cinder-traffic").apply { mkdirs() }
        // From here `traffic stop` has something to stop, and `traffic status` says so.
        File(dir, "state").writeText("starting…\n")
        val ready = File(dir, "ready").apply { delete() }
        // cinder-traffic.py decrypts HTTPS only when the user trusts the certificate (it's passed through otherwise).
        val decrypt = File(dir, "decrypt")
        if (trusted(NetworkMonitor.caCert(sandbox))) decrypt.writeText("") else decrypt.delete()
        // Whatever listens there gets every app's traffic: never something else that got there first.
        check(!listening()) { "another app is using port $PORT" }
        // A fresh login each run, so no other app on the phone can use mitmproxy's port as a proxy.
        val login = ByteArray(12).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        val p = sandbox.spawn(
            "echo \$\$ > /tmp/cinder-traffic/pid; exec mitmdump -q --mode socks5@127.0.0.1:$PORT " +
                "--set proxyauth=cinder:$login --set stream_large_bodies=1m -s /usr/local/libexec/cinder-traffic.py " +
                ">> /tmp/cinder-traffic/mitmproxy.log 2>&1"
        )
        proxy = p
        thread {
            p.waitFor()
            if (!stopping) failed("Stopped watching network traffic: mitmproxy exited (see /tmp/cinder-traffic/mitmproxy.log)")
        }
        waitForReady(p, ready)
        establish(apps, login, https = decrypt.exists(), dir)
    }

    /**
     * The VPN and the tunnel into mitmproxy, under the lock [shutdown] takes: a stop that came first
     * means nothing starts, and one that comes later finds [tun] to close. So the VPN is never up
     * without mitmproxy behind it, which would leave the phone offline.
     */
    @Synchronized private fun establish(apps: List<String>, login: String, https: Boolean, dir: File) {
        if (stopping) return
        val builder = Builder()
            .setSession("Cinder Lite: watching network traffic")
            .setMtu(MTU)
            .addAddress("198.18.0.1", 32).addAddress("fc00::1", 128)
            .addRoute("0.0.0.0", 0).addRoute("::", 0)
            .addDnsServer(DNS)
            .setConfigureIntent(openApp())
        if (apps.isEmpty()) builder.addDisallowedApplication(packageName)
        else apps.forEach { builder.addAllowedApplication(it) }
        val fd = builder.establish() ?: error("Android withdrew the VPN permission")
        tun = fd

        val config = File(filesDir, "traffic.yml")
        config.writeText(
            """
            tunnel:
              mtu: $MTU
              ipv4: 198.18.0.1
              ipv6: 'fc00::1'
            socks5:
              address: 127.0.0.1
              port: $PORT
              udp: 'udp'
              username: cinder
              password: $login
            mapdns:
              address: $DNS
              port: 53
              network: 100.64.0.0
              netmask: 255.192.0.0
              cache-size: 10000
            misc:
              log-level: warn
            """.trimIndent() + "\n"
        )
        check(TProxyService.TProxyStartService(config.absolutePath, fd.fd)) { "the tunnel didn't start" }

        val readable = if (https) "HTTPS readable" else "hostnames only, HTTPS not readable"
        val what = if (apps.isEmpty()) "all apps" else apps.joinToString(", ") { label(it) }
        File(dir, "state").writeText("watching $what · $readable\n")
        getSystemService(NotificationManager::class.java).notify(ID, build("${what.replaceFirstChar(Char::uppercase)} · $readable"))
    }

    /**
     * mitmproxy takes a few seconds to start under proot; the VPN only goes up once it listens. It says
     * so itself ([ready], from cinder-traffic.py once its port is bound): a probe of the port could be
     * answered by another app that bound it first.
     */
    private fun waitForReady(p: Process, ready: File) {
        val until = System.currentTimeMillis() + 60_000
        while (!stopping) {
            if (ready.exists()) return
            check(p.isAlive) { "mitmproxy didn't start (see /tmp/cinder-traffic/mitmproxy.log)" }
            check(System.currentTimeMillis() < until) { "mitmproxy took too long to start" }
            Thread.sleep(300)
        }
    }

    private fun listening() = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", PORT), 500) } }.isSuccess

    /** Every way out comes here: `traffic stop`, the notification, ⋮ → Stop, the module off, Android, mitmproxy. */
    @Synchronized private fun shutdown() {
        // Stopped before it was up: a `traffic start` still waiting hears so now, not after its timeout.
        if (running && tun == null) answer(this, "failed: stopped before it started")
        stopping = true
        if (tun != null) TProxyService.TProxyStopService()
        runCatching { tun?.close() }
        tun = null
        val dir = File(Sandbox(applicationContext).tmp, "cinder-traffic")
        // mitmdump runs under proot, so the pid it wrote is the real one.
        runCatching { File(dir, "pid").readText().trim().toInt() }.getOrNull()?.let { runCatching { Os.kill(it, OsConstants.SIGTERM) } }
        proxy?.destroy()
        proxy = null
        File(dir, "pid").delete()
        File(dir, "state").delete()
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() = shutdown()   // the user turned the VPN off in Settings, or another VPN took over

    private fun label(pkg: String) =
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    override fun onDestroy() {
        if (running) shutdown()
        super.onDestroy()
    }

    private fun say(text: String) = Handler(Looper.getMainLooper()).post { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }

    private fun openApp() =
        PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun build(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Watching network traffic", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TrafficVpn::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Watching network traffic")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(openApp())
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "cinder"
        private const val CHANNEL = "cinder-traffic"
        private const val ID = 9004
        private const val ACTION_STOP = "app.cinder.STOP_TRAFFIC"
        private const val EXTRA_APPS = "apps"
        private const val PORT = 10800
        private const val MTU = 8500
        private const val DNS = "198.18.0.2"
        const val CERT_NAME = "Cinder Lite certificate.crt"

        /** Whether it's watching (or starting to). */
        @Volatile var running = false
            private set

        /** Starts watching [apps] (package names), or every app but this one. Needs VPN consent first. */
        fun start(ctx: Context, apps: List<String>) {
            ctx.startForegroundService(Intent(ctx, TrafficVpn::class.java).putExtra(EXTRA_APPS, apps.toTypedArray()))
        }

        fun stop(ctx: Context) {
            if (running) ctx.startService(Intent(ctx, TrafficVpn::class.java).setAction(ACTION_STOP))
        }

        /** How a `traffic start` went, for the helper waiting on it: "started" isn't said, "failed: why" is. */
        fun answer(ctx: Context, text: String) {
            runCatching { File(Sandbox(ctx).tmp, "cinder-traffic").apply { mkdirs() }.resolve("answer").writeText("$text\n") }
        }

        /** A process that was killed while watching never cleaned up; its VPN went with it. */
        fun forgetStale(ctx: Context) {
            if (!running) File(Sandbox(ctx).tmp, "cinder-traffic/state").delete()
        }

        /** Whether the user has trusted [cert] in Settings (it's then among the user's CA certificates). */
        fun trusted(cert: File): Boolean = runCatching {
            val ours = cert.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            store.aliases().toList().any { it.startsWith("user:") && store.getCertificate(it) == ours }
        }.getOrDefault(false)

        /**
         * Puts [cert] in Downloads as [CERT_NAME], for Settings' CA certificate installer to pick:
         * since Android 11 no app may install one, or even open that installer.
         */
        fun saveToDownloads(ctx: Context, cert: File) {
            val downloads = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val resolver = ctx.contentResolver
            runCatching { resolver.delete(downloads, "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(CERT_NAME)) }
            val uri = resolver.insert(downloads, ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, CERT_NAME)
                put(MediaStore.Downloads.MIME_TYPE, "application/x-x509-ca-cert")
            }) ?: error("couldn't save the certificate to Downloads")
            resolver.openOutputStream(uri)!!.use { out -> cert.inputStream().use { it.copyTo(out) } }
        }
    }
}
