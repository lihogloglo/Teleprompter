package dev.metzook.teleprompter.session

import dev.metzook.teleprompter.follow.FollowTrace
import java.io.Closeable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Writes a follow-session trace (see [FollowTrace]) to a file, off the UI
 * thread, flushing per line so a crash or kill loses at most the last event.
 * Trace files live in the app's external files dir (pullable over USB/adb
 * without root) and only the last [KEEP] sessions are kept.
 */
class FollowTraceWriter private constructor(
    val file: File,
    private val startNanos: Long,
) : Closeable {

    private val queue = ArrayBlockingQueue<String>(1024)

    @Volatile
    private var closed = false

    private val thread = Thread({
        file.bufferedWriter().use { out ->
            while (true) {
                val line = queue.poll(500, TimeUnit.MILLISECONDS)
                if (line == null) {
                    if (closed) break
                    continue
                }
                if (line === END) break
                out.write(line)
                out.write("\n")
                out.flush()
            }
        }
    }, "follow-trace").apply { isDaemon = true }

    /** Seconds since the session started; the timestamp for every event. */
    fun elapsed(): Float = (System.nanoTime() - startNanos) / 1_000_000_000f

    /** Never blocks the caller: on backpressure the event is dropped. */
    fun log(event: FollowTrace.Event) {
        if (!closed) queue.offer(FollowTrace.encode(event))
    }

    override fun close() {
        if (closed) return
        closed = true
        queue.offer(END)
        thread.join(1000)
    }

    companion object {
        private const val KEEP = 5
        private val END = String() // identity sentinel

        fun start(dir: File, header: FollowTrace.Event.Header): FollowTraceWriter {
            dir.mkdirs()
            dir.listFiles { f -> f.name.startsWith("follow-") }
                ?.sortedByDescending { it.name }
                ?.drop(KEEP - 1)
                ?.forEach { it.delete() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
            val writer = FollowTraceWriter(File(dir, "follow-$stamp.tsv"), System.nanoTime())
            writer.thread.start()
            writer.log(header)
            return writer
        }

        /** Newest trace file in [dir], if any — for the settings share action. */
        fun latest(dir: File): File? =
            dir.listFiles { f -> f.name.startsWith("follow-") }?.maxByOrNull { it.name }
    }
}
