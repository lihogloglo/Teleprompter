package dev.metzook.teleprompter.vault

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Read-only access to the synced Obsidian vault via SAF (SPEC §4.1).
 * Uses DocumentsContract queries directly — DocumentFile.listFiles is far too
 * slow for a vault-sized tree. Scripts are never written from here.
 */
class VaultStore(private val context: Context) {

    data class VaultScript(
        val documentUri: Uri,
        val title: String,
        val modifiedAt: Long,
    )

    fun list(treeUri: Uri, maxFiles: Int = 500): List<VaultScript> {
        val out = mutableListOf<VaultScript>()
        runCatching {
            walk(treeUri, DocumentsContract.getTreeDocumentId(treeUri), out, depth = 0, maxFiles)
        }
        return out.sortedByDescending { it.modifiedAt }
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
