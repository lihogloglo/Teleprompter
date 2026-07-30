package dev.metzook.teleprompter.ui.prompter

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity

/**
 * Tracks when the reader's hand owns the scroll: a finger resting on the text,
 * or the fling that finger launched. While [busy] the auto-scroll drive must
 * not move the text — the hand always wins (SPEC §5.4). When the interaction
 * settles, the position the reader chose becomes the new truth; [moved] tells
 * the caller whether the text actually went anywhere.
 */
class UserScrollControl {
    var pointerDown by mutableStateOf(false)
    var flinging by mutableStateOf(false)

    /** True once the current interaction scrolled the text; the caller clears it. */
    var moved = false

    val busy: Boolean get() = pointerDown || flinging

    val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            // Programmatic scrolls never dispatch nested-scroll events, so any
            // vertical delta seen here is the user's drag or fling — never the drive.
            if (consumed.y != 0f) moved = true
            return Offset.Zero
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            flinging = true
            return Velocity.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            flinging = false
            return Velocity.Zero
        }
    }
}

/**
 * Remember a [UserScrollControl] plus its watchdog: an interrupted fling (a tap
 * that catches the text mid-flight) can skip `onPostFling`, which would leave
 * [UserScrollControl.flinging] latched and the drive parked forever. Clear it
 * once the scroll has been genuinely idle for a few frames.
 */
@Composable
fun rememberUserScrollControl(scrollState: ScrollState): UserScrollControl {
    val control = remember { UserScrollControl() }
    LaunchedEffect(control.flinging) {
        if (!control.flinging) return@LaunchedEffect
        var idleFrames = 0
        while (idleFrames < 3) {
            withFrameNanos { }
            idleFrames =
                if (!control.pointerDown && !scrollState.isScrollInProgress) idleFrames + 1 else 0
        }
        control.flinging = false
    }
    return control
}

/** Feeds [control]. Install on an ancestor of the scrolling text; consumes nothing. */
fun Modifier.userScrollControl(control: UserScrollControl): Modifier =
    nestedScroll(control.nestedScrollConnection).pointerInput(control) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            control.pointerDown = true
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    if (event.changes.none { it.pressed }) break
                }
            } finally {
                control.pointerDown = false
            }
        }
    }
