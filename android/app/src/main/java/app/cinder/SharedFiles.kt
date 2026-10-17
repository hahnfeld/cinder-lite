package app.cinder

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * Hands a sandbox file to the app the user picks (xdg-open on a file). Other apps can't read the
 * app's storage, and a file:// link reaches almost none of them: browsers only open content://.
 * Not exported, so an app reads only the one content:// URI it was granted, and only reads it.
 */
class SharedFiles : ContentProvider() {

    override fun onCreate() = true

    override fun getType(uri: Uri) = file(uri)?.let(::type)

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read-only")
        val file = file(uri) ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** The name and size, which apps show (and some insist on) before they read the file. */
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Cursor? {
        val file = file(uri) ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { when (it) { OpenableColumns.DISPLAY_NAME -> file.name; OpenableColumns.SIZE -> file.length(); else -> null } })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0
    override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0

    /** The URI's file, checked again now: the sandbox could have swapped a link in since it was handed out. */
    private fun file(uri: Uri): File? = uri.path?.let(::File)?.let { shareable(context ?: return null, it) }

    companion object {
        /** For a file [shareable] returned: its canonical path, so what's served is what was checked. */
        fun uri(ctx: Context, file: File): Uri =
            Uri.Builder().scheme("content").authority("${ctx.packageName}.files").path(file.path).build()

        /**
         * [file] with every link resolved, if it may be handed out: a regular file in the app's storage
         * (the sandbox) or the phone's shared storage, but not a hidden one (logins, keys) or an APK
         * (installing is the user's call, from their Download folder). Judged by where links lead, so
         * a link with an innocent name doesn't hand out what it points at.
         */
        fun shareable(ctx: Context, file: File): File? {
            val real = runCatching { file.canonicalFile }.getOrNull() ?: return null
            val root = listOf(ctx.filesDir, Environment.getExternalStorageDirectory()).map { it.canonicalFile }
                .find { real.startsWith(it) } ?: return null
            val hidden = real.relativeTo(root).path.split('/').any { it.startsWith(".") }
            return real.takeIf { it.isFile && !hidden && !it.name.endsWith(".apk", ignoreCase = true) }
        }

        /** By extension; otherwise plain text unless its start looks binary, so text editors still offer. */
        fun type(file: File): String {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())?.let { return it }
            val start = runCatching { file.inputStream().use { val b = ByteArray(4096); b.copyOf(maxOf(it.read(b), 0)) } }
                .getOrNull() ?: return "application/octet-stream"
            return if (0.toByte() in start) "application/octet-stream" else "text/plain"
        }
    }
}
