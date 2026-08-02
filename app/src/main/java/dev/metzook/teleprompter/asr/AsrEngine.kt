package dev.metzook.teleprompter.asr

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import dev.metzook.teleprompter.follow.TimedWord
import dev.metzook.teleprompter.follow.mergeBpeTokens
import java.io.File
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class AsrState(
    val listening: Boolean = false,
    val partial: String = "",
    val lastFinal: String = "",
    /** EMA of decode time per 100 ms chunk; sustained values near 100 mean the
     *  decoder can't keep up (thermal throttling) and words are being lost. */
    val decodeMs: Int = 0,
    val error: String? = null,
)

/**
 * Word-level stream for the matcher: growing partials per utterance, then a
 * final at the endpoint. Word timestamps are utterance-relative — the matcher
 * only compares times within one utterance.
 */
sealed class AsrEvent {
    abstract val utterance: Int
    abstract val words: List<TimedWord>

    data class Partial(override val utterance: Int, override val words: List<TimedWord>) : AsrEvent()
    data class Final(override val utterance: Int, override val words: List<TimedWord>) : AsrEvent()
}

/**
 * Mic → streaming recognizer loop (SPEC §3.1). Runs on its own thread; audio
 * is processed on-device and discarded. VOICE_RECOGNITION source keeps AGC
 * off, which §6.1's distance estimation will also need.
 *
 * Long-session reliability (docs/voice-follow-research.md §c — this is where
 * "follow stops working after a while" lived):
 *  - A dead [AudioRecord] (audio server restart, another app grabbing the mic,
 *    OEM power management) is detected by a negative `read()` and the record
 *    is torn down and reopened with backoff, instead of spinning silently on
 *    an error code while the overlay still claims to be listening.
 *  - sherpa-onnx ≥ 1.10 deliberately does *not* reset zipformer encoder
 *    states at endpoints (k2-fsa/sherpa-onnx#1700), so recognition decays
 *    over a long session. The stream is therefore rebuilt — not just reset —
 *    after every silence-only segment and every few utterances.
 *  - Decode lag is tracked as an EMA and surfaced via [AsrState.decodeMs]:
 *    if it approaches the 100 ms chunk budget, the mic buffer overruns and
 *    the OS silently drops audio.
 */
class AsrEngine(
    private val modelDir: File,
    private val model: AsrModel,
) {
    private val _state = MutableStateFlow(AsrState())
    val state: StateFlow<AsrState> = _state

    // Partials are idempotent (each carries the utterance's full word list),
    // but a dropped Final loses that utterance's tail words forever — so the
    // buffer is sized to make drops practically impossible; if the collector
    // is minutes behind, follow is already dead for other reasons.
    private val _events = MutableSharedFlow<AsrEvent>(
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<AsrEvent> = _events

    @Volatile
    private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ run() }, "asr-engine").also { it.start() }
    }

    fun stop() {
        running = false
        thread = null
    }

    private fun run() {
        // stop() doesn't join: a replacement engine may already be spinning up
        // while this one's loop still holds the mic. Serialize ownership so the
        // new AudioRecord can't race the old one into "Microphone unavailable".
        synchronized(MIC) {
            if (!running) return // stopped while waiting for the mic
            capture()
        }
    }

    @SuppressLint("MissingPermission") // caller ensures RECORD_AUDIO
    private fun openRecord(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, SAMPLE_RATE),
            )
        } catch (t: Throwable) {
            null
        }
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            return null
        }
        return try {
            record.startRecording()
            // Some OEMs hand out a record that silently refuses to start.
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                record.release()
                null
            } else record
        } catch (t: Throwable) {
            record.release()
            null
        }
    }

    private fun capture() {
        val recognizer = try {
            OnlineRecognizer(
                assetManager = null,
                config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = File(modelDir, model.encoder).absolutePath,
                            decoder = File(modelDir, model.decoder).absolutePath,
                            joiner = File(modelDir, model.joiner).absolutePath,
                        ),
                        tokens = File(modelDir, model.tokens).absolutePath,
                        numThreads = 4,
                        provider = "cpu",
                        modelType = model.modelType,
                    ),
                    // rule2 finalizes a segment after a short reading pause
                    // (0.7 s — natural pauses commit words quickly); rule1
                    // marks silence-only segments; rule3 bounds an utterance
                    // during continuous reading so the stream never runs
                    // unreset for minutes.
                    endpointConfig = EndpointConfig(
                        rule1 = EndpointRule(false, 2.4f, 0f),
                        rule2 = EndpointRule(true, 0.7f, 0f),
                        rule3 = EndpointRule(false, 0f, 20f),
                    ),
                    enableEndpoint = true,
                ),
            )
        } catch (t: Throwable) {
            _state.value = AsrState(error = "Model failed to load")
            running = false
            return
        }

        var stream = recognizer.createStream()
        val shorts = ShortArray(CHUNK)
        val floats = FloatArray(CHUNK)
        var lastFinal = ""
        var utterance = 0
        var utterancesSinceRebuild = 0
        var decodeEma = 0f
        var lastEmitted: List<String> = emptyList()

        /** Read/decode until stopped or the record dies; false = record died. */
        fun pump(record: AudioRecord): Boolean {
            while (running) {
                val n = record.read(shorts, 0, CHUNK)
                if (n == 0) continue
                if (n < 0) return false // record is dead; reopen it
                for (i in 0 until n) floats[i] = shorts[i] / 32768f
                val t0 = System.nanoTime()
                stream.acceptWaveform(if (n == CHUNK) floats else floats.copyOf(n), SAMPLE_RATE)
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                val decodeMs = (System.nanoTime() - t0) / 1_000_000f
                decodeEma += 0.1f * (decodeMs - decodeEma)
                val result = recognizer.getResult(stream)
                var partial = result.text
                val words = mergeBpeTokens(result.tokens.asList(), result.timestamps)
                if (recognizer.isEndpoint(stream)) {
                    if (words.isNotEmpty()) _events.tryEmit(AsrEvent.Final(utterance, words))
                    if (partial.isNotBlank()) lastFinal = partial
                    utterance++
                    utterancesSinceRebuild++
                    lastEmitted = emptyList()
                    partial = ""
                    // Rebuild (not just reset) after silence-only segments and
                    // every few utterances: reset() keeps stale encoder states
                    // (sherpa-onnx#1700) and recognition decays across a long
                    // session. A rebuild at an endpoint costs nothing audible.
                    if (words.isEmpty() || utterancesSinceRebuild >= REBUILD_UTTERANCES) {
                        stream.release()
                        stream = recognizer.createStream()
                        utterancesSinceRebuild = 0
                    } else {
                        recognizer.reset(stream)
                    }
                } else if (words.isNotEmpty()) {
                    // Emit only when the hypothesis actually changed: during
                    // pauses the recognizer repeats the same partial 10×/s.
                    val texts = words.map { it.text }
                    if (texts != lastEmitted) {
                        lastEmitted = texts
                        _events.tryEmit(AsrEvent.Partial(utterance, words))
                    }
                }
                _state.value = AsrState(
                    listening = true,
                    partial = partial,
                    lastFinal = lastFinal,
                    decodeMs = decodeEma.toInt(),
                )
            }
            return true
        }

        try {
            var failures = 0
            while (running) {
                val record = openRecord()
                if (record == null) {
                    failures++
                    if (failures >= MIC_RETRIES) {
                        _state.value = AsrState(error = "Microphone unavailable")
                        running = false
                        return
                    }
                    _state.value = _state.value.copy(
                        listening = false,
                        error = "Mic lost — reconnecting…",
                    )
                    Thread.sleep(MIC_RETRY_DELAY_MS)
                    continue
                }
                failures = 0
                _state.value = AsrState(listening = true, lastFinal = lastFinal)
                val cleanStop = try {
                    pump(record)
                } finally {
                    runCatching { record.stop() }
                    record.release()
                }
                if (!cleanStop && running) {
                    // Mic died mid-session (audio server restart, app switch):
                    // say so, back off briefly, reopen. Never spin silently.
                    _state.value = _state.value.copy(
                        listening = false,
                        error = "Mic lost — reconnecting…",
                    )
                    Thread.sleep(MIC_RETRY_DELAY_MS)
                }
            }
        } catch (t: InterruptedException) {
            // stop() while sleeping between retries: fall through to cleanup.
        } finally {
            stream.release()
            recognizer.release()
            _state.value = AsrState()
        }
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHUNK = 1600 // 100 ms of audio per decode step

        /** Consecutive failed mic opens before giving up for the session. */
        private const val MIC_RETRIES = 8
        private const val MIC_RETRY_DELAY_MS = 300L

        /** Utterances between stream rebuilds (sherpa-onnx#1700 mitigation). */
        private const val REBUILD_UTTERANCES = 6

        /** One mic owner across all engine instances (see [run]). */
        private val MIC = Any()
    }
}
