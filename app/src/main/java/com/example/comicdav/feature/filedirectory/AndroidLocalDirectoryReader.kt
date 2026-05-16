package com.example.comicdav.feature.filedirectory

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidLocalDirectoryReader(
    private val context: Context,
) : LocalDirectoryReader {
    override fun rootDocumentUri(treeUri: String): String {
        val uri = Uri.parse(treeUri)
        val documentId = DocumentsContract.getTreeDocumentId(uri)
        return DocumentsContract.buildDocumentUriUsingTree(uri, documentId).toString()
    }

    override suspend fun listChildren(documentUri: String): List<FileDirectoryBrowserItem> = withContext(Dispatchers.IO) {
        val uri = Uri.parse(documentUri)
        val documentId = DocumentsContract.getDocumentId(uri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val items = mutableListOf<FileDirectoryBrowserItem>()
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                val name = cursor.stringOrNull(nameColumn) ?: continue
                val mimeType = cursor.stringOrNull(mimeColumn).orEmpty()
                val isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
                if (!isDirectory && !name.endsWith(".cbz", ignoreCase = true) && !name.endsWith(".zip", ignoreCase = true)) {
                    continue
                }
                val childDocumentId = cursor.stringOrNull(idColumn) ?: continue
                val childUri = DocumentsContract.buildDocumentUriUsingTree(uri, childDocumentId)
                items += FileDirectoryBrowserItem(
                    name = name,
                    uri = childUri.toString(),
                    isDirectory = isDirectory,
                    size = cursor.longOrNull(sizeColumn),
                    lastModified = cursor.longOrNull(modifiedColumn),
                )
            }
        }
        items
    }

    private fun android.database.Cursor.stringOrNull(column: Int): String? {
        return if (column >= 0 && !isNull(column)) getString(column) else null
    }

    private fun android.database.Cursor.longOrNull(column: Int): Long? {
        return if (column >= 0 && !isNull(column)) getLong(column) else null
    }
}
