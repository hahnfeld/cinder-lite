package app.cinder

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Drives the real Claude Code binary inside the sandbox and turns its stream-json output into
 * typed events the UI can render.
 *
 * The CLI is run headless:
 *   claude --print --input-format stream-json --output-format stream-json --verbose
 * which speaks newline-delimited JSON both ways. Authentication is the CLI's own — it logs in
 * with OAuth and stores the credential under the sandbox's HOME, so this app never handles an
 * API key or a token itself.
 */
/**
 * Handed to the CLI with --append-system-prompt. It runs inside Alpine on a phone, which is not
 * what it would otherwise assume.
 */
private val SANDBOX_BRIEF = listOf(
    "You are running on an Android phone inside an Alpine Linux (musl, aarch64) sandbox under proot.",
    "The working directory /workspace is writable and persistent; /root is your home.",
    "System packages: apk add <pkg> / apk del <pkg>. These run real apk elevated via nested proot, " +
        "so full dependency trees install. You'll see a harmless 'database inconsistent' note at the " +
        "end — ignore it, the files are already installed and work.",
    "The full Alpine archive is enabled — stable main+community plus edge main/community/testing — " +
        "so the whole aarch64 package set is installable, including wine: apk add wine works. (wine " +
        "runs Windows PE binaries; on this ARM sandbox that means ARM64 ones, and there's no display, " +
        "so GUIs won't show — but it installs and CLI/headless use is possible.)",
    "npm install -g works: the prefix is /root/.npm-global, already on PATH.",
    "pip install works: it installs to /root/.local (PIP_USER is set), also on PATH. Use python3/pip3.",
    "If a language runtime is missing, install it first: apk add nodejs npm python3 py3-pip openjdk17 go rust.",
    "Android build tools are NOT in Alpine, and Google's SDK/NDK are x86-64 only so they cannot run here.",
    "Use termux-install <pkg> for Android/aarch64 builds of them: termux-install aapt2 d8 apksigner openjdk-17.",
    "To take an APK apart: apktool d <apk> (smali + resources), jadx <apk> (Java source).",
    "baksmali <apk> [out] and smali <dir> [out.apk] are shims onto apktool, which embeds both.",
    "If those are missing, run apk-tools-install once — it installs Alpine's JDK and the jars.",
    "Termux/Android binaries DO run here: their ELF interpreter is /system/bin/linker64 (a symlink " +
        "into /apex that proot can't resolve), so termux-install runs android-bootstrap, which copies " +
        "the real bionic linker plus a flat set of .so files into the rootfs and rewrites each binary's " +
        "interpreter and rpath to use them. If an Android binary ever reports 'not found' for its loader " +
        "or a library, just run: android-bootstrap — it re-copies and re-patches everything.",
    "Those land in /data/data/com.termux/files/usr and are already on PATH. Cross-compilers: termux-install clang binutils make cmake.",
    "To read device/system properties, run getprop by that bare name only (it is a wrapper on PATH " +
        "that returns instantly): getprop lists everything, getprop ro.product.model returns one " +
        "value. Never invoke it by an absolute path such as /system/bin/getprop — that path does NOT " +
        "resolve in this sandbox and will fail. There is no working dumpsys/pm/am/service here either.",
    "To install an APK you built onto the phone, run: install-apk <path-to.apk>. It hands the file " +
        "to Android's package installer, which asks the user to confirm — you can't install silently. " +
        "Run it ONCE per APK and wait; do not call it repeatedly, that just spams install prompts.",
    "To show the user an HTML page (one they uploaded to /workspace, or one you wrote), run: " +
        "preview <path-to.html>. It renders in an in-app WebView (JavaScript + same-directory CSS/JS " +
        "work). The user can also tap 'preview' on any .html file chip in the chat.",
    "To ping the user with a phone notification after a delay, run: notify <seconds> <title> [body]. " +
        "e.g. notify 60 \"Break time\" \"Step away from the screen\". It fires even if the app is in the " +
        "background. Use it for reminders or to signal a long task finished at a set time. Call it " +
        "ONCE per reminder; do not loop or repeat the same notify, that just spams notifications.",
    "Files you create in /workspace can be opened by the user from the app, so mention their paths."
).joinToString(" ")

/** Why a turn started on its own. Each has its own wording in the transcript. */
enum class WakeKind { BackgroundTask, Scheduled, Notification }

sealed interface Event {
    /** Assistant prose — rendered under a ⏺ marker. */
    data class Message(val text: String) : Event
    /** A thinking block. Its text is empty unless the CLI is set to summarize reasoning. */
    data class Thinking(val text: String) : Event
    /**
     * Reasoning has *started*. Fires on the content_block_start frame, which is the earliest
     * point the CLI tells us anything — the completed [Thinking] block doesn't arrive until the
     * model has finished reasoning, which on a long turn is tens of seconds of apparent freeze.
     */
    data class ThinkingStart(val effort: String) : Event
    /** A chunk of streamed *reasoning*, as it is produced. Drives the live marker. */
    data class ThinkingDelta(val text: String) : Event
    /** A small chunk of streamed output text (from --include-partial-messages), for live counting. */
    data class Delta(val text: String) : Event
    /**
     * Output produced by a subagent rather than the main turn. The CLI tags frames belonging to
     * a spawned agent with the tool_use id that created it, which is what lets a delta be
     * attributed to one agent instead of pooled into the main response counter.
     */
    data class SubagentDelta(val parentId: String, val text: String) : Event
    /**
     * How much of the context window the last exchange occupied. Reported by the CLI on every
     * assistant frame; without it a compaction is the first sign you were ever near the limit.
     */
    data class Usage(val tokens: Int) : Event
    /** The model called a tool: Read, Edit, Write, Bash, Glob, … */
    data class ToolCall(val id: String, val name: String, val input: JSONObject) : Event
    /** The result that came back for a tool call. */
    data class ToolResult(val id: String, val content: String, val isError: Boolean) : Event
    /** Turn finished; carries cost/duration when the CLI reports them. */
    data class Done(val subtype: String, val costUsd: Double?, val durationMs: Long?) : Event
    /** Session metadata from the init frame — session_id is what makes a turn resumable. */
    data class Init(
        val model: String?, val cwd: String?, val tools: List<String>, val sessionId: String?
    ) : Event
    /**
     * The session produced a turn nobody typed: a background task (Monitor, Agent, background
     * Bash) reported back, or a scheduled task fired. Without this the wake-up turn arrives with
     * no explanation at all, or — if the engine had to be restarted for it — reads as a brand-new
     * session. [detail] is the CLI's own render text for the wake.
     */
    data class Wake(val kind: WakeKind, val detail: String, val failed: Boolean = false) : Event
    /** The CLI dropped older context to stay inside the window. Silent until now. */
    data class Compacted(val trigger: String, val preTokens: Int, val postTokens: Int) : Event
    /**
     * The CLI is asking whether a tool may run, and the turn is parked until it gets an answer.
     *
     * Only ever arrives when the app has told the CLI it will answer — see [Engine.start]. In any
     * other configuration a call that would prompt is denied on the spot and this never fires.
     */
    data class Permission(
        val requestId: String,
        val tool: String,
        val display: String,
        val description: String,
        val input: JSONObject
    ) : Event

    /** Anything the bridge itself needs to say — startup, crashes. */
    data class Notice(val text: String, val isError: Boolean = false) : Event
    /** A stderr line from the engine — shown for visibility, but never ends the turn. */
    data class Stderr(val text: String) : Event
}

class Engine(private val sandbox: Sandbox, private val scope: CoroutineScope) {

    private var process: Process? = null
    private var stdin: BufferedWriter? = null
    val running get() = process?.isAlive == true

    /** One of the CLI's own modes: manual, acceptEdits, auto, dontAsk, bypassPermissions, plan. */
    var permissionMode: String = "bypassPermissions"

    /**
     * Whether this CLI build can route permission prompts to us over stdio.
     *
     * Set from a probe rather than assumed: an older binary rejects the flag outright ("unknown
     * option"), and since the flag is passed at launch that would leave the engine unable to
     * start at all rather than merely unable to prompt.
     */
    var promptsOverStdio: Boolean = false

    /** Model id handed to --model; empty means the CLI's own default. */
    var model: String = "claude-sonnet-5"

    /**
     * Reasoning effort handed to --effort: low, medium, high, xhigh, max.
     * Surfaced in the thinking marker so the user can see what they're waiting on — a max-effort
     * turn legitimately takes a long time, and saying so is the difference between "working" and
     * "hung".
     */
    var effort: String = "medium"

    /** When set, the next launch resumes that stored session instead of starting fresh. */
    var resumeId: String? = null

    /** Starts the CLI. [onEvent] is called from IO threads — hop to the main thread in the UI. */
    fun start(onEvent: (Event) -> Unit) {
        if (running) return
        if (!sandbox.engineReady) {
            onEvent(Event.Notice("Engine not installed — see settings", isError = true))
            return
        }
        val inner = buildString {
            append("exec /usr/local/bin/claude ")
            append("--print ")
            append("--input-format stream-json ")
            append("--output-format stream-json ")
            // Emit incremental text deltas (stream_event frames) so the UI can show a live token
            // count as the response is produced, instead of one block at the very end.
            append("--include-partial-messages ")
            // Without this a spawned agent is a black box: you see the Agent tool call go out
            // and nothing at all until it returns, however long that takes.
            append("--forward-subagent-text ")
            // Headless has no way to answer a permission prompt: an unattended request is simply
            // declined, which is what turned every Write into "permission denied". The workspace
            // is app-private and disposable, so the sandbox grants them up front.
            //
            // Note there's no --dangerously-skip-permissions here: the CLI rejects that flag
            // outright when it detects root, and it adds nothing over the permission mode.
            append("--permission-mode $permissionMode ")
            // Without this the CLI has nobody to ask, so anything the mode doesn't already cover
            // is auto-denied — which is what made acceptEdits look broken: every Bash call came
            // back "you haven't granted it yet" with no way to grant it.
            if (promptsOverStdio) append("--permission-prompt-tool stdio ")
            if (model.isNotBlank()) append("--model $model ")
            if (effort.isNotBlank()) append("--effort $effort ")
            resumeId?.let { append("--resume $it ") }
            // Without this the model assumes a normal Linux box and reaches for apt/brew/sudo-less
            // apk, all of which fail here.
            append("--append-system-prompt ")
            // The brief is embedded inside a double-quoted shell argument, so any backtick or $ in
            // it would be command-substituted by /bin/sh and wreck the launch ("unexpected end of
            // file"). Escape the four chars that stay special inside double quotes so the brief text
            // is passed through literally no matter what it contains.
            val safeBrief = SANDBOX_BRIEF
                .replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("$", "\\$").replace("`", "\\`")
            append('"').append(safeBrief).append("\" ")
            append("--verbose")
        }
        val cmd = sandbox.prootCommand(inner)
        val pb = ProcessBuilder(*cmd)
            .directory(sandbox.workspace)
            .redirectErrorStream(false)
        pb.environment().putAll(sandbox.env().associate { it.substringBefore('=') to it.substringAfter('=') })

        val p = try {
            pb.start()
        } catch (e: Exception) {
            onEvent(Event.Notice("Could not start engine: ${e.message}", isError = true))
            return
        }
        process = p
        stdin = BufferedWriter(OutputStreamWriter(p.outputStream))
        // Before anything is sent: the CLI decides how to settle permission prompts based on
        // whether a host announced itself, and it decides that at the first tool call.
        if (promptsOverStdio) announceHost()

        // Both readers must swallow their own failures: destroying the process to start a new
        // session closes these streams mid-read, and an uncaught IOException inside a coroutine
        // takes the whole app down.
        scope.launch(Dispatchers.IO) {
            runCatching {
                BufferedReader(InputStreamReader(p.inputStream)).forEachLine { line ->
                    if (line.isNotBlank()) parse(line, onEvent)
                }
            }
            if (process === p) {
                onEvent(Event.Notice("engine exited (${runCatching { p.waitFor() }.getOrDefault(-1)})"))
                process = null
            }
        }
        scope.launch(Dispatchers.IO) {
            runCatching {
                BufferedReader(InputStreamReader(p.errorStream)).forEachLine { line ->
                    // proot prints harmless "can't sanitize binding" warnings to stderr on every
                    // spawn; they are noise, not turn-ending errors, so drop them entirely.
                    if (line.isNotBlank() && !line.contains("proot warning") &&
                        !line.contains("can't sanitize") && !line.contains("linkerconfig")
                    ) {
                        onEvent(Event.Stderr(line))
                    }
                }
            }
        }
    }

    /**
     * Tells the CLI a host is present on the other end of stdin.
     *
     * Sent immediately after launch. Without it the CLI treats the stream as write-only and
     * settles anything that would prompt by itself — the flag alone is not enough.
     */
    private fun announceHost() {
        writeFrame(
            JSONObject()
                .put("type", "control_request")
                .put("request_id", "cinder-init")
                .put("request", JSONObject().put("subtype", "initialize"))
        )
    }

    /**
     * Answers a parked [Event.Permission]. The turn resumes the moment this lands, so an
     * unanswered request stalls it indefinitely — every path that raises one must reach here.
     */
    fun answerPermission(requestId: String, allow: Boolean, input: JSONObject, why: String = "") {
        val decision = JSONObject().put("behavior", if (allow) "allow" else "deny")
        if (allow) decision.put("updatedInput", input)
        else decision.put("message", why.ifBlank { "Denied from the phone." })
        writeFrame(
            JSONObject().put("type", "control_response").put(
                "response", JSONObject()
                    .put("subtype", "success")
                    .put("request_id", requestId)
                    .put("response", decision)
            )
        )
    }

    private fun writeFrame(frame: JSONObject) {
        val w = stdin ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { w.write(frame.toString()); w.write("\n"); w.flush() }
        }
    }

    fun send(text: String) {
        val w = stdin ?: return
        val msg = JSONObject()
            .put("type", "user")
            .put(
                "message", JSONObject()
                    .put("role", "user")
                    .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
            )
        scope.launch(Dispatchers.IO) {
            runCatching {
                w.write(msg.toString()); w.write("\n"); w.flush()
            }
        }
    }

    fun stop() {
        runCatching { stdin?.close() }
        process?.destroy()
        process = null
    }

    /** Tolerant parser: unknown frames are ignored rather than crashing the stream. */
    private fun parse(line: String, onEvent: (Event) -> Unit) {
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return
        when (json.optString("type")) {
            // The CLI asking permission for a tool call. It is blocked until answered.
            "control_request" -> {
                val req = json.optJSONObject("request") ?: return
                if (req.optString("subtype") != "can_use_tool") return
                val id = json.optString("request_id").takeIf { it.isNotBlank() } ?: return
                val tool = req.optString("tool_name")
                onEvent(
                    Event.Permission(
                        requestId = id,
                        tool = tool,
                        display = req.optString("display_name").ifBlank { tool },
                        description = req.optString("description"),
                        input = req.optJSONObject("input") ?: JSONObject()
                    )
                )
            }

            // The ack for our own initialize, and nothing else we act on.
            "control_response" -> return

            // Partial-message frames: a stream of Anthropic SSE events. We only want the text and
            // thinking deltas, purely to drive the live token counter — the transcript still uses
            // the complete assistant message that arrives at the end of the block.
            "stream_event" -> {
                val ev = json.optJSONObject("event") ?: return
                // Frames from a spawned agent carry the id of the tool call that started it.
                // They must not feed the main counters or the two turns' totals merge.
                val parent = json.optString("parent_tool_use_id").takeIf { it.isNotBlank() }
                if (parent != null) {
                    if (ev.optString("type") == "content_block_delta") {
                        val d = ev.optJSONObject("delta")
                        val txt = d?.optString("text").orEmpty()
                            .ifEmpty { d?.optString("thinking").orEmpty() }
                        if (txt.isNotEmpty()) onEvent(Event.SubagentDelta(parent, txt))
                    }
                    return
                }
                when (ev.optString("type")) {
                    // The moment reasoning begins. Without this the UI has nothing to show until
                    // the whole thinking block lands, which is exactly the "looks stuck" window.
                    "content_block_start" -> {
                        val t = ev.optJSONObject("content_block")?.optString("type")
                        if (t == "thinking" || t == "redacted_thinking") {
                            onEvent(Event.ThinkingStart(effort))
                        }
                    }
                    "content_block_delta" -> {
                        val delta = ev.optJSONObject("delta")
                        // Reasoning and prose both stream as deltas but mean different things to
                        // the UI: one drives the thinking marker, the other the response counter.
                        val think = delta?.optString("thinking").orEmpty()
                        if (think.isNotEmpty()) {
                            onEvent(Event.ThinkingDelta(think))
                        } else {
                            val text = delta?.optString("text").orEmpty()
                            if (text.isNotEmpty()) onEvent(Event.Delta(text))
                        }
                    }
                }
            }

            // Only "init" used to be read here; every other system frame was dropped, which is
            // why a turn started by a monitor, a watcher or a scheduled wake-up arrived with no
            // explanation — and, when the engine had been restarted for it, looked like a new
            // session. The subtypes below are the CLI's own stream-json system messages.
            "system" -> when (json.optString("subtype")) {
                "init" -> {
                    val tools = json.optJSONArray("tools")?.let { arr ->
                        (0 until arr.length()).map { arr.optString(it) }
                    } ?: emptyList()
                    onEvent(
                        Event.Init(
                            json.optString("model", null),
                            json.optString("cwd", null),
                            tools,
                            json.optString("session_id", null)
                        )
                    )
                }

                // A background task finished and handed its result back to the model. status is
                // completed | failed | stopped.
                "task_notification" -> {
                    val status = json.optString("status", "completed")
                    val summary = json.optString("summary").ifBlank {
                        json.optString("task_id").take(8).ifBlank { "background task" }
                    }
                    onEvent(
                        Event.Wake(
                            WakeKind.BackgroundTask,
                            "$status · $summary",
                            failed = status == "failed"
                        )
                    )
                }

                // A cron/scheduled task fired. `content` is the CLI's own render text.
                "scheduled_task_fire" ->
                    onEvent(Event.Wake(WakeKind.Scheduled, json.optString("content").trim()))

                // The loop's own notification queue — the phone equivalent of the REPL's toasts.
                "notification" -> {
                    val text = json.optString("text").trim()
                    if (text.isNotEmpty()) {
                        onEvent(
                            Event.Wake(
                                WakeKind.Notification, text,
                                failed = json.optString("priority") == "immediate"
                            )
                        )
                    }
                }

                "compact_boundary" -> {
                    val meta = json.optJSONObject("compact_metadata")
                    onEvent(
                        Event.Compacted(
                            meta?.optString("trigger", "auto") ?: "auto",
                            meta?.optInt("pre_tokens", 0) ?: 0,
                            meta?.optInt("post_tokens", 0) ?: 0
                        )
                    )
                }
            }

            "assistant" -> {
                json.optJSONObject("message")?.optJSONObject("usage")?.let { u ->
                    // Cached reads still occupy the window, so they count the same as fresh input.
                    val used = u.optInt("input_tokens") +
                        u.optInt("cache_read_input_tokens") +
                        u.optInt("cache_creation_input_tokens") +
                        u.optInt("output_tokens")
                    if (used > 0) onEvent(Event.Usage(used))
                }
                val content = json.optJSONObject("message")?.optJSONArray("content") ?: return
                for (i in 0 until content.length()) {
                    val block = content.optJSONObject(i) ?: continue
                    when (block.optString("type")) {
                        "text" -> block.optString("text").takeIf { it.isNotBlank() }
                            ?.let { onEvent(Event.Message(it)) }

                        "thinking", "redacted_thinking" ->
                            onEvent(Event.Thinking(block.optString("thinking")))

                        "tool_use" -> onEvent(
                            Event.ToolCall(
                                block.optString("id"),
                                block.optString("name"),
                                block.optJSONObject("input") ?: JSONObject()
                            )
                        )
                    }
                }
            }

            "user" -> {
                val content = json.optJSONObject("message")?.optJSONArray("content") ?: return
                for (i in 0 until content.length()) {
                    val block = content.optJSONObject(i) ?: continue
                    if (block.optString("type") == "tool_result") {
                        onEvent(
                            Event.ToolResult(
                                block.optString("tool_use_id"),
                                stringify(block.opt("content")),
                                block.optBoolean("is_error", false)
                            )
                        )
                    }
                }
            }

            "result" -> onEvent(
                Event.Done(
                    json.optString("subtype", "success"),
                    json.optDouble("total_cost_usd").takeIf { !it.isNaN() },
                    json.optLong("duration_ms").takeIf { it > 0 }
                )
            )
        }
    }

    /** tool_result content is a string on some frames and a block array on others. */
    private fun stringify(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        is JSONArray -> (0 until value.length()).mapNotNull { i ->
            val o = value.optJSONObject(i)
            when (o?.optString("type")) {
                "text" -> o.optString("text")
                null -> value.optString(i)
                else -> null
            }
        }.joinToString("\n")
        else -> value.toString()
    }
}
