package com.example.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.data.CommandDao
import com.example.data.CommandLogEntity
import com.example.data.ScheduledTaskDao
import com.example.data.ScheduledTaskEntity
import com.example.processor.CommandProcessor
import com.example.worker.ScheduledCommandWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val commandProcessor: CommandProcessor,
    private val commandDao: CommandDao,
    private val scheduledTaskDao: ScheduledTaskDao,
    private val workManager: WorkManager
) : ViewModel(), TextToSpeech.OnInitListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _currentText = MutableStateFlow("")
    val currentText: StateFlow<String> = _currentText.asStateFlow()

    val logs = commandDao.getAllLogs()

    init {
        textToSpeech = TextToSpeech(context, this)
        setupSpeechRecognizer()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech?.setLanguage(Locale("ar"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Timber.e("Arabic TTS not supported, falling back to English")
                textToSpeech?.setLanguage(Locale.ENGLISH)
            }
        }
    }

    private fun setupSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    _isListening.value = false
                }
                override fun onError(error: Int) {
                    _isListening.value = false
                    Timber.e("Speech Recognition Error: $error")
                    speak("عذراً، لم أتمكن من سماعك بوضوح")
                }

                override fun onResults(results: Bundle?) {
                    _isListening.value = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val command = matches[0]
                        _currentText.value = command
                        processInput(command)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    fun startListening() {
        _isListening.value = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "تحدث الآن...")
        }
        speechRecognizer?.startListening(intent)
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        _isListening.value = false
    }

    fun updateText(text: String) {
        _currentText.value = text
    }

    fun submitTextCommand() {
        val command = _currentText.value
        if (command.isNotBlank()) {
            processInput(command)
            _currentText.value = ""
        }
    }

    private fun processInput(rawCommand: String) {
        viewModelScope.launch {
            // Check for scheduling "بعد X دقيقة" / "after X minutes"
            val delayMinutes = extractDelayMinutes(rawCommand)
            if (delayMinutes > 0) {
                scheduleCommand(rawCommand, delayMinutes)
                val msg = "تم جدولة الأمر بعد $delayMinutes دقيقة"
                speak(msg)
                logCommand(rawCommand, true, msg)
                return@launch
            }

            // Normal processing
            val result = commandProcessor.processCommand(rawCommand)
            speak(result.message)
            logCommand(rawCommand, result.success, result.message)
        }
    }

    private fun extractDelayMinutes(command: String): Long {
        val regex = Regex("(بعد|after)\\s+(\\d+)\\s+(دقيقة|دقائق|minute|minutes)")
        val match = regex.find(command)
        return match?.groupValues?.get(2)?.toLongOrNull() ?: 0L
    }

    private suspend fun scheduleCommand(command: String, delayMinutes: Long) {
        val cleanCommand = command.replace(Regex("(بعد|after)\\s+\\d+\\s+(دقيقة|دقائق|minute|minutes)"), "").trim()
        val taskId = UUID.randomUUID().toString()
        
        val workRequest = OneTimeWorkRequestBuilder<ScheduledCommandWorker>()
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .setInputData(
                Data.Builder()
                    .putString("COMMAND_TEXT", cleanCommand)
                    .putString("TASK_ID", taskId)
                    .build()
            )
            .build()
            
        workManager.enqueue(workRequest)
        
        val task = ScheduledTaskEntity(
            id = taskId,
            commandText = cleanCommand,
            scheduledTime = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(delayMinutes)
        )
        scheduledTaskDao.insertTask(task)
    }

    private suspend fun logCommand(text: String, success: Boolean, reason: String) {
        commandDao.insertLog(
            CommandLogEntity(
                commandText = text,
                timestamp = System.currentTimeMillis(),
                status = if (success) "SUCCESS" else "FAILED",
                reason = reason
            )
        )
    }

    private fun speak(text: String) {
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    override fun onCleared() {
        super.onCleared()
        speechRecognizer?.destroy()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
    }
}
