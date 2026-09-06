package eu.kanade.tachiyomi.ui.source.browse

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.getResourceColor

/**
 * Draws a divider between consecutive [BrowseSourceListHolder] rows, the same way
 * [eu.kanade.tachiyomi.ui.library.LibraryListDivider] separates rows inside their own grouped
 * card. Only used when browsing a source in list mode -- the grid layout uses no decoration.
 */
@SuppressLint("UseKtx")
class BrowseSourceListDivider(
    context: Context,
) : RecyclerView.ItemDecoration() {
    private val divider: Drawable
    private val padding: Int = 12.dpToPx

    init {
        val a = context.obtainStyledAttributes(intArrayOf(android.R.attr.listDivider))
        divider = a.getDrawable(0)!!
        a.recycle()
    }

    override fun onDraw(
        c: Canvas,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        val childCount = parent.childCount
        for (i in 0 until childCount - 1) {
            val child = parent.getChildAt(i)
            val holder = parent.getChildViewHolder(child)
            if (holder is BrowseSourceListHolder &&
                parent.getChildViewHolder(parent.getChildAt(i + 1)) is BrowseSourceListHolder
            ) {
                val params = child.layoutParams as RecyclerView.LayoutParams
                val top = child.bottom + params.bottomMargin
                val bottom = top + divider.intrinsicHeight + 1.dpToPx
                val left = parent.paddingStart + padding
                val right = parent.width - parent.paddingEnd - padding

                divider.setBounds(left, top, right, bottom)
                divider.draw(c)
                c.drawColor(parent.context.getResourceColor(R.attr.background))
            }
        }
    }

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        if (parent.getChildViewHolder(view) !is BrowseSourceListHolder) {
            outRect.setEmpty()
            return
        }
        outRect.set(0, 0, 0, divider.intrinsicHeight + 1.dpToPx)
    }
}
