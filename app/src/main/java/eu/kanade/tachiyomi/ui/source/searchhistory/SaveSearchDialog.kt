package eu.kanade.tachiyomi.ui.source.searchhistory

import android.app.Activity
import android.content.DialogInterface
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.databinding.SaveSearchDialogBinding
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import uy.kohesive.injekt.injectLazy

/** Shows a dialog that creates a new saved [SearchHistoryEntry], or renames/re-flags an [existing] one. */
object SaveSearchDialog {
    private val preferences by injectLazy<PreferencesHelper>()
    private val sourceManager by injectLazy<SourceManager>()

    fun show(
        activity: Activity,
        existing: SearchHistoryEntry?,
        query: String,
        filters: List<SavedFilter>,
        sourceId: Long?,
        onSaved: () -> Unit = {},
    ) {
        val binding = SaveSearchDialogBinding.inflate(activity.layoutInflater)
        binding.name.append(existing?.name ?: "")

        if (sourceId != null) {
            binding.showOnSourceBtn.text =
                binding.root.context.getString(R.string.only_x, sourceManager.getOrStub(sourceId).name)
            val showOnAllSources = existing?.showOnAllSources ?: false
            binding.showOnGroup.check(if (showOnAllSources) binding.showOnAllBtn.id else binding.showOnSourceBtn.id)
        } else {
            // nothing to scope to a single source - there's no choice to offer
            binding.showOnRow.isVisible = false
        }

        val dialog =
            activity
                .materialAlertDialog()
                .apply {
                    setTitle(if (existing == null) R.string.save else R.string.edit)
                    setView(binding.root)
                    setNegativeButton(android.R.string.cancel, null)
                    setPositiveButton(R.string.save) { _, _ ->
                        val name =
                            binding.name.text
                                .toString()
                                .trim()
                        val showOnAllSources = sourceId == null || binding.showOnGroup.checkedButtonId == binding.showOnAllBtn.id
                        if (existing == null) {
                            preferences.addSavedSearch(name, query, filters, sourceId, showOnAllSources)
                        } else {
                            preferences.updateSavedSearch(existing.id, name, showOnAllSources)
                        }
                        onSaved()
                    }
                }.create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE)

            fun refreshPositiveButton() {
                val name =
                    binding.name.text
                        ?.toString()
                        ?.trim()
                        .orEmpty()
                positiveButton?.isEnabled = name.isNotBlank()
                val conflicts =
                    name.isNotBlank() &&
                        preferences.savedSearches().get().findConflictingEntry(name, sourceId, excludingId = existing?.id) != null
                positiveButton?.text = activity.getString(if (conflicts) R.string.replace else R.string.save)
            }
            refreshPositiveButton()
            binding.name.addTextChangedListener { refreshPositiveButton() }
        }
        dialog.show()
    }
}
