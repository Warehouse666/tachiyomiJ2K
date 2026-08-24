package eu.kanade.tachiyomi.ui.library

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.pow

/**
 * A [RecyclerView] that shrinks, fades, and pulls each cover toward the front (start) slot the
 * farther it scrolls from it, drawing the one closest to front on top.
 *
 * Both the transforms and the draw order are recomputed synchronously in [dispatchDraw], on
 * every single draw pass, rather than from scroll/attach listeners. Listener-based updates left
 * a real gap: a child freshly attached (first layout, or scrolled/prefetched into place) starts
 * out with whatever transform values its recycled ViewHolder last had, and nothing corrected
 * that until the next scroll or a posted callback ran - occasionally visible as a stray
 * wrong-sized cover floating over the rest for a frame. Recomputing on every draw means a child
 * is never rendered before its transform reflects its actual current layout position.
 */
class MangaStackRecyclerView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : RecyclerView(context, attrs) {
        private var drawOrder = IntArray(0)
        private var initialSnapChecked = false

        /** When false, the stack is a static row - there's nothing to scroll to. */
        var isScrollingEnabled = true

        override fun canScrollHorizontally(direction: Int) = isScrollingEnabled && super.canScrollHorizontally(direction)

        init {
            isChildrenDrawingOrderEnabled = true
        }

        override fun dispatchDraw(canvas: Canvas) {
            applyStackTransforms()
            ensureInitialSnap()
            super.dispatchDraw(canvas)
        }

        /**
         * A one-time verify-and-correct pass for the very first real draw. scrollToPosition(
         * WithOffset) requested during dialog setup is a pending, one-shot instruction that the
         * LayoutManager consumes on its next layout pass - but a dialog can go through more than
         * one measure/layout pass while it's still settling into its final size (e.g. during its
         * show animation), and if that pending scroll gets consumed against a not-yet-final
         * width, it's already spent by the time the RecyclerView reaches its real size, leaving
         * the front cover not actually flush until the user's first manual scroll re-resolves
         * it. Checking here instead - once, right before the first real draw, when sizing is
         * guaranteed final - catches and fixes that regardless of which layout pass it slipped
         * through on.
         *
         * Corrects with a direct scrollBy of the measured pixel gap rather than
         * scrollToPositionWithOffset, since that API's "offset" interacts with each item's own
         * negative overlap margin in a way that didn't land it exactly flush either.
         */
        private fun ensureInitialSnap() {
            if (initialSnapChecked || childCount == 0) return
            initialSnapChecked = true

            var closestDelta = Int.MAX_VALUE
            for (i in 0 until childCount) {
                val delta = getChildAt(i).left - paddingStart
                if (abs(delta) < abs(closestDelta)) closestDelta = delta
            }
            if (closestDelta == 0 || closestDelta == Int.MAX_VALUE) return
            post { scrollBy(closestDelta, 0) }
        }

        override fun getChildDrawingOrder(
            childCount: Int,
            i: Int,
        ): Int {
            if (i == 0) drawOrder = computeDrawOrder(childCount)
            return drawOrder.getOrElse(i) { i }
        }

        private fun applyStackTransforms() {
            val itemWidth = getChildAt(0)?.width?.toFloat()?.takeIf { it > 0f } ?: return
            val pitch = itemWidth * (1f - STACK_OVERLAP_FRACTION)

            for (i in 0 until childCount) {
                val child = getChildAt(i)
                // Capped: PeekingLinearLayoutManager prefetches items well beyond the visible
                // viewport so they're ready before scrolling into view, but translationX below
                // grows with magnitude squared and unbounded - without a cap, one of those
                // still off-screen (by layout) items could get pulled by so much that it
                // visually lands back in view, small and out of sequence.
                val magnitude = magnitudeOf(child, pitch).coerceAtMost(MAGNITUDE_CAP)
                val offset = (child.left - paddingStart) / pitch
                val scale = STACK_SCALE_STEP.pow(magnitude)
                child.scaleX = scale
                child.scaleY = scale
                // Pull each cover further toward the front the deeper into the stack it is, so
                // covers overlap progressively more (not just a fixed amount) the farther out
                // they are - a transform, so it doesn't touch layout/pitch.
                val direction = if (offset >= 0f) 1f else -1f
                child.translationX = -direction * STACK_OVERLAP_GROWTH * magnitude * magnitude * pitch
                val innerAlpha = (1f - magnitude * STACK_FADE_STEP).coerceAtLeast(STACK_MIN_ALPHA)
                (child as? ViewGroup)?.getChildAt(0)?.alpha = innerAlpha
                // The inner image fading to 0 still leaves the card's own opaque background
                // showing as a solid blob. Rather than snapping the card's own alpha between 1
                // and 0, ride it out over the same span the inner image spends fading through
                // its final [0, CARD_FADE_WINDOW] - so the card is fully opaque for the whole
                // visible cascade (no alpha-blending between overlapping cards, the exact thing
                // the inner-image-only fade above was for), and only fades alongside the image
                // right at the very end where it's about to disappear anyway.
                child.alpha = (innerAlpha / CARD_FADE_WINDOW).coerceIn(0f, 1f)
            }
        }

        private fun computeDrawOrder(childCount: Int): IntArray {
            val itemWidth = getChildAt(0)?.width?.toFloat()?.takeIf { it > 0f }
            if (itemWidth == null || childCount == 0) return IntArray(childCount) { it }
            val pitch = itemWidth * (1f - STACK_OVERLAP_FRACTION)

            return (0 until childCount)
                .sortedByDescending { index -> magnitudeOf(getChildAt(index), pitch) }
                .toIntArray()
        }

        private fun magnitudeOf(
            child: View?,
            pitch: Float,
        ): Float {
            child ?: return Float.MAX_VALUE
            return abs((child.left - paddingStart) / pitch)
        }

        companion object {
            private const val STACK_SCALE_STEP = 0.9f
            private const val STACK_OVERLAP_GROWTH = 0.12f
            private const val STACK_FADE_STEP = 0.25f

            // 0f, not a partial floor: with STACK_FADE_STEP * MAGNITUDE_CAP == 1f, alpha reaches
            // exactly 0 right at the cap - so prefetched-but-not-yet-in-sequence items (which all
            // share the same capped magnitude, and so would otherwise pile up as an identical,
            // permanently dim little cluster) are actually hidden instead of stuck half-visible.
            private const val STACK_MIN_ALPHA = 0f
            private const val MAGNITUDE_CAP = 4f

            /** The inner image's own alpha span, [0, this], over which the card fades out too. */
            private const val CARD_FADE_WINDOW = 0.25f
        }
    }
