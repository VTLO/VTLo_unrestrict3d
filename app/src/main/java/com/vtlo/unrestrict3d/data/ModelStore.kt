package com.vtlo.unrestrict3d.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class ModelFile(val name: String, val path: String, val sizeBytes: Long)

/** Keeps imported models in app-private storage (the runtime needs a real file path). */
class ModelStore(private val context: Context) {

    private val dir: File = File(context.filesDir, "models").apply { mkdirs() }
    val modelsDir: File get() = dir

    fun list(): List<ModelFile> =
        dir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { ModelFile(it.name, it.absolutePath, it.length()) }
            ?: emptyList()

    fun delete(path: String) {
        File(path).takeIf { it.parentFile == dir }?.delete()
    }

    fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) return c.getString(i)
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "model.litertlm"
    }

    private fun totalSize(uri: Uri): Long {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.SIZE)
            if (i >= 0 && c.moveToFirst()) return c.getLong(i)
        }
        return -1
    }

    /** Copies the picked file into private storage. [onProgress] gets 0f..1f (or -1f if unknown). */
    suspend fun import(uri: Uri, onProgress: (Float) -> Unit): ModelFile = withContext(Dispatchers.IO) {
        val safeName = displayName(uri).replace(Regex("[^A-Za-z0-9._-]"), "_")
        val total = totalSize(uri)
        val tmp = File(dir, "$safeName.part")
        val out = File(dir, safeName)
        context.contentResolver.openInputStream(uri)!!.use { input ->
            tmp.outputStream().use { output ->
                val buf = ByteArray(1 shl 20)
                var copied = 0L
                var lastReport = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    copied += n
                    if (copied - lastReport > (8L shl 20)) {
                        lastReport = copied
                        onProgress(if (total > 0) copied.toFloat() / total else -1f)
                    }
                }
            }
        }
        if (out.exists()) out.delete()
        tmp.renameTo(out)
        onProgress(1f)
        ModelFile(out.name, out.absolutePath, out.length())
    }
}
