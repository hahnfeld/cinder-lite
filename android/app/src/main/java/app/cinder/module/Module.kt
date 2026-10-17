package app.cinder.module

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import app.cinder.Sandbox
import app.cinder.prefs
import app.cinder.module.dev.AppDevelopment
import app.cinder.module.phone.PhoneUse
import app.cinder.module.traffic.NetworkMonitor

/**
 * An optional part of Cinder Lite, off until the user turns it on in ⋮ → Modules. The base app runs
 * Claude Code; a module adds one kind of reach on top: Phone Use, Network Monitor, App Development.
 *
 * Each one is a package under app/cinder/module/ plus a folder under assets/modules/<id>/:
 *   bin/        helpers, in /usr/local/bin (on PATH) only while it's on
 *   libexec/    other files it needs in the sandbox (/usr/local/libexec), likewise
 *   brief.md    what Claude is told about it, added to Claude's brief while it's on
 * The base never names a module's classes: it goes through [Modules], and a module plugs in by
 * overriding what it needs here. The iOS app's CinderModule protocol has the same shape.
 */
interface Module {
    /** Its folder under assets/modules/, and how settings remember it. */
    val id: String
    /** Its name in ⋮ → Modules. */
    val title: String
    /** What it lets Claude do, in a few words, for Claude's brief while it's off. */
    val blurb: String

    /**
     * Manifest components only it uses, on only while the module is: an accessibility service the
     * user never wanted doesn't sit in Settings, and a VPN service nothing asked for can't start.
     * They're android:enabled="false" in the manifest until then.
     */
    val components: List<Class<*>> get() = emptyList()

    /** Directories it adds to PATH, after Alpine's own and before Android's /system/bin. */
    val path: List<String> get() = emptyList()

    /** Lines (`export …; `) it adds to every session's shell prelude while it's on. */
    val prelude: String get() = ""

    /** Request files in /root/.cinder it answers (see SessionService), only while it's on. */
    val requests: Set<String> get() = emptySet()

    /**
     * One of its [requests], with the file's contents (the file is gone by now). On SessionService's
     * FileObserver thread, which every request shares: hand anything slow to a thread of its own.
     */
    fun onRequest(ctx: Context, name: String, text: String) {}

    // ---- turning it on and off (MainActivity's ⋮ → Modules) ----

    /** About how much its first turn-on downloads, for the confirm dialog; null if it's nothing much. */
    val download: String? get() = null

    /** Whether everything [install] puts down is there; turning it on installs only when it isn't. */
    fun isInstalled(sandbox: Sandbox): Boolean = true

    /**
     * Gets it ready, the first time it's turned on (it's on already, so its helpers are in place).
     * Off the main thread; logs to the setup pane; throws if it couldn't, and the module goes back off.
     */
    fun install(ctx: Context, sandbox: Sandbox, log: (String) -> Unit) {}

    /** Says what turning it off can also delete, and about how much; null if nothing. */
    fun downloads(sandbox: Sandbox): String? = null

    /** Deletes what [downloads] describes. Off the main thread. */
    fun deleteDownloads(ctx: Context, sandbox: Sandbox, log: (String) -> Unit) {}

    /** What the user still has to do outside Cinder Lite once it's on, for the "it's on" dialog; null if nothing. */
    fun nextStep(ctx: Context): String? = null

    /**
     * It's just been turned on (and installed): asks the user for whatever Android needs their say-so
     * for (permissions, consents), so everything it does works from now on. After the "it's on" dialog.
     */
    fun turnedOn(activity: Activity) {}

    /** Ends what it's running and any switch of its own (Shizuku): it's being turned off. */
    fun turnedOff(ctx: Context) = stop(ctx)

    // ---- while it's on ----

    /** SessionService has started, or it was just turned on: anything that should run while sessions do. */
    fun start(ctx: Context) {}

    /** ⋮ → Stop: end anything it's running. Asked of every module, on or off. */
    fun stop(ctx: Context) {}

    fun onResume(activity: Activity) {}

    /** Its own startActivityForResult answers; true if [requestCode] was one of its. Codes 100+ are modules'. */
    fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int): Boolean = false
}

/** The modules, which are on, and switching them. */
object Modules {
    /** In the order ⋮ → Modules lists them. */
    val all: List<Module> = listOf(PhoneUse, NetworkMonitor, AppDevelopment)

    private const val KEY = "modules"

    fun enabled(ctx: Context): List<Module> {
        val ids = prefs(ctx).getStringSet(KEY, emptySet()).orEmpty()
        return all.filter { it.id in ids }
    }

    fun isOn(ctx: Context, module: Module) = module in enabled(ctx)

    /** The module that answers request file [name], if one is on. */
    fun answering(ctx: Context, name: String) = enabled(ctx).find { name in it.requests }

    /** Every module's request file names, on or off: a request for one that's off is dropped. */
    val requests get() = all.flatMap { it.requests }.toSet()

    /** Turns [module] on or off: the setting, its components, then [Module.start] or [Module.turnedOff]. Its files follow at the next session start. */
    fun set(ctx: Context, module: Module, on: Boolean) {
        val ids = prefs(ctx).getStringSet(KEY, emptySet()).orEmpty().toMutableSet()
        if (on) ids += module.id else ids -= module.id
        prefs(ctx).edit().putStringSet(KEY, ids).commit()
        if (on) {
            components(ctx, module, true)
            runCatching { module.start(ctx) }
        } else {
            runCatching { module.turnedOff(ctx) }
            components(ctx, module, false)
        }
    }

    private fun components(ctx: Context, module: Module, on: Boolean) {
        val pm = ctx.packageManager
        // Always an explicit state, whatever the manifest says. DONT_KILL_APP, or Android kills this
        // process (and Claude with it) to apply the change.
        val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        for (c in module.components) {
            val name = ComponentName(ctx, c)
            if (pm.getComponentEnabledSetting(name) != state) pm.setComponentEnabledSetting(name, state, PackageManager.DONT_KILL_APP)
        }
    }
}
