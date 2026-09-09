package app.cinder

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch


class MainActivity : ComponentActivity() {
    private val vm: Session by viewModels()

    /**
     * POST_NOTIFICATIONS has been in the manifest all along but was never requested, so on
     * Android 13+ every notification this app posted — including the agent's own `notify` —
     * was dropped silently unless the user had granted it by hand in system settings.
     */
    private val askNotify =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        // Last-resort back: leave the app the way switching apps does, rather than finishing.
        //
        // Registered before setContent so every Compose BackHandler is dispatched ahead of it —
        // this only runs once nothing on screen is left to close. Finishing would destroy the
        // view model, and with it the transcript and the engine's own bookkeeping, for what the
        // user meant as "go back to what I was doing before". The CLI is a child of this
        // process, so that is also the difference between backgrounding a running turn and
        // ending it.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Orange, onPrimary = Ink, background = Ink,
                    surface = Panel, onSurface = Text0, onBackground = Text0
                )
            ) { Root(vm) }
        }
    }

    // A finished turn only earns a notification if the user has looked away. Tracking that here
    // rather than guessing from the lifecycle of a composable keeps it correct across rotation
    // and the drawer, which both recompose without the app ever leaving the screen.
    override fun onStart() {
        super.onStart()
        vm.foreground = true
        // Asks the engine what it can do. Can't happen while the view model is being built —
        // the probe reads state declared after the constructor runs.
        vm.warmUp()
    }

    override fun onStop() {
        super.onStop()
        vm.foreground = false
    }
}

private enum class Tab(val label: String) { Chat("chat"), Terminal("shell"), Settings("settings") }

/** Lets deep composables (the file chips) reach the session without threading it through. */
private val LocalSession = compositionLocalOf<Session?> { null }

@Composable
private fun Root(vm: Session) {
    var tab by remember { mutableStateOf(Tab.Chat) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    CompositionLocalProvider(LocalSession provides vm) {
        ModalNavigationDrawer(
            drawerState = drawer,
            drawerContent = {
                ModalDrawerSheet(drawerContainerColor = Ink, drawerContentColor = Text0) {
                    HistoryPane(vm) { record ->
                        vm.openSession(record)
                        tab = Tab.Chat
                        scope.launch { drawer.close() }
                    }
                }
            }
        ) {
            Scaffold(
                containerColor = Ink,
                topBar = {
                    Header(vm, tab, onMenu = { scope.launch { drawer.open() } }) { tab = it }
                }
            ) { pad ->
                // Back peels off one layer of UI at a time, and only leaves the app once there
                // is nothing left to close. Disabling it when there isn't hands the press to the
                // system, which is what makes the last press behave like a normal exit rather
                // than a dead button.
                //
                // The preview registers its own handler further down; a handler composed later
                // is dispatched first, so an open preview still closes before any of this.
                BackHandler(enabled = drawer.isOpen || vm.searchOpen || tab != Tab.Chat) {
                    when {
                        drawer.isOpen -> scope.launch { drawer.close() }
                        vm.searchOpen -> vm.closeSearch()
                        else -> tab = Tab.Chat
                    }
                }
                Box(Modifier.padding(pad).fillMaxSize()) {
                    when (tab) {
                        Tab.Chat -> ChatTab(vm)
                        Tab.Terminal -> TerminalTab(vm)
                        Tab.Settings -> SettingsTab(vm)
                    }
                    // HTML preview rides above the current tab so it can be opened from anywhere.
                    vm.previewFile?.let { HtmlPreview(it) { vm.closePreview() } }
                }
            }
        }
    }
}

@Composable
private fun HistoryPane(vm: Session, onOpen: (SessionRecord) -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("✦", color = Orange, fontSize = 14.sp)
            Spacer(Modifier.width(8.dp))
            Text("sessions", color = Text0, fontFamily = mono, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("${vm.history.size}", color = Dim, fontFamily = mono, fontSize = 11.sp)
            if (vm.history.isNotEmpty()) {
                Text(
                    "clear all", color = Red, fontFamily = mono, fontSize = 10.sp,
                    modifier = Modifier.clickable { vm.clearHistory() }.padding(start = 10.dp)
                )
            }
        }
        HorizontalDivider(color = Line)

        if (vm.history.isEmpty()) {
            Text(
                "Conversations show up here once they've had a reply. Tapping one resumes it — " +
                    "the CLI reloads that session's full context, not just this transcript.",
                color = Dim, fontSize = 11.sp, lineHeight = 16.sp
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(vm.history) { rec ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(Panel),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        Modifier.weight(1f).clickable { onOpen(rec) }.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(rec.title, color = Text0, fontSize = 12.sp, maxLines = 2, lineHeight = 16.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(ago(rec.updated), color = Dim, fontFamily = mono, fontSize = 9.sp)
                            Text(
                                rec.model.removePrefix("claude-"),
                                color = Dim, fontFamily = mono, fontSize = 9.sp
                            )
                            Text(
                                "${rec.turns.count { it.first == "u" }} turns",
                                color = Dim, fontFamily = mono, fontSize = 9.sp
                            )
                        }
                    }
                    Text(
                        "×", color = Dim, fontFamily = mono, fontSize = 16.sp,
                        modifier = Modifier.clickable { vm.deleteSession(rec) }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

private fun ago(ms: Long): String {
    val d = (System.currentTimeMillis() - ms) / 1000
    return when {
        d < 60 -> "just now"
        d < 3600 -> "${d / 60}m ago"
        d < 86400 -> "${d / 3600}h ago"
        else -> "${d / 86400}d ago"
    }
}

@Composable
private fun Header(vm: Session, tab: Tab, onMenu: () -> Unit, onTab: (Tab) -> Unit) {
    var confirmClean by remember { mutableStateOf(false) }
    if (confirmClean) CleanWorkspaceDialog(onDismiss = { confirmClean = false }) {
        confirmClean = false
        vm.cleanWorkspace()
    }
    Column(
        Modifier.background(Ink).statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "☰", color = Dim, fontSize = 16.sp,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { onMenu() }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
            Text("✦", color = Orange, fontSize = 18.sp)
            Text(
                "cinder", color = Text0, fontSize = 16.sp, fontFamily = mono,
                fontWeight = FontWeight.Bold, maxLines = 1
            )
            Spacer(Modifier.weight(1f))
            // Without maxLines these collapse to a sliver and wrap one character per line when
            // the row runs out of width, which is what turned the header into a tall column.
            Text(
                vm.status, color = if (vm.busy) Orange else Dim, fontSize = 11.sp,
                fontFamily = mono, maxLines = 1
            )
            Surface(
                color = Panel,
                shape = RoundedCornerShape(4.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Line),
                onClick = { vm.newSession() }
            ) {
                Text(
                    "+ new", color = Dim, fontFamily = mono, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            ModelPicker(vm)
        }
        // The effort picker lives on the path line rather than the title row: that row was
        // already at seven children and a phone has no width left to give it.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                vm.cwd.replace(Regex("^/data/(user/0|data)/[^/]+/files"), "~"),
                color = Dim, fontSize = 10.sp, fontFamily = mono, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // Nothing before the first turn reports usage, and no denominator until the CLI
            // has told us the real one — a percentage of a guessed window is worse than none.
            if (vm.contextTokens > 0) {
                val pct = if (vm.contextWindow > 0) vm.contextTokens * 100 / vm.contextWindow else 0
                Text(
                    if (vm.contextWindow > 0) "${fmtTok(vm.contextTokens)}/${fmtTok(vm.contextWindow)}"
                    else fmtTok(vm.contextTokens),
                    color = when {
                        pct >= 90 -> Red
                        pct >= 75 -> Orange
                        else -> Dim
                    },
                    fontSize = 10.sp, fontFamily = mono, maxLines = 1
                )
            }
            Text(
                "⌕", color = if (vm.searchOpen) Orange else Dim, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(4.dp))
                    .clickable {
                        if (vm.searchOpen) vm.closeSearch() else { vm.searchOpen = true; onTab(Tab.Chat) }
                    }
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            )
            EffortPicker(vm)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tab.entries.forEach { t ->
                val on = t == tab
                Surface(
                    color = if (on) Panel else Color.Transparent,
                    shape = RoundedCornerShape(4.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (on) Orange.copy(alpha = .5f) else Line),
                    onClick = { onTab(t) }
                ) {
                    Text(
                        t.label, color = if (on) Orange else Dim, fontFamily = mono, fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
            // Sits beside the tabs rather than inside them: it's an action, not a place to be.
            Surface(
                color = Color.Transparent,
                shape = RoundedCornerShape(4.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Line),
                onClick = { confirmClean = true }
            ) {
                Text(
                    "clean", color = Red, fontFamily = mono, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            Surface(
                color = Color.Transparent,
                shape = RoundedCornerShape(4.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Line),
                onClick = { vm.updateCli() }
            ) {
                Text(
                    if (vm.updating) "checking…" else "update",
                    color = if (vm.updating) Dim else Green, fontFamily = mono, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
        HorizontalDivider(color = Line)
    }
}

/**
 * Emptying the workspace throws away real work, so it asks first — and says plainly what it
 * does not touch, since the expensive things (the CLI, the musl rootfs, the toolchains) live
 * outside the workspace and survive.
 */
@Composable
private fun CleanWorkspaceDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        titleContentColor = Text0,
        textContentColor = Dim,
        title = { Text("clean /workspace?", fontFamily = mono, fontSize = 14.sp) },
        text = {
            Text(
                "Deletes every file the agent has created in /workspace. The claude CLI, the musl " +
                    "rootfs, proot, and the installed shell/build/apk tools live outside the " +
                    "workspace and are left alone — nothing has to be reinstalled after this.",
                fontSize = 12.sp, lineHeight = 17.sp
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("delete files", color = Red, fontFamily = mono, fontSize = 12.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("cancel", color = Dim, fontFamily = mono, fontSize = 12.sp)
            }
        }
    )
}

/**
 * Reasoning effort. Sits next to the model picker because it changes what a turn costs in time
 * the same way the model does — and because the thinking marker names it, so it needs to be
 * something the user can actually see and change.
 */
@Composable
private fun EffortPicker(vm: Session) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            color = Panel,
            shape = RoundedCornerShape(4.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Dim.copy(alpha = .4f)),
            onClick = { open = true }
        ) {
            Text(
                if (vm.ultracode) "⏣ ultracode ▾" else "✻ ${vm.effort} ▾",
                color = if (vm.ultracode) Purple else Dim, fontFamily = mono, fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Panel) {
            Text(
                "reasoning effort",
                color = Dim, fontFamily = mono, fontSize = 9.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
            vm.efforts.forEach { e ->
                DropdownMenuItem(
                    text = {
                        Text(
                            e, fontFamily = mono, fontSize = 12.sp,
                            color = if (e == vm.effort) Orange else Text0
                        )
                    },
                    onClick = { vm.chooseEffort(e); open = false }
                )
            }
            HorizontalDivider(color = Line)
            // Below the divider because it isn't an effort level: it's a keyword prefixed to the
            // prompt, so unlike effort it takes hold on the very next turn with no restart.
            DropdownMenuItem(
                text = {
                    Column {
                        Text(
                            if (vm.ultracode) "⏣ ultracode - on" else "⏣ ultracode - off",
                            fontFamily = mono, fontSize = 12.sp,
                            color = if (vm.ultracode) Purple else Text0
                        )
                        Text(
                            "multi-agent orchestration; can spawn dozens of agents",
                            fontFamily = mono, fontSize = 9.sp, color = Dim
                        )
                    }
                },
                onClick = { vm.toggleUltracode(); open = false }
            )
        }
    }
}

@Composable
private fun ModelPicker(vm: Session) {
    var open by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    val current = vm.models.firstOrNull { it.id == vm.model }
    Box {
        Surface(
            color = Panel,
            shape = RoundedCornerShape(4.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Orange.copy(alpha = .4f)),
            onClick = { open = true }
        ) {
            Text(
                // short label in the bar, exact id in the menu
                (current?.displayName ?: vm.model).removePrefix("Claude ") + " ▾",
                color = Orange, fontFamily = mono, fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Panel) {
            Text(
                if (vm.modelsLive) "available to your account" else "defaults — sign in to refresh",
                color = Dim, fontFamily = mono, fontSize = 9.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
            // Never show more than 5 at once unless expanded; keep the selected model visible even
            // when it isn't in the first five.
            val ordered = (listOfNotNull(current) + vm.models.filter { it.id != vm.model }).distinct()
            val shown = if (showAll) ordered else ordered.take(5)
            shown.forEach { m ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                m.displayName, fontFamily = mono, fontSize = 12.sp,
                                color = if (m.id == vm.model) Orange else Text0
                            )
                            Text(m.id, fontFamily = mono, fontSize = 9.sp, color = Dim)
                        }
                    },
                    onClick = { vm.chooseModel(m.id); open = false }
                )
            }
            HorizontalDivider(color = Line)
            if (vm.models.size > 5) DropdownMenuItem(
                text = {
                    Text(
                        if (showAll) "▴ show less" else "▾ show all (${vm.models.size})",
                        fontFamily = mono, fontSize = 11.sp, color = Orange
                    )
                },
                onClick = { showAll = !showAll }   // toggle in place, keep the menu open
            )
            DropdownMenuItem(
                text = { Text("refresh list", fontFamily = mono, fontSize = 11.sp, color = Dim) },
                onClick = { vm.refreshModels(); open = false }
            )
        }
    }
}

// ---------------- chat ----------------

@Composable
private fun ChatTab(vm: Session) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // A find with nothing typed yet still shows the whole conversation, so the bar can open
    // without the transcript blinking out from under you.
    val searching = vm.searchOpen && vm.searchQuery.isNotBlank()
    val shown = if (searching) vm.searchHits else vm.transcript
    LaunchedEffect(vm.transcript.size, vm.busy, searching) {
        // Following the tail while reading results would fight the scroll on every new frame.
        if (!searching && vm.transcript.isNotEmpty()) {
            scope.launch { listState.animateScrollToItem(vm.transcript.size) }
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (vm.searchOpen) FindBar(vm)
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (vm.transcript.isEmpty() && !searching) item { Welcome() }
            if (searching && shown.isEmpty()) {
                item { Text("no matches", color = Dim, fontFamily = mono, fontSize = 12.sp) }
            }
            items(shown) { TurnRow(it) }
            if (vm.busy && !searching) item { RespondingMarker(vm.liveLabel, vm.liveTokens) }
        }
        Composer(vm)
    }
}

/**
 * Find-in-conversation.
 *
 * A long session scrolls past the point of being able to find anything in it, and the turn you
 * want is usually one you can name — a filename, an error, something you asked. Matching turns
 * are shown in place of the transcript rather than highlighted within it: on a phone there is no
 * room to do both, and the list of hits is the more useful of the two.
 */
@Composable
private fun FindBar(vm: Session) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        Modifier.fillMaxWidth().background(Panel).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = vm.searchQuery,
            onValueChange = { vm.searchQuery = it },
            modifier = Modifier.weight(1f).focusRequester(focus),
            placeholder = { Text("find in conversation…", fontFamily = mono, fontSize = 12.sp, color = Dim) },
            textStyle = androidx.compose.ui.text.TextStyle(color = Text0, fontSize = 13.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Orange, unfocusedBorderColor = Line, cursorColor = Orange
            ),
            shape = RoundedCornerShape(4.dp),
            singleLine = true
        )
        if (vm.searchQuery.isNotBlank()) {
            Text(
                "${vm.searchHits.size}", color = Dim, fontFamily = mono, fontSize = 11.sp,
                maxLines = 1
            )
        }
        Text(
            "×", color = Dim, fontFamily = mono, fontSize = 16.sp,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { vm.closeSearch() }
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/**
 * Live marker shown while the model is producing output. Claude Code sends complete message blocks
 * rather than a token stream, so this reflects how much has arrived so far — a rough ~4-chars-per-
 * token estimate that ticks up as each block lands.
 */
@Composable
private fun RespondingMarker(label: String, tokens: Int) {
    val alpha by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse"
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("⏺", color = Orange, fontSize = 12.sp, modifier = Modifier.graphicsLayer { this.alpha = alpha })
        Spacer(Modifier.width(6.dp))
        Text("$label…", color = Text0, fontFamily = mono, fontSize = 12.sp)
        // No figure until there is one to show: "~0 tokens" reads as a counter that has stuck.
        if (tokens > 0) {
            Spacer(Modifier.width(8.dp))
            Text("~${fmtTok(tokens)} tokens", color = Dim, fontFamily = mono, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Welcome() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("✦ Cinder — a mobile Claude Code client", color = Orange, fontFamily = mono, fontSize = 13.sp)
        Text(
            "This app carries its own sandbox: a private Linux workspace where files are created, " +
                "edited and built. The shell tab works right now. The agent needs its engine — see settings.",
            color = Dim, fontSize = 12.sp, lineHeight = 18.sp
        )
    }
}

@Composable
private fun TurnRow(turn: Turn) {
    when (turn) {
        is Turn.User -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(">", color = Dim, fontFamily = mono, fontSize = 13.sp)
            SelectionContainer { Text(turn.text, color = Text0, fontSize = 13.sp, lineHeight = 19.sp) }
        }

        is Turn.Assistant -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val parts = remember(turn.text) { splitFences(turn.text) }
            parts.forEachIndexed { i, part ->
                when (part) {
                    is Part.Prose -> if (part.text.isNotBlank()) Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // only the first prose block carries the marker; the rest continue it
                        Text(
                            if (i == 0) "⏺" else " ",
                            color = Orange, fontSize = 12.sp,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                        Markdown(part.text)
                    }

                    is Part.Code -> CodeCanvas(part)
                }
            }
            FileChips(turn.text)
        }

        is Turn.Thinking -> {
            var open by remember { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = if (turn.text.isNotBlank())
                        Modifier.clip(RoundedCornerShape(3.dp)).clickable { open = !open }
                    else Modifier
                ) {
                    // While reasoning is live the marker has to prove the app is alive: the star
                    // cycles and the clock ticks. A frozen "thinking…" is indistinguishable from
                    // a hang, which is the entire reason this row exists.
                    var tick by remember(turn.startedAt) { mutableStateOf(0) }
                    LaunchedEffect(turn.live, turn.startedAt) {
                        while (turn.live) {
                            kotlinx.coroutines.delay(250)
                            tick++
                        }
                    }
                    val star = if (turn.live) "✻✢✳✶"[(tick / 2) % 4].toString() else "✻"
                    Text(star, color = if (turn.live) Orange else Dim, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp))

                    val label = if (turn.live) {
                        val secs = ((System.currentTimeMillis() - turn.startedAt) / 1000).toInt()
                        buildString {
                            append("thinking")
                            if (turn.effort.isNotBlank()) append(" (${turn.effort})")
                            append(" · ${secs}s")
                            if (turn.tokens > 0) {
                                append(" · ")
                                append(
                                    if (turn.tokens >= 1000) "%.1fk".format(turn.tokens / 1000f)
                                    else turn.tokens.toString()
                                )
                                append(" tokens")
                            }
                        }
                    } else if (turn.text.isBlank()) "thought"
                    else if (open) "thinking" else "thinking · tap to read"

                    Text(label, color = Dim, fontFamily = mono, fontSize = 11.sp)
                }
                // Reasoning text only exists when the CLI is set to summarize it; otherwise the
                // block arrives empty and the marker alone is the signal.
                if (open && turn.text.isNotBlank()) {
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp)).background(Panel)
                            .border(1.dp, Line, RoundedCornerShape(3.dp)).padding(8.dp)
                    ) {
                        SelectionContainer {
                            Text(turn.text, color = Dim, fontSize = 11.sp, lineHeight = 16.sp)
                        }
                    }
                }
            }
        }

        is Turn.Tool -> ToolCard(turn)

        is Turn.Notice -> Text(
            turn.text,
            color = if (turn.isError) Red else Dim,
            fontSize = 11.sp, fontFamily = mono, lineHeight = 16.sp
        )

        is Turn.Link -> {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            Button(
                onClick = {
                    ctx.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(turn.url))
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Ink)
            ) { Text(turn.label, fontFamily = mono, fontSize = 12.sp) }
        }

        is Turn.Done -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("⎿", color = Dim, fontFamily = mono, fontSize = 12.sp)
            Text(turn.summary, color = Dim, fontFamily = mono, fontSize = 11.sp)
        }
    }
}

/** A message splits into prose and fenced code; the code gets its own canvas. */
private sealed interface Part {
    data class Prose(val text: String) : Part
    data class Code(val language: String, val code: String, val open: Boolean) : Part
}

/**
 * Splits on ``` fences. A canvas opens at the opening fence and closes at the closing one — and if
 * a message ends mid-block (the closing fence never arrives), the canvas stays open rather than
 * spilling code into the prose.
 */
private fun splitFences(text: String): List<Part> {
    val parts = mutableListOf<Part>()
    val lines = text.lines()
    val buf = StringBuilder()
    var inCode = false
    var lang = ""
    var closed = true

    fun flushProse() {
        if (buf.isNotEmpty()) { parts.add(Part.Prose(buf.toString().trimEnd('\n'))); buf.clear() }
    }

    for (line in lines) {
        val fence = line.trimStart().startsWith("```")
        if (fence && !inCode) {
            flushProse()
            inCode = true; closed = false
            lang = line.trimStart().removePrefix("```").trim()
        } else if (fence && inCode) {
            parts.add(Part.Code(lang, buf.toString().trimEnd('\n'), open = false))
            buf.clear(); inCode = false; closed = true
        } else {
            buf.appendLine(line)
        }
    }
    if (inCode) parts.add(Part.Code(lang, buf.toString().trimEnd('\n'), open = !closed))
    else flushProse()
    return parts
}

@Composable
private fun CodeCanvas(part: Part.Code) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val lineCount = remember(part.code) { part.code.lines().size }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp))
            .background(Ink).border(1.dp, Line, RoundedCornerShape(5.dp))
    ) {
        Row(
            Modifier.fillMaxWidth().background(Panel).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                part.language.ifBlank { "code" },
                color = Orange, fontFamily = mono, fontSize = 10.sp
            )
            Text("$lineCount lines", color = Dim, fontFamily = mono, fontSize = 9.sp)
            if (part.open) Text("writing…", color = Blue, fontFamily = mono, fontSize = 9.sp)
            Spacer(Modifier.weight(1f))
            Text(
                if (copied) "copied" else "copy",
                color = if (copied) Green else Dim, fontFamily = mono, fontSize = 10.sp,
                modifier = Modifier.clip(RoundedCornerShape(3.dp))
                    .clickable {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(part.code))
                        copied = true
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        SelectionContainer {
            Text(
                part.code,
                color = Text0, fontFamily = mono, fontSize = 11.sp, lineHeight = 16.sp,
                softWrap = false,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(10.dp)
            )
        }
    }
}

/**
 * Any workspace path the model mentions becomes a chip. Tapping copies the file out of the
 * sandbox into Downloads and opens it — that's what makes "here's your file" actually usable,
 * since nothing outside the app can read app-private storage.
 */
@Composable
private fun FileChips(text: String) {
    val vm = LocalSession.current ?: return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var note by remember { mutableStateOf<String?>(null) }

    val candidates = remember(text) {
        Regex("""(?:/workspace/|\./|~/)?[\w.\-/]+\.[A-Za-z0-9]{1,6}""")
            .findAll(text).map { it.value }.distinct().take(8).toList()
    }
    val files = remember(candidates) { candidates.mapNotNull { vm.resolveWorkspaceFile(it) }.distinct() }
    if (files.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        files.forEach { f ->
            val isHtml = f.extension.lowercase() in listOf("html", "htm")
            val isApk = f.extension.lowercase() == "apk"
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(
                    onClick = {
                        vm.exportFile(f) { msg, uri ->
                            note = msg
                            if (uri != null) runCatching {
                                ctx.startActivity(
                                    android.content.Intent(android.content.Intent.ACTION_VIEW)
                                        .setDataAndType(uri, ctx.contentResolver.getType(uri))
                                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                )
                            }
                        }
                    },
                    shape = RoundedCornerShape(4.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Panel, contentColor = Blue)
                ) {
                    Text(
                        "⤓  ${f.name}  ${f.length() / 1024 + 1} KB",
                        fontFamily = mono, fontSize = 11.sp, maxLines = 1
                    )
                }
                if (isHtml) OutlinedButton(
                    onClick = { vm.previewHtml(f) },
                    shape = RoundedCornerShape(4.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Panel, contentColor = Orange)
                ) { Text("▶ preview", fontFamily = mono, fontSize = 11.sp, maxLines = 1) }
                if (isApk) OutlinedButton(
                    onClick = { vm.installApk(f) },
                    shape = RoundedCornerShape(4.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Panel, contentColor = Orange)
                ) { Text("⇩ install", fontFamily = mono, fontSize = 11.sp, maxLines = 1) }
            }
        }
        note?.let { Text(it, color = Dim, fontFamily = mono, fontSize = 10.sp) }
    }
}

/**
 * Full-screen WebView preview of an HTML file from the workspace. JavaScript and file access are on
 * so a self-contained page (inline or same-directory CSS/JS) renders as it would in a browser; the
 * base URL is the file's own directory so relative asset paths resolve.
 */
@Composable
private fun HtmlPreview(file: java.io.File, onClose: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().background(Ink)) {
        Row(
            Modifier.fillMaxWidth().background(Panel).statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("▶ ${file.name}", color = Text0, fontFamily = mono, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("✕ close", color = Orange, fontFamily = mono, fontSize = 12.sp) }
        }
        androidx.compose.ui.viewinterop.AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                android.webkit.WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = true
                    settings.domStorageEnabled = true
                    @Suppress("DEPRECATION")
                    settings.allowFileAccessFromFileURLs = true
                    webViewClient = android.webkit.WebViewClient()
                }
            },
            update = { web ->
                val html = runCatching { file.readText() }.getOrDefault("<h1>could not read ${file.name}</h1>")
                web.loadDataWithBaseURL(
                    "file://${file.parent}/", html, "text/html", "utf-8", null
                )
            }
        )
    }
}

@Composable
private fun ToolCard(tool: Turn.Tool) {
    var open by remember { mutableStateOf(false) }
    val accent = when {
        tool.failed -> Red
        tool.result != null -> Green
        else -> Blue
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
            modifier = Modifier.clip(RoundedCornerShape(3.dp))
        ) {
            Text("⏺", color = accent, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tool.name, color = Text0, fontFamily = mono, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(tool.headline, color = Dim, fontFamily = mono, fontSize = 12.sp, maxLines = 1)
                }
                // Live status for anything touching a file. The verb comes from the op worked
                // out when the call arrived — the tool's name can't tell a create from an
                // overwrite, and deletes arrive as Bash.
                if (tool.op != FileOp.None) {
                    val n = tool.lines
                    val diff = listOfNotNull(
                        tool.added?.let { "+$it" }, tool.removed?.let { "−$it" }
                    ).joinToString(" ").ifBlank { null }
                    val label = when (tool.op) {
                        FileOp.Read -> when {
                            !tool.finished -> "reading file…"
                            tool.failed -> "read failed"
                            n != null -> "read file · $n lines"
                            else -> "read file"
                        }
                        FileOp.Create -> when {
                            !tool.finished && n != null -> "creating file · $n lines written"
                            !tool.finished -> "creating file…"
                            tool.failed -> "write failed"
                            n != null -> "file created · $n lines"
                            else -> "file created"
                        }
                        FileOp.Edit -> when {
                            !tool.finished -> "editing file…"
                            tool.failed -> "edit failed"
                            diff != null -> "file edited · $diff lines"
                            else -> "file edited"
                        }
                        FileOp.Append -> when {
                            !tool.finished -> "appending…"
                            tool.failed -> "append failed"
                            n != null -> "appended · file now $n lines"
                            else -> "appended to file"
                        }
                        FileOp.Delete -> when {
                            !tool.finished -> "deleting…"
                            tool.failed -> "delete failed"
                            else -> "file deleted"
                        }
                        FileOp.None -> ""
                    }
                    Text(
                        label,
                        color = when {
                            tool.failed -> Red
                            !tool.finished -> Blue
                            tool.op == FileOp.Read -> Blue
                            // A completed delete is green-for-worked but orange-for-destructive;
                            // orange wins, because it's the one you'd want to notice scrolling back.
                            tool.op == FileOp.Delete -> Orange
                            else -> Green
                        },
                        fontFamily = mono, fontSize = 10.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                if (tool.body != null) {
                    Box(
                        Modifier.padding(top = 4.dp).fillMaxWidth()
                            .clip(RoundedCornerShape(3.dp)).background(Panel)
                            .border(1.dp, Line, RoundedCornerShape(3.dp)).padding(8.dp)
                    ) {
                        Text(
                            tool.body, fontFamily = mono, fontSize = 11.sp, lineHeight = 15.sp,
                            color = Text0
                        )
                    }
                }
                tool.result?.let { r ->
                    val lines = r.lines()
                    val expandable = lines.size > 1 || r.length > 90
                    Row(
                        // the whole row toggles, so the tap target isn't a 10sp word
                        Modifier.padding(top = 4.dp)
                            .then(
                                if (expandable) Modifier.clip(RoundedCornerShape(3.dp))
                                    .clickable { open = !open }
                                else Modifier
                            ),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("⎿", color = Dim, fontFamily = mono, fontSize = 11.sp)
                        Text(
                            if (open) r else lines.first().take(90),
                            color = if (tool.failed) Red else Dim,
                            fontFamily = mono, fontSize = 11.sp, lineHeight = 15.sp,
                            modifier = Modifier.weight(1f)
                        )
                        if (expandable) {
                            Text(
                                if (open) "less" else "+${lines.size - 1} more",
                                color = Orange, fontFamily = mono, fontSize = 10.sp,
                                modifier = Modifier.clip(RoundedCornerShape(2.dp))
                                    .background(Panel)
                                    .clickable { open = !open }
                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Live readout of spawned agents, collapsed by default.
 *
 * A workflow can fan out to dozens of these, so the row is capped and the rest are counted
 * rather than listed — an unbounded list would push the composer off the screen, which is the
 * same class of bug as the header overflow.
 */
@Composable
private fun SubagentPanel(vm: Session) {
    if (vm.subagents.isEmpty()) return
    val running = vm.subagents.count { it.running }
    val total = vm.subagentTokens
    val cap = 8
    // Opens itself while agents are live, so the per-agent stop is in reach during the only
    // window it can be used. Collapsing it by hand still sticks — this only forces the first one.
    LaunchedEffect(running > 0) { if (running > 0) vm.subagentsOpen = true }

    Column(
        Modifier.fillMaxWidth().padding(bottom = 8.dp)
            .clip(RoundedCornerShape(4.dp)).background(Ink)
            .border(1.dp, Line, RoundedCornerShape(4.dp))
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { vm.subagentsOpen = !vm.subagentsOpen }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("⚙", color = if (running > 0) Purple else Dim, fontSize = 12.sp)
            Text(
                buildString {
                    append("${vm.subagents.size} subagent")
                    if (vm.subagents.size != 1) append("s")
                    if (running > 0) append(" · $running running")
                    val stopping = vm.subagents.count { it.stopping }
                    if (stopping > 0) append(" · $stopping stopping")
                    if (total > 0) append(" · ${fmtTok(total)} tokens")
                },
                color = if (running > 0) Purple else Dim, fontFamily = mono, fontSize = 10.sp,
                maxLines = 1, modifier = Modifier.weight(1f)
            )
            Text(if (vm.subagentsOpen) "▴" else "▾", color = Dim, fontFamily = mono, fontSize = 10.sp)
        }
        if (vm.subagentsOpen) {
            HorizontalDivider(color = Line)
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                vm.subagents.take(cap).forEach { a ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // A running agent has to *look* like it is running. A static glyph next
                        // to a finished agent's tick reads as two kinds of done, which is what
                        // made a whole panel of live agents look like a completed list.
                        if (a.running) {
                            var tick by remember(a.id) { mutableStateOf(0) }
                            LaunchedEffect(a.id) {
                                while (true) {
                                    kotlinx.coroutines.delay(220)
                                    tick++
                                }
                            }
                            Text(
                                "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"[tick % 10].toString(),
                                color = if (a.stopping) Dim else Purple,
                                fontFamily = mono, fontSize = 10.sp
                            )
                        } else {
                            Text(
                                if (a.failed) "✕" else "✓",
                                color = if (a.failed) Red else Green,
                                fontFamily = mono, fontSize = 10.sp
                            )
                        }
                        Text(
                            a.name, color = if (a.running) Text0 else Dim,
                            fontFamily = mono, fontSize = 10.sp, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            if (a.stopping) "stopping" else a.type,
                            color = Dim, fontFamily = mono, fontSize = 9.sp, maxLines = 1
                        )
                        Text(
                            fmtTok(a.tokens), color = Dim, fontFamily = mono, fontSize = 9.sp,
                            maxLines = 1
                        )
                        // Offered only while there is something to stop, and only once, so a
                        // second tap can't read as "it didn't work, try again".
                        if (a.running && !a.stopping) {
                            Text(
                                "stop", color = Red, fontFamily = mono, fontSize = 9.sp,
                                modifier = Modifier.clip(RoundedCornerShape(3.dp))
                                    .clickable { vm.stopSubagent(a) }
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                if (vm.subagents.size > cap) {
                    Text(
                        "+ ${vm.subagents.size - cap} more",
                        color = Dim, fontFamily = mono, fontSize = 9.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

private fun fmtTok(n: Int): String = when {
    n >= 1_000_000 -> {
        val m = n / 1_000_000f
        if (m % 1f == 0f) "${m.toInt()}m" else "%.1fm".format(m)
    }
    n >= 1_000 -> "%.1fk".format(n / 1000f)
    else -> n.toString()
}

/**
 * Paints every occurrence of "ultracode" purple as you type.
 *
 * The offset mapping is the identity because nothing is inserted or removed — only styled — so
 * the cursor and selection stay exactly where the user put them.
 */
private object UltracodeHighlight : androidx.compose.ui.text.input.VisualTransformation {
    private val word = Regex("ultracode", RegexOption.IGNORE_CASE)

    override fun filter(t: androidx.compose.ui.text.AnnotatedString):
        androidx.compose.ui.text.input.TransformedText {
        val b = androidx.compose.ui.text.AnnotatedString.Builder(t.text)
        for (m in word.findAll(t.text)) {
            b.addStyle(
                androidx.compose.ui.text.SpanStyle(
                    color = Purple, fontWeight = FontWeight.Bold
                ),
                m.range.first, m.range.last + 1
            )
        }
        return androidx.compose.ui.text.input.TransformedText(
            b.toAnnotatedString(),
            androidx.compose.ui.text.input.OffsetMapping.Identity
        )
    }
}

/**
 * The tool call the CLI is waiting on.
 *
 * Sits directly above the composer because it is blocking: the turn does not move until it is
 * answered, so it has to be the thing in front of you, not something to scroll back to. What it
 * shows is the tool's actual input — the command, the path, the URL — since "Bash wants to run"
 * is not enough to decide on.
 */
@Composable
private fun AskCard(vm: Session) {
    val ask = vm.asks.firstOrNull() ?: return
    Column(
        Modifier.fillMaxWidth().padding(bottom = 8.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Orange.copy(alpha = .10f))
            .border(1.dp, Orange.copy(alpha = .55f), RoundedCornerShape(6.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("⏸", color = Orange, fontSize = 12.sp)
            Text(
                "${ask.display} needs permission",
                color = Orange, fontFamily = mono, fontSize = 11.sp,
                maxLines = 1, modifier = Modifier.weight(1f)
            )
            if (vm.asks.size > 1) {
                Text(
                    "+${vm.asks.size - 1} more", color = Dim, fontFamily = mono, fontSize = 10.sp,
                    maxLines = 1
                )
            }
        }
        if (ask.summary.isNotBlank()) {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(Ink)
                    .border(1.dp, Line, RoundedCornerShape(4.dp))
                    .horizontalScroll(rememberScrollState())
                    .padding(8.dp)
            ) {
                SelectionContainer {
                    Text(
                        ask.summary, color = Text0, fontFamily = mono, fontSize = 11.sp,
                        lineHeight = 15.sp, maxLines = 6
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
                color = Ink,
                shape = RoundedCornerShape(4.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Red.copy(alpha = .6f)),
                onClick = { vm.answerAsk(ask, allow = false) },
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    "deny", color = Red, fontFamily = mono, fontSize = 12.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                )
            }
            Surface(
                color = Orange,
                shape = RoundedCornerShape(4.dp),
                onClick = { vm.answerAsk(ask, allow = true) },
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    "allow", color = Ink, fontFamily = mono, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                )
            }
        }
        // Only worth offering once there's a queue: a run of similar calls is the case where
        // answering them one at a time is the annoying part.
        if (vm.asks.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "deny all", color = Dim, fontFamily = mono, fontSize = 10.sp,
                    modifier = Modifier.clip(RoundedCornerShape(3.dp))
                        .clickable { vm.answerAllAsks(allow = false) }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
                Text(
                    "allow all ${vm.asks.size}", color = Dim, fontFamily = mono, fontSize = 10.sp,
                    modifier = Modifier.clip(RoundedCornerShape(3.dp))
                        .clickable { vm.answerAllAsks(allow = true) }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun Composer(vm: Session) {
    var text by remember { mutableStateOf("") }
    // The keyword can arrive two ways — the toggle, or typed into the box. Both need to look
    // armed, or the banner lies about what the next prompt is going to do.
    val typedUltra = remember(text) { text.contains("ultracode", ignoreCase = true) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { vm.attachFile(it) }
    }
    // Dictation goes through the system recognizer rather than SpeechRecognizer: the intent form
    // needs no RECORD_AUDIO of our own — the recognizer app holds the microphone — and it gives
    // the familiar listening sheet instead of a bespoke one.
    val ctx = LocalContext.current
    val dictate = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val heard = res.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?: return@rememberLauncherForActivityResult
        val next = if (text.isBlank()) heard else "$text $heard"
        // Hands-free only if it was asked for: recognizers mishear, and a prompt that sends
        // itself leaves no moment to catch it.
        if (vm.autoSendVoice) {
            vm.send(next)
            text = ""
        } else {
            text = next
        }
    }
    val canDictate = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .resolveActivity(ctx.packageManager) != null
    }
    // navigationBarsPadding keeps it clear of the gesture bar; imePadding lifts it only while the
    // keyboard is up. Order matters — insets are consumed outermost first.
    Column(Modifier.background(Panel).navigationBarsPadding().imePadding().padding(10.dp)) {
        if (vm.attachments.isNotEmpty()) {
            Row(
                Modifier.padding(bottom = 8.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                vm.attachments.forEach { f ->
                    Row(
                        Modifier.clip(RoundedCornerShape(4.dp)).background(Ink)
                            .border(1.dp, Line, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "📎 ${f.name}", color = Blue, fontFamily = mono,
                            fontSize = 10.sp, maxLines = 1
                        )
                        Text(
                            "×", color = Dim, fontFamily = mono, fontSize = 12.sp,
                            modifier = Modifier.clickable { vm.removeAttachment(f) }
                        )
                    }
                }
            }
        }
        AskCard(vm)
        SubagentPanel(vm)

        // Ultracode can spawn a lot of agents, so its state is never implicit: while it's on the
        // next prompt is visibly marked, and this is the one-tap way out for a single turn.
        if (vm.ultracode || typedUltra) {
            // A keyword you typed yourself can't be "skipped" — it's your text, and silently
            // stripping it would be worse than leaving it. Only the toggle offers the opt-out.
            val skipping = vm.ultracode && vm.skipUltracodeOnce
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background((if (skipping) Dim else Purple).copy(alpha = .13f))
                    .border(1.dp, (if (skipping) Dim else Purple).copy(alpha = .5f),
                            RoundedCornerShape(4.dp))
                    .then(
                        if (vm.ultracode) Modifier.clickable { vm.skipUltracodeOnce = !skipping }
                        else Modifier
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(if (skipping) "○" else "⏣", color = if (skipping) Dim else Purple, fontSize = 12.sp)
                Text(
                    when {
                        skipping -> "ultracode skipped for this turn - tap to re-arm"
                        !vm.ultracode -> "ultracode keyword in your message - it will run"
                        else -> "next prompt runs ultracode - tap to skip once"
                    },
                    color = if (skipping) Dim else Purple, fontFamily = mono, fontSize = 10.sp,
                    maxLines = 1, modifier = Modifier.weight(1f)
                )
            }
        }
        // A turn that ended badly — interrupted, or a non-success result — usually wants to be
        // tried again verbatim. It's an offer, not a state: × waves it off and leaves the
        // conversation alone.
        if (vm.lastTurnFailed && vm.lastPrompt != null && !vm.busy) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Red.copy(alpha = .12f))
                    .border(1.dp, Red.copy(alpha = .45f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "that turn didn't finish", color = Red, fontFamily = mono, fontSize = 10.sp,
                    maxLines = 1, modifier = Modifier.weight(1f)
                )
                Text(
                    "edit", color = Dim, fontFamily = mono, fontSize = 10.sp,
                    modifier = Modifier.clip(RoundedCornerShape(3.dp))
                        .clickable { vm.takeLastForEdit()?.let { text = it } }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
                Text(
                    "send again", color = Orange, fontFamily = mono, fontSize = 10.sp,
                    modifier = Modifier.clip(RoundedCornerShape(3.dp))
                        .clickable { vm.resendLast() }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
                Text(
                    "×", color = Dim, fontFamily = mono, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(3.dp))
                        .clickable { vm.dismissRetry() }
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
                color = Ink,
                shape = RoundedCornerShape(4.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Line),
                onClick = { pick.launch(arrayOf("*/*")) }
            ) {
                Text(
                    "+", color = Dim, fontFamily = mono, fontSize = 17.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("ask claude…", fontFamily = mono, fontSize = 13.sp, color = Dim) },
                textStyle = androidx.compose.ui.text.TextStyle(color = Text0, fontSize = 13.sp),
                // Typing the keyword yourself is the other way ultracode turns on, and until now
                // that was invisible — you had no way to tell a multi-agent run was about to
                // happen. Colouring the word makes both routes look the same.
                visualTransformation = UltracodeHighlight,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = if (typedUltra) Purple else Orange,
                    unfocusedBorderColor = if (typedUltra) Purple.copy(alpha = .6f) else Line,
                    cursorColor = Orange
                ),
                shape = RoundedCornerShape(4.dp),
                maxLines = 5
            )
            if (canDictate && !vm.busy) {
                Surface(
                    color = Ink,
                    shape = RoundedCornerShape(4.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Line),
                    onClick = {
                        runCatching {
                            dictate.launch(
                                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                    .putExtra(
                                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                                    )
                                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "speak your prompt")
                            )
                        }
                    }
                ) {
                    Text(
                        "🎤", fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }
            if (vm.busy) {
                OutlinedButton(
                    onClick = { vm.interrupt() },
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Red)
                ) { Text("esc", fontFamily = mono, fontSize = 12.sp) }
            } else {
                Button(
                    onClick = { vm.send(text); text = "" },
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Ink)
                ) { Text("↵", fontFamily = mono, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

// ---------------- terminal ----------------

@Composable
private fun TerminalTab(vm: Session) {
    var cmd by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(vm.terminal.size) {
        if (vm.terminal.isNotEmpty()) scope.launch { listState.animateScrollToItem(vm.terminal.size - 1) }
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().background(Ink),
            contentPadding = PaddingValues(12.dp)
        ) {
            if (vm.terminal.isEmpty()) {
                item {
                    Text(
                        "sandbox shell — /system/bin/sh in this app's private workspace.\n" +
                            "try: pwd · ls -la · echo hi > a.txt · cat a.txt · uname -a",
                        color = Dim, fontFamily = mono, fontSize = 11.sp, lineHeight = 16.sp
                    )
                }
            }
            items(vm.terminal) { line ->
                Text(
                    line,
                    color = if (line.startsWith("$ ")) Orange else if (line.startsWith("[exit")) Red else Text0,
                    fontFamily = mono, fontSize = 11.sp, lineHeight = 15.sp
                )
            }
        }
        Row(
            Modifier.background(Panel).navigationBarsPadding().imePadding().padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = cmd,
                onValueChange = { cmd = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("sh command", fontFamily = mono, fontSize = 12.sp, color = Dim) },
                textStyle = androidx.compose.ui.text.TextStyle(color = Text0, fontSize = 12.sp, fontFamily = mono),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Orange, unfocusedBorderColor = Line, cursorColor = Orange
                ),
                shape = RoundedCornerShape(4.dp),
                singleLine = true
            )
            Button(
                onClick = { vm.run(cmd); cmd = "" },
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Ink)
            ) { Text("run", fontFamily = mono, fontSize = 12.sp) }
            OutlinedButton(
                onClick = { vm.clearTerminal() },
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Dim)
            ) { Text("clr", fontFamily = mono, fontSize = 11.sp) }
        }
    }
}

// ---------------- settings ----------------

/**
 * One row on the settings screen: what it's called, what it does, and whatever operates it.
 *
 * [alias] carries words a search should match that the visible text doesn't contain — "mic" for
 * dictation, "root" for the permission modes — so looking for the obvious word finds the setting
 * even when the screen calls it something else.
 */
private class Pref(
    val title: String,
    val blurb: String = "",
    val alias: String = "",
    val control: (@Composable () -> Unit)? = null,
    val below: (@Composable () -> Unit)? = null
) {
    fun matches(q: String): Boolean =
        q.isBlank() || listOf(title, blurb, alias).any { it.contains(q, ignoreCase = true) }
}

private class PrefGroup(val name: String, val prefs: List<Pref>) {
    /** A group whose own name matches keeps all of its rows: you searched for the group. */
    fun filter(q: String): PrefGroup? {
        if (q.isBlank() || name.contains(q, ignoreCase = true)) return this
        val kept = prefs.filter { it.matches(q) }
        return if (kept.isEmpty()) null else PrefGroup(name, kept)
    }
}

/** The pill used for every on/off setting, so they all read the same way. */
@Composable
private fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) {
    Surface(
        color = if (on) Green.copy(alpha = .13f) else Color.Transparent,
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (on) Green.copy(alpha = .6f) else Line),
        onClick = { onChange(!on) }
    ) {
        Text(
            if (on) "on" else "off",
            color = if (on) Green else Dim, fontFamily = mono, fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
        )
    }
}

/** A full-width action. [accent] marks the one thing a section is mainly for. */
@Composable
private fun Action(
    label: String,
    accent: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (accent) Orange else Ink,
            contentColor = if (accent) Ink else Orange,
            disabledContainerColor = Ink,
            disabledContentColor = Dim
        )
    ) { Text(label, fontFamily = mono, fontSize = 12.sp, maxLines = 1) }
}

/**
 * What each of the CLI's permission modes actually does, in the order the CLI lists them by how
 * much they let through. Kept beside the picker rather than in the CLI's help text because the
 * choice is meaningless without them — "auto" and "dontAsk" sound alike and are opposites.
 */
private val MODE_NOTES = listOf(
    "plan" to "Reads and researches, writes nothing. Comes back with a plan instead of doing it.",
    "manual" to "Asks before every tool call that isn't already allowed by a rule.",
    "acceptEdits" to "File edits go through on their own; everything else asks.",
    "auto" to "The CLI's classifier judges each call, and asks when it isn't confident.",
    "dontAsk" to "Never asks. Anything a rule doesn't already allow is silently declined.",
    "bypassPermissions" to "Allows everything. The workspace is app-private and disposable, " +
        "which is why this is the default here."
)

@Composable
private fun SettingsTab(vm: Session) {
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { vm.importBinary(it) }
    }
    var q by remember { mutableStateOf("") }

    val groups = listOf(
        PrefGroup("account", listOf(
            Pref(
                "signed in as",
                alias = "login logout oauth auth anthropic account",
                control = {
                    Text(vm.authStatus, color = Dim, fontFamily = mono, fontSize = 10.sp, maxLines = 1)
                }
            ),
            Pref(
                "how to sign in",
                "Type /login in chat. It runs the CLI's own OAuth on a real terminal, opens the " +
                    "page in your browser, and takes the code back as a chat message.",
                alias = "login signin sign in oauth"
            )
        )),
        PrefGroup("chat", listOf(
            Pref(
                "notify when a turn ends",
                "Buzzes the phone when a turn finishes — but only while the app is in the " +
                    "background, so replies you're already reading stay silent. Long agent runs " +
                    "are the point: send one, put the phone down, get told when it lands.",
                alias = "notification buzz alert ping background",
                control = { Toggle(vm.notifyOnDone) { vm.notifyOnDone = it } }
            ),
            Pref(
                "send dictation automatically",
                "With this on, speaking into the mic sends the message the moment the recognizer " +
                    "is done — properly hands-free. Off, the words land in the box first so you " +
                    "can fix anything it misheard.",
                alias = "voice mic microphone speech dictate auto send hands free",
                control = { Toggle(vm.autoSendVoice) { vm.autoSendVoice = it } }
            )
        )),
        PrefGroup("permissions", listOf(
            Pref(
                "what the agent may do unasked",
                "The CLI's own modes, from most cautious to least. Changing one starts a fresh " +
                    "session: the mode is fixed when the process launches.",
                alias = "root bypass accept edits sandbox allow permission mode plan manual auto",
                below = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        MODE_NOTES.forEach { (mode, note) ->
                            val on = vm.permissionMode == mode
                            Surface(
                                color = if (on) Orange.copy(alpha = .13f) else Ink,
                                shape = RoundedCornerShape(6.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp, if (on) Orange.copy(alpha = .7f) else Line
                                ),
                                onClick = { vm.choosePermissionMode(mode) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        if (on) "●" else "○",
                                        color = if (on) Orange else Dim, fontSize = 11.sp,
                                        modifier = Modifier.padding(top = 1.dp)
                                    )
                                    Column(
                                        Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            mode, color = if (on) Orange else Text0,
                                            fontFamily = mono, fontSize = 11.sp
                                        )
                                        Text(note, color = Dim, fontSize = 10.sp, lineHeight = 14.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            ),
            Pref(
                "answering prompts on the phone",
                if (vm.promptsSupported)
                    "This build can hand permission prompts to the app, so a mode that asks will " +
                        "put an allow/deny card above the composer and hold the turn until you " +
                        "answer it."
                else
                    "This CLI build can't hand permission prompts to the app, so a mode that asks " +
                        "will simply decline what it can't decide by itself. Update the CLI from " +
                        "the button next to the tabs, then reopen the app.",
                alias = "prompt ask allow deny stdio dialog",
                control = {
                    Text(
                        if (vm.promptsSupported) "supported" else "not available",
                        color = if (vm.promptsSupported) Green else Dim,
                        fontFamily = mono, fontSize = 10.sp, maxLines = 1
                    )
                }
            )
        )),
        PrefGroup("engine", listOf(
            Pref(
                "what is installed",
                alias = "runtime proot musl rootfs status claude binary engine",
                below = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        vm.installs.forEach { i ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    if (i.present) "●" else "○",
                                    color = if (i.present) Green else Dim, fontSize = 11.sp
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(i.label, color = Text0, fontSize = 12.sp, fontFamily = mono)
                                    Text(i.detail, color = Dim, fontSize = 10.sp, lineHeight = 14.sp)
                                }
                            }
                        }
                    }
                }
            ),
            Pref(
                "download claude",
                "proot, its loader, libtalloc and a musl rootfs ship inside this APK and install " +
                    "themselves on first launch. Claude Code is not bundled — it is Anthropic's " +
                    "binary, so this pulls it from npm and it logs in with its own OAuth. No API " +
                    "key is stored here.",
                alias = "engine npm install cli arm64 musl update",
                below = { Action("download claude (arm64 musl)", accent = true) { vm.downloadEngine() } }
            ),
            Pref(
                "shell and tools",
                "bash, git and ripgrep inside the sandbox — what the agent reaches for constantly.",
                alias = "bash git ripgrep apk alpine install posix",
                below = { Action("install shell + tools") { vm.installTools() } }
            ),
            Pref(
                "runtime",
                "Reinstalls the bundled sandbox from scratch, or brings an outside binary into it.",
                alias = "reinstall bootstrap import file binary repair",
                below = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) { Action("reinstall runtime") { vm.bootstrap(force = true) } }
                        Box(Modifier.weight(1f)) { Action("import file") { pickFiles.launch(arrayOf("*/*")) } }
                    }
                }
            )
        )),
        PrefGroup("toolchains", listOf(
            Pref(
                "android build tools",
                "About 250 MB: aapt2, d8, apksigner, ecj and a JDK (Android-native aarch64 " +
                    "builds), Google's android.jar for API 35, Gradle, plus clang/make for native " +
                    "code. Downloaded rather than bundled — Google's SDK terms do not allow " +
                    "shipping it inside this APK, and the NDK proper is x86-64 only, so clang is " +
                    "the ARM stand-in.",
                alias = "sdk gradle ndk aapt d8 apksigner jdk compile build",
                below = {
                    Action(
                        if (vm.buildToolsInstalling) "downloading… watch the log below"
                        else "install android build tools",
                        enabled = !vm.buildToolsInstalling
                    ) { vm.installBuildTools() }
                }
            ),
            Pref(
                "apk tools",
                "Takes APKs apart: apktool for smali and resources, jadx for Java source, dex2jar, " +
                    "plus aapt2/apksigner and a JDK. baksmali and smali are provided by apktool, " +
                    "which is built on them. Around 200 MB, downloaded rather than bundled.",
                alias = "apktool jadx dex2jar decompile smali reverse",
                below = {
                    Action(
                        if (vm.apkToolsInstalling) "downloading… watch the log below"
                        else "install apk tools",
                        enabled = !vm.apkToolsInstalling
                    ) { vm.installApkTools() }
                }
            )
        )),
        PrefGroup("diagnostics", listOf(
            Pref(
                "when something will not start",
                "Runs proot and prints exactly what it said, unfiltered — the first thing worth " +
                    "looking at when the engine will not come up.",
                alias = "diagnose debug log proot output refresh broken error",
                below = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) { Action("diagnose") { vm.diagnose() } }
                            Box(Modifier.weight(1f)) { Action("refresh") { vm.refreshInstalls() } }
                        }
                        if (vm.setupLog.isNotBlank()) {
                            Box(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(Ink)
                                    .border(1.dp, Line, RoundedCornerShape(4.dp)).padding(8.dp)
                            ) {
                                SelectionContainer {
                                    Text(
                                        vm.setupLog, color = Text0, fontFamily = mono,
                                        fontSize = 10.sp, lineHeight = 14.sp
                                    )
                                }
                            }
                        }
                    }
                }
            )
        ))
    )

    val shown = groups.mapNotNull { it.filter(q.trim()) }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = q,
            onValueChange = { q = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            placeholder = { Text("search settings…", fontFamily = mono, fontSize = 12.sp, color = Dim) },
            textStyle = androidx.compose.ui.text.TextStyle(color = Text0, fontSize = 13.sp),
            leadingIcon = { Text("⌕", color = Dim, fontSize = 15.sp) },
            trailingIcon = {
                if (q.isNotBlank()) {
                    Text(
                        "×", color = Dim, fontFamily = mono, fontSize = 15.sp,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { q = "" }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Orange, unfocusedBorderColor = Line, cursorColor = Orange
            ),
            shape = RoundedCornerShape(6.dp),
            singleLine = true
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 14.dp, bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            if (shown.isEmpty()) {
                Text("nothing matches", color = Dim, fontFamily = mono, fontSize = 12.sp)
            }
            shown.forEach { group ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        group.name.uppercase(), color = Dim, fontFamily = mono, fontSize = 10.sp,
                        letterSpacing = 1.5.sp, modifier = Modifier.padding(start = 2.dp)
                    )
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Panel)
                            .border(1.dp, Line, RoundedCornerShape(8.dp))
                    ) {
                        group.prefs.forEachIndexed { idx, pref ->
                            if (idx > 0) HorizontalDivider(color = Line)
                            Column(
                                Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        pref.title, color = Text0, fontFamily = mono,
                                        fontSize = 12.sp, modifier = Modifier.weight(1f)
                                    )
                                    pref.control?.invoke()
                                }
                                if (pref.blurb.isNotBlank()) {
                                    Text(pref.blurb, color = Dim, fontSize = 11.sp, lineHeight = 16.sp)
                                }
                                pref.below?.invoke()
                            }
                        }
                    }
                }
            }
        }
    }
}
