package io.shubham0204.smollmandroid.websearch

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import io.shubham0204.smollmandroid.assistant.ipc.IWebSearch

/**
 * Bound service guarded by the signature permission in the manifest. It additionally checks that
 * the caller is the assistant package, then performs one HTTPS GET against the configured
 * endpoint per call.
 */
class WebSearchService : Service() {
    private val binder =
        object : IWebSearch.Stub() {
            override fun search(query: String?): String {
                enforceCaller()
                val q = query.orEmpty().replace(Regex("[\\p{Cntrl}]"), " ").trim()
                require(q.isNotEmpty()) { "empty query" }
                require(q.length <= FetchLimits.MAX_QUERY_CHARS) { "query longer than ${FetchLimits.MAX_QUERY_CHARS} chars" }
                return try {
                    fetch(SearchEndpoint.load(this@WebSearchService), q)
                } catch (e: Exception) {
                    // only a few exception types cross Binder; carry the reason in one
                    throw IllegalStateException("${e.javaClass.simpleName}: ${e.message}")
                }
            }

            override fun endpoint(): String {
                enforceCaller()
                return SearchEndpoint.load(this@WebSearchService).display
            }
        }

    private fun enforceCaller() {
        val packages = packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        if (ASSISTANT_PACKAGE !in packages) throw SecurityException("caller is not $ASSISTANT_PACKAGE")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    companion object {
        const val ASSISTANT_PACKAGE = "io.shubham0204.smollmandroid.assistant"
    }
}
