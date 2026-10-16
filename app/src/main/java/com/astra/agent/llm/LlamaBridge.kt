package com.astra.agent.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

fun interface TokenCallback { fun onToken(bytes: ByteArray): Boolean }

object LlamaBridge {
    init { System.loadLibrary("astra_llama") }
    external fun nativeLoad(path: String, nCtx: Int, threads: Int): Boolean
    external fun nativeGenerate(prompt: ByteArray, maxTokens: Int, temp: Float, topP: Float, cb: TokenCallback)
    external fun nativeWarmup(prompt: ByteArray)
    external fun nativeStop()
    external fun nativeFree()
}

/** Thin safe wrapper — all native calls are serialised through this object. */
object LlmEngine {
    @Volatile var loadedName: String? = null
    @Volatile private var loading = false

    /**
     * Loads a model. Automatically unloads any previously loaded model first.
     * Returns false if: already loading, OOM, native error, or file missing.
     */
    suspend fun load(file: File, nCtx: Int, threads: Int): Boolean = withContext(Dispatchers.Default) {
        if (loading) return@withContext false
        if (!file.exists() || file.length() == 0L) return@withContext false
        loading = true
        try {
            // Free current model first to release RAM before loading the new one
            if (loadedName != null) {
                try { LlamaBridge.nativeFree() } catch (_: Throwable) {}
                loadedName = null
            }
            // Check available heap before attempting load
            val runtime = Runtime.getRuntime()
            val freeBytes = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
            if (freeBytes < 100_000_000L) {
                // Request GC to free any reachable garbage
                System.gc()
            }
            val ok = try {
                LlamaBridge.nativeLoad(file.absolutePath, nCtx.coerceIn(512, 8192), threads.coerceIn(1, 8))
            } catch (e: OutOfMemoryError) {
                false
            } catch (e: Throwable) {
                false
            }
            if (ok) loadedName = file.name
            ok
        } finally {
            loading = false
        }
    }

    /** Blocking text generation. Call from Dispatchers.Default only. */
    fun generate(prompt: String, maxTokens: Int, temp: Float, topP: Float, onText: (String) -> Boolean) {
        if (loadedName == null) return
        try {
            LlamaBridge.nativeGenerate(
                prompt.toByteArray(Charsets.UTF_8),
                maxTokens.coerceIn(64, 4096),
                temp.coerceIn(0f, 2f),
                topP.coerceIn(0f, 1f)
            ) { bytes -> onText(String(bytes, Charsets.UTF_8)) }
        } catch (e: OutOfMemoryError) {
            onText("\n[Out of memory — please load a smaller model]"); onText(""); false
        } catch (_: Throwable) {}
    }

    /** Pre-reads the fixed start of the chat prompt so the next answer starts sooner. Safe to fail. */
    fun warmup(prefix: String) {
        if (loadedName == null) return
        try { LlamaBridge.nativeWarmup(prefix.toByteArray(Charsets.UTF_8)) } catch (_: Throwable) {}
    }

    fun stop() { try { LlamaBridge.nativeStop() } catch (_: Throwable) {} }

    fun unload() {
        try { if (loadedName != null) LlamaBridge.nativeFree() } catch (_: Throwable) {}
        loadedName = null
    }
}
