package app.cinder

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo

/**
 * How Claude reads and operates the phone screen (`phone ui`, `click`, `type`, `scroll`): the
 * screen's elements as a numbered list, and actions on them by number. It only ever looks at the
 * phone screen's display, and only when a `phone` command asks; it ignores accessibility events.
 */
class PhoneAccessibility : AccessibilityService() {

    /** The elements the last [ui] listed, numbered from 1. */
    private var nodes: List<AccessibilityNodeInfo> = emptyList()

    override fun onServiceConnected() { instance = this }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    /**
     * One line per element worth acting on or reading, indented by nesting:
     * `[7] Button "Network & internet" (40,300,680,380) click`. Bounds are display pixels, the
     * same numbers as in a screenshot and for `phone tap`.
     */
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
        @Volatile var instance: PhoneAccessibility? = null
            private set
    }
}
