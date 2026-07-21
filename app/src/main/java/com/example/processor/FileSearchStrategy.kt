package com.example.processor

import com.example.files.FileIndexer
import com.example.files.FilePlaybackManager
import com.example.files.VoiceFileSearchManager
import com.example.managers.FileHit
import com.example.managers.FileKind
import com.example.managers.FileSearchManager
import com.example.managers.MusicManager
import com.example.data.FileIndexEntity
import javax.inject.Inject

/**
 * محرك البحث الصوتي عن الملفات وتشغيلها (النسخة الموسّعة):
 *
 *  الجديد في هذه النسخة:
 *   - «شغل أغنية من مجلد التنزيلات» / «افتح الصور» / «اعرض فيديوهات من مجلد الكاميرا»:
 *     تحليل معايير مركّبة (نوع + مجلد + فنان + اسم) عبر VoiceFileSearchManager.
 *   - نتائج متعددة ← سؤال توضيح صوتي مرقّم: «وجدت 5 مقاطع، تسمع الأولى؟»
 *     ويستطيع المستخدم قول «الثالث» — عبر Clarify.Choice في MainViewModel.
 *   - تشغيل الصوتيات مباشرة داخل التطبيق (مشغل هاي مانجر الداخلي) مع
 *     «التالي/السابق/كرر/عشوائي/واصل»، والصور/الفيديو/المستندات تُفتح
 *     بالعارض الافتراضي كالمعتاد.
 *   - اختيار المستخدم لنتيجة غير الأولى يُحفَظ كتفضيل متعلَّم فيتصدر لاحقاً.
 *   - أوامر داخلية: «شغل نتيجة 2» / «افتح نتيجة 3» (تصدر من آلية التوضيح
 *     ويمكن للمستخدم نطقها مباشرة).
 *
 *  المحافظة على القديم: مسار «آخر صورة/أحدث فيديو/آخر PDF» السريع لم يتغير.
 *
 * الموضع في البرلمان: قبل MusicStrategy (حتى يفوز أمر «من مجلد X» بالملفات
 * لا بالموسيقى العامة) وقبل فيديو البحث والويب وفاتح التطبيقات.
 */
class FileSearchStrategy @Inject constructor(
    private val fileSearchManager: FileSearchManager,
    private val voiceFiles: VoiceFileSearchManager,
    private val indexer: FileIndexer,
    private val playback: FilePlaybackManager,
    private val musicManager: MusicManager
) : CommandStrategy {

    override fun canHandle(command: String): Boolean {
        // أمر داخلي من آلية التوضيح — قبول مباشر
        if (PICK_RESULT_REGEX.matches(command.trim())) return true

        val hasDocWord = DOC_WORDS.any { command.contains(it) }
        val hasMediaWord = MEDIA_WORDS.any { command.contains(it) }
        val hasSongWord = SONG_WORDS.any { command.contains(it) }
        val hasFolderCue = FOLDER_CUES.any { command.contains(it) }
        val wantsLast = LAST_WORDS.any { command.contains(it) }
        val wantsSearch = SEARCH_WORDS.any { command.contains(it) }
        val wantsShow = SHOW_VERBS.any { command.contains(it) }

        // أغنية + مجلد ← أمر ملفات حتى لو بدأ بـ«شغل» («شغل أغنية من مجلد التنزيلات»)
        if (hasFolderCue && (hasSongWord || hasMediaWord || hasDocWord) && (wantsShow || wantsSearch)) return true

        // «افتح الصور» / «افتح أغنية» (فعل فتح صريح + نوع)
        if ((hasMediaWord || (hasSongWord && command.contains("افتح"))) && wantsShow && !hasFolderCue) return true

        if (!hasDocWord && !hasMediaWord) return false
        return wantsLast || wantsSearch || (wantsShow && command.contains("ملف"))
    }

    override suspend fun execute(command: String, isScheduled: Boolean): CommandResult {
        val trimmed = command.trim()

        // ---------- أمر داخلي: «شغل نتيجة N» / «افتح نتيجة N» ----------
        PICK_RESULT_REGEX.find(trimmed)?.let { m ->
            val idx = m.groupValues[2].toIntOrNull() ?: return CommandResult(false, "أي نتيجة؟ قل رقمها")
            val hit = voiceFiles.pickResult(idx)
                ?: return CommandResult(false, "انتهت قائمة النتائج — ابحث من جديد أولاً")
            // التعلم من التصحيح: المستخدم لم يختر الأولى ← نسجّل تفضيله
            if (idx > 1 && voiceFiles.lastQueryFree.isNotBlank()) {
                voiceFiles.recordChoiceCorrection(voiceFiles.lastQueryFree, hit)
            }
            // قائمة التشغيل المثالية هنا هي نفس نتائج البحث المعروضة
            val searchQueue = voiceFiles.lastResults.filter { it.kind == FileIndexer.KIND_AUDIO }
            val openVerbatim = m.groupValues[1].startsWith("افتح") || m.groupValues[1].startsWith("اعرض")
            return actOnFile(hit, openInsteadOfPlay = openVerbatim, queueHint = searchQueue)
        }

        val kinds = detectKinds(command)
        val wantsLast = LAST_WORDS.any { command.contains(it) }

        // ---------- «آخر/أحدث X» — المسار السريع القديم (بدون فهرس) ----------
        if (wantsLast || (kinds.isNotEmpty() && !SEARCH_WORDS.any { command.contains(it) } && !FOLDER_CUES.any { command.contains(it) })) {
            val kind = kinds.firstOrNull()
                ?: return CommandResult(false, "عن أي نوع تسأل؟ قل: آخر صورة، آخر فيديو، آخر PDF، آخر تسجيل")
            return openLatest(kind, command)
        }

        // ---------- البحث المركّب عبر الفهرس المحلي ----------
        val spec = voiceFiles.parse(command)

        // بوابات الأذونات قبل الفهرسة
        if ((FileIndexer.KIND_AUDIO in spec.kinds) && !musicManager.hasAudioPermission()) {
            return CommandResult(false, "أحتاج إذن الوصول للمقاطع الصوتية — افتح التطبيق ووافق عليه")
        }
        if (FileIndexer.KIND_IMAGE in spec.kinds && !fileSearchManager.hasMediaPermission(FileKind.IMAGE)) {
            return CommandResult(false, "أحتاج إذن الوصول للصور — افتح التطبيق ووافق عليه")
        }
        if (FileIndexer.KIND_VIDEO in spec.kinds && !fileSearchManager.hasMediaPermission(FileKind.VIDEO)) {
            return CommandResult(false, "أحتاج إذن الوصول للفيديوهات — افتح التطبيق ووافق عليه")
        }

        val summary = indexer.ensureIndexed()
        val scored = voiceFiles.search(spec, limit = 25)
        val hits = scored.map { it.entity }

        if (hits.isEmpty()) {
            val needsDocs = spec.kinds.any { it == FileIndexer.KIND_PDF || it == FileIndexer.KIND_WORD || it == FileIndexer.KIND_EXCEL || it == FileIndexer.KIND_OTHER }
            return if (needsDocs && !fileSearchManager.hasAllFilesAccess()) {
                fileSearchManager.openAllFilesAccessSettings()
                CommandResult(false, "لم أجد شيئاً بعد. للبحث في المستندات فعّل «الوصول لكل الملفات» — فتحت لك الصفحة، فعّله وجرّب مجدداً")
            } else if (summary.total == 0) {
                CommandResult(false, "الفهرس فارغ بعد المسح — تأكد من الأذونات ثم جرّب من جديد")
            } else {
                val where = if (spec.folder.isNotBlank()) " في مجلد «${spec.folder}»" else " على جهازك"
                val what = spec.free.ifBlank { spec.artist.ifBlank { kindListLabel(spec.kinds) } }
                CommandResult(false, "لم أجد «$what»$where — جرّب اسماً أقصر أو قل: ابحث عن ملف واسمه")
            }
        }

        // نتيجة واحدة ← تنفيذ فوري (مع توسيع القائمة بمرشحي نفس الاستعلام)
        if (hits.size == 1) {
            return actOnFile(hits.first(), openInsteadOfPlay = false, queueHint = hits)
        }

        // نتائج متعددة ← خيارات صوتية مرقّمة + قائمة مصغرة على الشاشة
        val top = hits.take(5)
        voiceFiles.rememberResults(spec.free.ifBlank { spec.artist }, top)
        val countWord = when (hits.size) { 2 -> "اثنين"; else -> hits.size.toString() }
        val kindPlural = when {
            FileIndexer.KIND_AUDIO in spec.kinds -> if (hits.size == 2) "مقطعين" else "مقاطع"
            FileIndexer.KIND_IMAGE in spec.kinds -> if (hits.size == 2) "صورتين" else "صور"
            FileIndexer.KIND_VIDEO in spec.kinds -> if (hits.size == 2) "فيديوهين" else "فيديوهات"
            else -> if (hits.size == 2) "ملفين" else "ملفات"
        }
        val extra = if (hits.size > 5) " (من أصل ${hits.size})" else ""
        val question = "وجدت $countWord $kindPlural$extra. الأول: ${voiceFiles.describe(top.first())}. " +
                "${if (FileIndexer.KIND_AUDIO in spec.kinds) "يتم تشغيله الآن؟ قل نعم للأول أو رقم آخر" else "أفتحه لك؟ قل نعم للأول أو رقم آخر"}"
        val options = top.mapIndexed { i, e ->
            val verb = if (e.kind == FileIndexer.KIND_AUDIO && !command.contains("افتح")) "شغل" else "افتح"
            voiceFiles.describe(e) to "$verb نتيجة ${i + 1}"
        }
        return CommandResult(
            success = true,
            message = question,
            fileHits = top.map { it.toFileHit() },
            choiceQuestion = question,
            choiceOptions = options
        )
    }

    // ------------------------------------------------------------------
    // التنفيذ على ملف واحد: تشغيل داخلي للصوتيات / فتح خارجي للبقية
    // ------------------------------------------------------------------

    private suspend fun actOnFile(
        hit: FileIndexEntity,
        openInsteadOfPlay: Boolean,
        queueHint: List<FileIndexEntity> = emptyList()
    ): CommandResult {
        if (hit.kind == FileIndexer.KIND_AUDIO && !openInsteadOfPlay) {
            val queue = queueHint.ifEmpty { listOf(hit) }
            val startIdx = queue.indexOfFirst { it.uriString == hit.uriString }.coerceAtLeast(0)
            return if (playback.playQueue(queue, startIdx)) {
                val queueNote = if (queue.size > 1) " — القائمة فيها ${queue.size} والتالي/السابق شغالين" else ""
                CommandResult(true, "شغّلت لك: ${voiceFiles.describe(hit)}$queueNote")
            } else {
                CommandResult(false, "وجدت ${hit.name} لكن تعذر تشغيله على المشغل الداخلي")
            }
        }
        val opened = fileSearchManager.open(hit.toFileHit())
        val msg = if (opened) "فتحت لك ${voiceFiles.kindLabelAr(hit.kind)}: ${hit.name}"
        else "وجدت ${hit.name} لكن لا يوجد تطبيق يفتحه — ثبّت عارضاً مناسباً"
        return CommandResult(opened, msg)
    }

    // ------------------------------------------------------------------
    // «آخر/أحدث X» — المسار السريع المحفوظ من النسخة السابقة (بلا فهرس)
    // ------------------------------------------------------------------

    private suspend fun openLatest(kind: FileKind, command: String): CommandResult {
        if (!fileSearchManager.hasMediaPermission(kind)) {
            return when (kind) {
                FileKind.IMAGE -> CommandResult(false, "أحتاج إذن الوصول للصور — افتح التطبيق ووافق عليه")
                FileKind.VIDEO -> CommandResult(false, "أحتاج إذن الوصول للفيديوهات — افتح التطبيق ووافق عليه")
                else -> docsPermissionGuard(command)
            }
        }
        val hit = fileSearchManager.lastOfType(kind)
            ?: return CommandResult(false, "لا أجد ${kindLabel(kind)} على جهازك")
        return if (kind == FileKind.AUDIO) {
            // الصوتيات تُشغَّل داخلياً (عبر الفهرس إن أمكن لضمان موضع الاستئناف)
            val entity = voiceFiles.byUri(hit.uriString)
            if (entity != null && playback.playSingle(entity)) {
                CommandResult(true, "شغّلت أحدث تسجيل: ${voiceFiles.describe(entity)}", fileHits = listOf(hit))
            } else {
                val ok = fileSearchManager.open(hit)
                CommandResult(ok, if (ok) "فتحت أحدث تسجيل: ${hit.name}" else "وجدت ${hit.name} لكن لا يوجد مشغل",
                    fileHits = listOf(hit))
            }
        } else {
            val ok = fileSearchManager.open(hit)
            val msg = if (ok) "عرضت لك ${kindLabel(kind)}: ${hit.name}"
            else "وجدت ${hit.name} لكن لا يوجد تطبيق يفتحه"
            CommandResult(ok, msg, fileHits = listOf(hit))
        }
    }

    // ------------------------------------------------------------------
    // أدوات تحويل وتحليل (المحافظ عليها)
    // ------------------------------------------------------------------

    private fun FileIndexEntity.toFileHit(): FileHit = FileHit(
        name = name,
        displayPath = folderPath.ifBlank { folder },
        uriString = uriString,
        mime = mime,
        modified = dateModified
    )

    private fun detectKinds(command: String): Set<FileKind> = buildSet {
        if (command.containsAny("pdf", "بي دي اف", "بيدياف")) add(FileKind.PDF)
        if (command.containsAny("وورد", "ورد", "word", "docx", "مستند word", "دوك")) add(FileKind.WORD)
        if (command.containsAny("اكسل", "إكسل", "excel", "xlsx", "جدول اكسل")) add(FileKind.EXCEL)
        if (command.containsAny("صورة", "صوره", "صور", "photo", "image")) add(FileKind.IMAGE)
        if (command.containsAny("فيديو", "فيديوهات", "مقطع فيديو", "video", "movie")) add(FileKind.VIDEO)
        if (command.containsAny("تسجيل", "تسجيلات", "صوتية", "مقطع صوتي", "صوتي", "voice", "recording")) add(FileKind.AUDIO)
    }

    private fun kindLabel(kind: FileKind): String = when (kind) {
        FileKind.IMAGE -> "أحدث صورة"
        FileKind.VIDEO -> "أحدث فيديو"
        FileKind.AUDIO -> "أحدث تسجيل صوتي"
        FileKind.PDF -> "أحدث ملف PDF"
        FileKind.WORD -> "أحدث ملف وورد"
        FileKind.EXCEL -> "أحدث ملف إكسل"
    }

    private fun kindListLabel(kinds: Set<String>): String = when {
        FileIndexer.KIND_AUDIO in kinds -> "مقاطع صوتية"
        FileIndexer.KIND_IMAGE in kinds -> "صوراً"
        FileIndexer.KIND_VIDEO in kinds -> "فيديوهات"
        FileIndexer.KIND_PDF in kinds -> "ملفات PDF"
        else -> "ملفات"
    }

    private fun docsPermissionGuard(command: String): CommandResult {
        if (!fileSearchManager.hasAllFilesAccess()) {
            fileSearchManager.openAllFilesAccessSettings()
            return CommandResult(false, "للبحث في المستندات فعّل «الوصول لكل الملفات» — فتحت لك صفحة الإعداد")
        }
        return CommandResult(false, "تعذر الوصول للملف")
    }

    private fun String.containsAny(vararg words: String) = words.any { contains(it) }

    companion object {
        /** «شغل نتيجة 2» / «افتح نتيجة 3» — الرقم من 1 إلى 9. */
        private val PICK_RESULT_REGEX = Regex("^(شغل|شغّل|شغيل|افتح|اعرض)\\s+نتيجه?\\s+([1-9])$")

        private val DOC_WORDS = listOf("ملف", "ملفات", "pdf", "بي دي اف", "وورد", "ورد", "اكسل", "إكسل", "مستند", "word", "excel", "docx")
        private val MEDIA_WORDS = listOf(
            "صورة", "صوره", "صور", "فيديو", "فيديوهات", "تسجيل", "تسجيلات",
            "مقطع صوتي", "صوتية", "صوتي", "لقطة", "photo", "video", "recording", "image"
        )
        private val SONG_WORDS = listOf("اغنية", "أغنية", "اغاني", "أغاني", "أنشودة", "انشودة", "موسيقى", "song", "songs", "music")
        private val FOLDER_CUES = listOf(
            "مجلد", "مجلدات", "التنزيلات", "تنزيلات", "الداونلود", "التحميلات", "المفضلة", "الكاميرا",
            "لقطات الشاشة", "البلوتوث", "سكرين شوت", "المستندات", "مستندات", "واتساب", "الواتساب"
        )
        private val LAST_WORDS = listOf("آخر", "اخر", "أحدث", "احدث", "الأخير", "الاخير", "الأخيرة", "الاخيرة", "last", "latest")
        private val SEARCH_WORDS = listOf("ابحث", "بحث", "فتش", "دوّر", "دور", "جيب", "هات", "وين", "فين", "search")
        private val SHOW_VERBS = listOf("اعرض", "اعرضلي", "اظهر", "أظهر", "افتح", "افتحلي", "شغل", "شغّل", "open", "show")
    }
}
