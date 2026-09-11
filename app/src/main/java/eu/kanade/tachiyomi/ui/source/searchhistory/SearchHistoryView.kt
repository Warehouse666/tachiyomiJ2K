package eu.kanade.tachiyomi.ui.source.searchhistory

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePaddingRelative
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.adapters.ItemAdapter
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.databinding.SearchHistoryViewBinding
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.view.GroupedRowDivider
import eu.kanade.tachiyomi.util.view.snack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

/**
 * Recent browse queries, shown over a browse screen's content while its search bar is open and
 * empty. Reads the pref itself so a query saved on one screen shows up on the next one.
 */
class SearchHistoryView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : LinearLayout(context, attrs) {
        private val preferences: PreferencesHelper by injectLazy()
        private val binding: SearchHistoryViewBinding
        private val itemAdapter = ItemAdapter<SearchHistoryItem>()
        private val fastAdapter = FastAdapter.with(itemAdapter)
        private var scope: CoroutineScope? = null

        var onQueryClicked: (SearchHistoryEntry) -> Unit = { _ -> }
        var onQueryFilled: (String) -> Unit = { _ -> }
        var onHistoryEmptied: () -> Unit = { }
        var showFilterSnapshots: Boolean = true

        init {
            orientation = VERTICAL
            setBackgroundColor(context.getResourceColor(R.attr.background))
            binding = SearchHistoryViewBinding.inflate(LayoutInflater.from(context), this)
            binding.recycler.layoutManager = LinearLayoutManager(context)
            binding.recycler.adapter = fastAdapter
            binding.recycler.addItemDecoration(
                GroupedRowDivider(context, isGroupedRow = { it is SearchHistoryItem.ViewHolder }),
            )
            fastAdapter.onClickListener = { _, _, item, _ ->
                onQueryClicked(SearchHistoryEntry(item.query, item.filters, item.timestamp, item.sourceId))
                true
            }
            fastAdapter.onLongClickListener = { view, _, _, position ->
                showDeletePopup(view, position)
                true
            }
            binding.clearAllButton.setOnClickListener { preferences.clearSearchHistory() }

            val swipeCallback = SwipeDeleteCallback { position -> deleteAt(position) }
            ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.recycler)

            binding.recycler.addOnScrollListener(
                object : RecyclerView.OnScrollListener() {
                    override fun onScrollStateChanged(
                        recyclerView: RecyclerView,
                        newState: Int,
                    ) {
                        if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                            context
                                .getSystemService<InputMethodManager>()
                                ?.hideSoftInputFromWindow(windowToken, 0)
                        }
                    }
                },
            )
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            scope = newScope
            preferences
                .browseSearchHistory()
                .asFlow()
                .onEach(::setHistory)
                .launchIn(newScope)
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            scope?.cancel()
            scope = null
        }

        fun setContentPadding(
            top: Int,
            bottom: Int,
        ) {
            binding.header.updatePaddingRelative(top = top)
            binding.recycler.updatePaddingRelative(bottom = bottom)
        }

        fun scrollToTop() = binding.recycler.scrollToPosition(0)

        private fun showDeletePopup(
            anchor: View,
            position: Int,
        ) {
            val popup = PopupMenu(anchor.context, anchor, Gravity.NO_GRAVITY)
            popup.menu.add(0, 0, 0, R.string.remove)
            popup.setOnMenuItemClickListener {
                deleteAt(position)
                true
            }
            popup.show()
        }

        // remove locally first so the item animator plays; the flow's later same-size set() is a silent rebind
        private fun deleteAt(position: Int) {
            val item = itemAdapter.getAdapterItem(position)
            val entry = SearchHistoryEntry(item.query, item.filters, item.timestamp, item.sourceId)
            itemAdapter.remove(position)
            preferences.removeFromSearchHistory(position)
            val undoSnack =
                snack(R.string.search_removed) {
                    setAction(R.string.undo) {
                        preferences.reinsertIntoSearchHistory(entry, position)
                    }
                }
            undoSnack.moveAboveSafeAreas(context)
            (context as? MainActivity)?.setUndoSnackBar(undoSnack)
        }

        private fun setHistory(history: List<SearchHistoryEntry>) {
            val shown = if (showFilterSnapshots) history else history.filter { it.query.isNotBlank() }
            itemAdapter.set(
                shown.mapIndexed { index, entry ->
                    SearchHistoryItem(
                        query = entry.query,
                        filters = entry.filters,
                        timestamp = entry.timestamp,
                        sourceId = entry.sourceId,
                        isTopOfGroup = index == 0,
                        isBottomOfGroup = index == shown.lastIndex,
                        onFillClicked = { onQueryFilled(it) },
                    )
                },
            )
            if (shown.isEmpty() && isVisible) {
                onHistoryEmptied()
            }
        }

        companion object {
            fun hasHistory(
                preferences: PreferencesHelper = Injekt.get(),
                includeFilterSnapshots: Boolean = true,
            ): Boolean =
                preferences.showBrowseSearchHistory().get() &&
                    preferences.browseSearchHistory().get().any { includeFilterSnapshots || it.query.isNotBlank() }
        }
    }

fun Snackbar.moveAboveSafeAreas(context: Context): Snackbar {
    val mainActivity = context as? MainActivity
    val bottomNav = mainActivity?.binding?.bottomNav?.takeIf { it.isVisible }
    val bottomNavHeight = bottomNav?.let { (it.height - it.translationY).toInt().coerceAtLeast(0) } ?: 0
    ViewCompat.setOnApplyWindowInsetsListener(view) { snackView, insets ->
        val bottomInset =
            insets
                .getInsets(WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.systemBars())
                .bottom
        snackView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = maxOf(bottomInset, bottomNavHeight)
        }
        insets
    }
    return this
}
