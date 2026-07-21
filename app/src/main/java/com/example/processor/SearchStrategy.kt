package com.example.processor

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject

/** "ابحث عن ..."، "دوّر على ..."، "search for ..." — opens a Google search. */
class SearchStrategy @Inject constructor(
    @ApplicationContext private val context: Context
) : CommandStrategy {

    override fun canHandle(command: String): Boolean {
        return command.contains("ابحث") || command.contains("بحث عن") ||
                command.contains("دور على") || command.contains("دوّر على") ||
                command.contains("فتش عن") || command.contains("search for") ||
                command.contains("google")
    }

    override suspend fun execute(command: String, isScheduled: Boolean): CommandResult {
        val query = SEARCH_PREFIXES.fold(command) { acc, p ->
            if (acc.startsWith(p)) acc.removePrefix(p) else acc
        }.replace(Regex("^(عن|عن موضوع|على)\\s+"), "").trim()

        if (query.isBlank()) {
            return CommandResult(false, "عن ماذا تريد البحث؟")
        }
        return try {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            CommandResult(true, "فتحت لك البحث عن: $query")
        } catch (e: Exception) {
            Timber.e(e, "Web search failed")
            CommandResult(false, "تعذر فتح البحث")
        }
    }

    companion object {
        private val SEARCH_PREFIXES = listOf(
            "ابحث لي عن", "ابحث عن", "ابحث", "بحث عن", "دور على", "دوّر على",
            "فتش عن", "search for", "search"
        )
    }
}
