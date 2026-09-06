package eu.kanade.tachiyomi.ui.library

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
 * Draws a divider between consecutive [LibraryListHolder] rows, the same way
 * [eu.kanade.tachiyomi.ui.recents.RecentMangaDivider] and
 * [eu.kanade.tachiyomi.ui.source.SourceDividerItemDecoration] separate rows inside their own
 * grouped card. Only relevant while the library is actually shown in list mode -- the grid layout
 * doesn't use this decoration, and the blank "category is empty" row reuses [LibraryListHolder]
 * even in grid mode, where it should stay undecorated.
 */
@SuppressLint("UseKtx")
class LibraryListDivider(
    context: Context,
) : RecyclerView.ItemDecoration() {
    private val divider: Drawable
    private val padding: Int = 12.dpToPx

    init {
        val a = context.obtainStyledAttributes(intArrayOf(android.R.attr.listDivider))
        divider = a.getDrawable(0)!!
        a.recycle()
    }

    private val isListMode: Boolean
        get() = LibraryItem.libraryLayout == LibraryItem.LAYOUT_LIST

    override fun onDraw(
        c: Canvas,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        if (!isListMode) return
        val childCount = parent.childCount
        for (i in 0 until childCount - 1) {
            val child = parent.getChildAt(i)
            val holder = parent.getChildViewHolder(child)
            if (holder is LibraryListHolder &&
                parent.getChildViewHolder(parent.getChildAt(i + 1)) is LibraryListHolder
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
        if (!isListMode || parent.getChildViewHolder(view) !is LibraryListHolder) {
            outRect.setEmpty()
            return
        }
        outRect.set(0, 0, 0, divider.intrinsicHeight + 1.dpToPx)
    }
}
