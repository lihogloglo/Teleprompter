package dev.metzook.teleprompter.asr

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Downloads and manages ASR model files in the app's private storage.
 * A ".complete" marker guards against partially-downloaded models.
 */
class ModelManager(private val baseDir: File) {

    fun dirFor(model: AsrModel): File = File(baseDir, model.id)

    fun isInstalled(model: AsrModel): Boolean = File(dirFor(model), COMPLETE_MARKER).exists()

    fun installedIds(): Set<String> =
        AsrModels.ALL.filter { isInstalled(it) }.map { it.id }.toSet()

    fun delete(model: AsrModel) {
        dirFor(model).deleteRecursively()
    }

    /**
     * Downloads all model files sequentially. Reports (fileIndex, fileCount, percent)
     * where percent is -1 while the size is unknown. Throws on failure.
     */
    suspend fun download(
        model: AsrModel,
        onProgress: (Int, Int, Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val dir = dirFor(model)
        dir.mkdirs()
        File(dir, COMPLETE_MARKER).delete()
        val count = model.files.size
        model.files.forEachIndexed { index, name ->
            val target = File(dir, name)
            if (target.length() > 0) {
                onProgress(index + 1, count, 100)
                return@forEachIndexed
            }
            val part = File(dir, "$name.part")
            val connection = URL("${model.repoBaseUrl}/$name").openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            try {
                if (connection.responseCode !in 200..299) {
                    error("HTTP ${connection.responseCode} for $name")
                }
                val total = connection.contentLengthLong
                connection.inputStream.use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -2
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            done += n
                            val pct = if (total > 0) ((done * 100) / total).toInt() else -1
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(index + 1, count, pct)
                            }
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            if (!part.renameTo(target)) error("Couldn't finalize $name")
        }
        File(dir, COMPLETE_MARKER).writeText("ok")
    }

    private companion object {
        const val COMPLETE_MARKER = ".complete"
    }
}
