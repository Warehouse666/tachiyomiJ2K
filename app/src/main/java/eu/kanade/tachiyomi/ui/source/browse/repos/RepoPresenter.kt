package eu.kanade.tachiyomi.ui.source.browse.repos

import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.extension.api.ExtensionApi
import eu.kanade.tachiyomi.ui.base.presenter.BaseCoroutinePresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.coroutines.cancellation.CancellationException

/**
 * Presenter of [RepoController]. Used to manage the repos for the extensions.
 */
class RepoPresenter(
    private val controller: RepoController,
    private val preferences: PreferencesHelper = Injekt.get(),
) : BaseCoroutinePresenter<RepoController>() {
    private var scope = CoroutineScope(Job() + Dispatchers.Default)

    /**
     * List containing repos.
     */
    private var repos: Set<String>
        get() =
            preferences
                .extensionRepos()
                .get()
                .map { "$it/index.pb" }
                .sorted()
                .toSet()
        set(value) = preferences.extensionRepos().set(value.map { it.toRepoBaseUrl() }.toSet())

    /**
     * Called when the presenter is created.
     */
    fun getRepos() {
        scope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                controller.updateRepos()
            }
        }
    }

    fun getReposWithCreate(): List<RepoItem> {
        val metadataByBaseUrl = preferences.extensionRepoMetadata().get()
        return (listOf(CREATE_REPO_ITEM) + repos).map { repo ->
            RepoItem(repo, if (repo == CREATE_REPO_ITEM) null else metadataByBaseUrl[repo.toRepoBaseUrl()])
        }
    }

    fun getRepoUrl(repo: String): String {
        val website = preferences.extensionRepoMetadata().get()[repo.toRepoBaseUrl()]?.website
        if (!website.isNullOrBlank()) return website

        return githubRepoRegex
            .find(repo)
            ?.let {
                val (user, repoName) = it.destructured
                "https://github.com/$user/$repoName"
            } ?: repo
    }

    fun getDiscordUrl(repo: String): String? = preferences.extensionRepoMetadata().get()[repo.toRepoBaseUrl()]?.discordUrl

    private fun String.toRepoBaseUrl(): String = removeSuffix("/index.pb").removeSuffix("/index.min.json")

    /**
     * Returns true if the URL is shaped like a repo index URL. A fast, offline check run
     * before [createOrRenameRepo] bothers hitting the network.
     */
    fun isValidRepoFormat(name: String): Boolean = name.matches(repoRegex)

    /**
     * Validates a repo over the network and, if reachable, adds it (or renames [oldRepo] to
     * it). Mirrors mihon's add flow: the repo is only saved once the fetch succeeds, so a
     * bad URL never ends up in the list.
     *
     * @param oldRepo The repo being renamed, or null when creating a new one.
     * @param newName The new repo URL.
     * @param onResult Called on the main thread with whether the repo was saved.
     */
    fun createOrRenameRepo(
        oldRepo: String?,
        newName: String,
        onResult: (success: Boolean) -> Unit,
    ) {
        if (oldRepo != null && oldRepo.equals(newName, true)) {
            onResult(true)
            return
        }

        if (repoExists(newName)) {
            controller.onRepoExistsError()
            onResult(false)
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                ExtensionApi().validateRepo(newName.toRepoBaseUrl())
                oldRepo?.let {
                    repos -= it
                    preferences.extensionRepoMetadata().set(preferences.extensionRepoMetadata().get() - it.toRepoBaseUrl())
                }
                repos += newName
                withContext(Dispatchers.Main) { onResult(true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    controller.onRepoUnreachableError()
                    onResult(false)
                }
            }
        }
    }

    /**
     * Deletes the repo from the database.
     *
     * @param repo The repo to delete.
     */
    fun deleteRepo(repo: String?) {
        val safeRepo = repo ?: return
        repos -= safeRepo
        preferences.extensionRepoMetadata().set(preferences.extensionRepoMetadata().get() - safeRepo.toRepoBaseUrl())
        controller.updateRepos()
    }

    /**
     * Returns true if a repo with the given name already exists.
     */
    private fun repoExists(name: String): Boolean = repos.any { it.equals(name, true) }

    companion object {
        private val repoRegex = """^https://.*/index\.(min\.json|pb)$""".toRegex()
        private val githubRepoRegex = """https://(?:raw.githubusercontent.com|github.com)/(.+?)/(.+?)/.+""".toRegex()
        const val CREATE_REPO_ITEM = "create_repo"
    }
}
