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
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AsrState(
    val listening: Boolean = false,
    val partial: String = "",
    val lastFinal: String = "",
    val decodeMs: Int = 0,
    val error: String? = null,
)

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

    @SuppressLint("MissingPermission") // caller ensures RECORD_AUDIO
    private fun run() {
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
        try {
            while (running) {
                val n = record.read(shorts, 0, CHUNK)
                if (n <= 0) continue
                for (i in 0 until n) floats[i] = shorts[i] / 32768f
                val t0 = System.nanoTime()
                stream.acceptWaveform(if (n == CHUNK) floats else floats.copyOf(n), SAMPLE_RATE)
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                val decodeMs = ((System.nanoTime() - t0) / 1_000_000).toInt()
                var partial = recognizer.getResult(stream).text
                if (recognizer.isEndpoint(stream)) {
                    if (partial.isNotBlank()) lastFinal = partial
                    recognizer.reset(stream)
                    partial = ""
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
    }
}
