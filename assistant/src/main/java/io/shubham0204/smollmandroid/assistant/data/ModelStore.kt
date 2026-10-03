package io.shubham0204.smollmandroid.assistant.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * GGUF models copied into app-private storage. The copy decouples the model from the provider
 * that served it (a SAF descriptor may be an unseekable pipe) and gives the inference process a
 * regular file it can mmap through the descriptor it is handed.
 */
class ModelStore(private val context: Context) {
    val dir: File = File(context.filesDir, "models").apply { mkdirs() }

    fun list(): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf") }.orEmpty().sortedBy { it.name }

    fun import(uri: Uri, onProgress: (Long) -> Unit): File {
        val name =
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "model.gguf"
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").let { if (it.endsWith(".gguf")) it else "$it.gguf" }
        val target = File(dir, safe)
        val partial = File(dir, "$safe.partial")
        context.contentResolver.openInputStream(uri)!!.use { input ->
            val magic = ByteArray(4)
            require(input.read(magic) == 4 && String(magic, Charsets.US_ASCII) == "GGUF") { "$name is not a GGUF file" }
            partial.outputStream().use { out ->
                out.write(magic)
                val buf = ByteArray(1 shl 20)
                var total = 4L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    onProgress(total)
                }
            }
        }
        check(partial.renameTo(target)) { "cannot move ${partial.name} into place" }
        return target
    }

    fun delete(file: File) {
        if (file.parentFile == dir) file.delete()
    }
}
