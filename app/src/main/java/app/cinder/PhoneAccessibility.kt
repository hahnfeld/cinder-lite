package app.cinder

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo

/**
 * How Claude reads and operates the phone screen (`phone ui`, `click`, `type`, `scroll`): the
 * screen's elements as a numbered list, and actions on them by number. It only ever looks at the
 * phone screen's display, and only when a `phone` command asks; it listens to accessibility events
 * only while waiting for the phone screen to settle after an action. Each `phone` command runs on its
 * own thread, so [ui] and [after] are synchronized; [click], [type] and [scroll] only run inside
 * [after], under the same lock.
 */
class PhoneAccessibility : AccessibilityService() {

    /** The elements the last [ui] listed, numbered from 1. */
    private var nodes: List<AccessibilityNodeInfo> = emptyList()

    override fun onServiceConnected() { instance = this }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    /** The display [after] is waiting on, and when its screen last changed. */
    @Volatile private var watched = -1
    @Volatile private var changed = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Before Android 13 events don't carry their display, so any change counts.
        if (event != null && (Build.VERSION.SDK_INT < 33 || event.displayId == watched)) changed = SystemClock.uptimeMillis()
    }

    /**
     * Runs [action], waits for the display's screen to settle, and lists it as [ui] does, so each
     * action shows Claude its result. Settled is [quiet] ms without a change, then the same list
     * twice running (screens slide in without reporting changes); or [longest] ms at most: spinners
     * and animations never stop, and the list is useful mid-way.
     */
    @Synchronized
    fun after(displayId: Int, quiet: Long, longest: Long, action: () -> Unit): String {
        val idle = serviceInfo   // a copy: no events, as in res/xml/phone_accessibility.xml
        watched = displayId
        serviceInfo = serviceInfo.apply { eventTypes = AccessibilityEvent.TYPES_ALL_MASK }
        try {
            action()
            val start = SystemClock.uptimeMillis()   // the wait counts from the end of the action
            changed = start
            var shown: String? = null
            while (SystemClock.uptimeMillis() - start < longest) {
                if (SystemClock.uptimeMillis() - changed < quiet) { Thread.sleep(50); continue }
                val list = ui(displayId)
                if (list == shown) return list
                shown = list
                Thread.sleep(100)
            }
        } finally {
            watched = -1
            serviceInfo = idle
        }
        return ui(displayId)
    }

    override fun onInterrupt() {}

    /**
     * One line per element worth acting on or reading, indented by nesting:
     * `[7] Button "Network & internet" (40,300,680,380) click`. Bounds are display pixels, the
     * same numbers as in a screenshot and for `phone tap`.
     */
    @Synchronized
    @Suppress("DEPRECATION")   // isChecked: still the only form before Android 16
    fun ui(displayId: Int): String {
        val found = mutableListOf<AccessibilityNodeInfo>()
        val out = StringBuilder()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (!node.isVisibleToUser) return
            val label = listOfNotNull(node.text, node.contentDescription, node.hintText)
                .map { it.toString().replace('\n', ' ').trim().take(120) }.filter { it.isNotEmpty() }
                .distinct().joinToString(" | ")
            val flags = buildList {
                if (node.isClickable) add("click")
                if (node.isLongClickable) add("long")
                if (node.isEditable) add("edit")
                if (node.isScrollable) add("scroll")
                if (node.isCheckable) add(if (node.isChecked) "checked" else "unchecked")
                if (node.isSelected) add("selected")
                if (node.isFocused) add("focused")
                if (!node.isEnabled) add("disabled")
            }
            var childDepth = depth
            if (label.isNotEmpty() || node.isClickable || node.isEditable || node.isScrollable || node.isCheckable) {
                found += node
                childDepth++
                val r = Rect().also(node::getBoundsInScreen)
                out.append("  ".repeat(depth)).append("[${found.size}] ").append(node.className?.toString()?.substringAfterLast('.'))
                if (label.isNotEmpty()) out.append(" \"").append(label).append('"')
                else node.viewIdResourceName?.let { out.append(" #").append(it.substringAfter(":id/")) }
                out.append(" (${r.left},${r.top},${r.right},${r.bottom})")
                if (flags.isNotEmpty()) out.append(' ').append(flags.joinToString(" "))
                out.append('\n')
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, childDepth) }
        }
        for (window in windowsOnAllDisplays[displayId].orEmpty()) {
            if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            val root = window.root ?: continue
            out.append("== ").append(window.title ?: "").append(" (").append(root.packageName).append(")\n")
            visit(root, 0)
        }
        nodes = found
        return out.ifEmpty { "(nothing on the phone screen yet)\n" }.toString()
    }

    /** Clicks element [n], or the nearest clickable element around it (a row around its label). */
    fun click(n: Int) {
        var node: AccessibilityNodeInfo? = node(n)
        while (node != null && !node.isClickable) node = node.parent
        if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) != true) {
            val r = Rect().also(node(n)::getBoundsInScreen)
            error("element $n can't be clicked. Try: phone tap ${r.centerX()} ${r.centerY()}")
        }
    }

    /** Replaces the text of field [n] directly, without a keyboard. */
    fun type(n: Int, text: String) {
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        check(node(n).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            "element $n isn't a text field. Click it, then try: phone text '…'"
        }
    }

    /** Scrolls element [n], or the nearest scrollable element around it. */
    fun scroll(n: Int, direction: String) {
        var node: AccessibilityNodeInfo? = node(n)
        while (node != null && !node.isScrollable) node = node.parent
        node ?: error("element $n isn't in a scrollable area. Try: phone swipe")
        val action = when (direction) {
            "up" -> AccessibilityAction.ACTION_SCROLL_UP
            "down" -> AccessibilityAction.ACTION_SCROLL_DOWN
            "left" -> AccessibilityAction.ACTION_SCROLL_LEFT
            "right" -> AccessibilityAction.ACTION_SCROLL_RIGHT
            else -> error("scroll direction must be up, down, left or right")
        }
        // Lists that only offer forward/backward: down and right are forward.
        val fallback = if (direction == "up" || direction == "left") AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        check(node.performAction(action.id) || node.performAction(fallback)) { "element $n didn't scroll $direction" }
    }

    private fun node(n: Int): AccessibilityNodeInfo {
        val node = nodes.getOrNull(n - 1) ?: error("no element $n. Run phone ui first")
        check(node.refresh()) { "element $n is gone; the screen has changed. Run phone ui again" }
        return node
    }

    companion object {
        /** The running service: on in Accessibility settings, and allowed to run. */
        @Volatile var instance: PhoneAccessibility? = null
            private set

        /** Opens this service's page in Accessibility settings, where the user turns it on. */
        fun openSettings(ctx: Context) {
            val component = ComponentName(ctx, PhoneAccessibility::class.java).flattenToString()
            val intent = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(intent) }.getOrElse {
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }

        /** App info, where Android 13+ hides "Allow restricted settings" for sideloaded apps. */
        fun openAppInfo(ctx: Context) = ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", ctx.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
