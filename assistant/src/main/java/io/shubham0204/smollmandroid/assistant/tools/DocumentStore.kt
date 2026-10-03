package io.shubham0204.smollmandroid.assistant.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Documents the user granted through ACTION_OPEN_DOCUMENT. Only these URIs are readable by the
 * read_file tool, and only through the persisted read grant; the app requests no storage
 * permission.
 */
class DocumentStore(private val context: Context) {
    data class Doc(val name: String, val uri: Uri, val mime: String)

    private val prefs = context.getSharedPreferences("documents", Context.MODE_PRIVATE)

    fun list(): List<Doc> {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Doc(o.getString("name"), Uri.parse(o.getString("uri")), o.optString("mime"))
        }
    }

    /** Takes the persistable read grant and records the document under a grammar-safe name. */
    fun add(uri: Uri): Doc {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val mime = context.contentResolver.getType(uri).orEmpty()
        require(mime.startsWith("text/") || mime in TEXT_LIKE) { "unsupported document type $mime (text and Markdown only)" }
        val raw =
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "document"
        val existing = list()
        var name = sanitizeName(raw)
        var i = 2
        while (existing.any { it.name == name }) name = sanitizeName(raw) + "-" + i++
        val doc = Doc(name, uri, mime)
        save(existing.filterNot { it.uri == uri } + doc)
        return doc
    }

    fun remove(doc: Doc) {
        runCatching { context.contentResolver.releasePersistableUriPermission(doc.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        save(list().filterNot { it.uri == doc.uri })
    }

    /** Reads at most [MAX_READ_BYTES], decodes UTF-8 strictly, and truncates to [maxChars]. */
    fun read(name: String, maxChars: Int): String {
        val doc = list().firstOrNull { it.name == name } ?: throw IllegalArgumentException("no granted document named $name")
        val bytes =
            context.contentResolver.openInputStream(doc.uri)?.use { input ->
                val buf = ByteArray(MAX_READ_BYTES)
                var n = 0
                while (n < buf.size) {
                    val r = input.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                }
                buf.copyOf(n)
            } ?: throw IllegalStateException("cannot open $name")
        val text =
            try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(trimPartialUtf8(bytes)))
                    .toString()
            } catch (e: CharacterCodingException) {
                throw IllegalArgumentException("$name is not UTF-8 text")
            }
        return if (text.length > maxChars) text.substring(0, maxChars) else text
    }

    private fun save(docs: List<Doc>) {
        val arr = JSONArray()
        docs.forEach { arr.put(JSONObject().put("name", it.name).put("uri", it.uri.toString()).put("mime", it.mime)) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        const val MAX_READ_BYTES = 64 * 1024
        private const val KEY = "docs"
        private val TEXT_LIKE = setOf("application/json", "application/xml", "application/x-yaml")
        val OPEN_MIME_TYPES = arrayOf("text/plain", "text/markdown", "text/x-markdown", "text/*", "application/json")

        /** Names become GBNF string literals, so they keep to a quote- and escape-free alphabet. */
        fun sanitizeName(raw: String): String =
            raw.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().take(48).ifEmpty { "document" }

        // a read cut at MAX_READ_BYTES may split a multi-byte sequence
        private fun trimPartialUtf8(b: ByteArray): ByteArray {
            var end = b.size
            var back = 0
            while (end - back - 1 >= 0 && back < 3 && (b[end - back - 1].toInt() and 0xC0) == 0x80) back++
            if (end - back - 1 >= 0) {
                val lead = b[end - back - 1].toInt() and 0xFF
                val need = when {
                    lead and 0x80 == 0 -> 1
                    lead and 0xE0 == 0xC0 -> 2
                    lead and 0xF0 == 0xE0 -> 3
                    lead and 0xF8 == 0xF0 -> 4
                    else -> 1
                }
                if (need > back + 1) end -= back + 1
            }
            return if (end == b.size) b else b.copyOf(end)
        }
    }
}
