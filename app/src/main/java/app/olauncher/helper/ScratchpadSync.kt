package app.olauncher.helper

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract.*
import androidx.core.net.toUri
import app.olauncher.data.Prefs

/** Mirrors the scratchpad to scratchpad.txt in a user-picked SAF folder (for Syncthing etc). */
object ScratchpadSync {
    private const val NAME = "scratchpad.txt"

    private fun fileUri(context: Context, prefs: Prefs, create: Boolean): Uri? {
        if (prefs.syncFolderUri.isEmpty()) return null
        val tree = prefs.syncFolderUri.toUri()
        val resolver = context.contentResolver
        val parentId = getTreeDocumentId(tree)
        resolver.query(
            buildChildDocumentsUriUsingTree(tree, parentId),
            arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { c ->
            while (c.moveToNext())
                if (c.getString(1) == NAME) return buildDocumentUriUsingTree(tree, c.getString(0))
        }
        return if (create) createDocument(resolver, buildDocumentUriUsingTree(tree, parentId), "text/plain", NAME) else null
    }

    private fun lastModified(context: Context, uri: Uri): Long =
        context.contentResolver.query(uri, arrayOf(Document.COLUMN_LAST_MODIFIED), null, null, null)
            ?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L

    private fun read(context: Context, uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { it.reader().readText() }.orEmpty()

    /** Writes the note if it differs from the file. ponytail: local wins on pause; no merge, add one if conflicts bite. */
    fun write(context: Context, prefs: Prefs, text: String) = runCatching {
        val uri = fileUri(context, prefs, create = true) ?: return@runCatching
        if (read(context, uri) != text)
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
        prefs.syncLastModified = lastModified(context, uri)
    }

    /** Returns the file's text if it was changed externally since we last synced and differs from the note. */
    fun readIfChanged(context: Context, prefs: Prefs): String? = runCatching {
        val uri = fileUri(context, prefs, create = false) ?: return null
        val modified = lastModified(context, uri)
        if (modified <= prefs.syncLastModified) return null
        prefs.syncLastModified = modified
        read(context, uri).takeIf { it != prefs.scratchpadText }
    }.getOrNull()
}
