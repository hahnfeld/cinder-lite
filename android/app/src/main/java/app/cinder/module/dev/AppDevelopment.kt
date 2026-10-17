package app.cinder.module.dev

import android.content.Context
import app.cinder.Sandbox
import app.cinder.Step
import app.cinder.module.Module
import java.io.File
import kotlin.concurrent.thread

/**
 * App Development: building Android apps and taking them apart, on the phone. Its checklist puts
 * the whole toolchain down: the device's Android libraries (so Termux's bionic tools run in
 * here, see android-bootstrap), Alpine's JDK, Termux's aapt2, d8 and apksigner, Google's android.jar
 * (android-sdk-install), and apktool and jadx (apk-tools-install). The helpers still install
 * whatever has gone missing when they're run.
 */
object AppDevelopment : Module {
    override val id = "dev"
    override val title = "App Development"
    override val blurb = "build Android apps and take them apart, with a JDK, aapt2, d8, apksigner, apktool and jadx"
    override val requests = setOf("stage-request")

    // Measured on the Android 15 emulator: the JDK 262 MB, Termux's tools 57, android.jar 26, jadx 75,
    // apktool 15; the device's libraries 259.
    private const val DOWNLOAD = "About 450 MB of tools in all, once. It takes a while; keep Cinder Lite open."
    private const val TERMUX = "/data/data/com.termux/files/usr"

    // Termux's tools go after Alpine's own dirs, never before. Termux ships bionic coreutils (sort,
    // head, cut, java, …) that need libandroid-selinux.so and the linker treatment; if they shadowed
    // Alpine's musl builds, ordinary commands — even the ones inside these helpers (termux-install
    // uses `sort -u`) — would die with "library not found". So Alpine wins for anything it has, and
    // Termux only supplies what Alpine lacks (aapt2, d8, apksigner).
    override val path = listOf("$TERMUX/bin", "/usr/lib/jvm/default-jvm/bin")

    override val prelude = buildString {
        append("export TERMUX_PREFIX=$TERMUX; ")
        append("export ANDROID_HOME=/root/android-sdk ANDROID_SDK_ROOT=/root/android-sdk; ")
        // A JVM under proot trips over three things by default: it maps a shared class-data
        // archive at a fixed address, writes perf data into /tmp/hsperfdata, and sizes its
        // heap from the device's total RAM rather than what this process may actually get.
        // -Xshare:off      : the CDS archive maps at a fixed address proot won't honour
        // -UsePerfData     : hsperfdata needs working shared memory
        // UseSerialGC      : G1 reserves large regions up front and fails under proot
        // CompressedClassSpaceSize: the default 1 GB reservation is refused
        // -Xmx512m         : the JVM sizes the heap from total RAM, then can't reserve it
        append("export JAVA_TOOL_OPTIONS=\"-XX:-UsePerfData -Xshare:off -XX:+UseSerialGC ")
        append("-XX:CompressedClassSpaceSize=64m -Xms16m -Xmx512m -Djava.io.tmpdir=/tmp\"; ")
        // Alpine's JVM, not Termux's: a musl-native HotSpot that JITs, rather than an Android
        // binary that can't find its loader in here.
        append("export JAVA_HOME=/usr/lib/jvm/default-jvm; ")
    }

    /** Set once [stageFullRuntime] has copied every bionic library (Termux's tools need them all). */
    private fun fullRuntimeMarker(sandbox: Sandbox) = File(sandbox.androidLib64, ".full")

    /** What android-sdk-install puts down, by the file each piece is known by. */
    private fun sdk(sandbox: Sandbox) = listOf(
        File(sandbox.rootfs, "usr/lib/jvm/default-jvm/bin/javac"),
        File(sandbox.rootfs, "$TERMUX/bin/aapt2".removePrefix("/")),
        File(sandbox.rootfs, "$TERMUX/bin/d8".removePrefix("/")),
        File(sandbox.rootfs, "$TERMUX/bin/apksigner".removePrefix("/")),
        File(sandbox.home, "android-sdk/platforms/android-35/android.jar"),
    )

    /** What apk-tools-install puts down. */
    private fun apkTools(sandbox: Sandbox) = listOf(
        File(sandbox.rootfs, "usr/local/lib/apktool.jar"),
        File(sandbox.rootfs, "usr/local/jadx/bin/jadx"),
    )

    private fun pieces(sandbox: Sandbox) = listOf(fullRuntimeMarker(sandbox)) + sdk(sandbox) + apkTools(sandbox)

    override fun steps(ctx: Context, sandbox: Sandbox) = listOf(
        Step.work("Copy the phone's Android libraries", "About 260 MB, copied from the phone itself: Termux's tools need them.",
            done = { fullRuntimeMarker(sandbox).exists() }) { stageFullRuntime(sandbox) },
        Step.work("Install the JDK, aapt2, d8, apksigner and android.jar", DOWNLOAD, download = true,
            done = { sdk(sandbox).all { it.exists() } }) { sandbox.run("android-sdk-install", log = it) },
        Step.work("Install apktool and jadx", DOWNLOAD, download = true, done = { apkTools(sandbox).all { it.exists() } }) {
            sandbox.run("apk-tools-install", log = it)
        },
    )

    /**
     * Copies the device's whole bionic library set into the sandbox (the base copies only what nested
     * proot needs). Only the app can read them, so android-bootstrap asks for it ([onRequest]).
     */
    @Synchronized   // android-bootstrap's asks and the install can overlap: copy once
    private fun stageFullRuntime(sandbox: Sandbox) {
        if (fullRuntimeMarker(sandbox).exists()) return
        sandbox.stageAndroidLibs { true }
        fullRuntimeMarker(sandbox).createNewFile()
    }

    /** android-bootstrap's ask for the full set of Android libraries; it waits for stage-done. */
    override fun onRequest(ctx: Context, name: String, text: String) {
        val sandbox = Sandbox(ctx.applicationContext)
        thread {
            runCatching { stageFullRuntime(sandbox) }
            File(sandbox.home, ".cinder/stage-done").writeText("")
        }
    }

    override fun downloads(sandbox: Sandbox) =
        if (pieces(sandbox).any { it.exists() }) "the JDK, the Android tools, apktool, jadx and the copied Android libraries (about 700 MB)" else null

    override fun deleteDownloads(ctx: Context, sandbox: Sandbox, log: (String) -> Unit) {
        val packages = listOf("openjdk17-jdk", "openjdk17-jre-headless", "dpkg", "patchelf")
            .filter { "P:$it" in sandbox.installedPackages }
        if (packages.isNotEmpty()) sandbox.run("/sbin/apk del ${packages.joinToString(" ")}", asRoot = true, log = log)
        log("deleting the Android tools and libraries…")
        sandbox.deleteTree(File(sandbox.rootfs, "data/data/com.termux"))
        sandbox.deleteTree(File(sandbox.home, "android-sdk"))
        sandbox.deleteTree(File(sandbox.rootfs, "usr/local/jadx"))
        File(sandbox.rootfs, "usr/local/lib/apktool.jar").delete()
        sandbox.tmp.listFiles { f -> f.name.startsWith("termux-") }?.forEach { sandbox.deleteTree(it) }
        // Then back to what the base needs: the few libraries nested proot (sudo, apk) uses.
        sandbox.deleteTree(sandbox.androidLib64)
        sandbox.stageAndroidRuntime()
    }
}
