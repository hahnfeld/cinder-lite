package app.cinder.module.phone

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.view.Menu
import android.widget.Toast
import app.cinder.PhoneAccessibility
import app.cinder.Sandbox
import app.cinder.module.Module

/**
 * Phone Use: adb-shell access for the sandbox (`rish`, `adb shell`) through the Shizuku app, and the
 * phone screen Claude operates apps on (`phone`). Nothing to download: its helpers run on the base
 * Python, and Shizuku is the user's own app. Shizuku stays a switch of its own (⋮ → Shizuku), since
 * it hands the sandbox adb-shell power.
 */
object PhoneUse : Module {
    override val id = "phone"
    override val title = "Phone Use"
    override val blurb = "use apps on the phone, and adb-shell commands through Shizuku"
    override val components = listOf(PhoneScreenActivity::class.java, PhoneAccessibility::class.java)
    override val nextStep = "Turn on ⋮ → Shizuku to let Claude use the phone (it needs the Shizuku app running)."

    /** Turned on through Shizuku before modules existed. */
    override fun isInUse(ctx: Context, sandbox: Sandbox) = ShizukuBridge.isEnabled(ctx)

    override fun start(ctx: Context) = ShizukuBridge.start(ctx)   // no-op unless ⋮ → Shizuku is on

    override fun stop(ctx: Context) { PhoneScreenActivity.current?.close() }

    /** Off means no adb-shell either: turning the module back on doesn't quietly bring that back. */
    override fun turnedOff(ctx: Context) {
        stop(ctx)
        ShizukuBridge.setEnabled(ctx, false)
    }

    override fun menu(activity: Activity, menu: Menu) {
        menu.add("Shizuku").setCheckable(true).setChecked(ShizukuBridge.isEnabled(activity))
            .setOnMenuItemClickListener { toggleShizuku(activity); true }
    }

    /**
     * Optional adb-shell access for the sandbox (`rish`, `adb shell`) through the Shizuku app.
     * Off by default; most people won't have Shizuku.
     */
    private fun toggleShizuku(activity: Activity) {
        val app = activity.applicationContext
        fun say(text: String) = Toast.makeText(app, text, Toast.LENGTH_LONG).show()
        fun on() { say("Shizuku on: rish and adb shell work in the sandbox"); askPhoneAccess(activity) }
        when {
            ShizukuBridge.isEnabled(app) -> {
                ShizukuBridge.setEnabled(app, false)
                say("Shizuku off: rish and adb shell are disabled")
            }
            !ShizukuBridge.isInstalled(app) ->
                say("Shizuku isn't installed. Get it from Google Play or github.com/RikkaApps/Shizuku")
            else -> {
                ShizukuBridge.setEnabled(app, true)
                when {
                    !ShizukuBridge.isRunning() -> say("Shizuku on. Start Shizuku in its app, then come back to " +
                        "Cinder Lite")
                    ShizukuBridge.hasPermission() -> on()
                    else -> ShizukuBridge.requestPermission { granted ->
                        activity.runOnUiThread {
                            if (granted) on() else say("Shizuku on, but permission was denied. rish will ask again")
                        }
                    }
                }
            }
        }
    }

    /**
     * Asks the user to turn on the accessibility service that lets Claude read and tap the phone
     * screen (`phone` in the sandbox), unless it's on already. Android won't let an app turn it on.
     */
    private fun askPhoneAccess(activity: Activity) {
        if (PhoneAccessibility.instance != null || activity.isDestroyed) return
        AlertDialog.Builder(activity)
            .setTitle("Phone screen access")
            .setMessage("With Shizuku on, Claude can also use apps on a separate phone screen, in a small " +
                "floating window, while you keep using yours. To read and tap what's on it, Claude needs " +
                "Cinder Lite's accessibility service.\n\nOn the next screen, turn on \"Cinder Lite phone " +
                "screen\". If Android says it's a restricted setting, tap App info here instead, then ⋮ → " +
                "Allow restricted settings, and turn it on in Settings → Accessibility.")
            .setPositiveButton("Open settings") { _, _ -> PhoneAccessibility.openSettings(activity) }
            .setNeutralButton("App info") { _, _ -> PhoneAccessibility.openAppInfo(activity) }
            .setNegativeButton("Not now", null)
            .show()
    }
}
