package io.shubham0204.smollm

import android.os.ParcelFileDescriptor

/**
 * llama.cpp binding for a process without filesystem access.
 *
 * The model is read from an open [ParcelFileDescriptor]; the caller formats the complete prompt
 * (chat template included) and may constrain decoding with a GBNF grammar per request. One
 * instance serves one model; [generate] calls must not overlap.
 */
class SandboxedLM private constructor(private var handle: Long) : AutoCloseable {
    fun interface PieceSink {
        /** Receives one UTF-8-complete piece; returns false to stop generation. */
        fun onPiece(piece: String): Boolean
    }

    data class Info(val description: String, val nParams: Long, val sizeBytes: Long, val nCtx: Int)

    companion object {
        init {
            System.loadLibrary("smollm")
        }

        /** Loads a GGUF from [model]; the descriptor stays owned by the caller. */
        fun load(model: ParcelFileDescriptor, nCtx: Int, nThreads: Int, useMmap: Boolean = true): SandboxedLM =
            SandboxedLM(nativeLoadStatic(model.fd, nCtx, nThreads, useMmap))

        private fun nativeLoadStatic(fd: Int, nCtx: Int, nThreads: Int, useMmap: Boolean): Long =
            SandboxedLM(0).nativeLoad(fd, nCtx, nThreads, useMmap)
    }

    fun info(): Info {
        val lines = nativeDescribe(checkHandle()).split("\n")
        return Info(lines[0], lines[1].toLong(), lines[2].toLong(), lines[3].toInt())
    }

    /**
     * Decodes [prompt] from an empty KV cache. [grammar] is GBNF text with a `root` rule, or null
     * for unconstrained text. Returns the number of generated tokens.
     *
     * @throws IllegalArgumentException when the grammar does not parse.
     */
    fun generate(
        prompt: String,
        grammar: String?,
        temperature: Float,
        minP: Float,
        maxTokens: Int,
        sink: PieceSink,
    ): Int {
        val n = nativeGenerate(checkHandle(), prompt, grammar, temperature, minP, maxTokens, sink)
        require(n >= 0) { "GBNF grammar failed to parse" }
        return n
    }

    /** Requests the running [generate] call to stop after its current token. */
    fun cancel() {
        if (handle != 0L) nativeCancel(handle)
    }

    override fun close() {
        if (handle != 0L) {
            nativeFree(handle)
            handle = 0L
        }
    }

    private fun checkHandle(): Long {
        check(handle != 0L) { "model is not loaded" }
        return handle
    }

    private external fun nativeLoad(fd: Int, nCtx: Int, nThreads: Int, useMmap: Boolean): Long

    private external fun nativeDescribe(ptr: Long): String

    private external fun nativeGenerate(
        ptr: Long,
        prompt: String,
        grammar: String?,
        temperature: Float,
        minP: Float,
        maxTokens: Int,
        sink: PieceSink,
    ): Int

    private external fun nativeCancel(ptr: Long)

    private external fun nativeFree(ptr: Long)
}
