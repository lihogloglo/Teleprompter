package dev.metzook.teleprompter.vault

/**
 * The parsed script flattened into display order. Both the prompter's styled
 * text and follow/'s spoken-token index are built by walking this exact list,
 * so the matcher's char offsets always line up with the rendered string.
 */
data class DisplaySegment(
    val text: String,
    /** Matched against speech? Headings, stage directions, rules, separators: no. */
    val spoken: Boolean,
    val kind: ParaKind,
    val level: Int = 0,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val highlight: Boolean = false,
    val stage: Boolean = false,
)

fun ParsedScript.toDisplaySegments(): List<DisplaySegment> {
    val out = ArrayList<DisplaySegment>()
    paragraphs.forEachIndexed { index, p ->
        if (index > 0) {
            // Separators render unstyled: BODY kind with no flags.
            out += DisplaySegment("\n\n", spoken = false, kind = ParaKind.BODY)
        }
        when (p.kind) {
            ParaKind.RULE -> out += DisplaySegment("· · ·", spoken = false, kind = p.kind)
            ParaKind.HEADING -> p.inlines.forEach {
                out += DisplaySegment(it.text, spoken = false, kind = p.kind, level = p.level)
            }
            ParaKind.BODY -> p.inlines.forEach {
                out += DisplaySegment(
                    text = it.text,
                    spoken = !it.stage,
                    kind = p.kind,
                    bold = it.bold,
                    italic = it.italic,
                    highlight = it.highlight,
                    stage = it.stage,
                )
            }
        }
    }
    return out
}
