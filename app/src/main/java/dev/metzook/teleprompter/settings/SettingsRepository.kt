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
}
