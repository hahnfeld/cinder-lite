package app.cinder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The markdown a chat reply is actually written in.
 *
 * Fenced code never reaches here — it is split out upstream and drawn in its own canvas — so this
 * covers the rest: headings, lists, quotes, tables, rules, emphasis, inline code, links, and maths
 * in both `$…$` and `\(…\)` form. Anything it doesn't recognise falls through as the literal text
 * that was written, which is what the app used to show for all of it.
 */

private sealed interface Block {
    data class Head(val level: Int, val text: String) : Block
    data class Para(val text: String) : Block
    data class Item(val depth: Int, val marker: String, val text: String) : Block
    data class Quote(val text: String) : Block
    data class Table(val header: List<String>, val rows: List<List<String>>) : Block
    data class Math(val latex: String) : Block
    object Rule : Block
}

/** True when a line is a table's `|---|:--:|` separator, which is what makes the row above a header. */
private fun isTableRule(line: String): Boolean {
    val t = line.trim()
    if (!t.startsWith("|")) return false
    return t.trim('|').split("|").all { cell ->
        val c = cell.trim()
        c.isNotEmpty() && c.all { it == '-' || it == ':' }
    }
}

private fun cellsOf(line: String): List<String> =
    line.trim().trim('|').split("|").map { it.trim() }

private fun parseBlocks(src: String): List<Block> {
    val out = mutableListOf<Block>()
    val lines = src.lines()
    val para = StringBuilder()

    fun flush() {
        if (para.isNotEmpty()) {
            out.add(Block.Para(para.toString().trim()))
            para.setLength(0)
        }
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trim()

        // Display maths owns whole lines and may run over several of them.
        val displayOpen = t.startsWith("$$") || t.startsWith("\\[")
        if (displayOpen) {
            flush()
            val closer = if (t.startsWith("$$")) "$$" else "\\]"
            val opener = if (t.startsWith("$$")) "$$" else "\\["
            val body = StringBuilder(t.removePrefix(opener))
            var closed = body.contains(closer)
            while (!closed && i + 1 < lines.size) {
                i++
                body.append("\n").append(lines[i])
                closed = lines[i].contains(closer)
            }
            out.add(Block.Math(body.toString().substringBeforeLast(closer).trim()))
            i++
            continue
        }

        when {
            t.isEmpty() -> flush()

            t.length >= 3 && (t.all { it == '-' } || t.all { it == '*' } || t.all { it == '_' }) -> {
                flush(); out.add(Block.Rule)
            }

            t.startsWith("#") -> {
                flush()
                val level = t.takeWhile { it == '#' }.length
                if (level <= 6 && t.getOrNull(level) == ' ') {
                    out.add(Block.Head(level, t.drop(level).trim()))
                } else {
                    para.appendLine(line)
                }
            }

            t.startsWith(">") -> {
                flush(); out.add(Block.Quote(t.removePrefix(">").trim()))
            }

            // A table is only a table once the separator row confirms it.
            t.startsWith("|") && i + 1 < lines.size && isTableRule(lines[i + 1]) -> {
                flush()
                val header = cellsOf(t)
                val rows = mutableListOf<List<String>>()
                i += 2
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    rows.add(cellsOf(lines[i])); i++
                }
                out.add(Block.Table(header, rows))
                continue
            }

            else -> {
                val indent = line.takeWhile { it == ' ' }.length
                val bullet = Regex("^[-*+] ").find(t)
                val number = Regex("^(\\d{1,3})[.)] ").find(t)
                when {
                    bullet != null -> {
                        flush()
                        out.add(Block.Item(indent / 2, "•", t.drop(2).trim()))
                    }
                    number != null -> {
                        flush()
                        out.add(
                            Block.Item(
                                indent / 2,
                                number.groupValues[1] + ".",
                                t.drop(number.value.length).trim()
                            )
                        )
                    }
                    // Inside a paragraph a newline is a soft wrap, so lines join with a space.
                    else -> {
                        if (para.isNotEmpty()) para.append(" ")
                        para.append(t)
                    }
                }
            }
        }
        i++
    }
    flush()
    return out
}

/**
 * Inline emphasis, code, links and maths.
 *
 * Written as a single left-to-right scan rather than a chain of regexes so that a construct can't
 * be found inside one that already claimed the text — the case that matters being `**` inside a
 * code span, which should stay literal.
 */
private fun inline(src: String, base: TextUnit): AnnotatedString = buildAnnotatedString {
    var i = 0

    fun closes(open: String, from: Int): Int {
        val at = src.indexOf(open, from)
        return if (at < 0) -1 else at
    }

    while (i < src.length) {
        val rest = src.substring(i)
        when {
            // Code first: nothing inside a code span is markup.
            rest.startsWith("`") -> {
                val end = closes("`", i + 1)
                if (end > i) {
                    withStyle(
                        SpanStyle(
                            fontFamily = mono, fontSize = base * 0.92f,
                            color = Blue, background = Panel
                        )
                    ) { append(src.substring(i + 1, end)) }
                    i = end + 1
                } else { append(src[i]); i++ }
            }

            rest.startsWith("**") || rest.startsWith("__") -> {
                val mark = rest.take(2)
                val end = src.indexOf(mark, i + 2)
                if (end > i) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Text0)) {
                        append(inline(src.substring(i + 2, end), base))
                    }
                    i = end + 2
                } else { append(src[i]); i++ }
            }

            rest.startsWith("~~") -> {
                val end = src.indexOf("~~", i + 2)
                if (end > i) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = Dim)) {
                        append(inline(src.substring(i + 2, end), base))
                    }
                    i = end + 2
                } else { append(src[i]); i++ }
            }

            // A lone * or _ is emphasis only when it wraps something on the same line. Bare
            // underscores are common in identifiers, so they only count with no space inside.
            (rest.startsWith("*") || rest.startsWith("_")) && !rest.startsWith("* ") -> {
                val mark = rest.take(1)
                val end = src.indexOf(mark, i + 1)
                val body = if (end > i) src.substring(i + 1, end) else ""
                if (end > i && body.isNotBlank() && !body.contains("\n") &&
                    (mark == "*" || !body.contains(" "))
                ) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(inline(body, base))
                    }
                    i = end + 1
                } else { append(src[i]); i++ }
            }

            rest.startsWith("[") -> {
                val close = src.indexOf("]", i)
                if (close > i && src.getOrNull(close + 1) == '(') {
                    val end = src.indexOf(")", close)
                    if (end > close) {
                        val label = src.substring(i + 1, close)
                        val url = src.substring(close + 2, end)
                        withLink(
                            LinkAnnotation.Url(
                                url,
                                TextLinkStyles(
                                    SpanStyle(color = Blue, textDecoration = TextDecoration.Underline)
                                )
                            )
                        ) { append(label) }
                        i = end + 1
                    } else { append(src[i]); i++ }
                } else { append(src[i]); i++ }
            }

            // \( … \) is unambiguous maths, unlike a dollar sign.
            rest.startsWith("\\(") -> {
                val end = src.indexOf("\\)", i + 2)
                if (end > i) {
                    append(Latex.render(src.substring(i + 2, end), base))
                    i = end + 2
                } else { append(src[i]); i++ }
            }

            rest.startsWith("$") -> {
                val end = src.indexOf("$", i + 1)
                val body = if (end > i) src.substring(i + 1, end) else ""
                // "$5 and $10" is money, not maths. Real inline maths either has no spaces in it
                // or contains a command, so that is the line drawn.
                val looksMath = body.isNotBlank() && !body.contains("\n") &&
                    (body.contains("\\") || !body.contains(" "))
                if (end > i && looksMath) {
                    append(Latex.render(body, base))
                    i = end + 1
                } else { append(src[i]); i++ }
            }

            else -> { append(src[i]); i++ }
        }
    }
}

/** Renders a block of assistant prose as markdown. */
@Composable
fun Markdown(text: String, base: TextUnit = 13.sp, color: Color = Text0) {
    val blocks = remember(text) { parseBlocks(text) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is Block.Head -> SelectionContainer {
                    Text(
                        inline(block.text, base),
                        color = if (block.level <= 2) Orange else Text0,
                        fontSize = when (block.level) {
                            1 -> base * 1.35f
                            2 -> base * 1.2f
                            3 -> base * 1.08f
                            else -> base
                        },
                        fontWeight = FontWeight.Bold,
                        lineHeight = base * 1.6f,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                is Block.Para -> SelectionContainer {
                    Text(inline(block.text, base), color = color, fontSize = base, lineHeight = base * 1.55f)
                }

                is Block.Item -> Row(
                    Modifier.padding(start = (block.depth * 14).dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        block.marker, color = Orange, fontSize = base,
                        fontFamily = mono, lineHeight = base * 1.55f
                    )
                    SelectionContainer {
                        Text(
                            inline(block.text, base), color = color, fontSize = base,
                            lineHeight = base * 1.55f
                        )
                    }
                }

                is Block.Quote -> Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(
                        Modifier.width(2.dp).clip(RoundedCornerShape(1.dp)).background(Line)
                    ) { Text("", fontSize = base, lineHeight = base * 1.55f) }
                    SelectionContainer {
                        Text(
                            inline(block.text, base), color = Dim, fontSize = base,
                            fontStyle = FontStyle.Italic, lineHeight = base * 1.55f
                        )
                    }
                }

                is Block.Rule -> HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 2.dp))

                // Maths gets its own line and its own ground so it reads as a formula rather than
                // as a sentence that happens to contain symbols.
                is Block.Math -> Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                ) {
                    SelectionContainer {
                        Text(
                            Latex.render(block.latex, base * 1.1f),
                            color = Text0, fontSize = base * 1.1f, lineHeight = base * 1.9f,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }

                is Block.Table -> MarkdownTable(block, base)
            }
        }
    }
}

/**
 * A table, scrolled sideways as one piece.
 *
 * Column widths can't be measured before layout without a full measuring pass, so each column is
 * given a share of a fixed width. A phone has no room to be cleverer than that, and scrolling the
 * whole grid keeps rows aligned with their header — which is the thing a table is for.
 */
@Composable
private fun MarkdownTable(table: Block.Table, base: TextUnit) {
    val widths = remember(table) {
        table.header.indices.map { col ->
            val longest = (listOf(table.header) + table.rows)
                .maxOfOrNull { it.getOrNull(col)?.length ?: 0 } ?: 0
            (longest.coerceIn(6, 40) * 7).dp
        }
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(vertical = 2.dp)
    ) {
        Column(
            Modifier.clip(RoundedCornerShape(6.dp)).background(Panel)
                .border(1.dp, Line, RoundedCornerShape(6.dp))
        ) {
            Row(Modifier.padding(PaddingValues(horizontal = 4.dp, vertical = 6.dp))) {
                table.header.forEachIndexed { col, cell ->
                    Text(
                        inline(cell, base * 0.92f),
                        color = Orange, fontSize = base * 0.92f, fontWeight = FontWeight.Bold,
                        lineHeight = base * 1.4f,
                        modifier = Modifier.width(widths[col]).padding(horizontal = 6.dp)
                    )
                }
            }
            HorizontalDivider(color = Line)
            table.rows.forEachIndexed { idx, row ->
                if (idx > 0) HorizontalDivider(color = Line.copy(alpha = .5f))
                Row(Modifier.padding(PaddingValues(horizontal = 4.dp, vertical = 6.dp))) {
                    widths.indices.forEach { col ->
                        Text(
                            inline(row.getOrNull(col) ?: "", base * 0.92f),
                            color = Text0, fontSize = base * 0.92f, lineHeight = base * 1.4f,
                            modifier = Modifier.width(widths[col]).padding(horizontal = 6.dp)
                        )
                    }
                }
            }
        }
    }
}
