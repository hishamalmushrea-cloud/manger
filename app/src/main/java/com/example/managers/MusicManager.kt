package com.example.managers

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device music library: finds tracks by title/artist (or picks one at
 * random) and hands playback to the user's preferred music player.
 * 100% offline — no internet, no AI, no API keys.
 */
@Singleton
class MusicManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    data class Track(val id: Long, val title: String, val artist: String, val uri: Uri)

    // ------------------------------------------------------------------
    // Permissions
    // ------------------------------------------------------------------

    fun hasAudioPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
    }

    // ------------------------------------------------------------------
    // Library access (MediaStore)
    // ------------------------------------------------------------------

    /** All music tracks stored on the device (newest first). */
    fun getAllTracks(): List<Track> {
        val tracks = mutableListOf<Track>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST
        )
        try {
            context.contentResolver.query(
                collection,
                projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    tracks.add(
                        Track(
                            id = id,
                            title = cursor.getString(titleCol) ?: "بدون اسم",
                            artist = cursor.getString(artistCol) ?: "",
                            uri = ContentUris.withAppendedId(collection, id)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to query MediaStore audio")
        }
        Timber.d("Indexed %d music tracks", tracks.size)
        return tracks
    }

    /** Picks any random track — used for «شغل أي أغنية». */
    fun randomTrack(): Track? = getAllTracks().randomOrNull()

    /** Best fuzzy match for a free-text query against title, then artist. */
    fun findTrack(query: String): Track? {
        val q = AppOpenerManager.normalize(query)
        if (q.isEmpty()) return null
        var best: Track? = null
        var bestScore = 0
        for (track in getAllTracks()) {
            val score = matchScore(
                q,
                AppOpenerManager.normalize(track.title),
                AppOpenerManager.normalize(track.artist)
            )
            if (score > bestScore) {
                bestScore = score
                best = track
            }
        }
        return if (bestScore >= MIN_SCORE) best else null
    }

    private fun matchScore(q: String, title: String, artist: String): Int {
        if (title == q) return 100
        if (q.length > 2 && title.contains(q)) return 90
        if (title.length > 2 && q.contains(title)) return 85
        if (q.length > 2 && artist.contains(q)) return 80
        val words = q.split(" ").filter { it.length > 1 }
        if (words.isNotEmpty() && words.all { title.contains(it) || artist.contains(it) }) return 70
        return 0
    }

    // ------------------------------------------------------------------
    // Playback
    // ------------------------------------------------------------------

    /** Hands the track to the user's preferred music player via ACTION_VIEW. */
    fun playTrack(track: Track): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(track.uri, "audio/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Timber.e(e, "No app could play ${track.title}")
            false
        }
    }

    /** Sends a media key to whichever app owns the current media session. */
    private fun dispatchMediaKey(keyCode: Int) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    fun pause() = dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
    fun next() = dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
    fun previous() = dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)

    companion object {
        private const val MIN_SCORE = 40
    }
}
