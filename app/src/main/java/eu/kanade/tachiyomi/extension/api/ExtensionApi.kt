package eu.kanade.tachiyomi.extension.api

import android.content.Context
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.extension.model.RepoMetadata
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import eu.kanade.tachiyomi.util.system.withIOContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import timber.log.Timber
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlin.coroutines.cancellation.CancellationException

internal class ExtensionApi {
    private val json: Json by injectLazy()
    private val protoBuf: ProtoBuf by injectLazy()
    private val networkService: NetworkHelper by injectLazy()
    private val preferences: PreferencesHelper by injectLazy()

    suspend fun findExtensions(): List<Extension.Available> {
        return withIOContext {
            val repos = preferences.extensionRepos().get()
            if (repos.isEmpty()) {
                return@withIOContext emptyList()
            }
            val extensions = repos.flatMap { getExtensions(it) }

            if (extensions.isEmpty()) {
                throw Exception()
            }

            extensions
        }
    }

    /**
     * Validates a repo URL by fetching it, throwing if none of the supported index formats
     * could be parsed. A successful fetch also caches the repo's display metadata
     * (name/website/discord) as a side effect. Used when adding/renaming a repo so a bad URL
     * gets rejected outright, unlike [getExtensions] which silently skips repos that fail
     * during a routine sync.
     */
    suspend fun validateRepo(repoBaseUrl: String) {
        withIOContext {
            var lastError: Throwable? = null
            for (attempt in extensionFetchAttempts(repoBaseUrl)) {
                try {
                    attempt()
                    return@withIOContext
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    lastError = e
                }
            }
            throw lastError ?: IllegalStateException("No supported extension index found at $repoBaseUrl")
        }
    }

    private fun extensionFetchAttempts(repoBaseUrl: String): List<suspend () -> List<Extension.Available>> =
        listOf(
            { getProtobufExtensions(repoBaseUrl) },
            { getIndexV2Extensions(repoBaseUrl) },
            { getLegacyExtensions(repoBaseUrl) },
        )

    /**
     * Tries each extension index format in turn, newest first, falling back only when a
     * format isn't available for this repo (missing file, unexpected response, etc).
     */
    private suspend fun getExtensions(repoBaseUrl: String): List<Extension.Available> {
        val attempts = extensionFetchAttempts(repoBaseUrl)
        attempts.forEachIndexed { index, attempt ->
            try {
                return attempt()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (index == attempts.lastIndex) {
                    Timber.e(e, "Failed to get extensions from $repoBaseUrl")
                }
            }
        }
        return emptyList()
    }

    private suspend fun getProtobufExtensions(repoBaseUrl: String): List<Extension.Available> {
        val response =
            networkService.client
                .newCall(GET("$repoBaseUrl/index.pb"))
                .awaitSuccess()

        return response.body.bytes().toAvailableExtensionsFromProtobufStore(repoBaseUrl)
    }

    /**
     * Some repos keep serving the legacy array at index.min.json for old app versions, while
     * pointing newer versions at the real protobuf index via repo.json's "index_v2" field
     * (which may live at an entirely different URL than a simple index.pb suffix swap).
     */
    private suspend fun getIndexV2Extensions(repoBaseUrl: String): List<Extension.Available> {
        val repoJsonResponse =
            networkService.client
                .newCall(GET("$repoBaseUrl/repo.json"))
                .awaitSuccess()

        val repoJson = with(json) { repoJsonResponse.parseAs<RepoJsonObject>() }
        repoJson.meta?.let { saveRepoMetadata(repoBaseUrl, it.name, it.website) }
        val indexV2Url = repoJson.indexV2 ?: throw IllegalStateException("repo.json for $repoBaseUrl has no index_v2 pointer")

        val response = networkService.client.newCall(GET(indexV2Url)).awaitSuccess()
        return response.body.bytes().toAvailableExtensionsFromProtobufStore(repoBaseUrl)
    }

    private suspend fun getLegacyExtensions(repoBaseUrl: String): List<Extension.Available> {
        val response =
            networkService.client
                .newCall(GET("$repoBaseUrl/index.min.json"))
                .awaitSuccess()

        return with(json) {
            response
                .parseAs<List<ExtensionJsonObject>>()
                .toExtensions(repoBaseUrl)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun ByteArray.toAvailableExtensionsFromProtobufStore(repoUrl: String): List<Extension.Available> {
        val store = protoBuf.decodeFromByteArray<ExtensionStoreProtoObject>(gunzipIfNeeded())
        saveRepoMetadata(repoUrl, store.name, store.contact?.website, store.contact?.discord)
        return store.extensionList
            ?.extensions
            .orEmpty()
            .toAvailableExtensions(repoUrl)
    }

    /**
     * Repos may publish index.pb gzip-compressed at the file level (distinct from any HTTP
     * Content-Encoding, which OkHttp already handles transparently). Detect it by magic
     * number and decompress before handing the bytes to the protobuf decoder.
     */
    private fun ByteArray.gunzipIfNeeded(): ByteArray {
        if (size < 2 || this[0] != 0x1F.toByte() || this[1] != 0x8B.toByte()) return this
        return GZIPInputStream(ByteArrayInputStream(this)).use { it.readBytes() }
    }

    private fun saveRepoMetadata(
        repoBaseUrl: String,
        name: String?,
        website: String?,
        discordUrl: String? = null,
    ) {
        if (name.isNullOrBlank() || website.isNullOrBlank()) return
        val metadata = RepoMetadata(name = name, website = website, discordUrl = discordUrl?.takeIf { it.isNotBlank() })
        preferences.extensionRepoMetadata().set(preferences.extensionRepoMetadata().get() + (repoBaseUrl to metadata))
    }

    suspend fun checkForUpdates(
        context: Context,
        prefetchedExtensions: List<Extension.Available>? = null,
    ): List<Extension.Available> =
        withIOContext {
            val extensions = prefetchedExtensions ?: findExtensions()

            val extensionManager: ExtensionManager = Injekt.get()
            val installedExtensions =
                extensionManager.installedExtensionsFlow.value.ifEmpty {
                    ExtensionLoader
                        .loadExtensionAsync(context)
                        .filterIsInstance<LoadResult.Success>()
                        .map { it.extension }
                }

            val extensionsWithUpdate = mutableListOf<Extension.Available>()
            for (installedExt in installedExtensions) {
                val pkgName = installedExt.pkgName
                val availableExt = extensions.find { it.pkgName == pkgName } ?: continue
                val hasUpdatedVer = availableExt.versionCode > installedExt.versionCode
                val hasUpdatedLib = availableExt.libVersion > installedExt.libVersion
                val hasUpdate = hasUpdatedVer || hasUpdatedLib
                if (hasUpdate) {
                    extensionsWithUpdate.add(availableExt)
                }
            }

            extensionsWithUpdate
        }

    private fun List<ExtensionJsonObject>.toExtensions(repoUrl: String): List<Extension.Available> =
        this
            .filter {
                val libVersion = it.extractLibVersion()
                libVersion >= ExtensionLoader.LIB_VERSION_MIN && libVersion <= ExtensionLoader.LIB_VERSION_MAX
            }.map {
                Extension.Available(
                    name = it.name.substringAfter("Tachiyomi: "),
                    pkgName = it.pkg,
                    versionName = it.version,
                    versionCode = it.code,
                    libVersion = it.extractLibVersion(),
                    lang = it.lang,
                    isNsfw = it.nsfw == 1,
                    sources = it.sources ?: emptyList(),
                    apkName = it.apk,
                    iconUrl = "$repoUrl/icon/${it.pkg}.png",
                    repoUrl = repoUrl,
                )
            }

    private fun List<ExtensionProtoObject>.toAvailableExtensions(repoUrl: String): List<Extension.Available> =
        this
            .filter {
                val libVersion = it.extensionLib.toDoubleOrNull() ?: return@filter false
                libVersion >= ExtensionLoader.LIB_VERSION_MIN && libVersion <= ExtensionLoader.LIB_VERSION_MAX
            }.map {
                val langs = it.sources.map { source -> source.language }.toSet()
                Extension.Available(
                    name = it.name.substringAfter("Tachiyomi: "),
                    pkgName = it.packageName,
                    versionName = it.versionName,
                    versionCode = it.versionCode,
                    libVersion = it.extensionLib.toDouble(),
                    lang = if (langs.size == 1) langs.first() else "all",
                    isNsfw = it.contentWarning >= ExtensionProtoObject.ContentWarning.MIXED,
                    sources =
                        it.sources.map { source ->
                            Extension.AvailableSource(
                                name = source.name,
                                id = source.id,
                                lang = source.language,
                                baseUrl = source.homeUrl,
                            )
                        },
                    apkName = it.resources.apkUrl,
                    iconUrl = it.resources.iconUrl,
                    repoUrl = repoUrl,
                )
            }

    fun getApkUrl(extension: ExtensionManager.ExtensionInfo): String =
        if (extension.apkName.startsWith("http")) {
            extension.apkName
        } else {
            "${extension.repoUrl}/apk/${extension.apkName}"
        }

    private fun ExtensionJsonObject.extractLibVersion(): Double = version.substringBeforeLast('.').toDouble()
}

@Serializable
private data class RepoJsonObject(
    @SerialName("index_v2") val indexV2: String? = null,
    val meta: RepoJsonMeta? = null,
)

@Serializable
private data class RepoJsonMeta(
    val name: String,
    val website: String,
)

@Serializable
private data class ExtensionJsonObject(
    val name: String,
    val pkg: String,
    val apk: String,
    val lang: String,
    val code: Long,
    val version: String,
    val nsfw: Int,
    val sources: List<Extension.AvailableSource>?,
)

@Serializable
private data class ExtensionStoreProtoObject(
    @ProtoNumber(1) val name: String = "",
    @ProtoNumber(4) val contact: ContactProtoObject? = null,
    @ProtoNumber(101) val extensionList: ExtensionListProtoObject? = null,
)

@Serializable
private data class ContactProtoObject(
    @ProtoNumber(1) val website: String = "",
    @ProtoNumber(2) val discord: String? = null,
)

@Serializable
private data class ExtensionListProtoObject(
    @ProtoNumber(1) val extensions: List<ExtensionProtoObject> = emptyList(),
)

@Serializable
private data class ExtensionProtoObject(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val packageName: String,
    @ProtoNumber(3) val resources: ResourcesProtoObject,
    @ProtoNumber(4) val extensionLib: String,
    @ProtoNumber(5) val versionCode: Long,
    @ProtoNumber(6) val versionName: String,
    @ProtoNumber(7) val contentWarning: ContentWarning = ContentWarning.UNSPECIFIED,
    @ProtoNumber(8) val sources: List<SourceProtoObject> = emptyList(),
) {
    @Serializable
    enum class ContentWarning {
        @ProtoNumber(0)
        UNSPECIFIED,

        @ProtoNumber(1)
        SAFE,

        @ProtoNumber(2)
        MIXED,

        @ProtoNumber(3)
        NSFW,
    }
}

@Serializable
private data class ResourcesProtoObject(
    @ProtoNumber(1) val apkUrl: String,
    @ProtoNumber(2) val iconUrl: String,
)

@Serializable
private data class SourceProtoObject(
    @ProtoNumber(1) val id: Long,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val language: String,
    @ProtoNumber(4) val homeUrl: String = "",
)
