package eu.kanade.tachiyomi.ui.source.searchhistory

import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.isIncognitoModeForSource
import kotlinx.serialization.Serializable

private const val SEARCH_HISTORY_LIMIT = 20

/** [timestamp] is each entry's identity, so the same query can be saved again with new filters. */
@Serializable
data class SearchHistoryEntry(
    val query: String,
    val filters: List<SavedFilter> = emptyList(),
    val timestamp: Long? = null,
    val sourceId: Long? = null,
)

/**
 * @param filters filters that changed from the source's default when this was saved.
 * @param sourceId the source being searched, so its extension's incognito setting counts too.
 * Null for global search, which only cares about the app wide one.
 */
fun PreferencesHelper.addToSearchHistory(
    query: String = "",
    filters: List<SavedFilter> = emptyList(),
    sourceId: Long? = null,
) {
    if (!showBrowseSearchHistory().get()) return
    if (isIncognitoModeForSource(sourceId, this)) return
    val trimmedQuery = query.trim()
    if (trimmedQuery.isBlank() && filters.isEmpty()) return
    val pref = browseSearchHistory()
    val entry = SearchHistoryEntry(trimmedQuery, filters, System.currentTimeMillis(), sourceId)
    // bump instead of duplicating only when it's a truly identical repeat - same query, same filters
    val history =
        listOf(entry) + pref.get().filterNot { it.query.equals(trimmedQuery, true) && it.filters == filters }
    pref.set(history.take(SEARCH_HISTORY_LIMIT))
}

fun PreferencesHelper.removeFromSearchHistory(position: Int) {
    val pref = browseSearchHistory()
    val history = pref.get().toMutableList()
    if (position !in history.indices) return
    history.removeAt(position)
    pref.set(history)
}

/** Undoes [removeFromSearchHistory], putting the entry back at the index it was removed from. */
fun PreferencesHelper.reinsertIntoSearchHistory(
    entry: SearchHistoryEntry,
    position: Int,
) {
    val pref = browseSearchHistory()
    val history = pref.get().toMutableList()
    history.add(position.coerceIn(0, history.size), entry)
    pref.set(history.take(SEARCH_HISTORY_LIMIT))
}

fun PreferencesHelper.clearSearchHistory() = browseSearchHistory().delete()
