package app.cinder.module.phone

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
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

    override fun nextStep(ctx: Context) = when {
        !ShizukuBridge.isInstalled(ctx) -> "It works through the Shizuku app, which isn't installed: get it from " +
            "Google Play or github.com/RikkaApps/Shizuku, start it, then come back to Cinder Lite."
        !ShizukuBridge.isRunning() -> "Start Shizuku in its app, then come back to Cinder Lite."
        else -> null
    }

    /** Shizuku's permission, then the accessibility service: the two things only the user can grant. */
    override fun turnedOn(activity: Activity) {
        val app = activity.applicationContext
        if (!ShizukuBridge.isRunning() || ShizukuBridge.hasPermission()) return askPhoneAccess(activity)
        ShizukuBridge.requestPermission { granted ->
            activity.runOnUiThread {
                if (!granted) Toast.makeText(app, "Shizuku permission denied. rish asks again when Claude uses it",
                    Toast.LENGTH_LONG).show()
                askPhoneAccess(activity)
            }
        }
    }

    override fun start(ctx: Context) = ShizukuBridge.start(ctx)

    override fun stop(ctx: Context) { PhoneScreenActivity.current?.close() }

    override fun turnedOff(ctx: Context) = ShizukuBridge.stop(ctx)

    /**
     * Asks the user to turn on the accessibility service that lets Claude read and tap the phone
     * screen (`phone` in the sandbox), unless it's on already. Android won't let an app turn it on.
     */
    private fun askPhoneAccess(activity: Activity) {
        if (PhoneAccessibility.instance != null || activity.isDestroyed) return
        AlertDialog.Builder(activity)
            .setTitle("Phone screen access")
            .setMessage("Claude uses apps on a separate phone screen, in a small floating window, while you keep " +
                "using yours. To read and tap what's on it, Claude needs Cinder Lite's accessibility service.\n\n" +
                "On the next screen, turn on \"Cinder Lite phone screen\". If Android says it's a restricted " +
                "setting, tap App info here instead, then ⋮ → Allow restricted settings, and turn it on in " +
                "Settings → Accessibility.")
            .setPositiveButton("Open settings") { _, _ -> PhoneAccessibility.openSettings(activity) }
            .setNeutralButton("App info") { _, _ -> PhoneAccessibility.openAppInfo(activity) }
            .setNegativeButton("Not now", null)
            .show()
    }
}
