package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.data.local.ProfileAddonEntry
import com.nuvio.tv.data.remote.supabase.SupabaseAddon
import com.nuvio.tv.domain.model.UserProfile
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AddonProfileTransfer"

enum class AddonTransferOutcome { ADDED, ALREADY_INSTALLED }

// Fork: send an installed addon to other profiles of the same account. It is a one-time copy: each
// profile keeps its own list afterwards. sync_push_addons replaces a profile's whole list, so the
// target's current list is read first (from the account when signed in, else this device's copy) and
// the addon is appended to it.
@Singleton
class AddonProfileTransferService @Inject constructor(
    private val postgrest: Postgrest,
    private val authManager: AuthManager,
    private val addonPreferences: AddonPreferences,
    private val profileManager: ProfileManager,
    private val syncClientIdentity: SyncClientIdentity
) {
    /**
     * Profiles that can receive an addon from the active one. A profile that mirrors the primary
     * profile's addons has no list of its own, so it is left out (and so is the primary when the
     * active profile is the one mirroring it).
     */
    val eligibleTargets: Flow<List<UserProfile>> = combine(
        profileManager.activeProfileId,
        profileManager.profiles
    ) { activeProfileId, profiles ->
        val active = profiles.firstOrNull { it.id == activeProfileId }
        val activeMirrorsPrimary = active?.mirrorsPrimaryAddons == true
        profiles.filter { profile ->
            profile.id != activeProfileId &&
                !profile.mirrorsPrimaryAddons &&
                !(activeMirrorsPrimary && profile.isPrimary)
        }
    }.distinctUntilChanged()

    suspend fun profilesWithAddon(url: String, profileIds: Collection<Int>): Result<Set<Int>> =
        withContext(Dispatchers.IO) {
            try {
                Result.success(
                    profileIds.filterTo(mutableSetOf()) { profileId ->
                        currentEntries(profileId).any { sameAddonUrl(it.url, url) }
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check which profiles have $url", e)
                Result.failure(e)
            }
        }

    suspend fun sendAddon(url: String, userSetName: String?, profileId: Int): Result<AddonTransferOutcome> =
        withContext(Dispatchers.IO) {
            try {
                val merged = appendAddonEntry(currentEntries(profileId), url, userSetName)
                    ?: return@withContext Result.success(AddonTransferOutcome.ALREADY_INSTALLED)
                if (authManager.isAuthenticated) {
                    pushToRemote(profileId, merged)
                }
                // Signed in, this is what the profile shows until its next pull; signed out, it is the
                // whole transfer.
                addonPreferences.addAddonForProfile(profileId, url, userSetName)
                Log.d(TAG, "Sent $url to profile $profileId (${merged.size} addons)")
                Result.success(AddonTransferOutcome.ADDED)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send $url to profile $profileId", e)
                Result.failure(e)
            }
        }

    private suspend fun currentEntries(profileId: Int): List<ProfileAddonEntry> {
        if (!authManager.isAuthenticated) return addonPreferences.addonEntriesForProfile(profileId)
        // A profile that never synced has no rows yet, and the app keeps the local list when the remote
        // one is empty. Its list is this device's copy (the defaults if never touched); pushing only the
        // new addon would wipe those on its next pull.
        return remoteEntries(profileId).ifEmpty { addonPreferences.addonEntriesForProfile(profileId) }
    }

    private suspend fun remoteEntries(profileId: Int): List<ProfileAddonEntry> {
        val effectiveUserId = authManager.getEffectiveUserId(fallbackToOwnIdOnFailure = false)
            ?: throw IllegalStateException("Unable to resolve sync owner for addon sync")
        val rows = withJwtRefreshRetry {
            postgrest.from("addons")
                .select { filter {
                    eq("user_id", effectiveUserId)
                    eq("profile_id", profileId)
                } }
                .decodeList<SupabaseAddon>()
        }
        return rows
            .sortedBy { it.sortOrder }
            .map { ProfileAddonEntry(url = it.url, name = it.name, enabled = it.enabled) }
    }

    private suspend fun pushToRemote(profileId: Int, entries: List<ProfileAddonEntry>) {
        val params = buildJsonObject {
            put("p_addons", buildJsonArray {
                entries.forEachIndexed { index, entry ->
                    addJsonObject {
                        put("url", entry.url)
                        put("sort_order", index)
                        put("enabled", entry.enabled)
                        if (!entry.name.isNullOrBlank()) {
                            put("name", entry.name)
                        }
                    }
                }
            })
            put("p_profile_id", profileId)
            putSyncOriginClientId(syncClientIdentity)
        }
        withJwtRefreshRetry {
            postgrest.rpc("sync_push_addons", params)
        }
    }

    private suspend fun <T> withJwtRefreshRetry(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            if (!authManager.refreshSessionIfJwtExpired(e)) throw e
            block()
        }
    }
}

private val UserProfile.mirrorsPrimaryAddons: Boolean
    get() = !isPrimary && usesPrimaryAddons

/** [entries] with the addon appended, or null when the list already has it. */
internal fun appendAddonEntry(
    entries: List<ProfileAddonEntry>,
    url: String,
    userSetName: String?
): List<ProfileAddonEntry>? {
    if (entries.any { sameAddonUrl(it.url, url) }) return null
    return entries + ProfileAddonEntry(
        url = canonicalAddonUrl(url),
        name = userSetName?.takeIf { it.isNotBlank() },
        enabled = true
    )
}

// The desktop app pushes characters like `|` percent-encoded (configured addons carry them in the URL).
internal fun sameAddonUrl(a: String, b: String): Boolean =
    canonicalAddonUrl(decodeUrlCharacters(a)).equals(canonicalAddonUrl(decodeUrlCharacters(b)), ignoreCase = true)

private val ENCODED_URL_CHARACTERS = Regex("%(20|22|3C|3E|5C|5E|60|7B|7C|7D)", RegexOption.IGNORE_CASE)

private fun decodeUrlCharacters(url: String): String =
    ENCODED_URL_CHARACTERS.replace(url) { match -> match.groupValues[1].toInt(16).toChar().toString() }

// Same canonical form as AddonPreferences and AddonSyncService: no trailing slash or /manifest.json,
// query kept.
private fun canonicalAddonUrl(url: String): String {
    val trimmed = url.trim().trimEnd('/')
    val queryStart = trimmed.indexOf('?')
    val path = if (queryStart >= 0) trimmed.substring(0, queryStart) else trimmed
    val query = if (queryStart >= 0) trimmed.substring(queryStart) else ""
    val cleanPath = if (path.endsWith("/manifest.json", ignoreCase = true)) {
        path.dropLast("/manifest.json".length).trimEnd('/')
    } else {
        path.trimEnd('/')
    }
    return cleanPath + query
}
