package com.example.processor

import android.content.Context
import android.media.AudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Volume control: "ارفع الصوت"، "خفض الصوت"، "كتم الصوت"، "الغي الكتم" */
class VolumeStrategy @Inject constructor(
    @ApplicationContext private val context: Context
) : CommandStrategy {

    override fun canHandle(command: String): Boolean {
        return command.contains("الصوت") || command.contains("صوت")
    }

    override suspend fun execute(command: String, isScheduled: Boolean): CommandResult {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        return when {
            command.contains("كتم") || command.contains("اصمت") || command.contains("ايكات") ||
                command.contains("اسكات") -> {
                audio.adjustVolume(AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                CommandResult(true, "تم كتم الصوت")
            }
            command.contains("الغي") || command.contains("إلغاء الكتم") ||
                command.contains("فك الكتم") || command.contains("unmute") -> {
                audio.adjustVolume(AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                CommandResult(true, "تم إلغاء الكتم")
            }
            command.contains("ارفع") || command.contains("زد") || command.contains("زياد") ||
                command.contains("علي") || command.contains("على") || command.contains("أعلى") ||
                command.contains("up") || command.contains("raise") || command.contains("louder") -> {
                repeat(2) {
                    audio.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                }
                CommandResult(true, "تم رفع الصوت")
            }
            command.contains("اخفض") || command.contains("خفض") || command.contains("قلل") ||
                command.contains("قل من") || command.contains("وطي") || command.contains("واطي") ||
                command.contains("down") || command.contains("lower") || command.contains("quieter") -> {
                repeat(2) {
                    audio.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                }
                CommandResult(true, "تم خفض الصوت")
            }
            else -> CommandResult(false, "أفهم أوامر الصوت مثل: ارفع الصوت، خفض الصوت، كتم الصوت")
        }
    }
}
