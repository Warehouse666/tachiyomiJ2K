package eu.kanade.tachiyomi.ui.source.searchhistory

import android.view.MenuItem
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.bluelinelabs.conductor.Controller
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.util.view.activityBinding
import eu.kanade.tachiyomi.util.view.moveRecyclerViewUp
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
 */
class SearchHistoryDelegate(
    private val controller: Controller,
    private val container: () -> ViewGroup,
    private val recycler: () -> RecyclerView?,
    private val isEnabled: () -> Boolean = { true },
    private val extraShouldShow: () -> Boolean = { true },
    private val requireSearchExpanded: Boolean = true,
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
                onQueryClicked = { searchView()?.setQuery(it, true) }
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
        val shouldShow =
            show &&
                extraShouldShow() &&
                (!requireSearchExpanded || controller.activityBinding?.searchToolbar?.isSearchExpanded == true) &&
                SearchHistoryView.hasHistory(preferences)
        val historyView = setUp() ?: return
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
