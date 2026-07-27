package dev.metzook.teleprompter.vault

import android.net.Uri

/**
 * Routes script ids to their source. Id format:
 *  - "local/<fileName>"          — app-private ScriptStore, editable
 *  - "vault/<encoded-doc-uri>"   — Obsidian vault via SAF, read-only
 */
class ScriptRepository(
    val local: ScriptStore,
    val vault: VaultStore,
) {
    fun localId(fileName: String) = "$LOCAL/$fileName"

    fun vaultId(documentUri: Uri) = "$VAULT/${Uri.encode(documentUri.toString())}"

    fun isLocal(id: String) = id.startsWith("$LOCAL/")

    fun load(id: String): Script? = when {
        id.startsWith("$LOCAL/") ->
            local.load(id.removePrefix("$LOCAL/"))?.copy(id = id)
        id.startsWith("$VAULT/") -> {
            val uri = Uri.parse(Uri.decode(id.removePrefix("$VAULT/")))
            vault.load(uri)?.let { raw ->
                Script(id = id, title = titleFromDocumentUri(uri), body = raw)
            }
        }
        else -> null
    }

    private fun titleFromDocumentUri(uri: Uri): String =
        (uri.lastPathSegment ?: "Script")
            .substringAfterLast('/')
            .removeSuffix(".md")

    private companion object {
        const val LOCAL = "local"
        const val VAULT = "vault"
    }
}
