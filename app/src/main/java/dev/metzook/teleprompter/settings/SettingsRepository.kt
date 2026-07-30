package dev.metzook.teleprompter.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Reading-frame inset per side, as a fraction of the screen — the phone is often
 * bigger than the prompter glass it sits behind, so the text area shrinks to fit
 * what the hood actually shows (SPEC §5.1). 0 = full screen, today's default.
 */
data class FrameMargins(val h: Float = 0f, val v: Float = 0f) {
    val isSet: Boolean get() = h > 0.001f || v > 0.001f

    companion object {
        /** Per side; 0.35 still leaves 30 % of the screen to read in. */
        const val MAX = 0.35f
    }
}

/** Prompter screen orientation; overrides the system rotation lock while prompting. */
enum class ScreenOrientation(val label: String) {
    AUTO("Auto"),
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape"),
}

data class PrompterSettings(
    val wpm: Int = 140,
    val fontSizeSp: Float = 40f,
    val mirrorH: Boolean = false,
    val mirrorV: Boolean = false,
    val countdownSec: Int = 3,
    val pinchResize: Boolean = true,
    val vaultUri: String? = null,
    val asrOverlay: Boolean = true,
    val asrModelId: String = "zipformer-en-20M",
    val followVoice: Boolean = false,
    /** "Go back" voice command mode; mutually exclusive with [followVoice]. */
    val voiceCommands: Boolean = false,
    // Margins are kept per orientation: the same rig crops portrait and
    // landscape by very different fractions.
    val marginsPortrait: FrameMargins = FrameMargins(),
    val marginsLandscape: FrameMargins = FrameMargins(),
    val orientation: ScreenOrientation = ScreenOrientation.AUTO,
    val gestures: Map<GestureSlot, PrompterAction> =
        GestureSlot.entries.associateWith { it.default },
)

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private val keyWpm = intPreferencesKey("wpm")
    private val keyFontSize = floatPreferencesKey("font_size_sp")
    private val keyMirrorH = booleanPreferencesKey("mirror_h")
    private val keyMirrorV = booleanPreferencesKey("mirror_v")
    private val keyCountdown = intPreferencesKey("countdown_sec")
    private val keyPinchResize = booleanPreferencesKey("pinch_resize")
    private val keyVaultUri = stringPreferencesKey("vault_uri")
    private val keyAsrOverlay = booleanPreferencesKey("asr_overlay")
    private val keyAsrModelId = stringPreferencesKey("asr_model_id")
    private val keyFollowVoice = booleanPreferencesKey("follow_voice")
    private val keyVoiceCommands = booleanPreferencesKey("voice_commands")
    private val keyMarginHPortrait = floatPreferencesKey("margin_h_portrait")
    private val keyMarginVPortrait = floatPreferencesKey("margin_v_portrait")
    private val keyMarginHLandscape = floatPreferencesKey("margin_h_landscape")
    private val keyMarginVLandscape = floatPreferencesKey("margin_v_landscape")
    private val keyOrientation = stringPreferencesKey("orientation")

    private fun gestureKey(slot: GestureSlot) =
        stringPreferencesKey("gesture_${slot.name.lowercase()}")

    val settings: Flow<PrompterSettings> = context.dataStore.data.map { p ->
        PrompterSettings(
            wpm = p[keyWpm] ?: 140,
            fontSizeSp = p[keyFontSize] ?: 40f,
            mirrorH = p[keyMirrorH] ?: false,
            mirrorV = p[keyMirrorV] ?: false,
            countdownSec = p[keyCountdown] ?: 3,
            pinchResize = p[keyPinchResize] ?: true,
            vaultUri = p[keyVaultUri],
            asrOverlay = p[keyAsrOverlay] ?: true,
            asrModelId = p[keyAsrModelId] ?: "zipformer-en-20M",
            followVoice = p[keyFollowVoice] ?: false,
            voiceCommands = p[keyVoiceCommands] ?: false,
            marginsPortrait = FrameMargins(
                h = p[keyMarginHPortrait] ?: 0f,
                v = p[keyMarginVPortrait] ?: 0f,
            ),
            marginsLandscape = FrameMargins(
                h = p[keyMarginHLandscape] ?: 0f,
                v = p[keyMarginVLandscape] ?: 0f,
            ),
            orientation = p[keyOrientation]
                ?.let { stored -> ScreenOrientation.entries.firstOrNull { it.name == stored } }
                ?: ScreenOrientation.AUTO,
            gestures = GestureSlot.entries.associateWith { slot ->
                p[gestureKey(slot)]
                    ?.let { stored -> PrompterAction.entries.firstOrNull { it.name == stored } }
                    ?: slot.default
            },
        )
    }

    suspend fun setWpm(value: Int) {
        context.dataStore.edit { it[keyWpm] = value }
    }

    suspend fun setFontSizeSp(value: Float) {
        context.dataStore.edit { it[keyFontSize] = value }
    }

    suspend fun setMirrorH(value: Boolean) {
        context.dataStore.edit { it[keyMirrorH] = value }
    }

    suspend fun setMirrorV(value: Boolean) {
        context.dataStore.edit { it[keyMirrorV] = value }
    }

    suspend fun setCountdownSec(value: Int) {
        context.dataStore.edit { it[keyCountdown] = value }
    }

    suspend fun setPinchResize(value: Boolean) {
        context.dataStore.edit { it[keyPinchResize] = value }
    }

    suspend fun setVaultUri(value: String?) {
        context.dataStore.edit {
            if (value == null) it.remove(keyVaultUri) else it[keyVaultUri] = value
        }
    }

    suspend fun setGesture(slot: GestureSlot, action: PrompterAction) {
        context.dataStore.edit { it[gestureKey(slot)] = action.name }
    }

    suspend fun setAsrOverlay(value: Boolean) {
        context.dataStore.edit { it[keyAsrOverlay] = value }
    }

    suspend fun setAsrModelId(value: String) {
        context.dataStore.edit { it[keyAsrModelId] = value }
    }

    suspend fun setFollowVoice(value: Boolean) {
        context.dataStore.edit { it[keyFollowVoice] = value }
    }

    suspend fun setVoiceCommands(value: Boolean) {
        context.dataStore.edit { it[keyVoiceCommands] = value }
    }

    suspend fun setMargins(landscape: Boolean, value: FrameMargins) {
        val h = value.h.coerceIn(0f, FrameMargins.MAX)
        val v = value.v.coerceIn(0f, FrameMargins.MAX)
        context.dataStore.edit {
            if (landscape) {
                it[keyMarginHLandscape] = h
                it[keyMarginVLandscape] = v
            } else {
                it[keyMarginHPortrait] = h
                it[keyMarginVPortrait] = v
            }
        }
    }

    suspend fun setOrientation(value: ScreenOrientation) {
        context.dataStore.edit { it[keyOrientation] = value.name }
    }
}
