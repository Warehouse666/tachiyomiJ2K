package eu.kanade.tachiyomi.ui.source.searchhistory

import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.isIncognitoModeForSource
import kotlinx.serialization.Serializable

private const val SEARCH_HISTORY_LIMIT = 20

/**
 * One remembered search: the query text, plus any filters that were changed from default when
 * it ran. [query] is blank for a filters-only snapshot (filters changed with nothing searched) -
 * such entries carry [timestamp] instead, so they can be shown/identified by date. [sourceId] is
 * the source it was captured on, so [filters] can be applied exactly (no loose matching needed)
 * when reused on that same source.
 */
@Serializable
data class SearchHistoryEntry(
    val query: String,
    val filters: List<SavedFilter> = emptyList(),
    val timestamp: Long? = null,
    val sourceId: Long? = null,
)

/**
 * @param filters filters that were changed from the source's default when this query was searched.
 * @param sourceId the source being searched, so its extension's incognito setting counts too.
 * Null for global search, which only cares about the app wide one.
 */
fun PreferencesHelper.addToSearchHistory(
    query: String,
    filters: List<SavedFilter> = emptyList(),
    sourceId: Long? = null,
) {
    if (!showBrowseSearchHistory().get()) return
    if (isIncognitoModeForSource(sourceId, this)) return
    val trimmedQuery = query.trim()
    if (trimmedQuery.isBlank()) return
    val pref = browseSearchHistory()
    // drop the old copy first so searching the same thing twice bumps it instead of doubling up
    val history =
        listOf(SearchHistoryEntry(trimmedQuery, filters, sourceId = sourceId)) +
            pref.get().filterNot { it.query.equals(trimmedQuery, true) }
    pref.set(history.take(SEARCH_HISTORY_LIMIT))
}

/**
 * Saves a dateless filter change as its own entry, for when the filter sheet closes with
 * something changed but no search query to attach it to. Each snapshot is kept as its own entry
 * (unlike [addToSearchHistory], which dedupes by query) since a blank query isn't a meaningful
 * identity to collapse multiple snapshots onto.
 */
fun PreferencesHelper.addFilterSnapshotToSearchHistory(
    filters: List<SavedFilter>,
    sourceId: Long? = null,
) {
    if (!showBrowseSearchHistory().get()) return
    if (isIncognitoModeForSource(sourceId, this)) return
    if (filters.isEmpty()) return
    val pref = browseSearchHistory()
    val entry = SearchHistoryEntry(query = "", filters = filters, timestamp = System.currentTimeMillis(), sourceId = sourceId)
    pref.set((listOf(entry) + pref.get()).take(SEARCH_HISTORY_LIMIT))
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
