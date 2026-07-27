package dev.metzook.teleprompter.ui.prompter

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import dev.metzook.teleprompter.vault.ParaKind
import dev.metzook.teleprompter.vault.ParsedScript

private val StageColor = Color(0xFF8F8F8F)
private val AccentColor = Color(0xFFFFC24B)
private val HighlightBackground = Color(0x40FFC24B)

/** Maps the parsed script to prompter-styled text (rendering rules in SPEC §4.2). */
fun ParsedScript.toAnnotatedString(): AnnotatedString = buildAnnotatedString {
    paragraphs.forEachIndexed { index, p ->
        if (index > 0) append("\n\n")
        when (p.kind) {
            ParaKind.RULE -> withStyle(SpanStyle(color = StageColor)) { append("· · ·") }
            ParaKind.HEADING -> {
                val size = (1.45f - 0.08f * (p.level - 1).coerceIn(0, 5)).em
                withStyle(
                    SpanStyle(fontSize = size, fontWeight = FontWeight.Bold, color = AccentColor),
                ) {
                    p.inlines.forEach { append(it.text) }
                }
            }
            ParaKind.BODY -> p.inlines.forEach { inline ->
                withStyle(
                    SpanStyle(
                        fontWeight = if (inline.bold) FontWeight.Bold else null,
                        fontStyle = if (inline.italic) FontStyle.Italic else null,
                        color = if (inline.stage) StageColor else Color.Unspecified,
                        background = if (inline.highlight) HighlightBackground else Color.Unspecified,
                    ),
                ) {
                    append(inline.text)
                }
            }
        }
    }
}
