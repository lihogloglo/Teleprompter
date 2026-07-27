package dev.metzook.teleprompter.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Dark-first: a prompter lives on a black background (SPEC §5.1). */
private val PrompterDark = darkColorScheme(
    primary = Color(0xFFFFC24B),
    onPrimary = Color(0xFF201600),
    secondary = Color(0xFF9E8F76),
    onSecondary = Color.Black,
    background = Color.Black,
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF141414),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF232323),
    onSurfaceVariant = Color(0xFFBDBDBD),
)

@Composable
fun TeleprompterTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PrompterDark, content = content)
}
