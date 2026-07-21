package com.example.managers

import android.content.Context
import android.media.AudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ضبط دقيق لصوت الوسائط بالنسبة المئوية (0..100) — يستخدمه محرك المحادثة
 * للاقتراحات الاستباقية («اضبطه على ٧٠٪») وللتراجع الفوري عن تغييرات الصوت.
 */
@Singleton
class VolumeManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** مستوى صوت الوسائط الحالي كنسبة مئوية 0..100 */
    fun currentMediaPercent(): Int {
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (max > 0) (current * 100) / max else 0
    }

    /** يضبط صوت الوسائط على نسبة مئوية مع عرض واجهة النظام. */
    fun setMediaVolume(percent: Int): Boolean {
        return try {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = ((percent.coerceIn(0, 100) * max) / 100).coerceIn(0, max)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
            true
        } catch (e: Exception) {
            Timber.w(e, "setMediaVolume failed")
            false
        }
    }
}
