package dev.metzook.teleprompter.vault

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * Read-only access to the synced Obsidian vault via SAF (SPEC §4.1).
 * Uses DocumentsContract queries directly — DocumentFile.listFiles is far too
 * slow for a vault-sized tree. Scripts are never written from here.
 *
 * The listing is cached (memory + disk) because the SAF walk costs one
 * ContentResolver IPC round-trip per folder — seconds on a real vault. The
 * library shows [cached] instantly and calls [list] behind it to revalidate,
 * the ebook-reader pattern.
 */
class VaultStore(private val context: Context) {

    data class VaultScript(
        val documentUri: Uri,
        val title: String,
        val modifiedAt: Long,
    )

    private class Listing(
        val treeUri: String,
        val scripts: List<VaultScript>,
        /** 0 when restored from disk: shown instantly but never counts as fresh. */
        val fetchedAt: Long,
    )

    @Volatile
    private var memory: Listing? = null
    private val diskCache = File(context.filesDir, "vault-listing.tsv")

    /** Last known listing for [treeUri] (memory, then disk) — never touches SAF. */
    fun cached(treeUri: Uri): List<VaultScript>? {
        val key = treeUri.toString()
        memory?.let { if (it.treeUri == key) return it.scripts }
        val fromDisk = readDisk(key) ?: return null
        memory = Listing(key, fromDisk, fetchedAt = 0L)
        return fromDisk
    }

    /** True while the last real walk of [treeUri] is younger than [maxAgeMs]. */
    fun isFresh(treeUri: Uri, maxAgeMs: Long): Boolean {
        val m = memory ?: return false
        return m.treeUri == treeUri.toString() &&
            System.currentTimeMillis() - m.fetchedAt < maxAgeMs
    }

    fun list(treeUri: Uri, maxFiles: Int = 500): List<VaultScript> {
        val out = mutableListOf<VaultScript>()
        runCatching {
            walk(treeUri, DocumentsContract.getTreeDocumentId(treeUri), out, depth = 0, maxFiles)
        }
        val sorted = out.sortedByDescending { it.modifiedAt }
        // An empty walk is indistinguishable from a revoked permission — never
        // let it clobber a listing that worked.
        if (sorted.isNotEmpty()) {
            memory = Listing(treeUri.toString(), sorted, System.currentTimeMillis())
            writeDisk(treeUri.toString(), sorted)
        }
        return sorted
    }

    private fun readDisk(treeUri: String): List<VaultScript>? = runCatching {
        val lines = diskCache.readLines()
        if (lines.firstOrNull()?.substringBefore('\t') != treeUri) return null
        lines.drop(1).mapNotNull { line ->
            val parts = line.split('\t', limit = 3)
            if (parts.size < 3) return@mapNotNull null
            VaultScript(
                documentUri = Uri.parse(parts[0]),
                modifiedAt = parts[1].toLongOrNull() ?: 0L,
                title = parts[2],
            )
        }.ifEmpty { null }
    }.getOrNull()

    private fun writeDisk(treeUri: String, scripts: List<VaultScript>) {
        runCatching {
            diskCache.writeText(
                buildString {
                    append(treeUri).append('\n')
                    for (s in scripts) {
                        append(s.documentUri).append('\t')
                            .append(s.modifiedAt).append('\t')
                            .append(s.title).append('\n')
                    }
                },
            )
        }
    }

    private fun walk(
        treeUri: Uri,
        parentDocumentId: String,
        out: MutableList<VaultScript>,
        depth: Int,
        maxFiles: Int,
    ) {
        if (depth > 6 || out.size >= maxFiles) return
        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null,
            null,
            null,
        )?.use { c ->
            while (c.moveToNext() && out.size < maxFiles) {
                val documentId = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2)
                val modified = c.getLong(3)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    // Skip .obsidian, .trash and friends.
                    if (!name.startsWith(".")) {
                        walk(treeUri, documentId, out, depth + 1, maxFiles)
                    }
                } else if (name.endsWith(".md", ignoreCase = true)) {
                    out += VaultScript(
                        documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                        title = name.dropLast(3),
                        modifiedAt = modified,
                    )
                }
            }
        }
    }

    fun load(documentUri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(documentUri)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()
}
