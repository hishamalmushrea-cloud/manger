package com.example.processor

import com.example.managers.FileHit
import com.example.managers.FileKind
import com.example.managers.FileSearchManager
import javax.inject.Inject

/**
 * Smart on-device file search:
 *  - «اعرض آخر صورة» / «افتح آخر فيديو» / «اعرض التسجيل الأخير»
 *  - «افتح آخر PDF» / «آخر ملف وورد» / «أحدث ملف إكسل»
 *  - «ابحث عن ملف اسمه فاتورة» (results shown on screen, best one opens)
 *
 * Placed BEFORE the web SearchStrategy and the app opener so «ابحث عن ملف»
 * is never treated as a web search and «افتح آخر صورة» as an app name.
 */
class FileSearchStrategy @Inject constructor(
    private val fileSearchManager: FileSearchManager
) : CommandStrategy {

    override fun canHandle(command: String): Boolean {
        val hasDocWord = DOC_WORDS.any { command.contains(it) }
        val hasMediaWord = MEDIA_WORDS.any { command.contains(it) }
        val wantsLast = LAST_WORDS.any { command.contains(it) }
        val wantsSearch = SEARCH_WORDS.any { command.contains(it) }
        val wantsShow = SHOW_VERBS.any { command.contains(it) }
        if (!hasDocWord && !hasMediaWord) return false
        return wantsLast || wantsSearch || (wantsShow && command.contains("ملف"))
    }

    override suspend fun execute(command: String, isScheduled: Boolean): CommandResult {
        val kinds = detectKinds(command)
        val wantsLast = LAST_WORDS.any { command.contains(it) }

        // ---------------- «آخر/أحدث X» ----------------
        if (wantsLast || (kinds.isNotEmpty() && !SEARCH_WORDS.any { command.contains(it) })) {
            val kind = kinds.firstOrNull()
                ?: return CommandResult(false, "عن أي نوع تسأل؟ قل: آخر صورة، آخر فيديو، آخر PDF، آخر تسجيل")
            return openLatest(kind, command)
        }

        // ---------------- «ابحث عن ملف اسمه X» ----------------
        val query = extractQuery(command)
        if (query.isBlank()) {
            return CommandResult(false, "ما اسم الملف؟ قل مثلاً: ابحث عن ملف اسمه فاتورة")
        }
        return searchAndOpen(query, kinds)
    }

    // ------------------------------------------------------------------
    // Latest of a kind
    // ------------------------------------------------------------------

    private fun openLatest(kind: FileKind, command: String): CommandResult {
        if (!fileSearchManager.hasMediaPermission(kind)) {
            return when (kind) {
                FileKind.IMAGE -> CommandResult(false, "أحتاج إذن الوصول للصور — افتح التطبيق ووافق عليه")
                FileKind.VIDEO -> CommandResult(false, "أحتاج إذن الوصول للفيديوهات — افتح التطبيق ووافق عليه")
                else -> docsPermissionGuard(command)
            }
        }
        val hit = fileSearchManager.lastOfType(kind)
            ?: return CommandResult(false, "لا أجد ${kindLabel(kind)} على جهازك")
        val ok = fileSearchManager.open(hit)
        val msg = if (ok) "عرضت لك ${kindLabel(kind)}: ${hit.name}"
        else "وجدت ${hit.name} لكن لا يوجد تطبيق يفتحه"
        return CommandResult(ok, msg, fileHits = listOf(hit))
    }

    // ------------------------------------------------------------------
    // Search by name
    // ------------------------------------------------------------------

    private fun searchAndOpen(query: String, kinds: Set<FileKind>): CommandResult {
        val effectiveKinds = if (kinds.isEmpty()) ALL_KINDS else kinds
        val needsDocs = effectiveKinds.any { it == FileKind.PDF || it == FileKind.WORD || it == FileKind.EXCEL }

        val hits = fileSearchManager.searchByName(query, effectiveKinds)
        if (hits.isEmpty()) {
            return if (needsDocs && !fileSearchManager.hasAllFilesAccess()) {
                fileSearchManager.openAllFilesAccessSettings()
                CommandResult(false, "لم أجد شيئاً بعد. للبحث في المستندات فعّل «الوصول لكل الملفات» — فتحت لك الصفحة، فعّله وجرّب مجدداً")
            } else {
                CommandResult(false, "لم أجد ملفات تطابق \"$query\" على جهازك")
            }
        }

        val best = hits.first()
        val opened = fileSearchManager.open(best)
        val names = hits.take(3).joinToString("، ") { it.name }
        val msg = buildString {
            append("وجدت ${hits.size} ")
            append(if (hits.size > 2) "ملفات: " else if (hits.size == 2) "ملفين: " else "ملف: ")
            append(names)
            if (opened) append(". فتحت لك أقربها: ${best.name}") else append(". تعذر فتح ${best.name} — لا يوجد عارض")
        }
        return CommandResult(true, msg, fileHits = hits)
    }

    // ------------------------------------------------------------------
    // Parsing helpers
    // ------------------------------------------------------------------

    private fun detectKinds(command: String): Set<FileKind> = buildSet {
        if (command.containsAny("pdf", "بي دي اف", "بيدياف")) add(FileKind.PDF)
        if (command.containsAny("وورد", "ورد", "word", "docx", "مستند word", "دوك")) add(FileKind.WORD)
        if (command.containsAny("اكسل", "إكسل", "excel", "xlsx", "جدول اكسل")) add(FileKind.EXCEL)
        if (command.containsAny("صورة", "صوره", "صور", "photo", "image")) add(FileKind.IMAGE)
        if (command.containsAny("فيديو", "فيديوهات", "مقطع فيديو", "video", "movie")) add(FileKind.VIDEO)
        if (command.containsAny("تسجيل", "تسجيلات", "صوتية", "مقطع صوتي", "صوتي", "voice", "recording")) add(FileKind.AUDIO)
    }

    private fun extractQuery(command: String): String {
        var s = " ${command.trim().replace(Regex("[،,؛;!؟?.]"), " ")} "
        for (phrase in STRIP_WORDS) s = s.replace(" $phrase ", " ")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private fun kindLabel(kind: FileKind): String = when (kind) {
        FileKind.IMAGE -> "أحدث صورة"
        FileKind.VIDEO -> "أحدث فيديو"
        FileKind.AUDIO -> "أحدث تسجيل صوتي"
        FileKind.PDF -> "أحدث ملف PDF"
        FileKind.WORD -> "أحدث ملف وورد"
        FileKind.EXCEL -> "أحدث ملف إكسل"
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
        private val ALL_KINDS = setOf(
            FileKind.IMAGE, FileKind.VIDEO, FileKind.AUDIO,
            FileKind.PDF, FileKind.WORD, FileKind.EXCEL
        )
        private val DOC_WORDS = listOf("ملف", "ملفات", "pdf", "بي دي اف", "وورد", "ورد", "اكسل", "إكسل", "مستند", "word", "excel", "docx")
        private val MEDIA_WORDS = listOf(
            "صورة", "صوره", "صور", "فيديو", "فيديوهات", "تسجيل", "تسجيلات",
            "مقطع صوتي", "صوتية", "صوتي", "photo", "video", "recording"
        )
        private val LAST_WORDS = listOf("آخر", "اخر", "أحدث", "احدث", "الأخير", "الاخير", "الأخيرة", "الاخيرة", "last", "latest")
        private val SEARCH_WORDS = listOf("ابحث", "بحث", "فتش", "دوّر", "دور", "search")
        private val SHOW_VERBS = listOf("اعرض", "اعرضلي", "اظهر", "أظهر", "افتح", "شغل")
        private val STRIP_WORDS = listOf(
            "ابحث", "بحث", "فتش", "دوّر", "دور", "عن", "اعرض", "اعرضلي", "اظهر", "أظهر",
            "افتح", "شغل", "ملف", "ملفات", "اسمه", "اسم", "باسم", "لي", "من", "فضلك",
            "آخر", "اخر", "أحدث", "احدث", "the", "a", "search", "open", "show",
            "pdf", "بي دي اف", "وورد", "ورد", "اكسل", "إكسل", "مستند",
            "صورة", "صوره", "فيديو", "تسجيل", "word", "excel", "search", "file"
        )
    }
}
