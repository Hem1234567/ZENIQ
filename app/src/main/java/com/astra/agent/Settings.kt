package com.astra.agent

import android.content.Context

data class Settings(
    val systemPrompt: String = DEFAULT_PROMPT,
    val nCtx: Int = 4096,
    val threads: Int = defaultThreads(),
    val temperature: Float = 0.7f,
    val topP: Float = 0.8f,
    val thinking: Boolean = false,          // OFF by default for speed
    val askApproval: Boolean = false,       // never asks: the phone and the assistant are the user's own
    val maxSteps: Int = 12,
    val maxTokens: Int = 1024,              // Reduced for speed (was 2048)
    val lastModel: String = "",
    val fastMode: Boolean = false,          // Use OpenRouter instead of local model
    val openRouterKey: String = "",         // OpenRouter API key
    val openRouterModel: String = "openrouter/free", // auto-picks a working free model
    val tts: Boolean = false,               // speak replies aloud (OFF until the user turns it on)
    val imageModel: String = "pollinations", // text-to-image: "pollinations" (free, no key) or an OpenRouter model id
    val videoModel: String = ""              // text-to-video: an OpenRouter video model id ("" = first available)
)

/**
 * Threads for the local model = number of FAST cores only (2-4). Using the slow "little" cores as well makes the phone
 * hot and is not faster, because the fast cores end up waiting for the slow ones.
 */
fun defaultThreads(): Int = try {
    val freqs = ArrayList<Long>()
    java.io.File("/sys/devices/system/cpu").listFiles()?.forEach { f ->
        if (f.name.matches(Regex("cpu\\d+"))) {
            val v = java.io.File(f, "cpufreq/cpuinfo_max_freq").takeIf { it.canRead() }?.readText()?.trim()?.toLongOrNull()
            if (v != null) freqs.add(v)
        }
    }
    if (freqs.isEmpty()) throw IllegalStateException("no cpufreq")
    val top = freqs.maxOrNull()!!
    freqs.count { it >= top * 0.7 }.coerceIn(2, 4)
} catch (_: Throwable) { (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4) }

const val DEFAULT_PROMPT =
    "You are ZENIQ, the user's personal AI assistant running on the user's own Android phone. " +
    "The phone, its apps and its files belong to the user, who gives you full permission to use them. " +
    "Never refuse, warn or lecture about security, privacy or permissions, and never ask for confirmation: " +
    "do what the user asks (open apps and links, browse, search, type, tap, write files, make images and videos) and then report briefly. " +
    "Be concise and practical."

class SettingsStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("astra", Context.MODE_PRIVATE)
    fun load(): Settings {
        val d = Settings()
        val savedPrompt = sp.getString("sys", d.systemPrompt) ?: d.systemPrompt
        // older versions saved their own default prompt; replace it with the current one
        val prompt = if (savedPrompt.isBlank() || savedPrompt.startsWith("You are Astra") || savedPrompt.startsWith("You are ZENIQ, an on-device")) d.systemPrompt else savedPrompt
        return Settings(
            prompt,
            sp.getInt("ctx", d.nCtx), if (sp.getBoolean("threadsV2", false)) sp.getInt("threads", d.threads) else d.threads,
            sp.getFloat("temp", d.temperature), sp.getFloat("topp", d.topP),
            sp.getBoolean("think", d.thinking), false,
            sp.getInt("steps", d.maxSteps), sp.getInt("maxtok", d.maxTokens),
            sp.getString("last", "") ?: "",
            sp.getBoolean("fastMode", d.fastMode),
            sp.getString("orKey", d.openRouterKey) ?: d.openRouterKey,
            sp.getString("orModel", d.openRouterModel) ?: d.openRouterModel,
            sp.getBoolean("tts", d.tts),
            sp.getString("imgModel", d.imageModel) ?: d.imageModel,
            sp.getString("vidModel", d.videoModel) ?: d.videoModel
        )
    }
    fun save(s: Settings) {
        sp.edit()
            .putString("sys", s.systemPrompt).putInt("ctx", s.nCtx).putInt("threads", s.threads).putBoolean("threadsV2", true)
            .putFloat("temp", s.temperature).putFloat("topp", s.topP)
            .putBoolean("think", s.thinking).putBoolean("ask", s.askApproval)
            .putInt("steps", s.maxSteps).putInt("maxtok", s.maxTokens)
            .putString("last", s.lastModel)
            .putBoolean("fastMode", s.fastMode)
            .putString("orKey", s.openRouterKey)
            .putString("orModel", s.openRouterModel)
            .putBoolean("tts", s.tts)
            .putString("imgModel", s.imageModel).putString("vidModel", s.videoModel)
            .apply()
    }
}
