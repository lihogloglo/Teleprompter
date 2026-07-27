package dev.metzook.teleprompter.vault

/**
 * Markdown → prompter model (SPEC §4.2). Pure Kotlin on purpose: this is the
 * first half of M3's ScriptModel (spoken tokens vs. display text), so it must
 * stay free of Android dependencies and fully unit-testable.
 */

data class ScriptOverrides(
    val wpm: Int? = null,
    val fontScale: Float? = null,
    val mirror: Boolean? = null,
    val lang: String? = null,
)

enum class ParaKind { BODY, HEADING, RULE }

data class Inline(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val highlight: Boolean = false,
    /** Stage direction ([bracketed] or %%comment%%): rendered dimmed, never spoken. */
    val stage: Boolean = false,
)

data class Paragraph(
    val kind: ParaKind,
    val level: Int = 0,
    val inlines: List<Inline> = emptyList(),
)

data class ParsedScript(
    val paragraphs: List<Paragraph>,
    val spokenWordCount: Int,
    val overrides: ScriptOverrides,
)

object MarkdownParser {

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val RULE = Regex("^(-{3,}|_{3,}|\\*{3,})$")

    fun parse(raw: String): ParsedScript {
        var lines = raw.replace("\r\n", "\n").replace('\r', '\n').split("\n")
        var overrides = ScriptOverrides()

        if (lines.isNotEmpty() && lines[0].trim() == "---") {
            val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
            if (end >= 0) {
                overrides = parseFrontmatter(lines.subList(1, end + 1))
                lines = lines.drop(end + 2)
            }
        }

        val paragraphs = mutableListOf<Paragraph>()
        val current = StringBuilder()
        var inFence = false

        fun flush() {
            val text = current.toString().trim()
            current.clear()
            if (text.isNotEmpty()) {
                val inlines = parseInlines(text)
                if (inlines.isNotEmpty()) paragraphs += Paragraph(ParaKind.BODY, inlines = inlines)
            }
        }

        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("```") -> {
                    flush()
                    inFence = !inFence
                }
                inFence -> Unit
                trimmed.isEmpty() -> flush()
                RULE.matches(trimmed) -> {
                    flush()
                    paragraphs += Paragraph(ParaKind.RULE)
                }
                HEADING.matches(trimmed) -> {
                    flush()
                    val m = HEADING.find(trimmed)!!
                    paragraphs += Paragraph(
                        ParaKind.HEADING,
                        level = m.groupValues[1].length,
                        inlines = parseInlines(m.groupValues[2]),
                    )
                }
                else -> {
                    if (current.isNotEmpty()) current.append(' ')
                    current.append(trimmed)
                }
            }
        }
        flush()

        val spoken = paragraphs
            .filter { it.kind == ParaKind.BODY }
            .sumOf { p -> p.inlines.filterNot { it.stage }.sumOf { countWords(it.text) } }

        return ParsedScript(paragraphs, spoken, overrides)
    }

    private fun parseFrontmatter(lines: List<String>): ScriptOverrides {
        var overrides = ScriptOverrides()
        for (line in lines) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.take(idx).trim().lowercase()
            val value = line.drop(idx + 1).trim().removeSurrounding("\"")
            overrides = when (key) {
                "wpm" -> overrides.copy(wpm = value.toIntOrNull())
                "font-scale", "fontscale" -> overrides.copy(fontScale = value.toFloatOrNull())
                "mirror" -> overrides.copy(mirror = value.lowercase().toBooleanStrictOrNull())
                "lang" -> overrides.copy(lang = value.ifBlank { null })
                else -> overrides
            }
        }
        return overrides
    }

    /**
     * Inline tokens, earliest-match-wins; ties broken by list order. Links keep
     * their visible text, embeds vanish, comments and [brackets] become stage
     * directions, style markers map to spans.
     */
    private val tokens: List<Pair<Regex, (MatchResult) -> Inline?>> = listOf(
        Regex("%%(.*?)%%") to { m -> stage(m.groupValues[1]) },
        Regex("!\\[\\[[^\\]]*\\]\\]") to { _ -> null },
        Regex("!\\[[^\\]]*\\]\\([^)]*\\)") to { _ -> null },
        Regex("\\[\\[([^\\]|]*)\\|([^\\]]*)\\]\\]") to { m -> plain(m.groupValues[2]) },
        Regex("\\[\\[([^\\]]*)\\]\\]") to { m -> plain(m.groupValues[1]) },
        Regex("\\[([^\\]]*)\\]\\(([^)]*)\\)") to { m -> plain(m.groupValues[1]) },
        Regex("\\[([^\\]]*)\\]") to { m -> stage(m.groupValues[1]) },
        Regex("==([^=]+)==") to { m -> Inline(m.groupValues[1], highlight = true) },
        Regex("\\*\\*([^*]+)\\*\\*") to { m -> Inline(m.groupValues[1], bold = true) },
        Regex("\\*([^*]+)\\*") to { m -> Inline(m.groupValues[1], italic = true) },
        Regex("_([^_]+)_") to { m -> Inline(m.groupValues[1], italic = true) },
        Regex("`([^`]+)`") to { m -> plain(m.groupValues[1]) },
    )

    private fun plain(text: String): Inline? = text.takeIf { it.isNotEmpty() }?.let { Inline(it) }

    private fun stage(text: String): Inline? =
        text.trim().takeIf { it.isNotEmpty() }?.let { Inline(it, italic = true, stage = true) }

    fun parseInlines(text: String): List<Inline> {
        val out = mutableListOf<Inline>()
        var pos = 0
        while (pos < text.length) {
            var best: MatchResult? = null
            var bestHandler: ((MatchResult) -> Inline?)? = null
            for ((re, handler) in tokens) {
                val m = re.find(text, pos) ?: continue
                if (best == null || m.range.first < best.range.first) {
                    best = m
                    bestHandler = handler
                }
            }
            if (best == null) {
                addText(out, text.substring(pos))
                break
            }
            if (best.range.first > pos) addText(out, text.substring(pos, best.range.first))
            bestHandler!!(best)?.let { if (it.text.isNotEmpty()) out += it }
            pos = best.range.last + 1
        }
        return out
    }

    private fun addText(out: MutableList<Inline>, text: String) {
        if (text.isNotEmpty()) out += Inline(text)
    }

    fun countWords(text: String): Int = text.split(Regex("\\s+")).count { it.isNotBlank() }
}
