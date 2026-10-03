package io.shubham0204.smollmandroid.assistant.inference

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import io.shubham0204.smollmandroid.assistant.ipc.IGenerationCallback
import io.shubham0204.smollmandroid.assistant.ipc.IInferenceService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/** App-side handle on the isolated inference process. */
class InferenceClient(private val context: Context) {
    data class ModelInfo(
        val description: String,
        val nParams: Long,
        val sizeBytes: Long,
        val nCtx: Int,
        val serviceUid: Int,
        val serviceIsolated: Boolean,
    )

    sealed class Event {
        data class Piece(val text: String) : Event()

        data class Done(val tokens: Int, val elapsedMs: Long) : Event()
    }

    private var service: IInferenceService? = null
    private var connected = CompletableDeferred<IInferenceService>()
    private var onDeath: (() -> Unit)? = null

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
                val s = IInferenceService.Stub.asInterface(binder)
                service = s
                binder.linkToDeath({ onDeath?.invoke() }, 0)
                connected.complete(s)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                connected = CompletableDeferred()
            }
        }

    fun bind(onProcessDeath: () -> Unit) {
        onDeath = onProcessDeath
        context.bindService(Intent(context, InferenceService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    fun unbind() {
        runCatching { context.unbindService(connection) }
        service = null
    }

    private suspend fun awaitService(): IInferenceService = service ?: withTimeout(10_000) { connected.await() }

    suspend fun load(model: File, nCtx: Int, nThreads: Int): ModelInfo =
        withContext(Dispatchers.IO) {
            val s = awaitService()
            val raw =
                ParcelFileDescriptor.open(model, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    s.load(pfd, nCtx, nThreads)
                }
            val f = raw.split("\n")
            ModelInfo(f[0], f[1].toLong(), f[2].toLong(), f[3].toInt(), f[4].toInt(), f[5].toBoolean())
        }

    /** Streams generated pieces, then one [Event.Done]; fails the flow on a service error. */
    fun generate(prompt: String, grammar: String?, temperature: Float, maxTokens: Int): Flow<Event> =
        callbackFlow {
            val s = awaitService()
            val cb =
                object : IGenerationCallback.Stub() {
                    override fun onPiece(piece: String) {
                        trySend(Event.Piece(piece))
                    }

                    override fun onDone(tokens: Int, elapsedMs: Long) {
                        trySend(Event.Done(tokens, elapsedMs))
                        close()
                    }

                    override fun onError(message: String) {
                        close(IllegalStateException(message))
                    }
                }
            withContext(Dispatchers.IO) { s.generate(prompt, grammar, temperature, maxTokens, cb) }
            awaitClose { }
        }

    suspend fun cancel() = withContext(Dispatchers.IO) { runCatching { service?.cancel() } }
}
