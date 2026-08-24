package eu.kanade.tachiyomi.ui.library

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.image.coil.loadManga
import eu.kanade.tachiyomi.databinding.BulkEditMangaDialogBinding
import eu.kanade.tachiyomi.databinding.BulkEditMangaStackCoverBinding
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.base.controller.DialogController
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.isInNightMode
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import eu.kanade.tachiyomi.util.view.applyTagColors
import eu.kanade.tachiyomi.util.view.tagChipColors
import kotlin.math.abs
import kotlin.math.roundToInt

/** How much each cover overlaps the one before it, as a fraction of the cover's width. */
internal const val STACK_OVERLAP_FRACTION = 0.25f

class BulkEditMangaDialog : DialogController {
    private val mangaIds: LongArray

    private lateinit var binding: BulkEditMangaDialogBinding
    private lateinit var initialState: BulkEditState

    private val displayedTags = mutableListOf<BulkSharedTag>()
    private val tagsToAdd = mutableListOf<String>()
    private val tagsToRemove = mutableListOf<String>()
    private var resetTags = false

    private val libraryController
        get() = targetController as LibraryController

    constructor(target: LibraryController, mangaIds: LongArray) : super(
        Bundle().apply { putLongArray(KEY_MANGA_IDS, mangaIds) },
    ) {
        targetController = target
        this.mangaIds = mangaIds
    }

    @Suppress("unused")
    constructor(bundle: Bundle) : super(bundle) {
        mangaIds = bundle.getLongArray(KEY_MANGA_IDS) ?: longArrayOf()
    }

    override fun onCreateDialog(savedViewState: Bundle?): Dialog {
        binding = BulkEditMangaDialogBinding.inflate(activity!!.layoutInflater)
        initialState = libraryController.presenter.getBulkEditState(mangaIds.toList())
        displayedTags += initialState.sharedTags

        setupMangaStack(libraryController.presenter.getBulkEditMangas(mangaIds.toList()))

        val statusEntries =
            listOf(activity!!.getString(R.string.bulk_edit_default)) +
                resources!!.getStringArray(R.array.manga_statuses).toList() +
                activity!!.getString(R.string.source_default)
        binding.mangaStatus.setEntries(statusEntries)
        val initialStatusPosition =
            initialState.commonStatus
                ?.coerceIn(SManga.UNKNOWN, SManga.ON_HIATUS)
                ?.plus(1) ?: DEFAULT_POSITION
        binding.mangaStatus.setSelection(initialStatusPosition)

        val seriesTypeEntries =
            listOf(activity!!.getString(R.string.bulk_edit_default)) +
                resources!!.getStringArray(R.array.series_type).toList() +
                activity!!.getString(R.string.source_default)
        binding.seriesType.setEntries(seriesTypeEntries)
        val initialSeriesTypePosition = initialState.commonSeriesType ?: DEFAULT_POSITION
        binding.seriesType.setSelection(initialSeriesTypePosition)
        binding.seriesType.onItemSelectedListener = {
            binding.resetsReadingMode.isVisible = it != initialSeriesTypePosition
        }

        setupTagEditor()
        binding.resetTags.setOnClickListener {
            resetTags = true
            tagsToAdd.clear()
            tagsToRemove.clear()
            displayedTags.clear()
            displayedTags += initialState.sourceSharedTags
            binding.addTagEditText.text?.clear()
            binding.addTagEditText.isVisible = false
            binding.addTagChip.isVisible = true
            renderSharedTags()
        }
        renderSharedTags()

        return activity!!
            .materialAlertDialog()
            .setTitle(resources!!.getQuantityString(R.plurals.edit_x_series, mangaIds.size, mangaIds.size))
            .setView(binding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                addTags(false)

                val selectedStatusPosition = binding.mangaStatus.selectedPosition
                val resetStatus = selectedStatusPosition == statusEntries.lastIndex
                val status =
                    selectedStatusPosition
                        .takeIf {
                            it != DEFAULT_POSITION &&
                                it != statusEntries.lastIndex &&
                                it != initialStatusPosition
                        }?.minus(1)

                val selectedSeriesTypePosition = binding.seriesType.selectedPosition
                val resetSeriesType = selectedSeriesTypePosition == seriesTypeEntries.lastIndex
                val seriesType =
                    selectedSeriesTypePosition.takeIf {
                        it != DEFAULT_POSITION &&
                            it != seriesTypeEntries.lastIndex &&
                            it != initialSeriesTypePosition
                    }

                libraryController.presenter.bulkEditManga(
                    mangaIds = mangaIds.toList(),
                    status = status,
                    resetStatus = resetStatus,
                    seriesType = seriesType,
                    resetSeriesType = resetSeriesType,
                    resetTags = resetTags,
                    tagsToAdd = tagsToAdd,
                    tagsToRemove = tagsToRemove,
                )
            }.create()
    }

    private fun setupTagEditor() {
        binding.addTagChip.setOnClickListener {
            binding.addTagChip.isVisible = false
            binding.addTagEditText.isVisible = true
            binding.addTagEditText.requestFocus()
            binding.addTagEditText.post {
                (activity?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(binding.addTagEditText, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        binding.addTagEditText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) addTags(false)
        }
        binding.addTagEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addTags(true)
                true
            } else {
                false
            }
        }
    }

    private fun addTags(closeKeyboard: Boolean) {
        val newTags =
            binding.addTagEditText.text
                ?.toString()
                .orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotBlank() }

        newTags.forEach(::addTag)
        binding.addTagEditText.text?.clear()
        binding.addTagEditText.isVisible = false
        binding.addTagChip.isVisible = true

        if (closeKeyboard) {
            (activity?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(binding.addTagEditText.windowToken, 0)
            binding.addTagEditText.clearFocus()
        }
        renderSharedTags()
    }

    private fun addTag(tag: String) {
        if (displayedTags.any { it.name.equals(tag, ignoreCase = true) }) return

        val originalTags = if (resetTags) initialState.sourceSharedTags else initialState.sharedTags
        val originalTag = originalTags.find { it.name.equals(tag, ignoreCase = true) }
        if (originalTag != null) {
            tagsToRemove.removeAll { it.equals(tag, ignoreCase = true) }
            displayedTags += originalTag
            return
        }

        if (tagsToAdd.none { it.equals(tag, ignoreCase = true) }) {
            tagsToAdd += tag
        }
        displayedTags += BulkSharedTag(tag, isCustom = true, removable = true)
    }

    private fun removeTag(tag: BulkSharedTag) {
        if (!tag.removable) return

        displayedTags.removeAll { it.name.equals(tag.name, ignoreCase = true) }
        val wasNew = tagsToAdd.removeAll { it.equals(tag.name, ignoreCase = true) }
        if (!wasNew && tagsToRemove.none { it.equals(tag.name, ignoreCase = true) }) {
            tagsToRemove += tag.name
        }
        renderSharedTags()
    }

    private fun setupMangaStack(mangas: List<Manga>) {
        val stack = binding.mangaStack
        val looping = mangas.size > LOOP_MIN_ITEMS

        val layoutManager = PeekingLinearLayoutManager(stack.context)
        stack.layoutManager = layoutManager
        stack.adapter = BulkEditMangaStackAdapter(mangas, looping)
        StartSnapHelper().attachToRecyclerView(stack)

        if (looping) {
            val midpoint = Int.MAX_VALUE / 2
            // scrollToPosition alone doesn't guarantee it lands flush at the start edge.
            layoutManager.scrollToPositionWithOffset(midpoint - midpoint % mangas.size, 0)
        } else {
            // Nothing to scroll to with this few items - lock it and give it more breathing
            // room than the tighter padding meant to let the next card peek in while scrolling.
            stack.isScrollingEnabled = false
            stack.updatePaddingRelative(start = STATIC_STACK_START_PADDING_DP.dpToPx)
        }
    }

    private fun renderSharedTags() {
        val tagGroup = binding.sharedTags
        tagGroup.children
            .toList()
            .filter { it.id != R.id.add_tag_chip && it.id != R.id.add_tag_edit_text }
            .forEach(tagGroup::removeView)

        val dark = tagGroup.context.isInNightMode()
        val amoled = libraryController.preferences.themeDarkAmoled().get()
        val tagColors = tagGroup.tagChipColors(dark, amoled)

        displayedTags.forEach { tag ->
            val chip =
                LayoutInflater
                    .from(tagGroup.context)
                    .inflate(R.layout.genre_chip, tagGroup, false) as Chip
            chip.id = View.generateViewId()
            chip.text = tag.name
            chip.applyTagColors(tagColors, tag.isCustom)
            chip.isCloseIconVisible = tag.removable
            if (tag.removable) {
                chip.setOnCloseIconClickListener { removeTag(tag) }
            }
            tagGroup.addView(chip, (tagGroup.childCount - STATIC_TAG_EDITOR_CHILDREN).coerceAtLeast(0))
        }
    }

    companion object {
        private const val KEY_MANGA_IDS = "manga_ids"
        private const val DEFAULT_POSITION = 0
        private const val STATIC_TAG_EDITOR_CHILDREN = 2
        private const val LOOP_MIN_ITEMS = 4
        private const val STATIC_STACK_START_PADDING_DP = 40
    }
}

/**
 * Lays out a couple of extra items beyond each edge of the viewport so covers are already
 * measured/positioned before they scroll into view, instead of popping in at full size the
 * moment they cross into the visible bounds. Kept modest, not a full extra screen, since
 * [MangaStackRecyclerView]'s overlap pull could otherwise yank a far-off item back into view.
 */
private class PeekingLinearLayoutManager(
    context: Context,
) : LinearLayoutManager(context, HORIZONTAL, false) {
    override fun calculateExtraLayoutSpace(
        state: RecyclerView.State,
        extraLayoutSpace: IntArray,
    ) {
        val extra = width / 2
        extraLayoutSpace[0] = extra
        extraLayoutSpace[1] = extra
    }
}

private class BulkEditMangaStackAdapter(
    private val mangas: List<Manga>,
    private val looping: Boolean,
) : RecyclerView.Adapter<BulkEditMangaStackAdapter.ViewHolder>() {
    override fun getItemCount() = if (looping) Int.MAX_VALUE else mangas.size

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder {
        val coverBinding = BulkEditMangaStackCoverBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        val layoutParams = coverBinding.root.layoutParams as ViewGroup.MarginLayoutParams
        layoutParams.marginStart = -(layoutParams.width * STACK_OVERLAP_FRACTION).roundToInt()
        return ViewHolder(coverBinding)
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        holder.binding.coverImage.loadManga(mangas[position % mangas.size])
    }

    class ViewHolder(
        val binding: BulkEditMangaStackCoverBinding,
    ) : RecyclerView.ViewHolder(binding.root)
}

/**
 * Snaps items to the start edge instead of [LinearSnapHelper]'s default of centering them.
 * [findSnapView] picks whichever child is closest to the start rather than
 * [LinearLayoutManager.findFirstVisibleItemPosition], which (with overlapping items) can return
 * an almost-fully-scrolled-past sliver - snapping that back to start caused a runaway
 * back-and-forth as each correction changed what counted as "first visible."
 */
private class StartSnapHelper : LinearSnapHelper() {
    override fun calculateDistanceToFinalSnap(
        layoutManager: RecyclerView.LayoutManager,
        targetView: View,
    ): IntArray {
        val out = IntArray(2)
        if (layoutManager.canScrollHorizontally()) {
            out[0] = targetView.left - layoutManager.paddingLeft
        }
        return out
    }

    override fun findSnapView(layoutManager: RecyclerView.LayoutManager): View? {
        if (layoutManager !is LinearLayoutManager) return super.findSnapView(layoutManager)
        var closest: View? = null
        var closestDistance = Int.MAX_VALUE
        for (i in 0 until layoutManager.childCount) {
            val child = layoutManager.getChildAt(i) ?: continue
            val distance = abs(child.left - layoutManager.paddingLeft)
            if (distance < closestDistance) {
                closestDistance = distance
                closest = child
            }
        }
        return closest
    }

    override fun findTargetSnapPosition(
        layoutManager: RecyclerView.LayoutManager,
        velocityX: Int,
        velocityY: Int,
    ): Int {
        if (layoutManager !is LinearLayoutManager) return RecyclerView.NO_POSITION
        val firstVisible = layoutManager.findFirstVisibleItemPosition()
        if (firstVisible == RecyclerView.NO_POSITION) return RecyclerView.NO_POSITION
        return if (velocityX > 0) firstVisible + 1 else firstVisible
    }
}
