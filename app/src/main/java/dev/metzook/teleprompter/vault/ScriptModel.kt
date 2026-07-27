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

/** A heading-delimited section: title + char offset where its content begins. */
data class Section(val title: String, val charStart: Int)

/**
 * Sections for per-section stats (SPEC §5.2.4). A section starts at the first
 * spoken segment after one or more headings; scripts without headings have none.
 */
fun ParsedScript.sections(): List<Section> {
    val out = ArrayList<Section>()
    val title = StringBuilder()
    var haveTitle = false
    var pendingSeparator = false
    var offset = 0
    for (seg in toDisplaySegments()) {
        if (seg.kind == ParaKind.HEADING) {
            // Inlines of one heading concatenate; stacked headings join with " · "
            // (a paragraph separator passed between them).
            if (!haveTitle) {
                title.clear()
                haveTitle = true
            } else if (pendingSeparator) {
                title.append(" · ")
            }
            title.append(seg.text)
            pendingSeparator = false
        } else if (seg.text.isBlank()) {
            if (haveTitle) pendingSeparator = true
        } else if (haveTitle && seg.spoken) {
            out += Section(title.toString().trim(), offset)
            haveTitle = false
        }
        offset += seg.text.length
    }
    return out
}

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
