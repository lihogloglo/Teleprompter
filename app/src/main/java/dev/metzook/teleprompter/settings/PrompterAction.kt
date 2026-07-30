package dev.metzook.teleprompter.settings

/** Everything a gesture or key can trigger during a session (SPEC §5.3). */
enum class PrompterAction(val label: String) {
    NONE("Nothing"),
    PLAY_PAUSE("Play / pause"),
    RESTART("Restart"),
    JUMP_BACK("Jump back"),
    JUMP_FORWARD("Jump forward"),
    SPEED_UP("Speed up"),
    SPEED_DOWN("Speed down"),
    FONT_UP("Bigger text"),
    FONT_DOWN("Smaller text"),
    FRAME("Adjust frame"),
    ROTATE("Rotate screen"),
}

/**
 * The mappable inputs. Single tap (play/pause) and pinch (text size) are fixed
 * idioms. The REMOTE_* slots cover Bluetooth remotes/clickers/keyboards, which
 * present as HID keys: OK/enter/space/play, up/left/page-up, down/right/page-down.
 */
enum class GestureSlot(val label: String, val default: PrompterAction) {
    DOUBLE_TAP("Double tap", PrompterAction.JUMP_BACK),
    // Default NONE on purpose: holding a finger on the text is the natural
    // "grab the moving text" gesture, and a long-press action would fire mid-grab
    // (RESTART here used to teleport readers to the top). Still fully mappable.
    LONG_PRESS("Long press", PrompterAction.NONE),
    VOLUME_UP("Volume up", PrompterAction.SPEED_UP),
    VOLUME_DOWN("Volume down", PrompterAction.SPEED_DOWN),
    REMOTE_CENTER("Remote · OK / play key", PrompterAction.PLAY_PAUSE),
    REMOTE_PREV("Remote · back / page-up key", PrompterAction.JUMP_BACK),
    REMOTE_NEXT("Remote · forward / page-down key", PrompterAction.JUMP_FORWARD),
}
