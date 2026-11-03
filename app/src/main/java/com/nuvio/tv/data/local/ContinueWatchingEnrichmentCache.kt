package com.nuvio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.profile.ProfileManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

// @Keep: gravados em JSON com Gson pelo nome do campo. Sem ele o R8 renomeia os campos na release e o arquivo
// gravado por um build de debug (ou por outra versão) deixa de ser lido.
@androidx.annotation.Keep
data class CachedNextUpItem(
    val contentId: String,
    val contentType: String,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val videoId: String,
    val season: Int,
    val episode: Int,
    val episodeTitle: String?,
    val episodeDescription: String? = null,
    val thumbnail: String?,
    val released: String? = null,
    val hasAired: Boolean = true,
    val airDateLabel: String? = null,
    val lastWatched: Long,
    val imdbRating: Float? = null,
    val genres: List<String> = emptyList(),
    val releaseInfo: String? = null,
    val sortTimestamp: Long,
    val releaseTimestamp: Long? = null,
    val isReleaseAlert: Boolean = false,
    val isNewSeasonRelease: Boolean = false,
    val seedSeason: Int? = null,
    val seedEpisode: Int? = null,
    val contentLanguage: String? = null,
    val ratingVisible: Boolean = true
)

@androidx.annotation.Keep
data class CachedInProgressItem(
    val contentId: String,
    val contentType: String,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val videoId: String,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val position: Long,
    val duration: Long,
    val lastWatched: Long,
    val progressPercent: Float?,
    val episodeThumbnail: String? = null,
    val episodeDescription: String? = null,
    val episodeImdbRating: Float? = null,
    val genres: List<String> = emptyList(),
    val releaseInfo: String? = null,
    val contentLanguage: String? = null,
    val ratingVisible: Boolean = true
)

@Singleton
class ContinueWatchingEnrichmentCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val TAG = "CwEnrichCache"
        private const val THROTTLE_MS = 1_000L
    }

    private val gson = Gson()
    private val mutex = Mutex()
    @Volatile private var lastNextUpWriteMs = 0L
    @Volatile private var lastInProgressWriteMs = 0L
    @Volatile private var lastNextUpHash = 0
    @Volatile private var lastInProgressHash = 0

    /** Incremented when cache is cleared; observers can collect to trigger refresh. */
    private val _cacheCleared = kotlinx.coroutines.flow.MutableStateFlow(0)
    val cacheCleared: kotlinx.coroutines.flow.StateFlow<Int> = _cacheCleared

    /** Incremented on every successful snapshot write; channel sync observes this. */
    private val _snapshotVersion = kotlinx.coroutines.flow.MutableStateFlow(0)
    val snapshotVersion: kotlinx.coroutines.flow.StateFlow<Int> = _snapshotVersion

    // --- Next Up snapshot cache ---

    private fun nextUpFile(): File {
        val profileId = profileManager.activeProfileId.value
        val dir = File(context.filesDir, "cw_enrichment")
        dir.mkdirs()
        return File(dir, "nextup_${profileId}.json")
    }

    suspend fun getNextUpSnapshot(): List<CachedNextUpItem> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val file = nextUpFile()
                if (!file.exists()) return@withContext emptyList()
                val parsed: List<CachedNextUpItem?>? =
                    gson.fromJson(file.readText(), object : TypeToken<List<CachedNextUpItem>>() {}.type)
                parsed.orEmpty().mapNotNull { it?.validOrNull() }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read next-up cache: ${e.message}")
                emptyList()
            }
        }
    }

    /**
     * @param force bypass throttle and content-change check (use at end of pipeline or for clears)
     */
    suspend fun saveNextUpSnapshot(items: List<CachedNextUpItem>, force: Boolean = false) = withContext(Dispatchers.IO) {
        val contentHash = items.hashCode()
        if (!force) {
            if (contentHash == lastNextUpHash) return@withContext
            val now = System.currentTimeMillis()
            if (now - lastNextUpWriteMs < THROTTLE_MS) return@withContext
        }
        mutex.withLock {
            try {
                val file = nextUpFile()
                atomicWrite(file, gson.toJson(items))
                lastNextUpWriteMs = System.currentTimeMillis()
                lastNextUpHash = contentHash
                _snapshotVersion.value++
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write next-up cache: ${e.message}")
            }
        }
    }

    // --- In-progress snapshot cache ---

    private fun inProgressFile(): File {
        val profileId = profileManager.activeProfileId.value
        val dir = File(context.filesDir, "cw_enrichment")
        dir.mkdirs()
        return File(dir, "inprogress_${profileId}.json")
    }

    suspend fun getInProgressSnapshot(): List<CachedInProgressItem> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val file = inProgressFile()
                if (!file.exists()) return@withContext emptyList()
                val parsed: List<CachedInProgressItem?>? =
                    gson.fromJson(file.readText(), object : TypeToken<List<CachedInProgressItem>>() {}.type)
                parsed.orEmpty().mapNotNull { it?.validOrNull() }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read in-progress cache: ${e.message}")
                emptyList()
            }
        }
    }

    /**
     * @param force bypass throttle and content-change check (use at end of pipeline or for clears)
     */
    suspend fun saveInProgressSnapshot(items: List<CachedInProgressItem>, force: Boolean = false) = withContext(Dispatchers.IO) {
        val contentHash = items.hashCode()
        if (!force) {
            if (contentHash == lastInProgressHash) return@withContext
            val now = System.currentTimeMillis()
            if (now - lastInProgressWriteMs < THROTTLE_MS) return@withContext
        }
        mutex.withLock {
            try {
                val file = inProgressFile()
                atomicWrite(file, gson.toJson(items))
                lastInProgressWriteMs = System.currentTimeMillis()
                lastInProgressHash = contentHash
                _snapshotVersion.value++
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write in-progress cache: ${e.message}")
            }
        }
    }

    /**
     * Deletes all CW enrichment cache files for the active profile.
     */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                nextUpFile().delete()
                inProgressFile().delete()
                lastNextUpHash = 0
                lastInProgressHash = 0
                Log.d(TAG, "Cleared CW enrichment cache for profile ${profileManager.activeProfileId.value}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear CW enrichment cache: ${e.message}")
            }
        }
        _cacheCleared.value++
    }

    private fun atomicWrite(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            // renameTo can fail on some filesystems; fall back to copy+delete
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

}

// O Gson preenche os campos por reflexão e ignora a nulidade do Kotlin: um arquivo gravado por outro build
// (debug sem R8 x release com campos renomeados, ou versão com campos diferentes) chega com campos obrigatórios
// nulos, e o NPE ("parameter contentId") estourava depois, na Home, ao escolher o perfil. Item incompleto é
// descartado; o pipeline do Continuar Assistindo refaz a lista. Os campos são lidos em variáveis anuláveis para
// a checagem valer em tempo de execução, e só depois o copy (o construtor confere cada parâmetro não nulo).

private fun CachedNextUpItem.validOrNull(): CachedNextUpItem? {
    val id: String? = contentId
    val type: String? = contentType
    val title: String? = name
    val video: String? = videoId
    if (id.isNullOrBlank() || type == null || title == null || video == null) return null
    val genreList: List<String>? = genres
    return if (genreList == null) copy(genres = emptyList()) else this
}

private fun CachedInProgressItem.validOrNull(): CachedInProgressItem? {
    val id: String? = contentId
    val type: String? = contentType
    val title: String? = name
    val video: String? = videoId
    if (id.isNullOrBlank() || type == null || title == null || video == null) return null
    val genreList: List<String>? = genres
    return if (genreList == null) copy(genres = emptyList()) else this
}
