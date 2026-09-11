package eu.kanade.tachiyomi.ui.source.searchhistory

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.util.system.toInt
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A source-independent snapshot of one changed [Filter]'s state, keyed by [name] instead of
 * position so it can be matched against a *different* source's filter list later - unlike an
 * index, a filter name (and a [Select]/[Sort]'s chosen value label) still means something there.
 */
@Serializable
sealed class SavedFilter {
    abstract val name: String
    abstract val filterName: String

    @Serializable
    @SerialName("checkbox")
    data class CheckBox(
        override val name: String,
        val checked: Boolean,
    ) : SavedFilter() {
        override val filterName: String
            get() = name
    }

    @Serializable
    @SerialName("tristate")
    data class TriState(
        override val name: String,
        val state: Int,
    ) : SavedFilter() {
        override val filterName: String
            get() = name
    }

    @Serializable
    @SerialName("text")
    data class Text(
        override val name: String,
        val text: String,
    ) : SavedFilter() {
        override val filterName: String
            get() = text
    }

    @Serializable
    @SerialName("select")
    data class Select(
        override val name: String,
        val value: String,
    ) : SavedFilter() {
        override val filterName: String
            get() = "$name: $value"
    }

    @Serializable
    @SerialName("sort")
    data class Sort(
        override val name: String,
        val value: String,
        val ascending: Boolean,
    ) : SavedFilter() {
        override val filterName: String
            get() = value
    }

    @Serializable
    @SerialName("group")
    data class Group(
        override val name: String,
        val children: List<SavedFilter>,
    ) : SavedFilter() {
        override val filterName: String
            get() = children.joinToString { it.filterName }
    }
}

private fun matchesType(
    filter: Filter<*>,
    saved: SavedFilter,
): Boolean =
    when (saved) {
        is SavedFilter.CheckBox -> filter is Filter.CheckBox
        is SavedFilter.TriState -> filter is Filter.TriState
        is SavedFilter.Text -> filter is Filter.Text
        is SavedFilter.Select -> filter is Filter.Select<*>
        is SavedFilter.Sort -> filter is Filter.Sort
        is SavedFilter.Group -> filter is Filter.Group<*>
    }

private fun looselyMatchesType(
    filter: Filter<*>,
    saved: SavedFilter,
): Boolean =
    when (saved) {
        is SavedFilter.CheckBox, is SavedFilter.TriState ->
            filter is Filter.CheckBox || filter is Filter.TriState
        is SavedFilter.Text, is SavedFilter.Select, is SavedFilter.Sort ->
            // Some sources might have the same filter logic but extended to a group of checkbox
            filter is Filter.Text || filter is Filter.Select<*> || filter is Filter.Sort || filter is Filter.Group<*>
        is SavedFilter.Group ->
            filter is Filter.Group<*> || filter is Filter.Text || filter is Filter.Select<*> || filter is Filter.Sort
    }

/**
 * Loosely equal for the purposes of cross-source filter matching: punctuation and spaces are
 * stripped before comparing (so e.g. "Oneshot" vs. "One-shot"), and a
 * match also counts if the shorter of the two strings is contained within the longer one.
 */
private fun String.looselyMatches(other: String): Boolean {
    val a = normalizedForLooseMatch()
    val b = other.normalizedForLooseMatch()
    if (a.isEmpty() || b.isEmpty()) return false
    return a == b || if (a.length <= b.length) b.contains(a) else a.contains(b)
}

private fun String.normalizedForLooseMatch(): String = filter { it.isLetterOrDigit() }.lowercase()

private fun Filter<*>.toSavedFilter(default: Filter<*>?): SavedFilter? =
    when (this) {
        is Filter.CheckBox -> if (state != (default as? Filter.CheckBox)?.state) SavedFilter.CheckBox(name, state) else null
        is Filter.TriState -> if (state != (default as? Filter.TriState)?.state) SavedFilter.TriState(name, state) else null
        is Filter.Text -> if (state != (default as? Filter.Text)?.state) SavedFilter.Text(name, state) else null
        is Filter.Select<*> -> {
            if (state != (default as? Filter.Select<*>)?.state) {
                values.getOrNull(state)?.toString()?.let { SavedFilter.Select(name, it) }
            } else {
                null
            }
        }
        is Filter.Sort -> {
            if (state != (default as? Filter.Sort)?.state) {
                state?.let { selection -> values.getOrNull(selection.index)?.let { SavedFilter.Sort(name, it, selection.ascending) } }
            } else {
                null
            }
        }
        is Filter.Group<*> -> {
            val defaultGroup = default as? Filter.Group<*>
            val children =
                state.mapIndexedNotNull { index, child ->
                    (child as? Filter<*>)?.toSavedFilter(defaultGroup?.state?.getOrNull(index) as? Filter<*>)
                }
            children.takeIf { it.isNotEmpty() }?.let { SavedFilter.Group(name, it) }
        }
        // Header/Separator aren't interactive, nothing to diff
        else -> null
    }

/**
 * The filters in `this` that differ from [default] - both must come from the same source (and
 * ideally the same, unedited [FilterList] instance from [eu.kanade.tachiyomi.source.CatalogueSource.getFilterList])
 * since they're compared by position, exactly like [eu.kanade.tachiyomi.ui.source.browse.BrowseSourcePresenter]'s
 * own default-tracking already does.
 */
fun FilterList.diffFromDefault(default: FilterList): List<SavedFilter> =
    mapIndexedNotNull { index, filter -> filter.toSavedFilter(default.getOrNull(index)) }

private fun SavedFilter.applyTo(filter: Filter<*>): Boolean =
    when (this) {
        is SavedFilter.CheckBox -> {
            val target = filter as? Filter.CheckBox ?: return false
            target.state = checked
            true
        }
        is SavedFilter.TriState -> {
            val target = filter as? Filter.TriState ?: return false
            target.state = state
            true
        }
        is SavedFilter.Text -> {
            val target = filter as? Filter.Text ?: return false
            target.state = text
            true
        }
        is SavedFilter.Select -> {
            val select = filter as? Filter.Select<*> ?: return false
            val index = select.values.indexOfFirst { it.toString().equals(value, ignoreCase = true) }
            if (index == -1) return false
            select.state = index
            true
        }
        is SavedFilter.Sort -> {
            val sort = filter as? Filter.Sort ?: return false
            val index = sort.values.indexOfFirst { it.equals(value, ignoreCase = true) }
            if (index == -1) return false
            sort.state = Filter.Sort.Selection(index, ascending)
            true
        }
        is SavedFilter.Group -> {
            val group = filter as? Filter.Group<*> ?: return false
            val groupChildren = group.state.filterIsInstance<Filter<*>>()
            children.isNotEmpty() &&
                children.all { saved ->
                    val target = groupChildren.find { it.name == saved.name && matchesType(it, saved) }
                    target != null && saved.applyTo(target)
                }
        }
    }

/**
 * Sets [value] onto [target] for whichever "one value out of many" filter kind it turns out to
 * be, including a [Filter.Group] - there, [value] is matched against a child's *name* (e.g.
 * "Completed") rather than the group's own name, and that child is switched on.
 */
private fun applyValueLoosely(
    value: String,
    target: Filter<*>,
): Boolean =
    when (target) {
        is Filter.Text -> {
            target.state = value
            true
        }
        is Filter.Select<*> -> {
            val index = target.values.indexOfFirst { it.toString().looselyMatches(value) }
            if (index == -1) return false
            target.state = index
            true
        }
        is Filter.Sort -> {
            val index = target.values.indexOfFirst { it.looselyMatches(value) }
            if (index == -1) return false
            target.state = Filter.Sort.Selection(index, target.state?.ascending ?: true)
            true
        }
        is Filter.Group<*> -> {
            when (val child = target.state.filterIsInstance<Filter<*>>().find { it.name.looselyMatches(value) }) {
                is Filter.CheckBox -> {
                    child.state = true
                    true
                }
                is Filter.TriState -> {
                    child.state = Filter.TriState.STATE_INCLUDE
                    true
                }
                else -> false
            }
        }
        else -> false
    }

/**
 * The single value this group collapses to, if any - i.e. exactly one child is switched "on"
 * (a checked [Filter.CheckBox] or included [Filter.TriState]). A group with zero or several such
 * children has no single value a flat filter (or a differently-shaped group) could hold instead.
 */
private val SavedFilter.Group.singleIncludedChildName: String?
    get() =
        children
            .mapNotNull { child ->
                when (child) {
                    is SavedFilter.CheckBox -> child.name.takeIf { child.checked }
                    is SavedFilter.TriState -> child.name.takeIf { child.state == Filter.TriState.STATE_INCLUDE }
                    else -> null
                }
            }.singleOrNull()

/**
 * @param filters the full list [filter] came from - needed only so a [SavedFilter.Group]'s
 * children that don't fit in [filter] (it was matched by name/type alone, not by contents) can
 * each still get their own [applyValueAnywhere] chance elsewhere in the list.
 */
private fun SavedFilter.looselyApplyTo(
    filter: Filter<*>,
    filters: FilterList,
): Boolean =
    when (this) {
        is SavedFilter.CheckBox -> {
            when (filter) {
                is Filter.CheckBox -> {
                    filter.state = checked
                    true
                }
                is Filter.TriState -> {
                    filter.state = checked.toInt()
                    true
                }
                else -> false
            }
        }
        is SavedFilter.TriState -> {
            when (filter) {
                is Filter.TriState -> {
                    filter.state = state
                    true
                }
                is Filter.CheckBox -> {
                    filter.state = state == Filter.TriState.STATE_INCLUDE
                    true
                }
                else -> false
            }
        }
        is SavedFilter.Text -> applyValueLoosely(text, filter)
        is SavedFilter.Select -> applyValueLoosely(value, filter)
        is SavedFilter.Sort -> {
            if (filter is Filter.Sort) {
                val index = filter.values.indexOfFirst { it.looselyMatches(value) }
                if (index == -1) return false
                filter.state = Filter.Sort.Selection(index, ascending)
                true
            } else {
                applyValueLoosely(value, filter)
            }
        }
        is SavedFilter.Group -> {
            when (filter) {
                is Filter.Group<*> -> {
                    val groupChildren = filter.state.filterIsInstance<Filter<*>>()
                    children.isNotEmpty() &&
                        children.all { saved ->
                            val exact = groupChildren.find { it.name.equals(saved.name, ignoreCase = true) && matchesType(it, saved) }
                            val exactApplied = exact != null && saved.applyTo(exact)
                            if (exactApplied) {
                                true
                            } else {
                                val loose =
                                    groupChildren.find { it.name.looselyMatches(saved.name) && looselyMatchesType(it, saved) }
                                val looseApplied = loose != null && saved.looselyApplyTo(loose, filters)
                                if (looseApplied) {
                                    true
                                } else {
                                    // this child didn't fit into the group [filter] matched on
                                    // name alone - it may just live under a differently-named
                                    // group (or flat filter) entirely
                                    saved.anyValue?.let { applyValueAnywhere(it, filters) } ?: false
                                }
                            }
                        }
                }
                is Filter.Text, is Filter.Select<*>, is Filter.Sort ->
                    singleIncludedChildName?.let { applyValueLoosely(it, filter) } ?: false
                else -> false
            }
        }
    }

/**
 * The one value this filter represents, if it has a clear "chosen option" to look for elsewhere -
 * a checked [SavedFilter.CheckBox]/included [SavedFilter.TriState]'s own name, a
 * [SavedFilter.Text]/[SavedFilter.Select]/[SavedFilter.Sort]'s value, or a [SavedFilter.Group]'s
 * [singleIncludedChildName]. `null` for a checkbox/tristate that isn't actually turned on - there's
 * nothing meaningful to go looking for.
 */
private val SavedFilter.anyValue: String?
    get() =
        when (this) {
            is SavedFilter.CheckBox -> name.takeIf { checked }
            is SavedFilter.TriState -> name.takeIf { state == Filter.TriState.STATE_INCLUDE }
            is SavedFilter.Text -> text
            is SavedFilter.Select -> value
            is SavedFilter.Sort -> value
            is SavedFilter.Group -> singleIncludedChildName
        }

/**
 * Last resort: ignore which filter [value] is even supposed to belong to, and just look for it
 * anywhere in [filters] - a top-level checkbox/tristate's own name, a select/sort's list of
 * values, or a group child's name. The same idea as how a manga's tags already get matched
 * against a filter list regardless of which filter they end up living in.
 */
private fun applyValueAnywhere(
    value: String,
    filters: FilterList,
    ascending: Boolean = true,
): Boolean {
    for (filter in filters) {
        when (filter) {
            is Filter.CheckBox ->
                if (filter.name.looselyMatches(value)) {
                    filter.state = true
                    return true
                }
            is Filter.TriState ->
                if (filter.name.looselyMatches(value)) {
                    filter.state = Filter.TriState.STATE_INCLUDE
                    return true
                }
            is Filter.Select<*> -> {
                val index = filter.values.indexOfFirst { it.toString().looselyMatches(value) }
                if (index != -1) {
                    filter.state = index
                    return true
                }
            }
            is Filter.Sort -> {
                val index = filter.values.indexOfFirst { it.looselyMatches(value) }
                if (index != -1) {
                    filter.state = Filter.Sort.Selection(index, ascending)
                    return true
                }
            }
            is Filter.Group<*> -> {
                when (val child = filter.state.filterIsInstance<Filter<*>>().find { it.name.looselyMatches(value) }) {
                    is Filter.CheckBox -> {
                        child.state = true
                        return true
                    }
                    is Filter.TriState -> {
                        child.state = Filter.TriState.STATE_INCLUDE
                        return true
                    }
                    else -> {}
                }
            }
            else -> {}
        }
    }
    return false
}

/** How many of a saved set of filters actually found a match to apply to. */
enum class FilterApplyResult {
    ALL,
    SOME,
    NONE,
}

/**
 * Applies whichever of these remembered filters still have a same-named, same-kind match in
 * [filters] - which may belong to a different source than the one they were captured from.
 * Mutates matching filters' `.state` in place, mirroring how
 * [eu.kanade.tachiyomi.ui.source.browse.BrowseSourceController.searchGenres] already restores a
 * genre filter by name.
 *
 * @param strict skip the loose/anywhere fallback tiers entirely - only an exact name+kind match
 * counts. Safe (and preferred) when [filters] is known to be the very same source these were
 * captured from, since matching should already be exact there and loose matching only exists to
 * bridge *different* sources' filter lists.
 */
fun List<SavedFilter>.applyTo(
    filters: FilterList,
    strict: Boolean = false,
): FilterApplyResult {
    if (isEmpty()) return FilterApplyResult.ALL
    var appliedCount = 0
    for (saved in this) {
        val target =
            filters.find { it.name.equals(saved.name, ignoreCase = true) && matchesType(it, saved) }
        val applied =
            if (target != null && saved.applyTo(target)) {
                true
            } else if (strict) {
                false
            } else {
                val looseTarget = filters.find { it.name.looselyMatches(saved.name) && looselyMatchesType(it, saved) }
                val looseApplied = looseTarget != null && saved.looselyApplyTo(looseTarget, filters)
                if (looseApplied) {
                    true
                } else if (saved is SavedFilter.Group) {
                    // no group in filters even loosely shares this one's name - fall back to
                    // giving each of its children their own independent anywhere-search, since
                    // a whole group with several children can't collapse to one anyValue
                    saved.children.isNotEmpty() &&
                        saved.children.all { child -> child.anyValue?.let { applyValueAnywhere(it, filters) } ?: false }
                } else {
                    val ascending = (saved as? SavedFilter.Sort)?.ascending ?: true
                    saved.anyValue?.let { applyValueAnywhere(it, filters, ascending) } ?: false
                }
            }
        if (applied) appliedCount++
    }
    return when (appliedCount) {
        size -> FilterApplyResult.ALL
        0 -> FilterApplyResult.NONE
        else -> FilterApplyResult.SOME
    }
}
