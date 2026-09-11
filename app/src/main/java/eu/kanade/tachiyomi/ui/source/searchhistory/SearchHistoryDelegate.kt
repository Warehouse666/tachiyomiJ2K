package eu.kanade.tachiyomi.ui.source.searchhistory

import android.view.MenuItem
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.bluelinelabs.conductor.Controller
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.util.view.activityBinding
import eu.kanade.tachiyomi.util.view.moveRecyclerViewUp
import eu.kanade.tachiyomi.util.view.snack
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Wires a [SearchHistoryView] for a controller
 *
 * @param container the ViewGroup the history view should be added to
 * @param recycler the recycler view behind the history view
 * @param isEnabled whether this controller supports the feature at all
 * @param extraShouldShow an extra condition for the history to show
 * @param requireSearchExpanded whether the search toolbar must be expanded before showing
 * @param onApplyFilters called when a picked entry carries filters, so the controller can apply
 * them to whatever single source's [eu.kanade.tachiyomi.source.model.FilterList] it owns (only
 * [eu.kanade.tachiyomi.ui.source.browse.BrowseSourceController] has one), passing along the
 * source id the entry was captured on so an exact match can skip loose matching entirely. Return
 * how many of them found a match - anything short of [FilterApplyResult.ALL] shows a heads-up.
 * @param showFilterSnapshots whether to show dateless, query-less filter-only entries - these
 * can only be applied on the source screen they were captured on, so
 * [eu.kanade.tachiyomi.ui.source.BrowseController] and
 * [eu.kanade.tachiyomi.ui.source.globalsearch.GlobalSearchController] leave this off. A lambda
 * (not a fixed value) since e.g. whether the current source even has filters isn't known yet
 * when this delegate is constructed.
 */
class SearchHistoryDelegate(
    private val controller: Controller,
    private val container: () -> ViewGroup,
    private val recycler: () -> RecyclerView?,
    private val isEnabled: () -> Boolean = { true },
    private val extraShouldShow: () -> Boolean = { true },
    private val requireSearchExpanded: Boolean = true,
    private val onApplyFilters: (List<SavedFilter>, Long?) -> FilterApplyResult = { _, _ -> FilterApplyResult.ALL },
    private val showFilterSnapshots: () -> Boolean = { false },
) {
    private val preferences: PreferencesHelper by lazy { Injekt.get() }

    var view: SearchHistoryView? = null
        private set

    private fun searchView(): SearchView? = controller.activityBinding?.searchToolbar?.searchView

    fun setUp(): SearchHistoryView? {
        view?.let { return it }
        if (!isEnabled() || !preferences.showBrowseSearchHistory().get()) return null
        view =
            SearchHistoryView(container().context).apply {
                isVisible = false
                onQueryClicked = { entry ->
                    val result =
                        if (entry.filters.isEmpty()) FilterApplyResult.ALL else onApplyFilters(entry.filters, entry.sourceId)
                    val isSnapshot = entry.query.isBlank()
                    if (isSnapshot) {
                        if (result != FilterApplyResult.NONE) {
                            // onApplyFilters above already re-searched with it, so there's no
                            // reason to keep the search bar open - if nothing landed there's
                            // nothing to show for it, so leave things as they were instead
                            setVisible(false)
                            controller.activityBinding
                                ?.searchToolbar
                                ?.searchItem
                                ?.collapseActionView()
                        }
                    } else {
                        searchView()?.setQuery(entry.query, true)
                    }
                    val message =
                        when (result) {
                            FilterApplyResult.NONE -> if (isSnapshot) R.string.no_filters_applied else R.string.some_filters_not_applied
                            FilterApplyResult.SOME -> R.string.some_filters_not_applied
                            FilterApplyResult.ALL -> null
                        }
                    message?.let { container().snack(it).moveAboveSafeAreas(container().context) }
                }
                onQueryFilled = { searchView()?.setQuery(it, false) }
                onHistoryEmptied = { setVisible(false) }
                container().addView(this, ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
        return view
    }

    /** Pads the history list under whatever height the app bar is currently showing on screen. */
    fun updatePadding() {
        val recycler = recycler() ?: return
        val appBar = controller.activityBinding?.appBar
        val visibleAppBarHeight =
            if (appBar != null) {
                (appBar.height + appBar.translationY).toInt().coerceAtLeast(0)
            } else {
                recycler.paddingTop
            }
        view?.setContentPadding(top = visibleAppBarHeight, bottom = recycler.paddingBottom)
    }

    fun setVisible(show: Boolean) {
        val historyView = setUp() ?: return
        // re-evaluated every call, not just once at setUp() - e.g. a source's filters might not
        // have loaded yet the first time this ran
        val showFilterSnapshots = showFilterSnapshots()
        historyView.showFilterSnapshots = showFilterSnapshots
        val shouldShow =
            show &&
                extraShouldShow() &&
                (!requireSearchExpanded || controller.activityBinding?.searchToolbar?.isSearchExpanded == true) &&
                SearchHistoryView.hasHistory(preferences, includeFilterSnapshots = showFilterSnapshots)
        if (historyView.isVisible == shouldShow) return
        historyView.isVisible = shouldShow
        if (!shouldShow) {
            recycler()?.suppressLayout(false)
            return
        }
        val revealHistory = {
            // freeze the recycler behind the history it can't drag the app bar
            recycler()?.suppressLayout(true)
            updatePadding()
            historyView.scrollToTop()
        }
        if (controller.activityBinding?.appBar?.y != 0f) {
            controller.moveRecyclerViewUp()
            recycler()?.post { revealHistory() }
        } else {
            revealHistory()
        }
    }

    fun onActionViewExpand(item: MenuItem?) = setVisible(true)

    fun onActionViewCollapse(item: MenuItem?) = setVisible(false)

    fun onDestroyView() {
        view = null
    }
}
