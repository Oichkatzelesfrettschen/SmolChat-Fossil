package io.shubham0204.smollmandroid.websearch

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/**
 * The single search endpoint this package fetches from. The assistant app supplies only the
 * query; scheme, host, and path come from this package's own preferences.
 */
sealed class SearchEndpoint {
    abstract val display: String

    abstract fun requestUrl(query: String): URL

    abstract fun parse(body: String): List<SearchResult>

    /** DuckDuckGo's JavaScript-free HTML endpoint. */
    object DuckDuckGoHtml : SearchEndpoint() {
        override val display = "https://html.duckduckgo.com/html/"

        override fun requestUrl(query: String) =
            URL("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8"))

        private val resultAnchor =
            Regex("""<a[^>]+class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        private val resultSnippet =
            Regex("""class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)

        override fun parse(body: String): List<SearchResult> {
            val anchors = resultAnchor.findAll(body).toList()
            val snippets = resultSnippet.findAll(body).map { stripHtml(it.groupValues[1]) }.toList()
            return anchors.mapIndexed { i, m ->
                SearchResult(
                    title = stripHtml(m.groupValues[2]),
                    url = unwrapDuckDuckGoRedirect(decodeEntities(m.groupValues[1])),
                    snippet = snippets.getOrElse(i) { "" },
                )
            }
        }

        // result links point at //duckduckgo.com/l/?uddg=<encoded target>
        private fun unwrapDuckDuckGoRedirect(href: String): String {
            val uri = Uri.parse(if (href.startsWith("//")) "https:$href" else href)
            return uri.getQueryParameter("uddg") ?: href
        }
    }

    /** A SearXNG instance with the JSON output format enabled (search.formats in settings.yml). */
    data class SearxJson(val baseUrl: String) : SearchEndpoint() {
        init {
            require(baseUrl.startsWith("https://")) { "SearXNG endpoint must be https" }
        }

        override val display = baseUrl

        override fun requestUrl(query: String) =
            URL(baseUrl.trimEnd('/') + "/search?format=json&q=" + URLEncoder.encode(query, "UTF-8"))

        override fun parse(body: String): List<SearchResult> {
            val results = org.json.JSONObject(body).optJSONArray("results") ?: return emptyList()
            return (0 until results.length()).map { i ->
                val r = results.getJSONObject(i)
                SearchResult(
                    title = stripHtml(r.optString("title")),
                    url = r.optString("url"),
                    snippet = stripHtml(r.optString("content")),
                )
            }
        }
    }

    companion object {
        private const val PREFS = "endpoint"
        private const val KEY_KIND = "kind"
        private const val KEY_SEARX = "searx_url"

        fun load(context: Context): SearchEndpoint {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return when (p.getString(KEY_KIND, "ddg")) {
                "searx" -> p.getString(KEY_SEARX, null)?.let { runCatching { SearxJson(it) }.getOrNull() }
                    ?: DuckDuckGoHtml
                else -> DuckDuckGoHtml
            }
        }

        fun saveDuckDuckGo(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_KIND, "ddg").apply()
        }

        fun saveSearx(context: Context, url: String) {
            SearxJson(url) // validates
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_KIND, "searx").putString(KEY_SEARX, url).apply()
        }
    }
}

data class SearchResult(val title: String, val url: String, val snippet: String)

/** Caps that bound what one search can pull in and hand back. */
object FetchLimits {
    const val CONNECT_TIMEOUT_MS = 5_000
    const val READ_TIMEOUT_MS = 8_000
    const val MAX_RESPONSE_BYTES = 512 * 1024
    const val MAX_RESULTS = 5
    const val MAX_OUTPUT_CHARS = 4_000
    const val MAX_QUERY_CHARS = 200
}

fun fetch(endpoint: SearchEndpoint, query: String): String {
    val url = endpoint.requestUrl(query)
    val conn = url.openConnection() as HttpsURLConnection
    try {
        conn.connectTimeout = FetchLimits.CONNECT_TIMEOUT_MS
        conn.readTimeout = FetchLimits.READ_TIMEOUT_MS
        // a redirect would move the request off the configured host
        conn.instanceFollowRedirects = false
        conn.useCaches = false
        conn.setRequestProperty("Accept", "text/html,application/json")
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) sandboxed-assistant")
        val code = conn.responseCode
        check(code == HttpURLConnection.HTTP_OK) { "search endpoint answered HTTP $code" }
        val out = ByteArrayOutputStream()
        conn.inputStream.use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, minOf(n, FetchLimits.MAX_RESPONSE_BYTES - out.size()))
                if (out.size() >= FetchLimits.MAX_RESPONSE_BYTES) break
            }
        }
        val results = endpoint.parse(out.toString("UTF-8")).take(FetchLimits.MAX_RESULTS)
        val text = results.joinToString("\n\n") { "${it.title}\n${it.url}\n${it.snippet}" }
        return if (text.length > FetchLimits.MAX_OUTPUT_CHARS) text.substring(0, FetchLimits.MAX_OUTPUT_CHARS) else text
    } finally {
        conn.disconnect()
    }
}

private val tag = Regex("<[^>]*>")
private val space = Regex("\\s+")

fun stripHtml(s: String): String = decodeEntities(s.replace(tag, " ")).replace(space, " ").trim()

fun decodeEntities(s: String): String =
    s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'").replace("&nbsp;", " ")
