package com.byeonggon.latent

import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import org.robolectric.Robolectric
import java.io.File
import java.io.FileNotFoundException

/**
 * The phone's storage as the system's files provider shows it, for what the app reaches through the library
 * folder it was handed. Like the real one it reads every file, also those shut to the app's own paths: a test
 * shuts a file with [File.setReadable] to stand for one another installation of the app left behind.
 */
internal class SystemFilesProvider : DocumentsProvider() {
    private val volume get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).parentFile!!

    private fun fileOf(documentId: String) = File(volume, documentId.removePrefix("primary:"))

    override fun onCreate() = true

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: arrayOf())

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val file = fileOf(documentId)
        if (!file.exists()) throw FileNotFoundException(documentId)
        val columns = projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID)
        return MatrixCursor(columns).apply { addRow(row(columns, documentId, file)) }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val columns = projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID)
        val folder = fileOf(parentDocumentId)
        if (!folder.isDirectory) throw FileNotFoundException(parentDocumentId)
        return MatrixCursor(columns).apply {
            folder.listFiles()!!.sortedBy { it.name }.forEach { addRow(row(columns, "$parentDocumentId/${it.name}", it)) }
        }
    }

    private fun row(columns: Array<out String>, documentId: String, file: File): Array<Any?> = columns.map {
        when (it) {
            Document.COLUMN_DOCUMENT_ID -> documentId
            Document.COLUMN_DISPLAY_NAME -> file.name
            Document.COLUMN_MIME_TYPE -> if (file.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream"
            Document.COLUMN_LAST_MODIFIED -> file.lastModified()
            Document.COLUMN_SIZE -> file.length()
            else -> null
        }
    }.toTypedArray()

    override fun isChildDocument(parentDocumentId: String, documentId: String) =
        documentId.startsWith(if (parentDocumentId.endsWith(":")) parentDocumentId else "$parentDocumentId/")

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val file = fileOf(documentId)
        val shut = file.exists() && !file.canRead()
        // Opened past the app's paths, and shut to them again: what is open stays readable.
        if (shut) file.setReadable(true)
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
        } finally {
            if (shut) file.setReadable(false)
        }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val file = fileOf(documentId)
        val renamed = File(file.parentFile, displayName)
        if (renamed.exists() || !file.renameTo(renamed)) throw FileNotFoundException(documentId)
        return documentId.substringBeforeLast('/') + "/" + displayName
    }

    override fun deleteDocument(documentId: String) {
        if (!fileOf(documentId).deleteRecursively()) throw FileNotFoundException(documentId)
    }

    companion object {
        /** Puts the provider in place of the system's, for this test. */
        fun install() {
            Robolectric.buildContentProvider(SystemFilesProvider::class.java).create(
                ProviderInfo().apply {
                    authority = "com.android.externalstorage.documents"
                    exported = true
                    grantUriPermissions = true
                    readPermission = android.Manifest.permission.MANAGE_DOCUMENTS
                    writePermission = android.Manifest.permission.MANAGE_DOCUMENTS
                },
            )
        }
    }
}
