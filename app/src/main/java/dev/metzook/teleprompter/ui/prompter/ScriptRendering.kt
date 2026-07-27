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
import dev.metzook.teleprompter.vault.toDisplaySegments

private val StageColor = Color(0xFF8F8F8F)
private val AccentColor = Color(0xFFFFC24B)
private val HighlightBackground = Color(0x40FFC24B)

/**
 * Maps the parsed script to prompter-styled text (rendering rules in SPEC §4.2).
 * Walks [toDisplaySegments] so the produced string is char-for-char identical
 * to the one follow/'s ScriptIndex indexes.
 */
fun ParsedScript.toAnnotatedString(): AnnotatedString = buildAnnotatedString {
    toDisplaySegments().forEach { seg ->
        when (seg.kind) {
            ParaKind.RULE -> withStyle(SpanStyle(color = StageColor)) { append(seg.text) }
            ParaKind.HEADING -> {
                val size = (1.45f - 0.08f * (seg.level - 1).coerceIn(0, 5)).em
                withStyle(
                    SpanStyle(fontSize = size, fontWeight = FontWeight.Bold, color = AccentColor),
                ) {
                    append(seg.text)
                }
            }
            ParaKind.BODY -> withStyle(
                SpanStyle(
                    fontWeight = if (seg.bold) FontWeight.Bold else null,
                    fontStyle = if (seg.italic) FontStyle.Italic else null,
                    color = if (seg.stage) StageColor else Color.Unspecified,
                    background = if (seg.highlight) HighlightBackground else Color.Unspecified,
                ),
            ) {
                append(seg.text)
            }
        }
    }
}
