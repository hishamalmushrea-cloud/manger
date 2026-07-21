package com.example.managers

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A located file anywhere on the device. */
data class FileHit(
    val name: String,
    val displayPath: String,
    val uriString: String,
    val mime: String,
    val modified: Long
)

enum class FileKind { IMAGE, VIDEO, AUDIO, PDF, WORD, EXCEL }

/**
 * Smart on-device file search: newest photo/video/recording, latest PDF,
 * and fuzzy name search across Downloads/Documents/media folders.
 * Opens hits via the user's default viewer (FileProvider-backed).
 */
@Singleton
class FileSearchManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    // ------------------------------------------------------------------
    // Permissions
    // ------------------------------------------------------------------

    fun hasMediaPermission(kind: FileKind): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
            val perm = when (kind) {
                FileKind.IMAGE -> Manifest.permission.READ_MEDIA_IMAGES
                FileKind.VIDEO -> Manifest.permission.READ_MEDIA_VIDEO
                else -> Manifest.permission.READ_MEDIA_AUDIO
            }
            granted(perm)
        }
        else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** "All files access" — needed for reliable document (PDF/Word/Excel) search on 11+. */
    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private fun granted(perm: String) =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    /** Opens the system page where the user grants "All files access". */
    fun openAllFilesAccessSettings() {
        try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Timber.e(e, "Cannot open all-files settings")
        }
    }

    // ------------------------------------------------------------------
    // "Show me the latest X"
    // ------------------------------------------------------------------

    fun lastOfType(kind: FileKind): FileHit? {
        val mimes = MIME_MAP[kind] ?: return null
        return queryFiles(mimes, null, maxResults = 1).firstOrNull()
    }

    // ------------------------------------------------------------------
    // Name search
    // ------------------------------------------------------------------

    /** Top fuzzy matches for [query] across the given kinds (best first). */
    fun searchByName(query: String, kinds: Set<FileKind>): List<FileHit> {
        val q = AppOpenerManager.normalize(query)
        if (q.isEmpty()) return emptyList()
        val mimes = kinds.flatMap { MIME_MAP[it] ?: emptyList() }
        val viaStore = queryFiles(mimes, null, maxResults = 400)
        val walked = if (hasAllFilesAccess()) walkFolders(q, kinds) else emptyList()
        val merged = (viaStore + walked).distinctBy { it.name.lowercase() }
        val words = q.split(" ").filter { it.isNotBlank() }
        return merged
            .map { it to scoreMatch(q, words, AppOpenerManager.normalize(it.name)) }
            .filter { it.second >= 35 }
            .sortedWith(compareByDescending<Pair<FileHit, Int>> { it.second }.thenByDescending { it.first.modified })
            .take(8)
            .map { it.first }
    }

    // ------------------------------------------------------------------
    // MediaStore.Files query (works for media on 13+ w/ media perms; docs on ≤12 or all-files)
    // ------------------------------------------------------------------

    private fun queryFiles(mimes: List<String>, nameLike: String?, maxResults: Int): List<FileHit> {
        val hits = mutableListOf<FileHit>()
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_MODIFIED
        )
        val sel = StringBuilder()
        val args = mutableListOf<String>()
        if (mimes.isNotEmpty()) {
            sel.append(MediaStore.Files.FileColumns.MIME_TYPE)
                .append(" IN (").append(mimes.joinToString(",") { "?" }).append(")")
            args.addAll(mimes)
        }
        if (!nameLike.isNullOrBlank()) {
            if (sel.isNotEmpty()) sel.append(" AND ")
            sel.append(MediaStore.Files.FileColumns.DISPLAY_NAME).append(" LIKE ?")
            args.add("%$nameLike%")
        }
        try {
            context.contentResolver.query(
                collection, projection,
                if (sel.isEmpty()) null else sel.toString(),
                if (args.isEmpty()) null else args.toTypedArray(),
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                while (cursor.moveToNext() && hits.size < maxResults) {
                    val name = cursor.getString(nameCol) ?: continue
                    val id = cursor.getLong(idCol)
                    val mime = cursor.getString(mimeCol) ?: continue
                    hits.add(
                        FileHit(
                            name = name,
                            displayPath = "",
                            uriString = ContentUris.withAppendedId(collection, id).toString(),
                            mime = mime,
                            modified = cursor.getLong(dateCol)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "MediaStore files query failed")
        }
        return hits
    }

    // ------------------------------------------------------------------
    // Direct folder walk (Downloads/Documents/etc.) — needs All-files access
    // ------------------------------------------------------------------

    private fun walkFolders(query: String, kinds: Set<FileKind>): List<FileHit> {
        val exts = kinds.flatMap { EXT_MAP[it] ?: emptyList() }.toSet()
        val roots = listOfNotNull(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS)
        ).filter { it.exists() }
        val root = Environment.getExternalStorageDirectory()
        val startTime = System.currentTimeMillis()
        val hits = mutableListOf<File>()
        val queue = ArrayDeque<Pair<File, Int>>()
        roots.forEach { queue.add(it to 0) }
        root.listFiles()?.filter { it.isFile }?.forEach { hits.addIfExt(it, exts) }

        var visited = 0
        while (queue.isNotEmpty() && hits.size < MAX_WALK_HITS && visited < MAX_WALK_VISITS) {
            if (System.currentTimeMillis() - startTime > MAX_WALK_MS) break
            val (dir, depth) = queue.removeFirst()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                visited++
                if (child.isFile) hits.addIfExt(child, exts)
                else if (depth < MAX_WALK_DEPTH && !child.name.startsWith(".")) {
                    queue.add(child to depth + 1)
                }
            }
        }
        return hits.mapNotNull { file ->
            val q = AppOpenerManager.normalize(file.name)
            val words = AppOpenerManager.normalize(query).split(" ").filter { it.isNotBlank() }
            if (scoreMatch(AppOpenerManager.normalize(query), words, q) < 35) return@mapNotNull null
            val uri = try {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            } catch (e: Exception) {
                return@mapNotNull null
            }
            FileHit(
                name = file.name,
                displayPath = file.parentFile?.name ?: "",
                uriString = uri.toString(),
                mime = guessMime(file.extension),
                modified = file.lastModified() / 1000L
            )
        }
    }

    private fun MutableList<File>.addIfExt(f: File, exts: Set<String>) {
        if (f.extension.lowercase() in exts) add(f)
    }

    private fun guessMime(ext: String): String = when (ext.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "3gp" -> "video/3gp"
        "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "amr" -> "audio/amr"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        else -> "*/*"
    }

    // ------------------------------------------------------------------
    // Matching & opening
    // ------------------------------------------------------------------

    private fun scoreMatch(q: String, words: List<String>, name: String): Int {
        if (name.isEmpty() || q.isEmpty()) return 0
        if (name == q) return 100
        if (name.contains(q) && q.length > 1) return 90
        val stem = name.substringBeforeLast('.')
        if (stem.contains(q) && q.length > 1) return 85
        if (words.isNotEmpty() && words.all { name.contains(it) }) return 70
        if (words.any { it.length > 2 && name.contains(it) }) return 45
        return 0
    }

    /** Open with the user's default viewer; returns false when none can. */
    fun open(hit: FileHit): Boolean {
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(hit.uriString), hit.mime.ifBlank { "*/*" })
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            true
        } catch (e: Exception) {
            Timber.e(e, "No viewer for ${hit.name}")
            false
        }
    }

    companion object {
        private const val MAX_WALK_MS = 3500L
        private const val MAX_WALK_DEPTH = 3
        private const val MAX_WALK_VISITS = 6000
        private const val MAX_WALK_HITS = 400

        private val MIME_MAP = mapOf(
            FileKind.IMAGE to listOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/heic", "image/heif"),
            FileKind.VIDEO to listOf("video/mp4", "video/3gp", "video/x-matroska", "video/avi", "video/webm"),
            FileKind.AUDIO to listOf("audio/mpeg", "audio/mp4", "audio/x-m4a", "audio/aac", "audio/amr", "audio/ogg", "audio/wav"),
            FileKind.PDF to listOf("application/pdf"),
            FileKind.WORD to listOf(
                "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            ),
            FileKind.EXCEL to listOf(
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            )
        )

        private val EXT_MAP = mapOf(
            FileKind.IMAGE to listOf("jpg", "jpeg", "png", "webp", "gif", "heic"),
            FileKind.VIDEO to listOf("mp4", "3gp", "mkv", "avi", "webm"),
            FileKind.AUDIO to listOf("mp3", "m4a", "aac", "amr", "wav", "ogg"),
            FileKind.PDF to listOf("pdf"),
            FileKind.WORD to listOf("doc", "docx"),
            FileKind.EXCEL to listOf("xls", "xlsx")
        )
    }
}
