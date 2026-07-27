package dev.metzook.teleprompter.ui.prompter

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.metzook.teleprompter.follow.SessionStats
import kotlin.math.roundToInt

private val Accent = Color(0xFFFFC24B)
private val Dim = Color.White.copy(alpha = 0.55f)

/** End screen (SPEC §5.2.4): what the take looked like, at a glance. */
@Composable
fun SessionSummary(
    stats: SessionStats,
    onDismiss: () -> Unit,
    onExit: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xF7000000))
            // Swallow taps so the prompter underneath doesn't react.
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(horizontal = 32.dp, vertical = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Session", style = MaterialTheme.typography.titleLarge, color = Accent)
            Spacer(Modifier.height(20.dp))

            Row(verticalAlignment = Alignment.Bottom) {
                BigStat(formatDuration(stats.activeMs), "delivering")
                Spacer(Modifier.width(28.dp))
                BigStat("${stats.avgWpm}", "avg wpm")
                Spacer(Modifier.width(28.dp))
                BigStat("${(stats.progress * 100).roundToInt()}%", "of script")
            }

            Spacer(Modifier.height(16.dp))
            Text(
                buildString {
                    append(countLabel(stats.freezes, "improv break"))
                    append(" · ")
                    append(countLabel(stats.retakes, "retake"))
                    if (stats.losts > 0) append(" · lost ${stats.losts}×")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Dim,
            )

            if (stats.sections.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                stats.sections.forEach { s ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            s.title,
                            Modifier.weight(1f),
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${formatDuration(s.activeMs)} · ${s.wpm} wpm",
                            color = Dim,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
            Row {
                TextButton(onClick = onDismiss) {
                    Text("Back to script", color = Color.White.copy(alpha = 0.7f))
                }
                Spacer(Modifier.width(16.dp))
                Button(onClick = onExit) { Text("Done") }
            }
        }
    }
}

@Composable
private fun BigStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineMedium, color = Color.White)
        Text(label, style = MaterialTheme.typography.labelMedium, color = Dim)
    }
}

private fun countLabel(n: Int, noun: String): String =
    if (n == 1) "1 $noun" else "$n ${noun}s"

private fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).toInt().coerceAtLeast(0)
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
