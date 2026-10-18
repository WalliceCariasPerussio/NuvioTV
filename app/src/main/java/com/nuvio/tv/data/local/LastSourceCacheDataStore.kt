package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fork: the source last played per content ID (series), so the next episode opened from Continue
 * Watching or Details starts on the same source, audio and quality, as the player's next episode
 * does (selectContinuation). Keeps what identifies the source: name, texts, addon, binge group and
 * file name. Stored locally only - not synced to remote.
 */
@Singleton
class LastSourceCacheDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "last_source_cache"
        private const val NAME = "name"
        private const val TITLE = "title"
        private const val DESCRIPTION = "description"
        private const val ADDON = "addon"
        private const val BINGE_GROUP = "binge_group"
        private const val FILENAME = "filename"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private fun prefKey(contentId: String, field: String) = stringPreferencesKey("${field}_$contentId")

    suspend fun save(contentId: String, stream: Stream) {
        val fields = mapOf(
            NAME to stream.name,
            TITLE to stream.title,
            DESCRIPTION to stream.description,
            ADDON to stream.addonName,
            BINGE_GROUP to stream.behaviorHints?.bingeGroup,
            FILENAME to stream.behaviorHints?.filename,
        )
        store().edit { prefs ->
            fields.forEach { (field, value) ->
                val key = prefKey(contentId, field)
                if (value.isNullOrBlank()) prefs.remove(key) else prefs[key] = value
            }
        }
    }

    suspend fun get(contentId: String): Stream? {
        val prefs = store().data.first()
        fun value(field: String) = prefs[prefKey(contentId, field)]
        val name = value(NAME)
        val addon = value(ADDON)
        val bingeGroup = value(BINGE_GROUP)
        if (name == null && addon == null && bingeGroup == null) return null
        return Stream(
            name = name,
            title = value(TITLE),
            description = value(DESCRIPTION),
            url = null,
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = StreamBehaviorHints(
                notWebReady = null,
                bingeGroup = bingeGroup,
                countryWhitelist = null,
                proxyHeaders = null,
                filename = value(FILENAME),
            ),
            addonName = addon.orEmpty(),
            addonLogo = null,
        )
    }
}
