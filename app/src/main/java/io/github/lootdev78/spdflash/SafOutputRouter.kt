package io.github.lootdev78.spdflash

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SafOutputRouter(private val context: Context) {
    private val prefs = context.getSharedPreferences("flash_state", Context.MODE_PRIVATE)
    private val lock = Any()

    private val _treeUri = MutableStateFlow(prefs.getString(KEY_TREE_URI, null)?.let(Uri::parse))
    val treeUri: StateFlow<Uri?> = _treeUri.asStateFlow()

    fun setTree(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
        val root = DocumentFile.fromTreeUri(context, uri)
        check(root?.isDirectory == true && root.canWrite()) { "Output directory is not writable" }
        val previous = _treeUri.value
        check(prefs.edit().putString(KEY_TREE_URI, uri.toString()).commit()) { "Could not save the output directory" }
        _treeUri.value = uri
        if (previous != null && previous != uri) release(previous)
    }

    fun clear() {
        val previous = _treeUri.value
        prefs.edit().remove(KEY_TREE_URI).commit()
        _treeUri.value = null
        previous?.let(::release)
    }

    fun hasUsableTree(): Boolean {
        val uri = _treeUri.value ?: return false
        return runCatching {
            DocumentFile.fromTreeUri(context, uri)?.let { it.isDirectory && it.canWrite() } == true
        }.getOrDefault(false)
    }

    fun label(): String {
        val uri = _treeUri.value ?: return "App workspace"
        val root = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
        return when {
            root == null -> "SAF-Zugriff abgelaufen"
            !root.canWrite() -> "SAF directory is not writable"
            else -> root.name?.let { "Direct: $it" } ?: "Direct SAF directory"
        }
    }

    /**
     * Returns a detached POSIX file descriptor owned by native fdopen/fclose.
     * Paths are kept below the selected tree and traversal components are rejected.
     */
    fun openOutputFd(relativePath: String, append: Boolean): Int = synchronized(lock) {
        runCatching {
            val uri = _treeUri.value ?: return@synchronized -1
            val root = DocumentFile.fromTreeUri(context, uri) ?: return@synchronized -1
            if (!root.isDirectory || !root.canWrite()) return@synchronized -1

            val normalized = relativePath.replace('\\', '/').trimStart('/')
            val rawParts = normalized.split('/').filter(String::isNotBlank)
            if (rawParts.isEmpty() || rawParts.any { it == "." || it == ".." }) return@synchronized -1
            val safeParts = rawParts.map(::safeName)

            var directory = root
            safeParts.dropLast(1).forEach { part ->
                val existing = directory.findFile(part)
                directory = when {
                    existing == null -> directory.createDirectory(part)
                    existing.isDirectory -> existing
                    else -> null
                } ?: return@synchronized -1
            }

            val fileName = safeParts.last()
            var document = directory.findFile(fileName)
            if (document?.isDirectory == true) return@synchronized -1
            if (document == null) {
                document = directory.createFile(mimeType(fileName), fileName) ?: return@synchronized -1
            }
            val mode = if (append) "wa" else "rwt"
            context.contentResolver.openFileDescriptor(document.uri, mode)?.detachFd() ?: -1
        }.getOrElse { -1 }
    }

    private fun release(uri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    private fun safeName(value: String): String {
        val sanitized = value.replace(Regex("[\\u0000-\\u001f\\u007f/:*?\"<>|]"), "_").trim()
        return sanitized.take(180).ifBlank { "output.bin" }
    }

    private fun mimeType(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: when (extension) {
            "xml" -> "application/xml"
            "log", "txt", "json" -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    companion object {
        private const val KEY_TREE_URI = "output_tree_uri"
    }
}
