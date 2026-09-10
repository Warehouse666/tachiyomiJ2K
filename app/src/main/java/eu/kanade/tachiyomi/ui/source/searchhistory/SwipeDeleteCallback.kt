package eu.kanade.tachiyomi.ui.source.searchhistory

import android.graphics.Canvas
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

class SwipeDeleteCallback(
    private val onSwiped: (position: Int) -> Unit,
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder,
    ) = false

    override fun onSwiped(
        viewHolder: RecyclerView.ViewHolder,
        direction: Int,
    ) {
        (viewHolder as? ISwipeableViewHolder)?.swipeableView?.translationX = 0f
        val position = viewHolder.bindingAdapterPosition
        if (position != RecyclerView.NO_POSITION) onSwiped.invoke(position)
    }

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean,
    ) {
        if (actionState != ItemTouchHelper.ACTION_STATE_SWIPE) {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            return
        }
        val swipeableView = (viewHolder as? ISwipeableViewHolder)?.swipeableView ?: viewHolder.itemView
        (viewHolder as? ISwipeableViewHolder)?.leftBackView?.isVisible = dX > 0
        (viewHolder as? ISwipeableViewHolder)?.rightBackView?.isVisible = dX < 0
        swipeableView.translationX = dX
    }
}
