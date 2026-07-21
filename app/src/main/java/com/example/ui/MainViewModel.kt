package com.example.ui

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.data.CommandDao
import com.example.data.CommandLogEntity
import com.example.data.PreferenceStore
import com.example.data.ScheduledTaskDao
import com.example.data.ScheduledTaskEntity
import com.example.data.SettingsRepository
import com.example.managers.AppOpenerManager
import com.example.managers.ConversationContextManager
import com.example.managers.VolumeManager
import com.example.managers.FileHit
import com.example.managers.FileSearchManager
import com.example.cognitive.Clarify
import com.example.cognitive.ClarifyAnswer
import com.example.cognitive.CognitiveEngine
import com.example.cognitive.CognitiveOutcome
import com.example.managers.HealthReport
import com.example.managers.LearningManager
import com.example.processor.CommandProcessor
import com.example.processor.CommandResult
import com.example.service.VoskCommandRecognizer
import com.example.worker.ScheduledCommandWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Calendar
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
    private val workManager: WorkManager,
    private val settingsRepository: SettingsRepository,
    private val learningManager: LearningManager,
    private val appOpenerManager: AppOpenerManager,
    private val fileSearchManager: FileSearchManager,
    private val volumeManager: VolumeManager,
    private val conversationContext: ConversationContextManager,
    private val preferenceStore: PreferenceStore,
    private val cognitiveEngine: CognitiveEngine
) : ViewModel(), TextToSpeech.OnInitListener {

    /** متعرف الأوامر العربي المحلي (Vosk) — لا إنترنت ولا مفاتيح API. */
    private val commandRecognizer = VoskCommandRecognizer(context)
    private var textToSpeech: TextToSpeech? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _currentText = MutableStateFlow("")
    val currentText: StateFlow<String> = _currentText.asStateFlow()

    val logs = commandDao.getAllLogs()

    // ---------------- User Learning state machine ----------------
    // While [pendingLearnPhrase] is set, the NEXT user input is treated as
    // the correction for that phrase (or a yes/no answer to our guess).
    private var pendingLearnPhrase: String? = null
    private var pendingGuessCommand: String? = null

    // ---------------- Conversation engine state ----------------
    /** سؤال استباقي عن مستوى الصوت بانتظار إجابة المستخدم (نعم/لا/رقم). */
    private var pendingProactive: ProactiveVolume? = null
    /** نص الأمر الذي صحّحه المستخدم بـ«ليس هذا» — بانتظار الأمر الصحيح. */
    private var pendingCorrection: String? = null
    /** سؤال توضيحي من الطبقة الإدراكية (نية ضمنية/غموض جهة/اختيار) بانتظار الرد. */
    private var pendingClarify: Clarify? = null

    // ---------------- مؤشر «بانتظار ردك» في الواجهة ----------------
    private val _awaitingPrompt = MutableStateFlow<String?>(null)
    val awaitingPrompt: StateFlow<String?> = _awaitingPrompt.asStateFlow()

    // ---------------- Continuous conversation session ----------------
    @Volatile private var continuousEnabledNow = true
    @Volatile private var conversationSecondsNow = SettingsRepository.DEFAULT_CONVERSATION_SECONDS
    @Volatile private var continuousSession = false
    @Volatile private var conversationDeadline = 0L
    @Volatile private var isSpeakingNow = false
    private val conversationTimeout = Runnable { onConversationTimeout() }

    /** How many corrections the assistant has memorized (settings badge). */
    val learnedCount: StateFlow<Int> = learningManager.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // ---------------- Device Health on-screen report ----------------
    private val _healthReport = MutableStateFlow<HealthReport?>(null)
    val healthReport: StateFlow<HealthReport?> = _healthReport.asStateFlow()

    fun dismissHealthReport() { _healthReport.value = null }

    // ---------------- File-search results (clickable on-screen list) ----------------
    private val _fileHits = MutableStateFlow<List<FileHit>?>(null)
    val fileHits: StateFlow<List<FileHit>?> = _fileHits.asStateFlow()

    fun dismissFileHits() { _fileHits.value = null }

    /** Dialog row tapped → open that file with the default viewer. */
    fun openFileHit(hit: FileHit) {
        val ok = fileSearchManager.open(hit)
        speak(if (ok) "فتحت ${hit.name}" else "لا يوجد تطبيق يفتح ${hit.name}")
    }

    // ---------------- API key entered from the in-app settings dialog ----------------

    val geminiApiKey: StateFlow<String> = settingsRepository.geminiApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** True when a usable (non-placeholder) Gemini key is available — drives the UI badge. */
    val isGeminiConfigured: StateFlow<Boolean> = settingsRepository.geminiApiKey
        .map { settingsRepository.isUsableKey(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // ---------------- Continuous conversation (settings-backed) ----------------
    val continuousEnabled: StateFlow<Boolean> = settingsRepository.continuousEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val conversationSeconds: StateFlow<Int> = settingsRepository.conversationSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
            SettingsRepository.DEFAULT_CONVERSATION_SECONDS)

    fun setContinuousEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setContinuousEnabled(enabled) }
        if (!enabled) endContinuousConversation(sayGoodbye = false)
    }

    fun setConversationSeconds(seconds: Int) {
        viewModelScope.launch { settingsRepository.setConversationSeconds(seconds) }
    }

    fun saveGeminiKey(key: String) {
        viewModelScope.launch {
            settingsRepository.setGeminiApiKey(key)
        }
        speak("تم حفظ الإعدادات")
    }

    /** Settings action: wipe everything the assistant has learned. */
    fun clearLearningData() {
        viewModelScope.launch {
            learningManager.clearAll()
            pendingLearnPhrase = null
            pendingGuessCommand = null
            speak("تم مسح كل ما تعلمته")
        }
    }

    init {
        textToSpeech = TextToSpeech(context, this)
        viewModelScope.launch { settingsRepository.continuousEnabled.collect { continuousEnabledNow = it } }
        viewModelScope.launch { settingsRepository.conversationSeconds.collect { conversationSecondsNow = it } }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech?.setLanguage(Locale("ar"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Timber.e("Arabic TTS not supported, falling back to English")
                textToSpeech?.setLanguage(Locale.ENGLISH)
            }
            // When a QUESTION finishes, re-open the mic so the user can answer hands-free.
            textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { isSpeakingNow = true }
                override fun onDone(utteranceId: String?) {
                    isSpeakingNow = false
                    resetQuestionTone()
                    when (utteranceId) {
                        UTTERANCE_QUESTION_ID ->
                            mainHandler.postDelayed({ startListening() }, 300)
                        UTTERANCE_CONTINUATION_ID -> {
                            if (continuousSession && System.currentTimeMillis() < conversationDeadline) {
                                mainHandler.postDelayed({ startListening() }, 350)
                            }
                        }
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    isSpeakingNow = false
                    resetQuestionTone()
                    if (utteranceId == UTTERANCE_QUESTION_ID) resumeWakeWordEngine()
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    isSpeakingNow = false
                    resetQuestionTone()
                    if (utteranceId == UTTERANCE_QUESTION_ID) resumeWakeWordEngine()
                }
            })
        }
    }

    fun startListening() {
        if (_isListening.value) return
        // Free the microphone from the wake-word (Vosk) engine first.
        context.sendBroadcast(
            Intent(com.example.service.SpeechListenerService.ACTION_PAUSE_WAKE_WORD)
                .setPackage(context.packageName)
        )
        _isListening.value = true
        // The pause broadcast reaches the service asynchronously, so give it a moment.
        mainHandler.postDelayed({ startVoskCapture() }, 150)
    }

    /** يلتقط الأمر الصوتي عبر نموذج Vosk العربي — دون إنترنت إطلاقاً. */
    private fun startVoskCapture() {
        if (!_isListening.value) return
        commandRecognizer.startListening(object : VoskCommandRecognizer.Listener {
            override fun onPartial(text: String) {
                _currentText.value = text
            }
            override fun onResult(text: String) {
                _isListening.value = false
                _currentText.value = text
                enterConversationWindow()
                processInput(text)
            }
            override fun onNoMatch() {
                _isListening.value = false
                // صمت المستخدم أمام سؤالنا (استباقي/تصحيح) = موافقة أو إلغاء لطيف.
                if (pendingProactive != null || pendingCorrection != null) {
                    onSilentWhileAwaiting()
                    return
                }
                if (continuousSession && System.currentTimeMillis() < conversationDeadline) {
                    // Inside a conversation window silence is fine — keep listening quietly.
                    mainHandler.postDelayed({ startListening() }, 400)
                } else {
                    speak("عذراً، لم أتمكن من سماعك بوضوح")
                    resumeWakeWordEngine()
                }
            }
            override fun onError(message: String?) {
                _isListening.value = false
                Timber.e("Command recognition error: %s", message)
                speak(message ?: "تعذّر تشغيل التعرف الصوتي")
                resumeWakeWordEngine()
            }
        })
    }

    fun stopListening() {
        _awaitingPrompt.value = null
        endContinuousConversation(sayGoodbye = false)
        commandRecognizer.stop()
        _isListening.value = false
        resumeWakeWordEngine()
    }

    /** Command capture finished — hand the mic back to the wake-word engine. */
    private fun resumeWakeWordEngine() {
        context.sendBroadcast(
            Intent(com.example.service.SpeechListenerService.ACTION_RESUME_WAKE_WORD)
                .setPackage(context.packageName)
        )
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

    // ------------------------------------------------------------------
    // Command pipeline with the learning loop
    // ------------------------------------------------------------------

    private fun processInput(rawCommand: String) {
        viewModelScope.launch {
            // الطبقة الإدراكية أولاً: معجمك الشخصي (دق←اتصل، اخي←محمد) يُطبَّق
            // على كل ما تقوله قبل أي بوابة، فيفهمك النظام كله بلهجتك.
            val input = cognitiveEngine.applyLexicon(rawCommand.trim())
            if (input.isEmpty()) return@launch

            // 0a) بانتظار إجابة المستخدم على سؤال استباقي (اقتراح مستوى الصوت).
            if (pendingProactive != null) {
                handleProactiveAnswer(input)
                syncAwaiting()
                return@launch
            }

            // 0b) بانتظار الأمر الصحيح بعد تصحيح فوري «ليس هذا».
            if (pendingCorrection != null) {
                handleCorrectionAnswer(input)
                syncAwaiting()
                return@launch
            }

            // 0b2) بانتظار إجابة على سؤال الطبقة الإدراكية (نية/غموض/اختيار).
            if (pendingClarify != null) {
                handleClarifyAnswer(input)
                syncAwaiting()
                return@launch
            }

            // 1) We're mid-learning: this input is the answer/correction.
            if (pendingLearnPhrase != null) {
                handleLearningAnswer(input)
                syncAwaiting()
                return@launch
            }

            // 2) «شكراً» أو «تم» → أغلق الاستماع فوراً (توفير البطارية) مع رد مؤدب.
            if (isThanks(input)) {
                endContinuousConversation(sayGoodbye = false)
                respond("العفو! سعيد بخدمتك، نادني «hey manager» متى احتجتني")
                logCommand(
                    text = input,
                    success = true,
                    reason = "شكر من المستخدم — أُنهيت جلسة المحادثة فوراً",
                    understoodCommand = input,
                    confidence = 0.90f,
                    actionTaken = "إنهاء المحادثة"
                )
                return@launch
            }

            // 3) تصحيح فوري: «لا» / «ليس هذا» مباشرة بعد إجراء قابل للتراجع.
            if (isCorrectionTrigger(input) &&
                conversationContext.lastUndoableFresh(CORRECTION_WINDOW_MS) != null
            ) {
                startCorrectionFlow()
                return@launch
            }

            // 4) Naked yes/no outside a question — politely ignore instead of learning it.
            if ((isYes(input) || isNo(input))) {
                respond("سمعتك. قل أمراً وسأنفذه")
                return@launch
            }

            // 5) A phrase we already learned — execute instantly, no questions asked.
            val learned = learningManager.lookup(input)
            if (learned != null) {
                learningManager.recordUse(learned)
                val prevVolume = if (containsVolumeKeyword(learned.correctCommand)) volumeManager.currentMediaPercent() else null
                val startMs = SystemClock.elapsedRealtime()
                val result = commandProcessor.processCommand(learned.correctCommand)
                val durationMs = SystemClock.elapsedRealtime() - startMs
                if (result.success) afterExecuted(input, result, prevVolume)
                respondOutcome(input, result)
                logCommand(
                    text = input,
                    success = result.success,
                    reason = result.message,
                    understoodCommand = learned.correctCommand,
                    confidence = learned.confidence.coerceIn(0f, 1f),
                    actionTaken = result.handledBy,
                    failReason = if (result.success) null else result.message,
                    durationMs = durationMs
                )
                return@launch
            }

            // 6) استكمال السياق: «و الفيس»، «ثم تيك توك»، أو اسم تطبيق مبتور.
            val completedInput = completeWithContext(input)

            // 6.5) الطبقة الإدراكية: تعليم صريح، نموذج المستخدم، نوايا ضمنية،
            // ترشيح الغموض، وتسلسل الأوامر — قبل الجدولة والاستراتيجيات.
            when (val outcome = cognitiveEngine.intercept(completedInput)) {
                null -> Unit
                is CognitiveOutcome.Handled -> {
                    respond(outcome.message)
                    logCommand(
                        text = input,
                        success = true,
                        reason = outcome.message,
                        understoodCommand = completedInput,
                        confidence = 0.92f,
                        actionTaken = outcome.label
                    )
                    return@launch
                }
                is CognitiveOutcome.Ask -> {
                    pendingClarify = outcome.clarify
                    ask(outcome.clarify.question)
                    logCommand(
                        text = input,
                        success = true,
                        reason = "سؤال توضيحي: ${outcome.clarify.question}",
                        understoodCommand = completedInput,
                        confidence = 0.80f,
                        actionTaken = "توضيح إدراكي"
                    )
                    return@launch
                }
                is CognitiveOutcome.Chain -> {
                    runChain(outcome.commands, input)
                    return@launch
                }
            }

            // 7) Scheduling «بعد X دقيقة»
            val delayMinutes = extractDelayMinutes(completedInput)
            if (delayMinutes > 0) {
                scheduleCommand(completedInput, delayMinutes)
                val msg = "تم جدولة الأمر بعد $delayMinutes دقيقة"
                respond(msg)
                logCommand(
                    text = input,
                    success = true,
                    reason = msg,
                    understoodCommand = input,
                    confidence = 0.90f,
                    actionTaken = "جدولة أمر",
                    durationMs = 0L
                )
                return@launch
            }

            // 8) أمر صوت بدون نسبة → سؤال استباقي باقتراح متعلَّم من التفضيلات.
            if (isIncompleteVolumeCommand(completedInput)) {
                startProactiveVolume(completedInput)
                return@launch
            }

            // 9) Normal strategy processing
            val prevVolume = if (containsVolumeKeyword(completedInput)) volumeManager.currentMediaPercent() else null
            val startMs = SystemClock.elapsedRealtime()
            val result = commandProcessor.processCommand(completedInput)
            val durationMs = SystemClock.elapsedRealtime() - startMs
            if (result.unrecognized) {
                // Low confidence → don't guess-execute; ASK, then learn.
                enterLearning(input)
                logCommand(
                    text = input,
                    success = false,
                    reason = "لم يُفهم — دخل وضع التعلم",
                    confidence = result.confidence,
                    actionTaken = "لم يُنفذ شيء",
                    failReason = result.message,
                    durationMs = durationMs
                )
            } else {
                if (result.success) afterExecuted(input, result, prevVolume)
                respondOutcome(input, result)
                logCommand(
                    text = input,
                    success = result.success,
                    reason = result.message,
                    understoodCommand = input,
                    confidence = result.confidence,
                    actionTaken = result.handledBy,
                    failReason = if (result.success) null else result.message,
                    durationMs = durationMs
                )
            }
            syncAwaiting()
        }
    }

    // ------------------------------------------------------------------
    // Continuous conversation window
    // ------------------------------------------------------------------

    /** After a voice command, keep the mic open for follow-ups (settings-gated). */
    private fun enterConversationWindow() {
        if (!continuousEnabledNow) {
            resumeWakeWordEngine()
            return
        }
        continuousSession = true
        conversationDeadline = System.currentTimeMillis() + conversationSecondsNow * 1000L
        mainHandler.removeCallbacks(conversationTimeout)
        mainHandler.postDelayed(conversationTimeout, conversationSecondsNow * 1000L)
        Timber.d("Conversation window open (%ds)", conversationSecondsNow)
    }

    private fun onConversationTimeout() {
        if (!continuousSession) return
        val now = System.currentTimeMillis()
        if (now < conversationDeadline - 300) {
            // Window was extended meanwhile — re-arm the remaining time.
            mainHandler.postDelayed(conversationTimeout, conversationDeadline - now)
            return
        }
        if (isSpeakingNow || _isListening.value) {
            // Don't cut a reply or an in-flight capture — check again shortly.
            mainHandler.postDelayed(conversationTimeout, 2500)
            return
        }
        endContinuousConversation(sayGoodbye = true)
    }

    private fun endContinuousConversation(sayGoodbye: Boolean) {
        if (!continuousSession) return
        continuousSession = false
        // أسئلة الصوت الاستباقية والتصحيحات والتوضيحات تنتهي مع الجلسة (التعلم يبقى).
        pendingProactive = null
        pendingCorrection = null
        pendingClarify = null
        _awaitingPrompt.value = null
        mainHandler.removeCallbacks(conversationTimeout)
        try {
            commandRecognizer.cancel()
        } catch (e: Exception) {
            Timber.w(e, "Recognizer cancel during session end")
        }
        _isListening.value = false
        if (sayGoodbye) speak("انتهت جلسة المحادثة")
        resumeWakeWordEngine()
    }

    // ------------------------------------------------------------------
    // Learning: question -> suggestion (yes/no) -> correction -> save
    // ------------------------------------------------------------------

    private fun enterLearning(phrase: String) {
        pendingLearnPhrase = phrase
        val guess = guessAppToOpen(phrase)
        if (guess != null) {
            pendingGuessCommand = "افتح ${guess.label}"
            ask("لم أفهم الأمر. هل تقصد فتح ${guess.label}؟ أجب بنعم أو لا، أو قل الأمر الصحيح")
        } else {
            pendingGuessCommand = null
            ask("لم أفهم «$phrase». قل الأمر الصحيح مرة واحدة وسأتذكره دائماً، مثلاً: افتح واتساب")
        }
    }

    private suspend fun handleLearningAnswer(input: String) {
        val phrase = pendingLearnPhrase ?: return
        val guess = pendingGuessCommand

        // User rejected our app guess → ask the open question instead.
        if (guess != null && isNo(input)) {
            pendingGuessCommand = null
            ask("حسناً، فما الأمر الصحيح؟ قل مثلاً: كتم الصوت، أو اتصل بأحمد")
            return
        }

        val correctCommand = if (guess != null && isYes(input)) guess else input

        // Don't memorize a mapping of the phrase onto itself.
        if (LearningManager.normalize(correctCommand) == LearningManager.normalize(phrase)) {
            cancelLearning("يبدو أنني ما زلت لا أفهم، ألغيت هذه المحاولة")
            return
        }

        val startMs = SystemClock.elapsedRealtime()
        val result = commandProcessor.processCommand(correctCommand)
        val durationMs = SystemClock.elapsedRealtime() - startMs
        if (result.unrecognized) {
            cancelLearning("لم أفهم هذا الأمر أيضاً، ألغيت وضع التعلم")
            logCommand(
                text = phrase,
                success = false,
                reason = "فشل التعلم ← $correctCommand",
                understoodCommand = correctCommand,
                confidence = result.confidence,
                actionTaken = "تعلم أمر جديد",
                failReason = result.message,
                durationMs = durationMs
            )
        } else {
            learningManager.learn(phrase, correctCommand)
            pendingLearnPhrase = null
            pendingGuessCommand = null
            respond("تعلمت! «$phrase» ستعني من الآن «$correctCommand». ${result.message}")
            logCommand(
                text = phrase,
                success = true,
                reason = "تعلم ← $correctCommand",
                understoodCommand = correctCommand,
                confidence = result.confidence,
                actionTaken = "تعلم أمر جديد وتنفيذه",
                durationMs = durationMs
            )
        }
    }

    private fun cancelLearning(message: String) {
        pendingLearnPhrase = null
        pendingGuessCommand = null
        respond(message)
    }

    /** Best-effort app suggestion for «هل تقصد فتح واتساب؟» style questions. */
    private fun guessAppToOpen(phrase: String): AppOpenerManager.ResolvedApp? {
        val cleaned = phrase.lowercase()
            .replace(Regex("(لو سمحت|من فضلك|أريد|اريد|ابغي|ابي|ممكن|تكفى|يا )"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        appOpenerManager.resolveApp(cleaned)?.let { return it }
        val words = cleaned.split(" ")
        if (words.size >= 2) {
            appOpenerManager.resolveApp(words.takeLast(2).joinToString(" "))?.let { return it }
        }
        if (words.size >= 2) {
            appOpenerManager.resolveApp(words.last())?.let { return it }
        }
        return null
    }

    private fun isYes(input: String): Boolean {
        val padded = " ${input.trim()} "
        return YES_WORDS.any { padded.contains(" $it ") }
    }

    private fun isNo(input: String): Boolean {
        val padded = " ${input.trim()} "
        return NO_WORDS.any { padded.contains(" $it ") }
    }

    // ==================================================================
    // 🧠 محرك المحادثة: سياق + أسئلة استباقية + تصحيحات فورية
    // ==================================================================

    private fun isThanks(input: String): Boolean {
        val padded = " ${input.trim()} "
        return THANKS_WORDS.any { padded.contains(" $it ") }
    }

    private fun isCorrectionTrigger(input: String): Boolean {
        val padded = " ${input.trim()} "
        return CORRECTION_WORDS.any { padded.contains(" $it ") }
    }

    private fun containsVolumeKeyword(input: String): Boolean = input.contains("صوت")

    private fun cleanFillers(text: String): String =
        text.replace(Regex("(لو سمحت|من فضلك|من فضلكم|يا مدير|تكفى|ارجوك|ارجوكي)"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    // ---------------- 1) استكمال السياق (أوامر مبتورة/مربوطة) ----------------

    /** أدوات الربط في بداية الأمر: «و الفيس»، «ثم تيك توك»، «أو خرائط». */
    private val continuationPrefix = Regex("^(و|ثم|أو|او|وبعدين|بعدين|بعدها|وبعدها|ثم بعدها)\\s+")

    /** وجود فعل/أداة استفهام يعني أن الأمر مكتمل بذاته ولا يحتاج سياقاً. */
    private val verbMarkers = listOf(
        "افتح", "شغل", "شغّل", "اتصل", "كلم", "ارسل", "أرسل", "ابعث", "اكتب",
        "قل ل", "ارفع", "اخفض", "وطي", "كتم", "الغي", "فك", "اضبط", "حط",
        "ابحث", "دور", "فتش", "اعرض", "اقرأ", "اقرا", "شغيل", "ذكرني", "نبهني",
        "كم", "هل", "متى", "وش", "شنو", "ايش", "إيش", "وين", "أين", "كيف",
        "صور", "سجل", "جدول", "كلمني", "بعد"
    )

    /**
     * استكمال الأمر من سياق المحادثة:
     * يزيل أدوات الربط (و/ثم/أو)، وإذا بقي اسم تطبيقٍ مبتور فقط وكانت
     * المحادثة نشطة حديثاً، يُكمَل تلقائياً إلى «افتح <app>».
     */
    private fun completeWithContext(input: String): String {
        var cleaned = input
        val match = continuationPrefix.find(cleaned)
        val hadContinuation = match != null
        if (match != null) cleaned = cleaned.substring(match.range.last + 1).trim()
        if (cleaned.isEmpty()) return input

        val hasVerb = verbMarkers.any { cleaned.contains(it) }
        if (!hasVerb) {
            val contextAlive = hadContinuation ||
                conversationContext.lastEntityWithin(CONTEXT_WINDOW_MS) != null ||
                conversationContext.hasRecentAction(CONTEXT_WINDOW_MS)
            if (contextAlive) {
                appOpenerManager.resolveApp(cleaned)?.let { resolved ->
                    return "افتح ${resolved.label}"
                }
            }
        }
        return if (hadContinuation) cleaned else input
    }

    // ---------------- 2) الأسئلة الاستباقية (اقتراح ذكي للصوت) ----------------

    /** سؤال استباقي عن مستوى الصوت بانتظار قبول المستخدم أو رقماً جديداً. */
    private data class ProactiveVolume(
        val originalInput: String,
        val mode: Mode,
        val directionUp: Boolean?,
        val suggestedPercent: Int?,
        val previousPercent: Int?,
        val askedAt: Long = System.currentTimeMillis()
    ) {
        enum class Mode { CONFIRM_SUGGESTION, ENTER_VALUE }
    }

    private val volumeUpExact = Regex("^(ارفع|ارفعي|علي|علّي|زود|زوّد|زيد)\\s*(ال)?صوت$")
    private val volumeDownExact = Regex("^(اخفض|اخفضي|خفض|خفّض|قلل|قلّل|قللي|وطي|وطّي|انزل|انزلي|نزل|نزّل)\\s*(من)?\\s*(ال)?صوت$")
    private val volumeSetExact = Regex("^(اضبط|اضبطي|حط|خليه?|سوي|سويه)\\s*(ال)?صوت$")

    /** «ارفع الصوت» بدون نسبة = أمر ناقص يحتاج استكمالاً استباقياً. */
    private fun isIncompleteVolumeCommand(input: String): Boolean {
        val cleaned = cleanFillers(input.lowercase())
        if (extractPercent(cleaned) != null) return false
        return volumeUpExact.matches(cleaned) ||
            volumeDownExact.matches(cleaned) ||
            volumeSetExact.matches(cleaned)
    }

    /** يبدأ سؤالاً استباقياً باقتراح متعلَّم من تفضيلات المستخدم السابقة. */
    private fun startProactiveVolume(input: String) {
        val cleaned = cleanFillers(input.lowercase())
        val directionUp: Boolean? = when {
            volumeUpExact.matches(cleaned) -> true
            volumeDownExact.matches(cleaned) -> false
            else -> null
        }
        val previous = volumeManager.currentMediaPercent()
        val suggestion = preferenceStore.volumePreference(hourBucket())
        if (suggestion != null) {
            pendingProactive = ProactiveVolume(
                input, ProactiveVolume.Mode.CONFIRM_SUGGESTION, directionUp, suggestion, previous
            )
            val verb = when (directionUp) { true -> "رفعه"; false -> "خفضه"; null -> "ضبطه" }
            ask("هل تريد $verb إلى $suggestion بالمئة مثل آخر مرة؟ قل نعم، أو اذكر رقماً آخر")
        } else {
            pendingProactive = ProactiveVolume(
                input, ProactiveVolume.Mode.ENTER_VALUE, directionUp, null, previous
            )
            ask("إلى كم بالمئة تريد الصوت؟ قل رقماً من صفر إلى مئة")
        }
    }

    /** يعالج جواب المستخدم على السؤال الاستباقي: نعم / لا / رقم / أمر جديد. */
    private suspend fun handleProactiveAnswer(input: String) {
        val request = pendingProactive ?: return
        val number = extractPercent(input)
        when (request.mode) {
            ProactiveVolume.Mode.ENTER_VALUE -> {
                pendingProactive = null
                when {
                    number != null -> applySmartVolume(request, number)
                    isNo(input) || isThanks(input) -> respond("حسناً، ألغيت ضبط الصوت")
                    else -> respond("لم أفهم الرقم، ألغيت ضبط الصوت. قل مثلاً: اضبط الصوت سبعين")
                }
            }
            ProactiveVolume.Mode.CONFIRM_SUGGESTION -> {
                when {
                    number != null -> {
                        pendingProactive = null
                        applySmartVolume(request, number)
                    }
                    isYes(input) -> {
                        pendingProactive = null
                        applySmartVolume(request, request.suggestedPercent ?: volumeManager.currentMediaPercent())
                    }
                    isNo(input) -> {
                        pendingProactive = request.copy(
                            mode = ProactiveVolume.Mode.ENTER_VALUE,
                            askedAt = System.currentTimeMillis()
                        )
                        ask("حسناً، إلى كم بالمئة إذن؟")
                    }
                    else -> {
                        // أمر مختلف تماماً → ألغِ الاقتراح وعالجه كأمر عادي.
                        pendingProactive = null
                        processInput(input)
                    }
                }
            }
        }
    }

    /** صمت المستخدم أمام سؤالنا ← نفّذ الاقتراح (أو ألغِ بلطف). */
    private fun onSilentWhileAwaiting() {
        val request = pendingProactive
        viewModelScope.launch {
            if (request != null) {
                pendingProactive = null
                if (request.mode == ProactiveVolume.Mode.CONFIRM_SUGGESTION &&
                    request.suggestedPercent != null
                ) {
                    applySmartVolume(request, request.suggestedPercent, acceptedBySilence = true)
                } else {
                    respond("حسناً، ألغيت ضبط الصوت")
                }
            } else if (pendingCorrection != null) {
                pendingCorrection = null
                respond("حسناً، تجاهلت التصحيح")
            } else if (pendingClarify != null) {
                pendingClarify = null
                respond("حسناً، تجاهلت السؤال")
            }
            syncAwaiting()
        }
    }

    /** تطبيق مستوى الصوت نهائياً: تنفيذ + حفظ التفضيل + تراجع متاح + تسجيل. */
    private suspend fun applySmartVolume(
        request: ProactiveVolume,
        percent: Int,
        acceptedBySilence: Boolean = false
    ) {
        val clamped = percent.coerceIn(0, 100)
        val ok = volumeManager.setMediaVolume(clamped)
        if (ok) {
            conversationContext.pushVolume(clamped)
            conversationContext.noteSuccessfulAction()
            request.previousPercent?.let { prev ->
                conversationContext.setUndoable(
                    ConversationContextManager.Undoable("volume", request.originalInput, "الصوت", prev.toString())
                )
            }
            preferenceStore.saveVolumePreference(hourBucket(), clamped)
            val silenceNote = if (acceptedBySilence) " (اعتبرت صمتك موافقة)" else ""
            respond("تم! ضبطت الصوت على $clamped بالمئة$silenceNote")
            logCommand(
                text = request.originalInput,
                success = true,
                reason = "ضُبط الصوت على $clamped بالمئة",
                understoodCommand = "اضبط الصوت $clamped",
                confidence = if (acceptedBySilence) 0.80f else 0.95f,
                actionTaken = "التحكم بمستوى الصوت (اقتراح ذكي)"
            )
        } else {
            respond("عذراً، تعذّر ضبط الصوت")
            logCommand(
                text = request.originalInput,
                success = false,
                reason = "تعذّر ضبط الصوت",
                understoodCommand = "اضبط الصوت $clamped",
                confidence = 0.60f,
                actionTaken = "التحكم بمستوى الصوت",
                failReason = "فشل setStreamVolume"
            )
        }
    }

    // ---------------- 3) التصحيحات الفورية («ليس هذا») ----------------

    /** بدء التصحيح الفوري: نتراجع عن آخر إجراء قابل للتراجع ونسأل عن الصحيح. */
    private fun startCorrectionFlow() {
        val undo = conversationContext.lastUndoableFresh(CORRECTION_WINDOW_MS) ?: return
        conversationContext.clearUndoable()
        pendingCorrection = undo.userText
        val undone = undoLastAction(undo)
        val question = if (undone != null) "حسناً! $undone. ما الأمر الصحيح؟" else "حسناً! ما الأمر الصحيح؟"
        ask(question)
    }

    /** التراجع الفعلي حسب نوع الإجراء: فتح تطبيق ← نعود لشاشتنا، صوت ← نعيد القيمة. */
    private fun undoLastAction(undo: ConversationContextManager.Undoable): String? = when (undo.actionKey) {
        "open_app" -> {
            bringSelfToFront()
            "خرجت من ${undo.label}"
        }
        "volume" -> {
            val prev = undo.payload?.toIntOrNull()
            if (prev != null) {
                volumeManager.setMediaVolume(prev)
                "أعدت الصوت إلى $prev بالمئة"
            } else null
        }
        else -> null
    }

    /** إحضار شاشة المساعد للأمام (لدينا إذن الظهور فوق التطبيقات الأخرى). */
    private fun bringSelfToFront() {
        try {
            val intent = Intent(context, com.example.MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Timber.w(e, "bringSelfToFront failed")
        }
    }

    /** تنفيذ الأمر الصحيح بعد «ليس هذا» + تسجيل التصحيح في قاعدة التعلم. */
    private suspend fun handleCorrectionAnswer(input: String) {
        val original = pendingCorrection ?: return
        pendingCorrection = null
        if (isNo(input) || isThanks(input)) {
            respond("حسناً، ألغيت التصحيح")
            return
        }
        if (LearningManager.normalize(input) == LearningManager.normalize(original)) {
            respond("هذا نفس الأمر السابق، ألغيت التصحيح")
            return
        }
        val startMs = SystemClock.elapsedRealtime()
        val result = commandProcessor.processCommand(input)
        val durationMs = SystemClock.elapsedRealtime() - startMs
        if (result.unrecognized) {
            respond("لم أفهم هذا الأمر أيضاً، اعتبرني لم أفعل شيئاً")
            logCommand(
                text = original,
                success = false,
                reason = "تصحيح غير مفهوم ← $input",
                understoodCommand = input,
                confidence = result.confidence,
                actionTaken = "تصحيح فوري",
                failReason = result.message,
                durationMs = durationMs
            )
            return
        }
        if (result.success) afterExecuted(input, result, null)
        respondOutcome(input, result)
        preferenceStore.recordCorrection(original, input)
        // سجّل التصحيح في قاعدة بيانات التعلم حتى لا يتكرر الخطأ مستقبلاً.
        learningManager.learn(original, input)
        respond("فهمت خطأي ولن أكرره! ${result.message}")
        logCommand(
            text = original,
            success = result.success,
            reason = "صُحّح إلى «$input» — ${result.message}",
            understoodCommand = input,
            confidence = result.confidence,
            actionTaken = "تصحيح فوري",
            failReason = if (result.success) null else result.message,
            durationMs = durationMs
        )
    }

    // ---------------- الطبقة الإدراكية: توضيح + تسلسل + تشخيص ----------------

    /** الرد الموحّد على نتيجة أمر: يبث الحمولات الغنية، ويُلحق تشخيص السبب عند الفشل. */
    private suspend fun respondOutcome(userText: String, result: CommandResult) {
        result.healthReport?.let { _healthReport.value = it }
        result.fileHits?.let { _fileHits.value = it }
        val hint = if (!result.success) cognitiveEngine.diagnose(result.actionKey, userText) else null
        respond(if (hint != null) "${result.message}، $hint" else result.message)
    }

    /** إجابة المستخدم على سؤال الطبقة الإدراكية: نفّذ/ألغِ/عالج كأمر جديد. */
    private suspend fun handleClarifyAnswer(input: String) {
        val pending = pendingClarify ?: return
        when (val answer = cognitiveEngine.answerClarified(
            pending, input, isYes(input), isNo(input)
        )) {
            is ClarifyAnswer.Proceed -> {
                pendingClarify = null
                processInput(answer.command)
            }
            is ClarifyAnswer.Cancel -> {
                pendingClarify = null
                respond(answer.message)
            }
            ClarifyAnswer.PassThrough -> {
                // قال المستخدم أمراً مختلفاً تماماً — ألغِ التوضيح وعالجه طبيعياً.
                pendingClarify = null
                processInput(input)
            }
        }
    }

    /** تنفيذ أوامر متسلسلة واحداً تلو الآخر مع تقارير صوتية متراصة. */
    private fun runChain(commands: List<String>, origin: String) {
        viewModelScope.launch {
            respond("سأنفذ ${commands.size} أوامر بالتتابع")
            kotlinx.coroutines.delay(1500)
            commands.forEachIndexed { index, part ->
                val startMs = SystemClock.elapsedRealtime()
                val result = commandProcessor.processCommand(part)
                val durationMs = SystemClock.elapsedRealtime() - startMs
                if (result.success) afterExecuted(part, result, null)
                respondOutcome(part, result)
                logCommand(
                    text = origin,
                    success = result.success,
                    reason = "تسلسل ${index + 1}/${commands.size}: ${result.message}",
                    understoodCommand = part,
                    confidence = result.confidence,
                    actionTaken = result.handledBy,
                    failReason = if (result.success) null else result.message,
                    durationMs = durationMs
                )
                if (index < commands.lastIndex) kotlinx.coroutines.delay(1800)
            }
        }
    }

    // ---------------- تحديث سياق المحادثة بعد كل تنفيذ ناجح ----------------

    /**
     * بعد كل تنفيذ ناجح: تحديث آخر 3 كيانات نشطة (تطبيق/جهة اتصال/صوت)،
     * وحفظ تفضيل الصوت، وتجهيز الإجراء القابل للتراجع،
     * وتغذية ذاكرة العادات الطويلة في الطبقة الإدراكية.
     */
    private suspend fun afterExecuted(userText: String, result: CommandResult, prevVolumePercent: Int?) {
        cognitiveEngine.noteSuccess(userText, result)
        conversationContext.noteSuccessfulAction()
        when (result.actionKey) {
            "open_app" -> {
                extractAppName(userText)?.let { name ->
                    appOpenerManager.resolveApp(name)?.let { resolved ->
                        conversationContext.pushApp(resolved.label, resolved.packageName)
                        conversationContext.setUndoable(
                            ConversationContextManager.Undoable(
                                "open_app", userText, resolved.label, resolved.packageName
                            )
                        )
                    }
                }
            }
            "volume" -> {
                val percent = extractPercent(userText)
                if (percent != null) preferenceStore.saveVolumePreference(hourBucket(), percent)
                conversationContext.pushVolume(percent ?: volumeManager.currentMediaPercent())
                prevVolumePercent?.let { prev ->
                    conversationContext.setUndoable(
                        ConversationContextManager.Undoable("volume", userText, "الصوت", prev.toString())
                    )
                }
            }
            "call" -> {
                extractContactName(userText)?.let { conversationContext.pushContact(it) }
            }
        }
    }

    /** اسم التطبيق بعد إزالة فعل الفتح من بداية الأمر. */
    private fun extractAppName(input: String): String? {
        val stripped = input.replace(
            Regex("^(افتح|افتحي|شغل|شغلي|شغّل|حطلي|طلعلي|ادخل|ادخلي|وديني)\\s+(لي\\s+|علي\\s+|على\\s+)?"),
            ""
        ).trim()
        return stripped.ifBlank { null }
    }

    /** اسم جهة الاتصال بعد إزالة فعل الاتصال (تقريبي — لسياق المحادثة فقط). */
    private fun extractContactName(input: String): String? {
        var name = input.replace(
            Regex("^(اتصل|اتصلي|كلم|كلمني|كلمي)\\s*(ال|على|الي|الى|إلى)?\\s*"),
            ""
        ).trim()
        // «بأحمد» — الباء ملتصقة بالاسم (الاسم يُستخدم للسياق فقط، لا للتنفيذ).
        if (name.length > 2 && name.startsWith("ب")) name = name.drop(1)
        return name.ifBlank { null }
    }

    // ---------------- أدوات مساعدة ----------------

    /** فترة اليوم الحالية — تخصّص اقتراح الصوت (صباح/ظهر/مساء/ليل). */
    private fun hourBucket(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "morning"
        in 12..16 -> "afternoon"
        in 17..21 -> "evening"
        else -> "night"
    }

    /** تحويل الأرقام العربية المشرقية/الفارسية إلى أرقام لاتينية. */
    private fun normalizeDigits(text: String): String {
        val arabicIndic = "٠١٢٣٤٥٦٧٨٩"
        val persianIndic = "۰۱۲۳۴۵۶۷۸۹"
        return text.map { c ->
            val a = arabicIndic.indexOf(c)
            if (a >= 0) '0' + a else {
                val p = persianIndic.indexOf(c)
                if (p >= 0) '0' + p else c
            }
        }.joinToString("")
    }

    /** استخراج نسبة مئوية من النص (0..100) أو null إن لم توجد. */
    private fun extractPercent(text: String): Int? {
        val match = Regex("\\d{1,3}").find(normalizeDigits(text)) ?: return null
        return match.value.toIntOrNull()?.coerceIn(0, 100)
    }

    /** يخفي مؤشر «بانتظار ردك» عندما لا تبقى أسئلة معلّقة. */
    private fun syncAwaiting() {
        if (pendingProactive == null && pendingCorrection == null &&
            pendingLearnPhrase == null && pendingClarify == null
        ) {
            _awaitingPrompt.value = null
        }
    }

    // ------------------------------------------------------------------
    // Scheduling «بعد X دقيقة»
    // ------------------------------------------------------------------

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

    private suspend fun logCommand(
        text: String,
        success: Boolean,
        reason: String,
        understoodCommand: String? = null,
        confidence: Float = 0f,
        actionTaken: String? = null,
        failReason: String? = null,
        durationMs: Long = 0L
    ) {
        commandDao.insertLog(
            CommandLogEntity(
                commandText = text,
                timestamp = System.currentTimeMillis(),
                status = if (success) "SUCCESS" else "FAILED",
                reason = reason,
                understoodCommand = understoodCommand,
                confidence = confidence,
                actionTaken = actionTaken,
                failReason = failReason,
                durationMs = durationMs
            )
        )
    }

    // ------------------------------------------------------------------
    // Command Center actions (re-execute / delete / wipe)
    // ------------------------------------------------------------------

    /** حذف أمر محدد من السجل. */
    fun deleteLog(id: Int) {
        viewModelScope.launch { commandDao.deleteById(id) }
    }

    /** مسح سجل الأوامر بالكامل. */
    fun clearCommandLog() {
        viewModelScope.launch { commandDao.clearAll() }
        speak("تم مسح سجل الأوامر بالكامل")
    }

    /**
     * إعادة تنفيذ أمر من السجل بضغطة واحدة.
     * يشغّل الصيغة التي فهمها التطبيق (أو النص الأصلي إن لم تُحفظ صيغة مفهومة).
     */
    fun rerunCommand(entry: CommandLogEntity) {
        // إعادة التنفيذ تبدأ من صفر حتى لا تبتلعها أي أسئلة معلّقة.
        pendingLearnPhrase = null
        pendingGuessCommand = null
        pendingProactive = null
        pendingCorrection = null
        pendingClarify = null
        _awaitingPrompt.value = null
        val target = entry.understoodCommand?.takeIf { it.isNotBlank() } ?: entry.commandText
        if (target.isBlank()) return
        speak("إعادة تنفيذ: $target")
        processInput(target)
    }

    // ------------------------------------------------------------------
    // Speaking
    // ------------------------------------------------------------------

    private fun speak(text: String) {
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    /** Answer the user; inside a conversation window the mic re-opens afterwards. */
    private fun respond(text: String) {
        if (continuousSession) {
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_CONTINUATION_ID)
        } else {
            speak(text)
        }
    }

    /**
     * Speak a question, then auto-reopen the mic so the user can answer.
     * نبرة أعلى قليلاً وإيقاع أبطأ لتوضيح أنها سؤال، مع مؤشر بصري في الواجهة.
     */
    private fun ask(text: String) {
        _awaitingPrompt.value = text
        textToSpeech?.setPitch(QUESTION_PITCH)
        textToSpeech?.setSpeechRate(QUESTION_RATE)
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_QUESTION_ID)
    }

    /** استعادة النبرة والسرعة الطبيعية بعد انتهاء السؤال. */
    private fun resetQuestionTone() {
        textToSpeech?.setPitch(1.0f)
        textToSpeech?.setSpeechRate(1.0f)
    }

    override fun onCleared() {
        super.onCleared()
        commandRecognizer.shutdown()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
    }

    companion object {
        private const val UTTERANCE_QUESTION_ID = "hey_manager_learning_question"
        private const val UTTERANCE_CONTINUATION_ID = "hey_manager_continuation"
        private val YES_WORDS = listOf(
            "نعم", "ايوه", "ايوة", "أيوه", "أيوا", "ايوا",
            "صح", "صحيح", "بالضبط", "أكيد", "اكيد", "موافق", "تمام",
            "ماشي", "اوكي", "طيب", "زين", "yes", "yeah", "ok", "okay"
        )
        private val NO_WORDS = listOf("لا", "كلا", "غلط", "خطأ", "no")
        private val THANKS_WORDS = listOf(
            "شكرا", "شكراً", "مشكور", "مشكوره", "ممنون", "يعطيك العافية",
            "الله يعطيك العافية", "تسلم", "تسلمي", "تم", "خلاص", "كافي",
            "كفاية", "كفايه", "بس", "بس كذا", "شكراً جزيلاً", "thanks", "thank you"
        )
        private val CORRECTION_WORDS = listOf(
            "ليس هذا", "ليس هذي", "ليست هذه", "مو هذا", "مو هذي", "ماهو هذا",
            "لا هذا", "هذا غلط", "هذي غلط", "كذا غلط", "ليس كذا", "مو كذا",
            "فتحت غلط", "غلط", "خطأ", "لا", "كلا"
        )
        /** مهلة اعتبار آخر إجراء قابلاً للتصحيح الفوري. */
        private const val CORRECTION_WINDOW_MS = 25_000L
        /** مهلة اعتبار سياق المحادثة حياً لاستكمال الأوامر المبتورة. */
        private const val CONTEXT_WINDOW_MS = 120_000L
        /** نبرة/سرعة صوت السؤال (ارتفاع طفيف يوحي بالاستفهام). */
        private const val QUESTION_PITCH = 1.25f
        private const val QUESTION_RATE = 0.95f
    }
}
