package com.example.files

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.data.FileIndexDao
import com.example.data.FileIndexEntity
import com.example.managers.AppOpenerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** ملخص عملية الفهرسة — يظهر في شاشة مكتبة الملفات وسجلات التيمبر. */
data class IndexSummary(
    val total: Int,
    val audio: Int,
    val images: Int,
    val videos: Int,
    val docs: Int,
    val cached: Boolean,
    val tookMs: Long
)

/**
 * فهرس الملفات — العمود الفقري للبحث الصوتي المحلي.
 *
 * يبني لقطة موحّدة (Unified Snapshot) لكل ملفات المستخدم في جدول Room:
 *  - صوتيات: MediaStore.Audio (فنان/ألبوم/مدة) — «IS_MUSIC != 0».
 *  - صور: MediaStore.Images. فيديوهات: MediaStore.Video.
 *  - مستندات: MediaStore.Files مقيدة بامتدادات PDF/Word/Excel/نصوص.
 *  - مجلدات يضيفها المستخدم يدوياً (زر «إضافة مجلد» في شاشة المكتبة) تُحوَّل
 *    شجرة SAF إلى مسار فعلي وتُمسح بالملف — بفضل إذن الوصول لكل الملفات.
 *
 * إحصاءات التشغيل (playCount/lastPositionMs/hidden) تُنسخ من اللقطة القديمة
 * إلى الجديدة حتى لا يفقد التطبيق ذاكرته عبر الفحوص المتتالية.
 *
 * كل شيء يحدث محلياً على الجهاز — لا شبكة، لا رفع بيانات، لا طرف ثالث.
 */
@Singleton
class FileIndexer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: FileIndexDao
) {
    private val prefs = context.getSharedPreferences("file_index_prefs", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    // ------------------------------------------------------------------
    // الواجهة العامة
    // ------------------------------------------------------------------

    /**
     * يضمن فهرساً حديثاً قبل البحث. كسول وذكي: لا يعيد الفحص إن كانت اللقطة
     * أحدث من [maxAgeMs] — يمرر عندها بلا كلفة (أسرع مسار لكل أمر تالٍ).
     * [force] يفرض فحصاً كاملاً (زر «إعادة الفهرسة الآن»).
     */
    suspend fun ensureIndexed(force: Boolean = false, maxAgeMs: Long = STALE_MS): IndexSummary = mutex.withLock {
        val lastScan = prefs.getLong(KEY_LAST_SCAN, 0L)
        val count = dao.count()
        val fresh = !force && count > 0 && (System.currentTimeMillis() - lastScan) < maxAgeMs
        if (fresh) {
            return IndexSummary(count, -1, -1, -1, -1, cached = true, tookMs = 0)
        }
        return scanAll()
    }

    /** فحص كامل: MediaStore + مجلدات المستخدم، مع دمج الإحصاءات القديمة. */
    suspend fun scanAll(): IndexSummary = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()

        val oldStats = dao.allStats().associateBy { it.uriString }
        val seenKeys = HashSet<String>()     // dedup: nameNorm+size (SAF يكرر MediaStore أحياناً)
        val fresh = ArrayList<FileIndexEntity>(2048)

        fresh += queryAudio(seenKeys)
        fresh += queryImages(seenKeys)
        fresh += queryVideos(seenKeys)
        fresh += queryDocuments(seenKeys)
        fresh += walkUserFolders(seenKeys)

        // دمج إحصاءات اللقطة السابقة في الصفوف الجديدة قبل الاستبدال.
        val merged = fresh.map { row ->
            val old = oldStats[row.uriString]
            if (old != null) {
                row.copy(
                    playCount = old.playCount,
                    lastPlayedAt = old.lastPlayedAt,
                    lastPositionMs = old.lastPositionMs,
                    hidden = old.hidden
                )
            } else row
        }

        dao.clear()
        merged.chunked(400).forEach { chunk ->
            dao.upsertAll(chunk)
            delay(1) // فرصة للجدولة حتى لا نحتكر المعالج على الأجهزة الضعيفة
        }

        prefs.edit().putLong(KEY_LAST_SCAN, System.currentTimeMillis()).apply()

        val summary = IndexSummary(
            total = merged.size,
            audio = merged.count { it.kind == KIND_AUDIO },
            images = merged.count { it.kind == KIND_IMAGE },
            videos = merged.count { it.kind == KIND_VIDEO },
            docs = merged.count { it.kind == KIND_PDF || it.kind == KIND_WORD || it.kind == KIND_EXCEL || it.kind == KIND_OTHER },
            cached = false,
            tookMs = System.currentTimeMillis() - started
        )
        Timber.d("File index rebuilt: %s", summary)
        summary
    }

    // ------------------------------------------------------------------
    // مجلدات المستخدم (اختيارية — تُضاف من شاشة المكتبة عبر SAF)
    // ------------------------------------------------------------------

    fun addSafTree(treeUriString: String) {
        val set = safTrees().toMutableSet()
        set.add(treeUriString)
        prefs.edit().putStringSet(KEY_SAF_TREES, set).apply()
    }

    fun safTrees(): Set<String> = prefs.getStringSet(KEY_SAF_TREES, emptySet()) ?: emptySet()

    /**
     * يحوّل URI شجرة SAF إلى مجلد فعلي قابل للمسح بـ java.io.File.
     * مثال: content://com.android.externalstorage.documents/tree/primary:Documents
     *     ← /storage/emulated/0/Documents
     */
    private fun treeUriToDir(treeUriString: String): File? {
        return try {
            val uri = Uri.parse(treeUriString)
            val docId = uri.lastPathSegment ?: return null   // "primary:Download/Stuff"
            val afterColon = docId.substringAfter(':', "")
            if (afterColon.isEmpty()) null else File(Environment.getExternalStorageDirectory(), Uri.decode(afterColon))
        } catch (e: Exception) {
            Timber.w(e, "Cannot resolve SAF tree %s", treeUriString)
            null
        }
    }

    private fun walkUserFolders(seenKeys: MutableSet<String>): List<FileIndexEntity> {
        val rows = mutableListOf<FileIndexEntity>()
        for (treeString in safTrees()) {
            val root = treeUriToDir(treeString) ?: continue
            if (!root.exists()) continue
            val queue = ArrayDeque<Pair<File, Int>>()
            queue.add(root to 0)
            var visited = 0
            val started = System.currentTimeMillis()
            while (queue.isNotEmpty()) {
                if (visited > MAX_SAF_VISITS || System.currentTimeMillis() - started > MAX_SAF_MS) break
                val (dir, depth) = queue.removeFirst()
                val children = dir.listFiles() ?: continue
                for (child in children) {
                    visited++
                    if (child.isFile) {
                        val ext = child.extension.lowercase()
                        val kind = EXT_KIND[ext] ?: continue
                        val key = AppOpenerManager.normalize(child.name) + "|" + child.length()
                        if (!seenKeys.add(key)) continue // نفس الملف وصل عبر MediaStore
                        val uri = try {
                            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", child)
                        } catch (e: Exception) { continue }
                        rows += FileIndexEntity(
                            uriString = uri.toString(),
                            mediaStoreId = -1L,
                            kind = kind,
                            name = child.name,
                            nameNorm = AppOpenerManager.normalize(child.name),
                            stem = AppOpenerManager.normalize(child.nameWithoutExtension),
                            folder = AppOpenerManager.normalize(child.parentFile?.name ?: ""),
                            folderPath = child.absolutePath,
                            mime = mimeFor(ext),
                            durationMs = 0L,
                            sizeBytes = child.length(),
                            dateModified = child.lastModified() / 1000L,
                            dateAdded = child.lastModified() / 1000L,
                            fromSaf = true
                        )
                    } else if (depth < MAX_SAF_DEPTH && !child.name.startsWith(".")) {
                        queue.add(child to depth + 1)
                    }
                }
            }
        }
        return rows
    }

    // ------------------------------------------------------------------
    // استعلامات MediaStore
    // ------------------------------------------------------------------

    private fun collectionUri(base: Uri): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (base) {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                else -> MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            }
        } else base

    /** العمود المساري المناسب لكل إصدار: RELATIVE_PATH (29+) أو DATA للأقدم. */
    private val pathColumn: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.MediaColumns.RELATIVE_PATH
        } else {
            MediaStore.MediaColumns.DATA
        }

    private fun folderOf(pathRaw: String?): Pair<String, String> {
        val raw = pathRaw.orEmpty()
        if (raw.isEmpty()) return "" to ""
        // RELATIVE_PATH يأتي مثل "Download/Stuff/" أو "DCIM/Camera/"
        val trimmed = raw.trimEnd('/')
        val last = trimmed.substringAfterLast('/')
        return AppOpenerManager.normalize(last) to trimmed
    }

    private fun FileIndexEntity.register(seenKeys: MutableSet<String>): FileIndexEntity? {
        val key = nameNorm + "|" + sizeBytes
        return if (seenKeys.add(key)) this else null
    }

    private fun queryAudio(seenKeys: MutableSet<String>): List<FileIndexEntity> {
        val rows = mutableListOf<FileIndexEntity>()
        val collection = collectionUri(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.MIME_TYPE,
            pathColumn
        )
        try {
            context.contentResolver.query(
                collection, projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { c ->
                val idI = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameI = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val artI = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albI = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durI = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeI = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val addedI = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val modI = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                val mimeI = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val pathI = c.getColumnIndexOrThrow(pathColumn)
                while (c.moveToNext()) {
                    val id = c.getLong(idI)
                    val name = c.getString(nameI) ?: continue
                    val artist = c.getString(artI)?.takeIf { it.isNotBlank() && !it.contains("unknown", true) } ?: ""
                    val (folder, folderPath) = folderOf(c.getString(pathI))
                    FileIndexEntity(
                        uriString = ContentUris.withAppendedId(collection, id).toString(),
                        mediaStoreId = id,
                        kind = KIND_AUDIO,
                        name = name,
                        nameNorm = AppOpenerManager.normalize(name),
                        stem = AppOpenerManager.normalize(name.substringBeforeLast('.')),
                        artist = artist,
                        artistNorm = AppOpenerManager.normalize(artist),
                        album = c.getString(albI) ?: "",
                        folder = folder,
                        folderPath = folderPath,
                        mime = c.getString(mimeI) ?: "audio/*",
                        durationMs = c.getLong(durI),
                        sizeBytes = c.getLong(sizeI),
                        dateAdded = c.getLong(addedI),
                        dateModified = c.getLong(modI)
                    ).register(seenKeys)?.let { rows += it }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "MediaStore audio indexing failed")
        }
        return rows
    }

    private fun queryImages(seenKeys: MutableSet<String>) = queryMediaKind(
        collection = collectionUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI),
        kind = KIND_IMAGE,
        seenKeys = seenKeys,
        withDuration = false
    )

    private fun queryVideos(seenKeys: MutableSet<String>) = queryMediaKind(
        collection = collectionUri(MediaStore.Video.Media.EXTERNAL_CONTENT_URI),
        kind = KIND_VIDEO,
        seenKeys = seenKeys,
        withDuration = true
    )

    private fun queryMediaKind(
        collection: Uri,
        kind: String,
        seenKeys: MutableSet<String>,
        withDuration: Boolean
    ): List<FileIndexEntity> {
        val rows = mutableListOf<FileIndexEntity>()
        val columns = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
            pathColumn
        )
        if (withDuration) columns += MediaStore.MediaColumns.DURATION
        try {
            context.contentResolver.query(
                collection, columns.toTypedArray(), null, null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { c ->
                val idI = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val addedI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val modI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val mimeI = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val pathI = c.getColumnIndexOrThrow(pathColumn)
                val durI = if (withDuration) c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION) else -1
                while (c.moveToNext()) {
                    val id = c.getLong(idI)
                    val name = c.getString(nameI) ?: continue
                    val (folder, folderPath) = folderOf(c.getString(pathI))
                    FileIndexEntity(
                        uriString = ContentUris.withAppendedId(collection, id).toString(),
                        mediaStoreId = id,
                        kind = kind,
                        name = name,
                        nameNorm = AppOpenerManager.normalize(name),
                        stem = AppOpenerManager.normalize(name.substringBeforeLast('.')),
                        folder = folder,
                        folderPath = folderPath,
                        mime = c.getString(mimeI) ?: when (kind) { KIND_IMAGE -> "image/*"; else -> "video/*" },
                        durationMs = if (durI >= 0) c.getLong(durI) else 0L,
                        sizeBytes = c.getLong(sizeI),
                        dateAdded = c.getLong(addedI),
                        dateModified = c.getLong(modI)
                    ).register(seenKeys)?.let { rows += it }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "MediaStore %s indexing failed", kind)
        }
        return rows
    }

    private fun queryDocuments(seenKeys: MutableSet<String>): List<FileIndexEntity> {
        val rows = mutableListOf<FileIndexEntity>()
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val mimes = DOC_MIMES
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            pathColumn
        )
        val sel = StringBuilder(MediaStore.Files.FileColumns.MIME_TYPE)
            .append(" IN (").append(mimes.joinToString(",") { "?" }).append(")")
        // على الأجهزة الأقدم نلتقط الامتدادات مباشرة لأن MIME قد يكون null.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            sel.append(" OR (")
            DOC_EXT.forEachIndexed { i, ext ->
                if (i > 0) sel.append(" OR ")
                sel.append(MediaStore.Files.FileColumns.DISPLAY_NAME).append(" LIKE ?")
            }
            sel.append(")")
        }
        val args = mutableListOf<String>().apply {
            addAll(mimes)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) DOC_EXT.forEach { add("%.$ext") }
        }
        try {
            context.contentResolver.query(
                collection, projection, sel.toString(), args.toTypedArray(),
                "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
            )?.use { c ->
                val idI = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameI = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val mimeI = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val sizeI = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val addedI = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
                val modI = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val pathI = c.getColumnIndexOrThrow(pathColumn)
                while (c.moveToNext()) {
                    val id = c.getLong(idI)
                    val name = c.getString(nameI) ?: continue
                    val ext = name.substringAfterLast('.', "").lowercase()
                    val kind = EXT_KIND[ext] ?: KIND_OTHER
                    val (folder, folderPath) = folderOf(c.getString(pathI))
                    FileIndexEntity(
                        uriString = ContentUris.withAppendedId(collection, id).toString(),
                        mediaStoreId = id,
                        kind = kind,
                        name = name,
                        nameNorm = AppOpenerManager.normalize(name),
                        stem = AppOpenerManager.normalize(name.substringBeforeLast('.')),
                        folder = folder,
                        folderPath = folderPath,
                        mime = c.getString(mimeI) ?: mimeFor(ext),
                        sizeBytes = c.getLong(sizeI),
                        dateAdded = c.getLong(addedI),
                        dateModified = c.getLong(modI)
                    ).register(seenKeys)?.let { rows += it }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "MediaStore documents indexing failed")
        }
        return rows
    }

    // ------------------------------------------------------------------
    // ثوابت
    // ------------------------------------------------------------------

    companion object {
        const val KIND_AUDIO = "AUDIO"
        const val KIND_IMAGE = "IMAGE"
        const val KIND_VIDEO = "VIDEO"
        const val KIND_PDF = "PDF"
        const val KIND_WORD = "WORD"
        const val KIND_EXCEL = "EXCEL"
        const val KIND_OTHER = "OTHER"

        private const val KEY_LAST_SCAN = "last_scan_at"
        private const val KEY_SAF_TREES = "saf_trees"

        /** اللقطة تُعتبر طازجة لنصف ساعة — الفحص الكامل مكلف نسبياً على مكتبات ضخمة. */
        private const val STALE_MS = 30L * 60_000L

        private const val MAX_SAF_DEPTH = 4
        private const val MAX_SAF_VISITS = 4000
        private const val MAX_SAF_MS = 4000L

        /** امتداد ← تصنيف (يغذي كل من MediaStore.Files ومسح مجلدات المستخدم). */
        val EXT_KIND: Map<String, String> = buildMap {
            listOf("mp3", "m4a", "aac", "amr", "wav", "ogg", "opus", "flac").forEach { put(it, KIND_AUDIO) }
            listOf("jpg", "jpeg", "png", "webp", "gif", "heic", "bmp").forEach { put(it, KIND_IMAGE) }
            listOf("mp4", "3gp", "mkv", "avi", "webm", "mov").forEach { put(it, KIND_VIDEO) }
            put("pdf", KIND_PDF)
            listOf("doc", "docx", "txt", "rtf", "odt").forEach { put(it, KIND_WORD) }
            listOf("xls", "xlsx", "csv").forEach { put(it, KIND_EXCEL) }
        }

        private val DOC_MIMES = listOf(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/plain", "text/csv", "application/rtf"
        )

        private val DOC_EXT = listOf("pdf", "doc", "docx", "txt", "rtf", "odt", "xls", "xlsx", "csv")

        fun mimeFor(ext: String): String = when (ext.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "3gp" -> "video/3gpp"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "amr" -> "audio/amr"
            "wav" -> "audio/wav"
            "ogg", "opus" -> "audio/ogg"
            "flac" -> "audio/flac"
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "txt" -> "text/plain"
            "csv" -> "text/csv"
            else -> "*/*"
        }
    }
}
