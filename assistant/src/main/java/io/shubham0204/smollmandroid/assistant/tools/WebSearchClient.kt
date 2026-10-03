package io.shubham0204.smollmandroid.assistant.tools

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import io.shubham0204.smollmandroid.assistant.ipc.IWebSearch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Binds the web-search package (a different UID, the only one holding INTERNET) for one call.
 * The binding requires the signature permission WEB_SEARCH.
 */
class WebSearchClient(private val context: Context) {
    private val component = ComponentName(PACKAGE, "$PACKAGE.WebSearchService")

    fun isInstalled(): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    suspend fun <T> withService(block: (IWebSearch) -> T): T {
        check(isInstalled()) { "the web-search app ($PACKAGE) is not installed" }
        val ready = CompletableDeferred<IWebSearch>()
        val conn =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
                    ready.complete(IWebSearch.Stub.asInterface(binder))
                }

                override fun onServiceDisconnected(name: ComponentName?) {}
            }
        val bound = context.bindService(Intent().setComponent(component), conn, Context.BIND_AUTO_CREATE)
        check(bound) { "cannot bind $PACKAGE (not installed, or signed with a different key)" }
        try {
            val svc = withTimeout(5_000) { ready.await() }
            // the web-search package bounds the call itself (connect 5 s, read 8 s)
            return withContext(Dispatchers.IO) { block(svc) }
        } finally {
            context.unbindService(conn)
        }
    }

    suspend fun endpoint(): String = withService { it.endpoint() }

    suspend fun search(query: String): String = withService { it.search(query) }

    companion object {
        const val PACKAGE = "io.shubham0204.smollmandroid.websearch"
    }
}
