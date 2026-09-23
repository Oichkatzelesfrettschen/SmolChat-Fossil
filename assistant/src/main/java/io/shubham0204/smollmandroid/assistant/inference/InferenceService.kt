package io.shubham0204.smollmandroid.assistant.inference

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import io.shubham0204.smollm.SandboxedLM
import io.shubham0204.smollmandroid.assistant.ipc.IGenerationCallback
import io.shubham0204.smollmandroid.assistant.ipc.IInferenceService
import java.util.concurrent.Executors

/**
 * Runs in the `:inference` isolatedProcess. Its inputs are a model descriptor and prompt text;
 * its only output is generated text on [IGenerationCallback]. It reads no files, holds no
 * permissions, and cannot create sockets (isolated_app SELinux domain).
 */
class InferenceService : Service() {
    // one thread owns the model; load, generate, and unload are serialized on it
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "llama-worker") }

    @Volatile private var lm: SandboxedLM? = null

    private val binder =
        object : IInferenceService.Stub() {
            override fun load(model: ParcelFileDescriptor, nCtx: Int, nThreads: Int): String {
                enforceCaller()
                return worker.submit<String> {
                    model.use { pfd ->
                        lm?.close()
                        lm = null
                        val loaded = SandboxedLM.load(pfd, nCtx, nThreads)
                        lm = loaded
                        val info = loaded.info()
                        val uid = Process.myUid()
                        listOf(info.description, info.nParams, info.sizeBytes, info.nCtx, uid, isIsolatedUid(uid))
                            .joinToString("\n")
                    }
                }.get()
            }

            override fun generate(
                prompt: String,
                grammar: String?,
                temperature: Float,
                maxTokens: Int,
                cb: IGenerationCallback,
            ) {
                enforceCaller()
                worker.execute {
                    val model = lm
                    if (model == null) {
                        runCatching { cb.onError("no model loaded") }
                        return@execute
                    }
                    val start = SystemClock.elapsedRealtime()
                    try {
                        val n =
                            model.generate(prompt, grammar, temperature, MIN_P, maxTokens) { piece ->
                                try {
                                    cb.onPiece(piece)
                                    true
                                } catch (e: RemoteException) {
                                    false // app process went away
                                }
                            }
                        cb.onDone(n, SystemClock.elapsedRealtime() - start)
                    } catch (e: RemoteException) {
                        // app process went away
                    } catch (e: Exception) {
                        runCatching { cb.onError("${e.javaClass.simpleName}: ${e.message}") }
                    }
                }
            }

            override fun cancel() {
                enforceCaller()
                lm?.cancel()
            }

            override fun unload() {
                enforceCaller()
                worker.submit {
                    lm?.close()
                    lm = null
                }.get()
            }
        }

    // The service is not exported, so only this package's UID can bind it;
    // the check keeps that true if the manifest ever changes.
    private fun enforceCaller() {
        if (Binder.getCallingUid() != applicationInfo.uid) throw SecurityException("caller is not the assistant app")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        worker.execute {
            lm?.close()
            lm = null
        }
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val MIN_P = 0.05f

        // Process.isIsolated() is API 28; the app-id ranges are fixed in Process.java:
        // FIRST_APP_ZYGOTE_ISOLATED_UID 90000 .. LAST_ISOLATED_UID 99999
        fun isIsolatedUid(uid: Int): Boolean = (uid % 100_000) in 90_000..99_999
    }
}
