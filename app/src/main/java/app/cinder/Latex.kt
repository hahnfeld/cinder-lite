package app.cinder

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Turns LaTeX into styled text.
 *
 * This is not a typesetting engine and does not try to be one. Rendering real LaTeX means either
 * shipping KaTeX in a WebView — a browser instance per formula inside a scrolling list — or a
 * layout engine larger than the rest of this app. What a coding assistant actually writes in chat
 * is a much smaller language: named symbols, sub- and superscripts, fractions, roots, and sums
 * with limits. Those are all expressible as a styled string, which costs nothing to draw, selects
 * and copies like the text around it, and matches the surrounding type.
 *
 * The deal is that anything beyond that degrades rather than fails: an unknown command loses its
 * backslash and shows its name, and braces it can't interpret are dropped. Wrong-but-readable
 * beats a red error box in a chat transcript.
 */
object Latex {

    /** Symbols, longest names first so that \subseteq is never matched as \subset. */
    private val SYMBOLS: List<Pair<String, String>> = listOf(
        // greek, lower
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε",
        "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η", "theta" to "θ", "vartheta" to "ϑ",
        "iota" to "ι", "kappa" to "κ", "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ",
        "pi" to "π", "varpi" to "ϖ", "rho" to "ρ", "varrho" to "ϱ", "sigma" to "σ",
        "varsigma" to "ς", "tau" to "τ", "upsilon" to "υ", "phi" to "φ", "varphi" to "ϕ",
        "chi" to "χ", "psi" to "ψ", "omega" to "ω",
        // greek, upper
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ",
        "Pi" to "Π", "Sigma" to "Σ", "Upsilon" to "Υ", "Phi" to "Φ", "Psi" to "Ψ",
        "Omega" to "Ω",
        // relations
        "leqslant" to "≤", "geqslant" to "≥", "leq" to "≤", "geq" to "≥", "le" to "≤",
        "ge" to "≥", "neq" to "≠", "ne" to "≠", "equiv" to "≡", "approx" to "≈",
        "simeq" to "≃", "cong" to "≅", "propto" to "∝", "sim" to "∼", "ll" to "≪",
        "gg" to "≫", "doteq" to "≐",
        // operators
        "times" to "×", "cdot" to "·", "cdots" to "⋯", "ldots" to "…", "dots" to "…",
        "vdots" to "⋮", "ddots" to "⋱", "div" to "÷", "pm" to "±", "mp" to "∓",
        "ast" to "∗", "star" to "⋆", "circ" to "∘", "bullet" to "∙", "oplus" to "⊕",
        "otimes" to "⊗", "odot" to "⊙",
        // sets and logic
        "varnothing" to "∅", "emptyset" to "∅", "subseteq" to "⊆", "supseteq" to "⊇",
        "subset" to "⊂", "supset" to "⊃", "notin" to "∉", "in" to "∈", "ni" to "∋",
        "cup" to "∪", "cap" to "∩", "setminus" to "∖", "forall" to "∀", "exists" to "∃",
        "nexists" to "∄", "neg" to "¬", "lnot" to "¬", "land" to "∧", "lor" to "∨",
        "wedge" to "∧", "vee" to "∨", "therefore" to "∴", "because" to "∵",
        // big operators
        "sum" to "∑", "prod" to "∏", "coprod" to "∐", "int" to "∫", "iint" to "∬",
        "iiint" to "∭", "oint" to "∮", "bigcup" to "⋃", "bigcap" to "⋂",
        // arrows
        "Longrightarrow" to "⟹", "Longleftarrow" to "⟸", "longrightarrow" to "⟶",
        "longleftarrow" to "⟵", "Leftrightarrow" to "⇔", "leftrightarrow" to "↔",
        "Rightarrow" to "⇒", "Leftarrow" to "⇐", "rightarrow" to "→", "leftarrow" to "←",
        "uparrow" to "↑", "downarrow" to "↓", "mapsto" to "↦", "to" to "→", "gets" to "←",
        // misc
        "infty" to "∞", "partial" to "∂", "nabla" to "∇", "surd" to "√", "angle" to "∠",
        "perp" to "⊥", "parallel" to "∥", "hbar" to "ℏ", "ell" to "ℓ", "Re" to "ℜ",
        "Im" to "ℑ", "aleph" to "ℵ", "prime" to "′", "degree" to "°", "deg" to "°",
        "checkmark" to "✓", "dagger" to "†", "quad" to "  ", "qquad" to "    ",
        // named functions keep their names; listed so the backslash is eaten cleanly
        "log" to "log", "ln" to "ln", "exp" to "exp", "sin" to "sin", "cos" to "cos",
        "tan" to "tan", "arcsin" to "arcsin", "arccos" to "arccos", "arctan" to "arctan",
        "sinh" to "sinh", "cosh" to "cosh", "tanh" to "tanh", "min" to "min", "max" to "max",
        "lim" to "lim", "inf" to "inf", "sup" to "sup", "det" to "det", "dim" to "dim",
        "gcd" to "gcd", "arg" to "arg", "bmod" to "mod", "pmod" to "mod", "mod" to "mod"
    ).sortedByDescending { it.first.length }

    /** Blackboard-bold, the one font command worth honouring: ℝ and ℕ carry meaning. */
    private val BLACKBOARD = mapOf(
        'R' to "ℝ", 'N' to "ℕ", 'Z' to "ℤ", 'Q' to "ℚ", 'C' to "ℂ", 'P' to "ℙ",
        'E' to "𝔼", 'F' to "𝔽", 'H' to "ℍ", 'A' to "𝔸", 'B' to "𝔹", 'D' to "𝔻"
    )

    /** Commands whose single argument is set in a different style. */
    private val FONT_COMMANDS = setOf(
        "text", "textrm", "mathrm", "textbf", "mathbf", "textit", "mathit",
        "boldsymbol", "mathsf", "mathtt", "texttt", "operatorname", "mathbb", "mathcal"
    )

    /**
     * Renders [src] as styled text.
     *
     * [base] is the surrounding font size; scripts are set relative to it so a formula in a
     * heading and one in a paragraph both look right.
     */
    fun render(src: String, base: TextUnit = 13.sp): AnnotatedString = buildAnnotatedString {
        Renderer(this, base).run(clean(src))
    }

    /** Strips the wrappers that carry no meaning for a linear rendering. */
    private fun clean(src: String): String {
        var s = src.trim()
        for (junk in listOf(
            "\\left", "\\right", "\\displaystyle", "\\textstyle", "\\limits", "\\nolimits",
            "\\!", "\\,", "\\;", "\\:", "\\ ", "&"
        )) {
            s = s.replace(junk, if (junk == "\\," || junk == "\\;" || junk == "\\:" || junk == "\\ ") " " else "")
        }
        // Environments are announced then laid out row by row; the announcement itself is noise.
        s = ENVIRONMENT.replace(s, "")
        return s.replace("\\\\", "\n").trim()
    }

    private val ENVIRONMENT = Regex("""\\(begin|end)\{[a-zA-Z*]+\}""")

    private class Renderer(val out: AnnotatedString.Builder, val base: TextUnit) {

        fun run(s: String) {
            var i = 0
            while (i < s.length) {
                i = when (val c = s[i]) {
                    '\\' -> command(s, i)
                    '^' -> script(s, i, up = true)
                    '_' -> script(s, i, up = false)
                    '{', '}' -> i + 1          // grouping the linear form doesn't need
                    else -> { out.append(c); i + 1 }
                }
            }
        }

        /** Reads the argument at [i] — a braced group, or the single next character. */
        private fun arg(s: String, i: Int): Pair<String, Int> {
            if (i >= s.length) return "" to i
            if (s[i] != '{') return s[i].toString() to (i + 1)
            var depth = 0
            var k = i
            while (k < s.length) {
                if (s[k] == '{') depth++
                if (s[k] == '}') {
                    depth--
                    if (depth == 0) return s.substring(i + 1, k) to (k + 1)
                }
                k++
            }
            return s.substring(i + 1) to s.length      // unbalanced: take the rest
        }

        private fun command(s: String, at: Int): Int {
            var k = at + 1
            while (k < s.length && s[k].isLetter()) k++
            if (k == at + 1) {
                // An escaped character: \$ \% \{ \& and friends stand for themselves.
                if (k < s.length) out.append(s[k])
                return k + 1
            }
            val name = s.substring(at + 1, k)
            return when {
                name == "frac" || name == "dfrac" || name == "tfrac" -> fraction(s, k)
                name == "sqrt" -> root(s, k)
                name in FONT_COMMANDS -> font(name, s, k)
                else -> {
                    val symbol = SYMBOLS.firstOrNull { it.first == name }?.second
                    // Unknown commands lose the backslash and keep their name: readable, and it
                    // makes the gap obvious rather than swallowing the term entirely.
                    out.append(symbol ?: name)
                    k
                }
            }
        }

        private fun fraction(s: String, at: Int): Int {
            val (num, i) = arg(s, at)
            val (den, j) = arg(s, i)
            // Linear form, bracketed only where dropping the brackets would change what it means.
            part(num)
            out.append("⁄")
            part(den)
            return j
        }

        private fun part(p: String) {
            val brackets = needsBrackets(p)
            if (brackets) out.append("(")
            Renderer(out, base).run(p)
            if (brackets) out.append(")")
        }

        private fun needsBrackets(part: String): Boolean =
            part.any { it in "+-" } && !part.startsWith("-")

        private fun root(s: String, at: Int): Int {
            // \sqrt[3]{x}: the index, when present, sits in brackets before the argument.
            var i = at
            if (i < s.length && s[i] == '[') {
                val close = s.indexOf(']', i)
                if (close > 0) {
                    out.append(superscriptOf(s.substring(i + 1, close)))
                    i = close + 1
                }
            }
            val (body, j) = arg(s, i)
            out.append("√")
            val braces = body.length > 1
            if (braces) out.append("(")
            Renderer(out, base).run(body)
            if (braces) out.append(")")
            return j
        }

        private fun font(name: String, s: String, at: Int): Int {
            val (body, j) = arg(s, at)
            when (name) {
                "mathbb" -> {
                    // Set names read as symbols, not as letters in a different face.
                    body.forEach { out.append(BLACKBOARD[it] ?: it.toString()) }
                    return j
                }
                "textbf", "mathbf", "boldsymbol" ->
                    out.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { Renderer(out, base).run(body) }
                "textit", "mathit" ->
                    out.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { Renderer(out, base).run(body) }
                "mathtt", "texttt" ->
                    out.withStyle(SpanStyle(fontFamily = mono)) { out.append(body) }
                // \text{} is prose inside a formula: it must not be re-parsed as maths.
                "text", "textrm", "operatorname" -> out.append(body)
                else -> Renderer(out, base).run(body)
            }
            return j
        }

        private fun script(s: String, at: Int, up: Boolean): Int {
            val (body, j) = arg(s, at + 1)
            out.withStyle(
                SpanStyle(
                    baselineShift = if (up) BaselineShift.Superscript else BaselineShift.Subscript,
                    fontSize = base * 0.72f
                )
            ) { Renderer(out, base * 0.72f).run(body) }
            return j
        }

        /** Plain-unicode superscript, for the root index where a styled span can't be nested. */
        private fun superscriptOf(s: String): String {
            val map = "0123456789+-=()n" to "⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾ⁿ"
            return s.map { c -> map.first.indexOf(c).let { if (it >= 0) map.second[it] else c } }
                .joinToString("")
        }
    }
}
