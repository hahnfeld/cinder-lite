package app.cinder.module.phone

import android.content.Context
import app.cinder.MainActivity
import app.cinder.Sandbox
import app.cinder.Step
import app.cinder.module.Module

/**
 * Phone Use: adb-shell access for the sandbox (`rish`, `adb shell`) through the Shizuku app, and the
 * phone screen Claude operates apps on (`phone`). Nothing to download: its helpers run on the base
 * Python, and Shizuku is the user's own app. Its checkbox is the only switch: on, the sandbox has
 * Shizuku's adb-shell power and the phone screen; off, neither.
 */
object PhoneUse : Module {
    override val id = "phone"
    override val title = "Phone Use"
    override val blurb = "use apps on the phone, and adb-shell commands through Shizuku"
    override val components = listOf(PhoneScreenActivity::class.java, PhoneAccessibility::class.java)

    /** Shizuku, its permission, then the accessibility service: the things only the user can do. */
    override fun steps(ctx: Context, sandbox: Sandbox) = listOf(
        Step.ask("Install Shizuku", "Open download page", "Phone Use reaches the phone through Shizuku, a free app.",
            done = { ShizukuBridge.isInstalled(ctx) }) { ShizukuBridge.open(it) },
        Step.ask("Start Shizuku", "Open Shizuku", "Start it in its app (it walks you through Wireless debugging), " +
            "then come back here. Starting it also replaces a Shizuku left running from before a reinstall.",
            done = { ShizukuBridge.isInstalled(ctx) && ShizukuBridge.isRunning() }) { ShizukuBridge.open(it) },
        Step.ask("Allow Cinder Lite in Shizuku", "Allow", "If Shizuku closes without asking, tap Allow again: it " +
            "opens Shizuku, to start it again. If you told Shizuku not to ask again, allow Cinder Lite there.",
            done = { ShizukuBridge.hasPermission() }) {
            if (ShizukuBridge.unanswered()) {
                ShizukuBridge.open(it)
                error("Shizuku didn't ask. In Shizuku, start it again (stop it first if it says it's running), then come back.")
            }
            if (ShizukuBridge.askable()) ShizukuBridge.requestPermission { MainActivity.recheck() } else ShizukuBridge.open(it)
        },
        Step.ask("Turn on phone screen access", "Open Settings", "Claude uses apps on a separate phone screen, in a " +
            "small floating window, while you keep using yours. Turn on \"Cinder Lite phone screen\". If Android says " +
            "it's a restricted setting: Settings → Apps → Cinder Lite → ⋮ → Allow restricted settings, then try again.",
            done = { PhoneAccessibility.instance != null }) { PhoneAccessibility.openSettings(it) },
    )

    override fun start(ctx: Context) = ShizukuBridge.start(ctx)

    override fun stop(ctx: Context) { PhoneScreenActivity.current?.close() }

    override fun turnedOff(ctx: Context) = ShizukuBridge.stop(ctx)
}
