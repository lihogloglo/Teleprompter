package dev.metzook.teleprompter

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Routes hardware volume keys to the prompter while a session is active,
 * so speed can be nudged without looking at the screen (SPEC §5.3).
 */
object VolumeKeyBus {
    @Volatile
    var active: Boolean = false

    private val _events = MutableSharedFlow<Int>(extraBufferCapacity = 16)
    val events: SharedFlow<Int> = _events

    /** direction: +1 for volume-up, -1 for volume-down */
    fun emit(direction: Int) {
        _events.tryEmit(direction)
    }
}
