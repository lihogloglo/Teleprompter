package dev.metzook.teleprompter

import android.view.KeyEvent
import dev.metzook.teleprompter.settings.GestureSlot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Routes hardware keys to the prompter while a session is active (SPEC §5.3).
 * Bluetooth remotes, clickers, page-turners and keyboards all present as HID
 * key events; each maps to a GestureSlot the user can rebind in Settings.
 */
object KeyCommandBus {
    @Volatile
    var active: Boolean = false

    private val _events = MutableSharedFlow<GestureSlot>(extraBufferCapacity = 16)
    val events: SharedFlow<GestureSlot> = _events

    fun emit(slot: GestureSlot) {
        _events.tryEmit(slot)
    }

    fun slotForKeyCode(keyCode: Int): GestureSlot? = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> GestureSlot.VOLUME_UP
        KeyEvent.KEYCODE_VOLUME_DOWN -> GestureSlot.VOLUME_DOWN

        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_HEADSETHOOK,
        -> GestureSlot.REMOTE_CENTER

        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_PAGE_UP,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_MEDIA_REWIND,
        -> GestureSlot.REMOTE_PREV

        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_PAGE_DOWN,
        KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        -> GestureSlot.REMOTE_NEXT

        else -> null
    }
}
