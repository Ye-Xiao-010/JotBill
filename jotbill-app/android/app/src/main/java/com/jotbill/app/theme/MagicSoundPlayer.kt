package com.jotbill.app.theme

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.jotbill.app.R
import java.util.concurrent.ConcurrentHashMap

/**
 * 哈利波特主题原生短音效播放器（基于 Android SoundPool）。
 *
 * 所有音频位于 res/raw/hp_sfx_*，均已裁剪为短片段（<1.2s / <100KB），
 * 避免触发 SoundPool 单条 1MB 未压缩 PCM 上限。
 */
internal object MagicSoundPlayer {
    private val soundResources = mapOf(
        "coin_drop" to R.raw.hp_sfx_coin_drop,
        "coin_clink" to R.raw.hp_sfx_coin_clink,
        "quill" to R.raw.hp_sfx_quill,
        "chime" to R.raw.hp_sfx_chime
    )

    @Volatile
    private var soundPool: SoundPool? = null
    private val soundIds = ConcurrentHashMap<String, Int>()
    private val loadedIds = ConcurrentHashMap.newKeySet<Int>()
    private val pendingPlayIds = ConcurrentHashMap.newKeySet<Int>()

    private fun ensurePool(context: Context): SoundPool? {
        soundPool?.let { return it }
        synchronized(this) {
            soundPool?.let { return it }
            return runCatching {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val pool = SoundPool.Builder()
                    .setMaxStreams(4)
                    .setAudioAttributes(attrs)
                    .build()
                pool.setOnLoadCompleteListener { sp, sampleId, status ->
                    if (status == 0) {
                        loadedIds.add(sampleId)
                        if (pendingPlayIds.remove(sampleId)) {
                            runCatching { sp.play(sampleId, 0.72f, 0.72f, 1, 0, 1.0f) }
                        }
                    } else {
                        pendingPlayIds.remove(sampleId)
                    }
                }
                val appContext = context.applicationContext
                soundResources.forEach { (key, resId) ->
                    val id = pool.load(appContext, resId, 1)
                    if (id > 0) {
                        soundIds[key] = id
                    }
                }
                soundPool = pool
                pool
            }.getOrNull()
        }
    }

    fun prewarm(context: Context) {
        ensurePool(context)
    }

    fun play(context: Context, key: String): Boolean {
        if (key !in soundResources) return false
        val pool = ensurePool(context) ?: return false
        val sampleId = soundIds[key] ?: return false
        return if (loadedIds.contains(sampleId)) {
            runCatching {
                pool.play(sampleId, 0.72f, 0.72f, 1, 0, 1.0f) != 0
            }.getOrDefault(false)
        } else {
            pendingPlayIds.add(sampleId)
            true
        }
    }

    fun supportedKeys(): Set<String> = soundResources.keys
}
