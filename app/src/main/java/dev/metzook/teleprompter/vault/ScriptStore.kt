package dev.metzook.teleprompter.vault

import java.io.File

data class ScriptMeta(
    val id: String,
    val title: String,
    val wordCount: Int,
    val modifiedAt: Long,
)

data class Script(
    val id: String,
    val title: String,
    val body: String,
)

/**
 * M0 storage: scripts are plain .md files in the app's private files dir,
 * filename = title. Deliberately the same shape as the Obsidian vault folder
 * that replaces this in M1 — only the directory (and SAF access) will change.
 */
class ScriptStore(private val dir: File) {

    init {
        if (!dir.exists()) {
            dir.mkdirs()
            File(dir, "Welcome.md").writeText(SAMPLE_SCRIPT)
        }
    }

    fun list(): List<ScriptMeta> =
        (dir.listFiles { f -> f.isFile && f.extension == "md" } ?: emptyArray())
            .map { f ->
                ScriptMeta(
                    id = f.name,
                    title = f.nameWithoutExtension,
                    wordCount = countWords(f.readText()),
                    modifiedAt = f.lastModified(),
                )
            }
            .sortedByDescending { it.modifiedAt }

    fun load(id: String): Script? {
        val f = File(dir, id)
        if (!f.isFile) return null
        return Script(id = id, title = f.nameWithoutExtension, body = f.readText())
    }

    /** Saves and returns the (possibly new) id. Renames the file when the title changed. */
    fun save(id: String?, title: String, body: String): String {
        val fallback = body.lineSequence().firstOrNull { it.isNotBlank() }?.take(40).orEmpty()
        val base = sanitize(title.ifBlank { fallback }).ifBlank { "Script" }

        if (id != null) {
            val existing = File(dir, id)
            if (existing.isFile && existing.nameWithoutExtension == base) {
                existing.writeText(body)
                return id
            }
            existing.delete()
        }

        var candidate = File(dir, "$base.md")
        var n = 2
        while (candidate.exists()) {
            candidate = File(dir, "$base ${n++}.md")
        }
        candidate.writeText(body)
        return candidate.name
    }

    fun delete(id: String) {
        File(dir, id).delete()
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(60)

    companion object {
        fun countWords(text: String): Int =
            text.split(Regex("\\s+")).count { it.isNotBlank() }
    }
}

private val SAMPLE_SCRIPT = """
Welcome! This is your teleprompter.

Tap anywhere to start. After a short countdown, the text begins to glide upward. Tap again to pause it.

While paused, sliders let you change the speed and the text size. The volume keys nudge the speed up or down at any moment, even mid-read, so your thumb never has to leave the side of the phone.

The small amber marker on the left is your eyeline: keep your reading there and the words will come to you. The thin bar on the right shows how much of the script remains, and the faint timer in the corner estimates the time left at the current pace.

If you read through a beam-splitter rig, flip the mirror toggles in the pause menu and the whole surface reverses.

Soon, this prompter will listen while you speak. It will follow your pace instead of imposing one, wait for you when you improvise, and catch up the moment you return to the script.

For now, enjoy the classic glide — and break a leg.
""".trim()
