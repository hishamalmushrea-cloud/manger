package com.example.processor

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject

/**
 * Deep-link into YouTube, no AI involved:
 *  - «افتح يوتيوب وابحث عن فيروز»
 *  - «شغل في يوتيوب وصفة كبسة»
 *  - «ابحث في اليوتيوب عن آخر المباريات»
 * Opens the YouTube app's native search; falls back to the browser if the
 * app is not installed.
 */
class YouTubeStrategy @Inject constructor(
    @ApplicationContext private val context: Context
) : CommandStrategy {

    override fun canHandle(command: String): Boolean {
        val mentionsYouTube = YT_WORDS.any { command.contains(it) }
        if (!mentionsYouTube) return false
        val hasSearchIntent = INTENT_WORDS.any { command.contains(it) }
        if (!hasSearchIntent) return false
        return extractQuery(command).isNotBlank()
    }

    override suspend fun execute(command: String, isScheduled: Boolean): CommandResult {
        val query = extractQuery(command)
        if (query.isBlank()) return CommandResult(false, "عن ماذا أبحث لك في يوتيوب؟")

        // 1) Native YouTube app deep-link
        try {
            val ytIntent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage("com.google.android.youtube")
                putExtra(SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(ytIntent)
            return CommandResult(true, "بحثت في يوتيوب عن: $query")
        } catch (e: Exception) {
            Timber.w(e, "YouTube deep-link failed — falling back to browser")
        }

        // 2) Browser fallback
        return try {
            val web = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(web)
            CommandResult(true, "بحثت في يوتيوب عن: $query")
        } catch (e: Exception) {
            Timber.e(e, "YouTube fallback failed")
            CommandResult(false, "تعذر فتح يوتيوب")
        }
    }

    /** Removes command/filler words, keeping only what to search for. */
    internal fun extractQuery(command: String): String {
        var result = " $command "
        for (phrase in STRIP_WORDS) {
            result = result.replace(" $phrase ", " ")
        }
        return result.replace(Regex("\\s+"), " ").trim()
    }

    companion object {
        private val YT_WORDS = listOf("يوتيوب", "اليوتيوب", "يو تيوب", "يوتييوب", "youtube")
        private val INTENT_WORDS = listOf(
            "ابحث", "بحث", "دوّر", "دور", "فتش", "شغل", "تشغيل", "عن", "play", "search"
        )
        private val STRIP_WORDS = listOf(
            "وابحث عن", "ابحث عن", "افتح تطبيق", "افتح برنامج",
            "افتح", "تطبيق", "برنامج", "وابحث", "ابحث", "بحث", "دوّر", "دور", "فتش",
            "شغل", "تشغيل", "شغلي", "لي", "عن", "في", "على", "من", "فضلك",
            "يوتيوب", "اليوتيوب", "يو تيوب", "يوتييوب", "youtube",
            "فيديو", "فيديوهات", "حلقة", "مقطع", "play", "search", "for"
        )
    }
}
