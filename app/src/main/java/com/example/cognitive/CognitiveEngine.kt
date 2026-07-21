package com.example.cognitive

import com.example.managers.AppOpenerManager
import com.example.managers.ContactResolver
import com.example.managers.LearningManager
import com.example.processor.CommandResult
import javax.inject.Inject
import javax.inject.Singleton

/** مخرجات الطبقة الإدراكية عند اعتراض أمر. */
sealed class CognitiveOutcome {
    /** الطبقة عالجت الأمر بالكامل: جملة جاهزة تُقال للمستخدم. */
    data class Handled(val message: String, val label: String) : CognitiveOutcome()

    /** نحتاج توضيحاً: اطرح السؤال واحفظ الطلب حتى إجابة المستخدم. */
    data class Ask(val clarify: Clarify) : CognitiveOutcome()

    /** أوامر متسلسلة: «افتح الواتس ثم اتصل بمحمد». */
    data class Chain(val commands: List<String>) : CognitiveOutcome()
}

/** طلب توضيح معلّق بانتظار إجابة المستخدم. */
sealed class Clarify {
    abstract val question: String

    /** تأكيدي: «نعم» نفّذ [yesCommand]، «لا» اعتذر بـ [noReply]. */
    data class Confirm(
        override val question: String,
        val yesCommand: String,
        val noReply: String = "حسناً، لن أفعل شيئاً"
    ) : Clarify()

    /** خيارات مرقمة: «الأول/الثاني/الثالث» أو اسم الخيار صراحة. */
    data class Choice(
        override val question: String,
        /** كل زوج: التسمية المحكية ← الأمر القابل للتنفيذ. */
        val options: List<Pair<String, String>>
    ) : Clarify()
}

/** نتيجة تحليل إجابة المستخدم على سؤال التوضيح. */
sealed class ClarifyAnswer {
    /** وافق/اختار ← نفّذ هذا الأمر عبر المسار الطبيعي. */
    data class Proceed(val command: String) : ClarifyAnswer()

    /** رفض/ألغى ← قل هذه الجملة فقط. */
    data class Cancel(val message: String) : ClarifyAnswer()

    /** قال شيئاً آخر لا علاقة له ← ألغِ التوضيح وعالجه كأمر جديد. */
    object PassThrough : ClarifyAnswer()
}

/**
 * الطبقة الإدراكية (Cognitive Layer v1) — تجلس بين المعجم الشخصي وبرلمان
 * الاستراتيجيات، وتضيف ست قدرات Offline بالكامل:
 *
 *  1) تعليم صريح بالصوت: «كلمة دق تعني اتصل» / «محمد هو أخي».
 *  2) أسئلة نموذج المستخدم: «ماذا تعرف عني؟» / «أكثر تطبيق أستخدمه؟».
 *  3) النوايا الضمنية: «بطاريتي خلصت» ← اقتراح ذكي + سؤال تأكيد.
 *  4) ترشيح الغموض: عدة جهات باسم واحد ← قائمة مرتبة بالعادات.
 *  5) التأني قبل التنفيذ: عند الغموض نسأل بدل أن نخمّن.
 *  6) التسلسل: «أمر ثم أمر» باستخدام أفعالك الطبيعية.
 *
 * لا تنفذ شيئاً بنفسها أبداً — كل قرار تنفيذ يمر عبر الاستراتيجيات المجرَّبة.
 */
@Singleton
class CognitiveEngine @Inject constructor(
    private val facts: UserFactsStore,
    private val habits: HabitsEngine,
    private val diagnoser: FailureDiagnoser,
    private val contactResolver: ContactResolver,
    private val appOpenerManager: AppOpenerManager
) {

    // ------------------------------------------------------------------
    // 0) المعجم الشخصي — يُطبَّق على الأمر الخام قبل كل شيء
    // ------------------------------------------------------------------

    suspend fun applyLexicon(input: String): String = facts.applyLexicon(input)

    // ------------------------------------------------------------------
    // 1) الاعتراض الرئيسي — يعيد null إن لم يكن للطبقة رأي (مضي للبرلمان)
    // ------------------------------------------------------------------

    suspend fun intercept(raw: String): CognitiveOutcome? {
        val text = raw.trim()
        if (text.length < 3) return null

        // الأكثر حسماً أولاً: التعليم الصريح لا يخضع لأي تخمين.
        teachOutcome(text)?.let { return it }
        insightsOutcome(text)?.let { return it }
        deepIntentOutcome(text)?.let { return it }
        ambiguityOutcome(text)?.let { return it }
        chainOutcome(text)?.let { return it }
        return null
    }

    // ------------------------------------------------------------------
    // 2) التعليم الصريح بالصوت (مرادفات + خريطة علاقات)
    // ------------------------------------------------------------------

    private val questionStarters = setOf(
        "وش", "شنو", "ايش", "إيش", "كم", "هل", "كيف", "متى", "وين",
        "أين", "ليش", "لماذا", "ليه", "شلون", "منو", "مين", "ماذا", "ما"
    )

    private val teachPatterns = listOf(
        Regex("^(?:سجل|سجّل|احفظ|تعلم|علمتك|علمته|علمها)\\s+(?:أن|ان)?\\s*(?:كلمة|كملة)?\\s*(\\S+)\\s+(?:تعني|معناها|معناه|قصدي|يعني)\\s+(.+)$"),
        Regex("^(?:كلمة|كملة)\\s+(\\S+)\\s+(?:تعني|معناها|معناه|يعني)\\s+(.+)$"),
        Regex("^(\\S+)\\s+(?:تعني|معناها|معناه)\\s+(.+)$")
    )

    /** مفردات القرابة والعلاقات المعروفة لخريطة المستخدم الاجتماعية. */
    private val relationWords = listOf(
        "اخي", "اخوي", "أخي", "أخوي", "ابي", "أبي", "ابوي", "أبوي",
        "امي", "أمي", "عمي", "عمتي", "خالي", "خالتي", "جدي", "جدتي",
        "زوجي", "زوجتي", "صديقي", "زميلي", "ابني", "بنتي", "اختي", "أختي",
        "والدي", "والدتي", "حبيبي"
    )
    /** «محمد هو أخي» ← الاسم ثم العلاقة. */
    private val aliasForward = Regex(
        "^(.+?)\\s+(?:هو|هي)\\s+(" + relationWords.joinToString("|") + ")$"
    )
    /** «أخي اسمه محمد» ← العلاقة ثم الاسم. */
    private val aliasReverse = Regex(
        "^(" + relationWords.joinToString("|") + ")\\s+(?:اسمه|اسمها)\\s+(.+)$"
    )

    private suspend fun teachOutcome(text: String): CognitiveOutcome? {
        if (text.contains("؟") || text.contains("?")) return null

        // «محمد هو أخي» / «أخي اسمه محمد» — خريطة العلاقات.
        aliasForward.find(text)?.let { m ->
            val person = m.groupValues[1].trim()
            val relation = m.groupValues[2].trim()
            if (person.length >= 2 && person.split(" ").size <= 4) {
                facts.teachPersonAlias(relation, person)
                return CognitiveOutcome.Handled(
                    "حفظت! «$relation» صارت تشير إلى $person — جرّب: اتصل ب$relation",
                    "خريطة العلاقات"
                )
            }
        }
        aliasReverse.find(text)?.let { m ->
            val relation = m.groupValues[1].trim()
            val person = m.groupValues[2].trim().trimEnd('.', '،')
            if (person.length >= 2 && person.split(" ").size <= 4) {
                facts.teachPersonAlias(relation, person)
                return CognitiveOutcome.Handled(
                    "حفظت! «$relation» صارت تشير إلى $person — جرّب: اتصل ب$relation",
                    "خريطة العلاقات"
                )
            }
        }

        // «كلمة دق تعني اتصل» — مرادفات اللهجة.
        teachPatterns.forEachIndexed { index, pattern ->
            val m = pattern.find(text) ?: return@forEachIndexed
            val spoken = m.groupValues[1].trim()
            val meaning = m.groupValues[2].trim().trimEnd('.', '،')
            if (spoken.contains(" ")) return@forEachIndexed
            if (spoken.length !in 2..15) return@forEachIndexed
            if (spoken in questionStarters) return@forEachIndexed
            val meaningFirst = meaning.split(" ").firstOrNull() ?: return@forEachIndexed
            if (meaningFirst in questionStarters) return@forEachIndexed
            // الصيغة الفضفاضة الأخيرة أكثر مخاطرة — نقيّدها أكثر.
            if (index == teachPatterns.lastIndex && meaning.split(" ").size > 3) return@forEachIndexed
            if (meaning.length < 2 || meaning.split(" ").size > 5) return@forEachIndexed
            facts.teachSynonym(spoken, meaning)
            return CognitiveOutcome.Handled(
                "حفظتها! من الآن كلمة «$spoken» تُفهم «$meaning»",
                "معجم اللهجة الشخصي"
            )
        }
        return null
    }

    // ------------------------------------------------------------------
    // 3) نموذج المستخدم: أسئلة «ماذا تعرف عني؟»
    // ------------------------------------------------------------------

    private val insightsTriggers = listOf(
        "تعرف عني", "تعلمت عني", "لاحظت عني", "عاداتي", "معلوماتك عني",
        "وش فهمت عني", "ايش تعرف عني", "إيش تعرف عني", "شنو تعرف عني"
    )
    private val topAppTriggers = listOf(
        "اكثر تطبيق استخدمه", "أكثر تطبيق استخدمه",
        "اكثر تطبيق افتحه", "أكثر تطبيق أفتحه"
    )
    private val topContactTriggers = listOf(
        "اكثر شخص اكلم", "أكثر شخص أكلم", "اكثر واحد اكلم", "أكثر واحد أكلم",
        "اكثر واحد اتصل فيه", "أكثر واحد اتصل فيه", "اكثر شخص اتواصل معه"
    )

    private suspend fun insightsOutcome(text: String): CognitiveOutcome? {
        when {
            topAppTriggers.any { text.contains(it) } -> {
                val top = habits.top("open_app", 30, 1).firstOrNull()
                val msg = if (top != null) {
                    "أكثر تطبيق تفتحه بصوتك هو ${top.label} — ${top.c} مرة خلال ثلاثين يوماً"
                } else "لم أجمع بعد بيانات كافية عن التطبيقات التي تفتحها، استخدمني أكثر"
                return CognitiveOutcome.Handled(msg, "نموذج المستخدم")
            }
            topContactTriggers.any { text.contains(it) } -> {
                val who = habits.topContactLabel(30)
                val msg = if (who != null) {
                    "أكثر شخص تتواصل معه بصوتك هو $who"
                } else "لم أجمع بعد بيانات كافية عن تواصلك، استخدمني أكثر"
                return CognitiveOutcome.Handled(msg, "نموذج المستخدم")
            }
            insightsTriggers.any { text.contains(it) } -> {
                return CognitiveOutcome.Handled(habits.insights(), "نموذج المستخدم")
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // 4) النوايا الضمنية — فهم الهدف لا الكلمات (+ التأني: نسأل قبل الفعل)
    // ------------------------------------------------------------------

    /** وجود فعل أمر صريح يلغي قراءة النية الضمنية (احتراماً لصراحة المستخدم). */
    private val explicitCommandMarkers = listOf(
        "افتح", "شغل", "شغّل", "اتصل", "كلم", "دق ", "ارسل", "أرسل", "ابعث",
        "نبه", "نبّه", "ذكرني", "ابحث", "اضبط", "ارفع", "اخفض", "كتم",
        "صحة الجهاز", "صحه الجهاز", "ارجع", "ايقاف", "أوقف", "اوقف"
    )

    private val batteryImplicit = Regex(
        "بطاري|الشحن\\s+(قرب|خلص|يخلص|ضعيف|واطي)|شحني\\s+(قرب|خلص|يخلص|ضعيف|واطي|ع)\\b"
    )
    private val slowImplicit = Regex(
        "(الجهاز|التلفون|الهاتف|الموبايل|الجوال).*(بطيء|بطيئ|ثقيل|يهنق|يهنج|يحنج|علق|يتهنق|يلقط)"
    )
    private val hotImplicit = Regex(
        "الجو\\s+حر|حر\\s+اليوم|الحرارة\\s+عالية|الحرارة\\s+عاليه|الحر\\s+قوي|الجو\\s+حار"
    )
    private val boredImplicit = Regex("زهقان|مليت|ضايج|ضايجه|فاضي|فاضيه|ابي\\s+تسلية|أبي\\s+تسلية")
    private val outsideImplicit = Regex(
        "طالع\\b|خارج\\s+البيت|في\\s+السيارة|في\\s+السياره|رايح\\s+السوق|في\\s+السوق|عندي\\s+مشوار"
    )
    private val appointmentImplicit = Regex(
        "عندي\\s+مكالمة|عندي\\s+موعد|متاخر\\s+عن|متأخر\\s+عن|لازم\\s+اكلم|لازم\\s+أكلم"
    )

    private suspend fun deepIntentOutcome(text: String): CognitiveOutcome? {
        if (explicitCommandMarkers.any { text.contains(it) }) return null

        if (batteryImplicit.containsMatchIn(text)) {
            return CognitiveOutcome.Ask(
                Clarify.Confirm(
                    "يبدو أن البطارية تضعف. أفتح لك تقرير صحة الجهاز وأخفض الصوت توفيراً للطاقة؟",
                    yesCommand = "صحة الجهاز ثم اخفض الصوت",
                    noReply = "حسناً، لن أفعل شيئاً بخصوص البطارية"
                )
            )
        }
        if (slowImplicit.containsMatchIn(text)) {
            return CognitiveOutcome.Ask(
                Clarify.Confirm(
                    "أفحص صحة الجهاز وأخبرك بالسبب الأرجح للبطء؟",
                    yesCommand = "صحة الجهاز"
                )
            )
        }
        if (hotImplicit.containsMatchIn(text)) {
            return CognitiveOutcome.Ask(
                Clarify.Confirm(
                    "أعرض لك تقرير حرارة الجهاز وصحته؟",
                    yesCommand = "صحة الجهاز"
                )
            )
        }
        if (boredImplicit.containsMatchIn(text)) {
            return CognitiveOutcome.Ask(
                Clarify.Choice(
                    "أي واحد يسليك؟ قل موسيقى أو يوتيوب",
                    listOf("موسيقى" to "افتح الموسيقى", "يوتيوب" to "افتح يوتيوب")
                )
            )
        }
        if (outsideImplicit.containsMatchIn(text)) {
            return CognitiveOutcome.Ask(
                Clarify.Confirm(
                    "هل أفتح لك الخرائط لتدلّك الطريق؟",
                    yesCommand = "افتح الخرائط"
                )
            )
        }
        if (appointmentImplicit.containsMatchIn(text)) {
            val who = habits.topContactLabel(30)
            if (who != null) {
                return CognitiveOutcome.Ask(
                    Clarify.Confirm("أتصل لك بـ$who؟", yesCommand = "اتصل بـ$who")
                )
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // 5) ترشيح الغموض: عدة جهات بنفس الاسم ← قائمة مرتبة بالعادات
    // ------------------------------------------------------------------

    private val callIntent = Regex(
        "^(اتصل|اتصلي|كلم|كلمني|كلمي|دق|دقي|عايد|عايدي)\\s+(?:على|الي|الى|إلى|ب)?\\s*(.+)$"
    )
    private val whatsappIntent = Regex(
        "^(?:ارسل|أرسل|ابعث|اكتب)\\s+(?:رسالة\\s+)?(?:واتساب|واتس|وتساب)\\s+(?:الى|إلى|لـ|ل|على)\\s*(.+)$"
    )
    /** وجود نص رسالة يعني أن التفكيك أدق من أن نعيد بناءه — نترك المسار المجرّب. */
    private val bodyHints = listOf("قل له", "قلها", "تقول", "نصها", "اكتب", "بأن", "بأنه", "قول له")

    private suspend fun ambiguityOutcome(text: String): CognitiveOutcome? {
        if (bodyHints.any { text.contains(it) }) return null

        var kind: String? = null
        var rawName: String? = null
        callIntent.find(text)?.let { kind = "call"; rawName = it.groupValues[2] }
        if (kind == null) {
            whatsappIntent.find(text)?.let { kind = "whatsapp"; rawName = it.groupValues[1] }
        }
        val k = kind ?: return null
        val name = rawName?.trim() ?: return null
        if (name.length < 2 || name.any { it.isDigit() }) return null

        val matches = contactResolver.findAll(name, 5)
        // أزِل المكرر بالاسم الموحَّد، وتجاهل الصفوف التي اسمها رقمها.
        val distinct = LinkedHashMap<String, com.example.managers.ContactMatch>()
        for (m in matches) {
            val key = LearningManager.normalize(m.displayName)
            if (key.isEmpty()) continue
            if (key == LearningManager.normalize(m.number)) continue
            distinct.putIfAbsent(key, m)
        }
        if (distinct.size < 2) return null

        // محرك الاحتمالات: عاداتك + قرب الاسم من كلامك + موضعه في النتائج.
        val needleNorm = LearningManager.normalize(name)
        val scored = distinct.values.mapIndexed { index, m ->
            val norm = LearningManager.normalize(m.displayName)
            var score = habits.boostScore(k, m.displayName)
            if (norm == needleNorm) score += 0.05f
            if (norm.startsWith(needleNorm)) score += 0.03f
            score -= index * 0.01f
            m to score
        }.sortedByDescending { it.second }.take(3)

        val options = scored.map { (m, _) ->
            val command = if (k == "call") "اتصل ب${m.displayName}" else "ارسل واتساب لـ ${m.displayName}"
            m.displayName to command
        }
        val spokenOptions = options.mapIndexed { i, o -> "${ordinalArabic(i)} ${o.first}" }
            .joinToString("، ")
        val question = "عندك أكثر من $name: $spokenOptions. أي واحد تقصد؟ قل اسمه، أو قل الأول أو الثاني"
        return CognitiveOutcome.Ask(Clarify.Choice(question, options))
    }

    private fun ordinalArabic(index: Int) = when (index) {
        0 -> "الأول"
        1 -> "الثاني"
        else -> "الثالث"
    }

    // ------------------------------------------------------------------
    // 6) التسلسل متعدد الخطوات: «افتح الواتس ثم اتصل بمحمد»
    // ------------------------------------------------------------------

    private val chainSplit = Regex("\\s+(?:ثم|وبعدين|بعدين|بعدها|وبعدها)\\s+")
    private val chainMarkers = listOf(
        "افتح", "شغل", "شغّل", "اتصل", "كلم", "دق", "ارسل", "أرسل", "ابعث",
        "ابحث", "ذكرني", "نبهني", "نبه", "اضبط", "ارفع", "اخفض", "كتم",
        "شغيل", "عايد", "صحة الجهاز", "صحه الجهاز"
    )

    private fun chainOutcome(text: String): CognitiveOutcome? {
        if (!chainSplit.containsMatchIn(text)) return null
        val parts = text.split(chainSplit).map { it.trim() }
            .filter { it.length >= 4 }
        if (parts.size !in 2..3) return null
        // أمان: لا ننشئ تسلسلاً إلا وفعل أمرٍ صريح في جزء واحد على الأقل.
        if (parts.none { p -> chainMarkers.any { m -> p.contains(m) } }) return null
        return CognitiveOutcome.Chain(parts)
    }

    // ------------------------------------------------------------------
    // 7) معالجة إجابة المستخدم على سؤال التوضيح
    // ------------------------------------------------------------------

    fun answerClarified(clarify: Clarify, input: String, yes: Boolean, no: Boolean): ClarifyAnswer {
        return when (clarify) {
            is Clarify.Confirm -> when {
                yes -> ClarifyAnswer.Proceed(clarify.yesCommand)
                no || input.contains("شكر") -> ClarifyAnswer.Cancel(clarify.noReply)
                else -> ClarifyAnswer.PassThrough
            }
            is Clarify.Choice -> {
                ordinalIndex(input)?.let { idx ->
                    clarify.options.getOrNull(idx)?.let { return ClarifyAnswer.Proceed(it.second) }
                    return ClarifyAnswer.Cancel("ليس عندي هذا الخيار، ألغيت الاختيار")
                }
                val norm = LearningManager.normalize(input)
                if (norm.isNotEmpty()) {
                    clarify.options.firstOrNull { opt ->
                        val nLabel = LearningManager.normalize(opt.first)
                        nLabel.isNotEmpty() &&
                            (norm.contains(nLabel) || (norm.length >= 3 && nLabel.contains(norm)))
                    }?.let { return ClarifyAnswer.Proceed(it.second) }
                }
                if (no || input.contains("شكر") || input.contains("الغي") || input.contains("إلغاء")) {
                    return ClarifyAnswer.Cancel("حسناً، ألغيت الاختيار")
                }
                ClarifyAnswer.PassThrough
            }
        }
    }

    /** «الأول/الثاني/واحد/1» — فهم الترتيب الشفهي لقائمة الخيارات. */
    private fun ordinalIndex(input: String): Int? {
        val t = input.trim()
        val words = mapOf(
            "الاول" to 0, "الأول" to 0, "اول" to 0, "واحد" to 0, "الأولى" to 0, "الاولى" to 0,
            "الثاني" to 1, "ثاني" to 1, "الثانيه" to 1, "الثانية" to 1, "اثنين" to 1,
            "الثالث" to 2, "ثالث" to 2, "الثالثه" to 2, "الثالثة" to 2, "ثلاثه" to 2, "ثلاثة" to 2
        )
        words.entries.firstOrNull { t.contains(it.key) }?.let { return it.value }
        return Regex("^\\s*([123])\\s*$").find(t)?.groupValues?.get(1)?.toIntOrNull()?.minus(1)
    }

    // ------------------------------------------------------------------
    // 8) خدمات مساندة للـ ViewModel
    // ------------------------------------------------------------------

    /** تمرير تشخيص الفشل إلى المختص. */
    fun diagnose(actionKey: String?, userText: String): String? =
        diagnoser.diagnose(actionKey, userText)

    /** تسجيل نجاح في دفتر العادات (تسمية التطبيق أو اسم جهة الاتصال عموماً). */
    suspend fun noteSuccess(userText: String, result: CommandResult) {
        val kind = result.actionKey ?: return
        val label = when (kind) {
            "open_app" -> {
                val name = userText.replace(appVerbs, "").trim()
                if (name.isEmpty()) null
                else appOpenerManager.resolveApp(name)?.label ?: name
            }
            "call", "whatsapp", "sms" -> extractTargetName(userText)
            else -> null
        }
        habits.record(kind, label, userText)
    }

    private val appVerbs = Regex(
        "^(افتح|افتحي|شغل|شغلي|شغّل|ادخل|ادخلي|وديني)\\s+(لي\\s+|على\\s+|علي\\s+)?"
    )

    /** اسم الهدف من أمر اتصال/رسالة — تسجيل عادات فقط، لا يطال التنفيذ. */
    private fun extractTargetName(userText: String): String? {
        var t = userText.replace(Regex("^(اتصل|اتصلي|كلم|كلمني|كلمي|دق|دقي|عايد|عايدي|ارسل|أرسل|ابعث|اكتب)\\s*"), "").trim()
        t = t.replace(Regex("^(رسالة\\s+)?(واتساب|واتس|وتساب|sms|نصية)\\s*"), "").trim()
        t = t.replace(Regex("^(على|الي|الى|إلى|لـ|ل|ال|ب)\\s*"), "").trim()
        // لأوامر الرسائل: خذ ما قبل مؤشرات النص لو وُجدت.
        bodyHints.firstOrNull { t.contains(it) }?.let { t = t.substringBefore(it).trim() }
        if (t.length < 2 || t.any { it.isDigit() }) return null
        return t.split(" ").take(3).joinToString(" ")
    }
}
