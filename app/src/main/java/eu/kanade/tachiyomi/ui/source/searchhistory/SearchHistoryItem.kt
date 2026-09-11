package eu.kanade.tachiyomi.ui.source.searchhistory

import android.view.View
import androidx.core.view.isVisible
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.items.AbstractItem
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.databinding.SearchHistoryItemBinding
import eu.kanade.tachiyomi.util.view.makeContainerShape

class SearchHistoryItem(
    val query: String,
    val filters: List<SavedFilter>,
    val timestamp: Long?,
    val sourceId: Long?,
    private val isTopOfGroup: Boolean,
    private val isBottomOfGroup: Boolean,
    private val onFillClicked: (String) -> Unit,
) : AbstractItem<FastAdapter.ViewHolder<SearchHistoryItem>>() {
    override val type: Int = R.id.history_card

    override val layoutRes: Int = R.layout.search_history_item

    override var identifier = if (query.isNotBlank()) query.hashCode().toLong() else timestamp ?: 0L

    override fun getViewHolder(v: View): FastAdapter.ViewHolder<SearchHistoryItem> = ViewHolder(v)

    class ViewHolder(
        view: View,
    ) : FastAdapter.ViewHolder<SearchHistoryItem>(view),
        ISwipeableViewHolder {
        private val binding = SearchHistoryItemBinding.bind(view)

        override val swipeableView: View = binding.historyCard
        override val leftBackView: View = binding.leftBackView
        override val rightBackView: View = binding.rightBackView

        override fun bindView(
            item: SearchHistoryItem,
            payloads: List<Any>,
        ) {
            binding.historyCard.translationX = 0f
            binding.backView.isVisible = false
            val isSnapshot = item.query.isBlank()
            binding.title.isVisible = !isSnapshot
            binding.title.text = item.query
            val shape =
                binding.historyCard.makeContainerShape(
                    item.isTopOfGroup,
                    item.isBottomOfGroup,
                    clipContentTo = binding.frontView,
                )
            binding.historyCard.shapeAppearanceModel = shape
            binding.backView.shapeAppearanceModel = shape
            binding.fillButton.isVisible = item.filters.isEmpty()
            binding.subtitle.isVisible = item.filters.isNotEmpty()
            if (binding.subtitle.isVisible) {
                binding.subtitle.text = binding.root.context.getString(R.string.parenthesis, item.filters.joinToString { it.filterName })
            }
            binding.fillButton.setOnClickListener { item.onFillClicked(item.query) }
        }

        override fun unbindView(item: SearchHistoryItem) {
            binding.title.text = null
            binding.fillButton.setOnClickListener(null)
            // so a recycled holder doesn't reappear mid-swipe from whatever row it last showed
            binding.historyCard.translationX = 0f
            binding.backView.isVisible = false
        }
    }
}

public interface ISwipeableViewHolder {
    public abstract val swipeableView: View
    public abstract val leftBackView: View
    public abstract val rightBackView: View
}
