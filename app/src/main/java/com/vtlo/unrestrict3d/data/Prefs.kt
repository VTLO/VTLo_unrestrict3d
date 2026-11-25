package com.vtlo.unrestrict3d.data

import android.content.Context
import com.vtlo.unrestrict3d.engine.BackendChoice
import com.vtlo.unrestrict3d.engine.SamplerSettings

data class AppSettings(
    val backend: BackendChoice = BackendChoice.CPU,
    val sampler: SamplerSettings = SamplerSettings(),
    val systemPrompt: String = "",
    val autoLoadLast: Boolean = false,       // lazy load on first message = lower idle RAM
    val lastModelPath: String = "",
    val contextWindow: Int = 4096,           // KV-cache size: the biggest RAM lever
    val autoCompress: Boolean = true,
    val idleReleaseMin: Int = 3,             // 0 = never release
    val rootBoost: Boolean = false,
    val releaseAfterPlan: Boolean = true,    // unload the model once the pipeline is planned
    val aiBudget: Int = 4,                   // max AI recalls per pipeline run
)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("vtlo_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        backend = runCatching { BackendChoice.valueOf(sp.getString("backend", "CPU")!!) }
            .getOrDefault(BackendChoice.CPU),
        sampler = SamplerSettings(
            temperature = sp.getFloat("temperature", 0.8f).toDouble(),
            topK = sp.getInt("topK", 40),
            topP = sp.getFloat("topP", 0.95f).toDouble(),
        ),
        systemPrompt = sp.getString("system", "") ?: "",
        autoLoadLast = sp.getBoolean("autoLoad", false),
        lastModelPath = sp.getString("lastModel", "") ?: "",
        contextWindow = sp.getInt("ctx", 4096),
        autoCompress = sp.getBoolean("autoCompress", true),
        idleReleaseMin = sp.getInt("idleMin", 3),
        rootBoost = sp.getBoolean("root", false),
        releaseAfterPlan = sp.getBoolean("releaseAfterPlan", true),
        aiBudget = sp.getInt("aiBudget", 4),
    )

    fun save(s: AppSettings) {
        sp.edit()
            .putString("backend", s.backend.name)
            .putFloat("temperature", s.sampler.temperature.toFloat())
            .putInt("topK", s.sampler.topK)
            .putFloat("topP", s.sampler.topP.toFloat())
            .putString("system", s.systemPrompt)
            .putBoolean("autoLoad", s.autoLoadLast)
            .putString("lastModel", s.lastModelPath)
            .putInt("ctx", s.contextWindow)
            .putBoolean("autoCompress", s.autoCompress)
            .putInt("idleMin", s.idleReleaseMin)
            .putBoolean("root", s.rootBoost)
            .putBoolean("releaseAfterPlan", s.releaseAfterPlan)
            .putInt("aiBudget", s.aiBudget)
            .apply()
    }
}
