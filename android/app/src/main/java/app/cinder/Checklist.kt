package app.cinder

import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import app.cinder.module.Module
import kotlin.concurrent.thread

/**
 * One row of a [Checklist]. [done] says whether it's done, from the state it leaves on disk or in the
 * system, so it's asked again whenever the list is looked at: a list that was interrupted, failed or
 * is opened again starts at its first row that isn't. A row is either work the app does by itself,
 * off the main thread ([work]; a [download] waits for the list's one Download tap first), or something
 * only the user can do ([button], which [act]s: opens Settings, a prompt, a page). [optional] rows can
 * be skipped. The iOS app's Step is the same.
 */
class Step private constructor(
    val title: String,
    val detail: String?,
    val done: () -> Boolean,
    val work: ((log: (String) -> Unit) -> Unit)?,
    val download: Boolean,
    val button: String?,
    val act: ((Activity) -> Unit)?,
    val optional: Boolean,
) {
    companion object {
        fun work(title: String, detail: String? = null, download: Boolean = false, done: () -> Boolean,
                 work: (log: (String) -> Unit) -> Unit) = Step(title, detail, done, work, download, null, null, false)

        fun ask(title: String, button: String, detail: String? = null, optional: Boolean = false, done: () -> Boolean,
                act: (Activity) -> Unit) = Step(title, detail, done, null, false, button, act, optional)
    }
}

/**
 * Setup ([module] null), or a module being turned on, as a list of [Step]s ticked off in order. Nothing
 * here remembers where it got to: each look ([advance], on return to the app, after a prompt, when
 * work ends) asks every row whether it's done, and the first that isn't either runs (work) or waits
 * with its button (the user's). Lives as long as the process; [MainActivity] shows the current one.
 * The iOS app's Checklist works the same way.
 */
class Checklist(
    val title: String,
    val steps: List<Step>,
    val module: Module? = null,
    val summary: String? = null,
    private val log: (String) -> Unit,
    /** The last line [log] printed, shown under the row that's running. */
    private val progress: () -> String?,
    /** Whether something else (a reset) is changing the sandbox: no work may start meanwhile. */
    private val paused: () -> Boolean,
    private val finished: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val skipped = mutableSetOf<Step>()
    /** The user tapped Download: this list's downloads may run. */
    private var agreed = false
    private var failed: Pair<Step, String>? = null
    @Volatile var running: Step? = null
        private set
    var complete = false
        private set
    /** Redraws whatever shows this list; set by [MainActivity]. */
    var changed: () -> Unit = {}

    private fun next(done: List<Boolean>) = steps.indices.firstOrNull { !done[it] && steps[it] !in skipped }?.let { steps[it] }

    /** Runs the first row that isn't done, if it's the app's to do; otherwise waits. On main. */
    fun advance() {
        if (running != null || paused()) return changed()
        val next = next(steps.map { it.done() })
        if (next == null) {
            if (!complete) { complete = true; finished() }
            return changed()
        }
        complete = false
        if (failed?.first !== next) failed = null
        val work = next.work
        if (work != null && failed == null && (!next.download || agreed)) {
            running = next
            thread {
                val error = runCatching { work(log); check(next.done()) { "it didn't finish (Details says why)" } }.exceptionOrNull()
                if (error != null) Log.e("cinder", "${next.title} failed", error)
                main.post {
                    running = null
                    failed = error?.let { next to (it.message ?: it.toString()) }
                    advance()
                }
            }
        }
        changed()
    }

    /** Draws the list into [into]: a row per step, its state, and the current row's detail and buttons. */
    fun render(activity: Activity, into: LinearLayout, sp: (Float) -> Int) {
        into.removeAllViews()
        val done = steps.map { it.done() }
        val next = next(done)
        steps.forEachIndexed { i, step ->
            val current = step === next
            val error = failed?.takeIf { it.first === step && current }?.second
            val state = when {
                done[i] -> "✓"
                step in skipped -> "–"
                step === running -> null
                error != null -> "✗"
                current -> "→"
                else -> "○"
            }
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, sp(6f), 0, sp(6f))
            }
            val mark: View = if (state == null) ProgressBar(activity, null, android.R.attr.progressBarStyleSmall)
            else TextView(activity).apply {
                text = state
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(when (state) { "✓" -> GREEN; "✗" -> RED; "→" -> ORANGE; else -> Color.GRAY })
            }
            row.addView(mark, LinearLayout.LayoutParams(sp(28f), sp(24f)))
            val text = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            text.addView(TextView(activity).apply {
                this.text = step.title
                textSize = 15f
                setTextColor(if (done[i] || current) Color.WHITE else Color.GRAY)
            })
            val detail = when {
                !current -> null
                error != null -> error
                state == null -> progress() ?: step.detail
                else -> step.detail
            }
            if (detail != null) text.addView(TextView(activity).apply {
                this.text = detail
                textSize = 13f
                setTextColor(Color.LTGRAY)
                setPadding(0, sp(2f), 0, 0)
            })
            if (current && running == null) {
                val buttons = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                fun button(label: String, onClick: () -> Unit) = buttons.addView(Button(activity).apply {
                    this.text = label
                    isAllCaps = false
                    setOnClickListener { onClick(); advance() }
                })
                when {
                    error != null -> button("Retry") { failed = null }
                    step.download && !agreed -> button("Download") { agreed = true }
                    step.act != null -> button(step.button!!) {
                        runCatching { step.act.invoke(activity) }.onFailure { failed = step to (it.message ?: it.toString()) }
                    }
                }
                if (step.optional) button("Skip") { skipped += step }
                if (buttons.childCount > 0) text.addView(buttons)
            }
            row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
            into.addView(row)
        }
        if (complete && module != null) into.addView(TextView(activity).apply {
            text = listOfNotNull(summary, RESTART).joinToString("\n\n")
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, sp(12f), 0, 0)
        })
    }

    companion object {
        val ORANGE: Int = Color.rgb(0xE0, 0x7A, 0x3A)
        private val GREEN = Color.rgb(0x7C, 0xB3, 0x42)
        private val RED = Color.rgb(0xE5, 0x53, 0x4B)
        /** Under every finished module's list; the iOS app says the same. */
        const val RESTART = "Claude learns about it when it next starts: ⋮ → Restart Claude (claude --continue " +
            "picks the conversation back up)."
    }
}
