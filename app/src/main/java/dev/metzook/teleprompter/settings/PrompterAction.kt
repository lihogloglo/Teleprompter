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
}

/** The mappable inputs. Single tap (play/pause) and pinch (text size) are fixed idioms. */
enum class GestureSlot(val label: String, val default: PrompterAction) {
    DOUBLE_TAP("Double tap", PrompterAction.JUMP_BACK),
    LONG_PRESS("Long press", PrompterAction.RESTART),
    VOLUME_UP("Volume up", PrompterAction.SPEED_UP),
    VOLUME_DOWN("Volume down", PrompterAction.SPEED_DOWN),
}
