package com.example.files

import com.example.data.ChoiceFixDao
import com.example.data.ChoiceFixEntity
import com.example.data.FileIndexDao
import com.example.data.FileIndexEntity
import com.example.managers.AppOpenerManager
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * محلل الأمر الصوتي لمحرك البحث عن الملفات — يجيب على ثلاثة أسئلة:
 *   «ماذا يريد أن يشغل/يفتح؟»   ← kind: AUDIO / IMAGE / VIDEO / PDF / ...
 *   «بأي معايير؟»              ← فنان + كلمات حرة + مجلد
 *   «كيف نرتب الإجابات؟»        ← دالة التقييم متعددة العوامل بالأسفل
 */
data class FileQuerySpec(
    val kinds: Set<String>,
    val artist: String = "",
    val folder: String = "",          // المجلد كما نطقه المستخدم (غير مطبَّع)
    val free: String = "",            // الكلمات الحرة (اسم الملف/الأغنية)
    val wantsLast: Boolean = false,
    val wantsRandom: Boolean = false
)

/** نتيجة مقيّمة — الدرجة تشرح نفسها في سجلات التيمبر. */
data class ScoredFile(
    val entity: FileIndexEntity,
    val score: Int
)

/**
 * محرك البحث الصوتي عن الملفات — عقل أوامر مثل:
 *   «شغل أغنيـة»، «شغل أغاني حمود»، «شغل أغنية للفنان عيسى الليث»,
 *   «افتح أغنية من مجلد التنزيلات»، «افتح الصور»، «اعرض فيديوهات من مجلد الكاميرا»,
 *   «ابحث عن ملف العقد»، «افتح ملف الإيجار».
 *
 * مبادئ التصميم:
 *  1) الفهرس أولاً: كل البحث يتم على جدول Room المحلي (ذاكرة داخلية) —
 *     لا استعلامات MediaStore متكررة، هدفنا أقل من ثانية من الأمر للتنفيذ.
 *  2) الفهم المتسامح: التطبيع الموحّد (AppOpenerManager.normalize) يمحو
 *     الفرق بين «عيسى/عیسی/عيسىٰ» ويقبل المطابقة الجزئية «الليث ← عيسى الليث».
 *  3) الغموض لا يُكتم: عدة نتائج متقاربة تُعرض كخيارات صوتية مرقّمة عبر
 *     آلية Clarify.Choice الموجودة في الطبقة الإدراكية.
 *  4) التصحيح درس: إن اختار المستخدم نتيجة غير الأولى نحفظ تفضيله في
 *     file_choice_fixes فتتصدّر اختيارَه المرة القادمة.
 */
@Singleton
class VoiceFileSearchManager @Inject constructor(
    private val dao: FileIndexDao,
    private val fixes: ChoiceFixDao
) {

    // ------------------------------------------------------------------
    // 1) التحليل: الأمر الخام ← FileQuerySpec
    // ------------------------------------------------------------------

    fun parse(rawCommand: String): FileQuerySpec {
        var s = " ${rawCommand.trim().lowercase()} "

        // أ) نوع الملف المطلوب
        val kinds = mutableSetOf<String>()
        if (containsAny(s, "اغنية", "أغنية", "اغاني", "أغاني", "اغنيه", "انشودة", "أنشودة", "موسيقى", "مقطع صوتي", "song", "music", "songs")) kinds += FileIndexer.KIND_AUDIO
        if (containsAny(s, "صورة", "صوره", "صور", "لقطة", "photo", "image", "photos")) kinds += FileIndexer.KIND_IMAGE
        if (containsAny(s, "فيديو", "فيديوهات", "مقطع فيديو", "video", "movie")) kinds += FileIndexer.KIND_VIDEO
        if (containsAny(s, "pdf", "بي دي اف", "بيدياف")) kinds += FileIndexer.KIND_PDF
        if (containsAny(s, "وورد", "ورد", "word", "docx", "مستند وورد")) kinds += FileIndexer.KIND_WORD
        if (containsAny(s, "اكسل", "إكسل", "excel", "xlsx")) kinds += FileIndexer.KIND_EXCEL
        if (containsAny(s, "تسجيل", "تسجيلات", "صوتية", "صوتي", "recording")) kinds += FileIndexer.KIND_AUDIO

        // ب) الفنان: «للفنان عيسى الليث» / «فنان عيسى الليث» / «من غناء...»
        var artist = ""
        ARTIST_REGEXES.forEach { rx ->
            rx.find(s)?.let { m ->
                val g = m.groupValues.getOrNull(1)?.trim().orEmpty()
                if (g.length >= 2) artist = g
            }
        }

        // ج) المجلد: «من مجلد التنزيلات» / «من التنزيلات» / «في مجلد الكاميرا»
        var folder = ""
        FOLDER_REGEXES.forEach { rx ->
            rx.find(s)?.let { m ->
                val g = m.groupValues.getOrNull(1)?.trim().orEmpty()
                if (g.length >= 2) folder = g
            }
        }
        if (folder.isEmpty()) {
            // اسم مجلد مباشر دون كلمة «مجلد»: «من التنزيلات» «من واتساب»
            FOLDER_ALIASES.keys.firstOrNull { alias -> s.contains(" $alias ") }?.let { folder = it }
        }

        // د) كلمات حرة: ما تبقى بعد إزالة الأفعال وكلمات النوع والمجلد والحشو
        var cleaned = s
        for (phrase in STRIP_WORDS) cleaned = cleaned.replace(" $phrase ", " ")
        if (artist.isNotEmpty()) cleaned = cleaned.replace(artist, " ")
        if (folder.isNotEmpty()) cleaned = cleaned.replace(folder, " ")
        val free = cleaned.replace(Regex("\\s+"), " ").trim()

        return FileQuerySpec(
            kinds = kinds.ifEmpty { ALL_KINDS },
            artist = AppOpenerManager.normalize(artist),
            folder = folder,
            free = AppOpenerManager.normalize(free),
            wantsLast = containsAny(s, "آخر", "اخر", "أحدث", "احدث", "الأخير", "الاخير", "الأخيرة", "الاخيرة", "latest", "last"),
            wantsRandom = containsAny(s, "اي اغنية", "أي أغنية", "عشوائي", "عشوائية", "random")
        )
    }

    // ------------------------------------------------------------------
    // 2) البحث والتقييم
    // ------------------------------------------------------------------

    /**
     * البحث الرئيسي: يعيد مرشحين مرتبين تنازلياً بالدرجة.
     * [limit] يحدد سقف قائمة التشغيل (الافتراضي 25 — حجم يكفي للتالي/السابق
     * دون إثقال الذاكرة).
     */
    suspend fun search(spec: FileQuerySpec, limit: Int = 25): List<ScoredFile> {
        val pool = dao.activeByKinds(spec.kinds.toList())
        if (pool.isEmpty()) return emptyList()

        val fix = if (spec.free.isNotEmpty()) fixes.forQuery(spec.free) else null
        val words = spec.free.split(" ").filter { it.length > 1 }
        val folderNorms = folderNormsFor(spec.folder)
        val now = System.currentTimeMillis() / 1000L

        val scored = pool.map { entity ->
            var score = 0

            // — المجلد (قيد صلب إن حدده المستخدم): من خارج المجلد يخرج من السباق
            if (spec.folder.isNotEmpty()) {
                val inFolder = folderNorms.any { fn ->
                    entity.folder.contains(fn) || entity.folderPath.lowercase().contains(fn)
                } || (spec.folder.length >= 3 &&
                        (entity.folder.contains(AppOpenerManager.normalize(spec.folder)) ||
                                entity.folderPath.lowercase().contains(AppOpenerManager.normalize(spec.folder))))
                if (!inFolder) return@map entity to -1
                score += 40 // داخل المجلد الصحيح — قاعدة انطلاق قوية
            }

            // — الاسم/السيق
            if (spec.free.isNotEmpty()) {
                score += when {
                    entity.nameNorm == spec.free -> 100
                    spec.free.length > 1 && entity.nameNorm.contains(spec.free) -> 85
                    spec.free.length > 1 && entity.stem.contains(spec.free) -> 80
                    spec.free.length > 2 && spec.free.contains(entity.stem) && entity.stem.length > 2 -> 70
                    else -> 0
                }
            }

            // — الفنان (ذو أولوية للصوتيات)
            if (spec.artist.isNotEmpty()) {
                score += when {
                    entity.artistNorm == spec.artist -> 95
                    spec.artist.length > 2 && entity.artistNorm.contains(spec.artist) -> 85
                    spec.artist.length > 2 && spec.artist.contains(entity.artistNorm) && entity.artistNorm.length > 2 -> 75
                    else -> 0
                }
            }

            // — تغطية الكلمات الحرة (اسم أو فنان)
            if (words.isNotEmpty()) {
                val hitWords = words.count { w -> entity.nameNorm.contains(w) || entity.artistNorm.contains(w) }
                score += (hitWords * 55) / words.size
            }

            // — تفضيل متعلَّم من تصحيح سابق: «أقصد هذه الأغنية تحديداً»
            if (fix != null && fix.uriString == entity.uriString) score += 35

            // — العادة والحداثة
            score += minOf(entity.playCount, 5) * 3
            val age = now - entity.dateModified
            score += when {
                age < 7L * 86_400L -> 4
                age < 30L * 86_400L -> 2
                else -> 0
            }

            entity to score
        }

        val threshold = when {
            spec.folder.isNotEmpty() && spec.free.isEmpty() && spec.artist.isEmpty() -> 1   // «افتح الصور من مجلد الكاميرا» — كل محتوى المجلد
            spec.free.isEmpty() && spec.artist.isEmpty() -> 1                                  // «افتح الصور» — كل الشبكة
            else -> 40
        }

        var result = scored
            .filter { it.second >= threshold }
            .sortedWith(
                compareByDescending<Pair<FileIndexEntity, Int>> { it.second }
                    .thenByDescending { it.first.dateModified }
            )
            .map { ScoredFile(it.first, it.second) }

        // «آخر صورة/أحدث فيديو» → الأحدث زمنياً يتصدر مهما كانت الدرجة
        if (spec.wantsLast && result.isNotEmpty()) {
            result = result.sortedByDescending { it.entity.dateModified }
        }
        if (spec.wantsRandom && result.size > 1) {
            result = result.shuffled()
        }

        Timber.d("file search spec=%s → %d hits (pool %d)", spec, result.size, pool.size)
        return result.take(limit)
    }

    /** مرشحو تشغيل صوتي لأمر موسيقى («شغل أغني فيروز») — جاهز كقائمة تشغيل. */
    suspend fun searchAudio(query: String, limit: Int = 25): List<FileIndexEntity> {
        val normQuery = AppOpenerManager.normalize(query)
        val spec = FileQuerySpec(kinds = setOf(FileIndexer.KIND_AUDIO), free = normQuery)
        return search(spec, limit).map { it.entity }
    }

    /** جلب صف واحد بالـURI (جسر بين FileHit القديم والفهرس الجديد). */
    suspend fun byUri(uriString: String): FileIndexEntity? = dao.byUri(uriString)

    /** «شغل أي أغنية» — قائمة عشوائية حية من كل الصوتيات. */
    suspend fun randomAudioQueue(limit: Int = 40): List<FileIndexEntity> =
        dao.allAudio().filter { it.durationMs > 20_000L }.shuffled().take(limit)

    // ------------------------------------------------------------------
    // 3) حالة آخر بحث (أساس «شغل نتيجة 2» + التعلم من التصحيح)
    // ------------------------------------------------------------------

    @Volatile var lastQueryFree: String = ""
        private set
    @Volatile var lastResults: List<FileIndexEntity> = emptyList()
        private set

    /** تحفظها الاستراتيجية بعد كل بحث متعدد النتائج. */
    fun rememberResults(queryFree: String, hits: List<FileIndexEntity>) {
        lastQueryFree = queryFree
        lastResults = hits
    }

    /** تنفيذ «شغل نتيجه N»: يعيد الملف رقم N (1-based) من آخر نتائج معروضة. */
    fun pickResult(index1Based: Int): FileIndexEntity? =
        lastResults.getOrNull(index1Based - 1)

    /**
     * درس التفضيل: المستخدم لم يرض بالنتيجة الأولى واختار غيرها — نربط
     * استعلامه المطبَّع باختياره فيتصدّر في المرة القادمة (+35 في التقييم).
     */
    suspend fun recordChoiceCorrection(queryFree: String, chosen: FileIndexEntity) {
        if (queryFree.isBlank()) return
        fixes.put(ChoiceFixEntity(queryNorm = queryFree, uriString = chosen.uriString, at = System.currentTimeMillis()))
        Timber.d("learned choice fix: %s ← %s", queryFree, chosen.name)
    }

    // ------------------------------------------------------------------
    // 4) عرض محكي وترجمات
    // ------------------------------------------------------------------

    /** تسمية محكية غنية: «عيسى الليث — حبيبي (من مجلد التنزيلات)». */
    fun describe(e: FileIndexEntity): String = buildString {
        append(e.stem.ifBlank { e.name })
        if (e.artist.isNotBlank()) append(" — ").append(e.artist)
        if (e.folder.isNotBlank()) append(" (مجلد ${e.folder})")
    }

    fun kindLabelAr(kind: String): String = when (kind) {
        FileIndexer.KIND_AUDIO -> "مقطع صوتي"
        FileIndexer.KIND_IMAGE -> "صورة"
        FileIndexer.KIND_VIDEO -> "فيديو"
        FileIndexer.KIND_PDF -> "ملف PDF"
        FileIndexer.KIND_WORD -> "مستند"
        FileIndexer.KIND_EXCEL -> "جدول إكسل"
        else -> "ملف"
    }

    // ------------------------------------------------------------------
    // مرادفات المجلدات العربية ← أسماء المجلدات الفعلية المحتملة
    // ------------------------------------------------------------------

    private fun folderNormsFor(spoken: String): List<String> {
        if (spoken.isEmpty()) return emptyList()
        val spokenNorm = AppOpenerManager.normalize(spoken)
        val mapped = FOLDER_ALIASES[spoken] ?: FOLDER_ALIASES.entries.firstOrNull { (k, _) -> spokenNorm == AppOpenerManager.normalize(k) }?.value
        return (mapped ?: emptyList()) + spokenNorm + spoken.lowercase()
    }

    private fun containsAny(hay: String, vararg needles: String) = needles.any { hay.contains(it) }

    companion object {
        val ALL_KINDS = setOf(
            FileIndexer.KIND_AUDIO, FileIndexer.KIND_IMAGE, FileIndexer.KIND_VIDEO,
            FileIndexer.KIND_PDF, FileIndexer.KIND_WORD, FileIndexer.KIND_EXCEL, FileIndexer.KIND_OTHER
        )

        /** تعابير التقاط الفنان (المجموعة 1 = اسم الفنان). */
        private val ARTIST_REGEXES = listOf(
            Regex("للفنانة?\\s+([\\p{L} ]{2,40})"),
            Regex("فنان\\s+([\\p{L} ]{2,40})"),
            Regex("من غناء\\s+([\\p{L} ]{2,40})"),
            Regex("للمطربة?\\s+([\\p{L} ]{2,40})")
        )

        /** تعابير التقاط المجلد (المجموعة 1 = المجلد كما نطقه). */
        private val FOLDER_REGEXES = listOf(
            Regex("(?:من|في|داخل)\\s+مجلد(?:ات)?\\s+([\\p{L}\\p{N}_ ]{2,30})"),
            Regex("مجلد\\s+([\\p{L}\\p{N}_ ]{2,30})")
        )

        /**
         * المجلد كما يقوله المستخدم العربي/اليمني ← أسماء مجلدات الأنظمة الفعلية.
         * تُطابَق بـ«contains» على اسم المجلد وعلى المسار النسبي.
         */
        private val FOLDER_ALIASES = linkedMapOf(
            "التنزيلات" to listOf("download", "downloads"),
            "تنزيلات" to listOf("download", "downloads"),
            "الداونلود" to listOf("download", "downloads"),
            "التحميلات" to listOf("download", "downloads"),
            "المفضلة" to listOf("favorite", "favorites", "المفضلة", "المفضله"),
            "مفضلة" to listOf("favorite", "favorites", "المفضلة", "المفضله"),
            "الكاميرا" to listOf("dcim", "camera"),
            "كاميرا" to listOf("dcim", "camera"),
            "لقطات الشاشة" to listOf("screenshot", "screenshots"),
            "لقطات" to listOf("screenshot", "screenshots"),
            "سكرين شوت" to listOf("screenshot", "screenshots"),
            "السكرين" to listOf("screenshot", "screenshots"),
            "واتساب" to listOf("whatsapp"),
            "الواتساب" to listOf("whatsapp"),
            "الواتس" to listOf("whatsapp"),
            "البلوتوث" to listOf("bluetooth"),
            "بلوتوث" to listOf("bluetooth"),
            "المستندات" to listOf("documents"),
            "مستندات" to listOf("documents"),
            "الملفات" to listOf("documents"),
            "الموسيقى" to listOf("music"),
            "الصوتيات" to listOf("music", "recordings", "voice"),
            "التسجيلات" to listOf("recordings", "voice", "recorder"),
            "الصور" to listOf("pictures")
        )

        /** أفعال وكلمات حشو تُحذف لعزل اسم الملف — حذف «كلمة كاملة بحواف فراغات». */
        private val STRIP_WORDS = listOf(
            "شغل لي", "شغلي", "شغّل", "شغل", "تشغيل", "افتح لي", "افتحلي", "افتح", "اعرض لي", "اعرضلي", "اعرض",
            "اظهر", "أظهر", "ابحث", "بحث", "فتش", "دوّر", "دور", "جيب", "هات", "وين", "فين",
            "عن", "في", "من", "داخل", "مجلد", "مجلدات", "باسم", "اسمه", "اسم", "لي", "يا", "لو سمحت", "من فضلك", "رجاء",
            "اغنية", "أغنية", "اغاني", "أغاني", "اغنيه", "انشودة", "أنشودة", "موسيقى", "مقطع صوتي",
            "صورة", "صوره", "صور", "لقطة", "فيديو", "فيديوهات", "مقطع فيديو",
            "ملف", "ملفات", "مستند", "وورد", "ورد", "اكسل", "إكسل", "pdf", "بي دي اف",
            "تسجيل", "تسجيلات", "صوتية", "صوتي", "حفظة", "محفوظ",
            "للفنان", "للفنانة", "الفنان", "فنان", "للمطرب", "غناء",
            "song", "songs", "music", "photo", "photos", "image", "video", "file", "search", "open", "show", "play",
            "آخر", "اخر", "أحدث", "احدث", "الأخير", "الاخير", "الأخيرة", "الاخيرة", "اي", "أي", "عشوائي", "عشوائية"
        )
    }
}
