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
 * Mic → streaming recognizer loop (SPEC §3.1, M2 slice). Runs on its own
 * thread; audio is processed on-device and discarded. VOICE_RECOGNITION
 * source keeps AGC off, which §6.1's distance estimation will also need.
 */
class AsrEngine(
    private val modelDir: File,
    private val model: AsrModel,
) {
    private val _state = MutableStateFlow(AsrState())
    val state: StateFlow<AsrState> = _state

    private val _events = MutableSharedFlow<AsrEvent>(
        extraBufferCapacity = 256,
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
                        numThreads = 2,
                        provider = "cpu",
                        modelType = model.modelType,
                    ),
                    endpointConfig = EndpointConfig(
                        rule1 = EndpointRule(false, 2.4f, 0f),
                        rule2 = EndpointRule(true, 1.2f, 0f),
                        rule3 = EndpointRule(false, 0f, 30f),
                    ),
                    enableEndpoint = true,
                ),
            )
        } catch (t: Throwable) {
            _state.value = AsrState(error = "Model failed to load")
            running = false
            return
        }

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
            _state.value = AsrState(error = "Microphone unavailable")
            record?.release()
            recognizer.release()
            running = false
            return
        }

        val stream = recognizer.createStream()
        record.startRecording()
        _state.value = AsrState(listening = true)

        val shorts = ShortArray(CHUNK)
        val floats = FloatArray(CHUNK)
        var lastFinal = ""
        var utterance = 0
        try {
            while (running) {
                val n = record.read(shorts, 0, CHUNK)
                if (n <= 0) continue
                for (i in 0 until n) floats[i] = shorts[i] / 32768f
                val t0 = System.nanoTime()
                stream.acceptWaveform(if (n == CHUNK) floats else floats.copyOf(n), SAMPLE_RATE)
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                val decodeMs = ((System.nanoTime() - t0) / 1_000_000).toInt()
                val result = recognizer.getResult(stream)
                var partial = result.text
                val words = mergeBpeTokens(result.tokens.asList(), result.timestamps)
                if (recognizer.isEndpoint(stream)) {
                    if (words.isNotEmpty()) _events.tryEmit(AsrEvent.Final(utterance, words))
                    if (partial.isNotBlank()) lastFinal = partial
                    utterance++
                    recognizer.reset(stream)
                    partial = ""
                } else if (words.isNotEmpty()) {
                    _events.tryEmit(AsrEvent.Partial(utterance, words))
                }
                _state.value = AsrState(
                    listening = true,
                    partial = partial,
                    lastFinal = lastFinal,
                    decodeMs = decodeMs,
                )
            }
        } finally {
            runCatching { record.stop() }
            record.release()
            stream.release()
            recognizer.release()
            _state.value = AsrState()
        }
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHUNK = 1600 // 100 ms of audio per decode step

        /** One mic owner across all engine instances (see [run]). */
        private val MIC = Any()
    }
}
