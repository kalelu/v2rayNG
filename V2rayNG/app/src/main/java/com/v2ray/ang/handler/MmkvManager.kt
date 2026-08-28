package com.v2ray.ang.handler

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import com.tencent.mmkv.MMKV
import com.tencent.mmkv.MMKVHandler
import com.tencent.mmkv.MMKVLogLevel
import com.tencent.mmkv.MMKVRecoverStrategic
import com.v2ray.ang.AppConfig.DEFAULT_SUBSCRIPTION_ID
import com.v2ray.ang.AppConfig.PREF_IS_BOOTED
import com.v2ray.ang.AppConfig.PREF_LITE_CANDIDATE_GUIDS
import com.v2ray.ang.AppConfig.PREF_LITE_EXCLUDED_PROFILE_IDENTITIES_PREFIX
import com.v2ray.ang.AppConfig.PREF_ROUTING_RULESET
import com.v2ray.ang.AppConfig.TAG
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.entities.AssetUrlCache
import com.v2ray.ang.dto.entities.AssetUrlItem
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.dto.entities.ServerAffiliationInfo
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.dto.entities.WebDavConfig
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

internal class ProfileStorageException(message: String) : IllegalStateException(message)

internal data class ServerProfileSaveResult(
    val profileCount: Int,
    val committed: Boolean,
)

object MmkvManager {

    //region private

    private const val ID_MAIN = "MAIN"
    private const val ID_PROFILE_FULL_CONFIG = "PROFILE_FULL_CONFIG"
    private const val ID_SERVER_RAW = "SERVER_RAW"
    private const val ID_SERVER_AFF = "SERVER_AFF"
    private const val ID_SUB = "SUB"
    private const val ID_ASSET = "ASSET"
    private const val ID_SETTING = "SETTING"
    private const val KEY_SELECTED_SERVER = "SELECTED_SERVER"
    private const val KEY_ANG_CONFIGS = "ANG_CONFIGS"
    private const val KEY_SUB_SERVER_PREFIX = "SUB_SERVERS_"
    private const val KEY_SUB_IDS = "SUB_IDS"
    private const val KEY_WEBDAV_CONFIG = "WEBDAV_CONFIG"

    private val recoveryHandler = object : MMKVHandler {
        override fun onMMKVCRCCheckFail(mmapID: String) =
            recoverFromStorageError(mmapID, "CRC check")

        override fun onMMKVFileLengthError(mmapID: String) =
            recoverFromStorageError(mmapID, "file length check")

        override fun wantLogRedirecting(): Boolean = false

        override fun mmkvLog(
            level: MMKVLogLevel,
            file: String,
            line: Int,
            function: String,
            message: String
        ) = Unit
    }

    private val mainStorage by lazy { MMKV.mmkvWithID(ID_MAIN, MMKV.MULTI_PROCESS_MODE) }
    private val profileFullStorage by lazy { MMKV.mmkvWithID(ID_PROFILE_FULL_CONFIG, MMKV.MULTI_PROCESS_MODE) }
    private val serverRawStorage by lazy { MMKV.mmkvWithID(ID_SERVER_RAW, MMKV.MULTI_PROCESS_MODE) }
    private val serverAffStorage by lazy { MMKV.mmkvWithID(ID_SERVER_AFF, MMKV.MULTI_PROCESS_MODE) }
    private val subStorage by lazy { MMKV.mmkvWithID(ID_SUB, MMKV.MULTI_PROCESS_MODE) }
    private val assetStorage by lazy { MMKV.mmkvWithID(ID_ASSET, MMKV.MULTI_PROCESS_MODE) }
    private val settingsStorage by lazy { MMKV.mmkvWithID(ID_SETTING, MMKV.MULTI_PROCESS_MODE) }

    private inline fun <T> withProfileIndexLock(block: () -> T): T {
        return synchronized(mainStorage) {
            mainStorage.lock()
            try {
                block()
            } finally {
                mainStorage.unlock()
            }
        }
    }

    private inline fun <T> withSettingsStorageLock(block: () -> T): T {
        return synchronized(settingsStorage) {
            settingsStorage.lock()
            try {
                block()
            } finally {
                settingsStorage.unlock()
            }
        }
    }

    internal fun decodeLiteCandidateGuids(): Set<String> = withSettingsStorageLock {
        settingsStorage.decodeStringSet(PREF_LITE_CANDIDATE_GUIDS)?.toSet().orEmpty()
    }

    internal fun replaceLiteCandidateGuids(guids: Set<String>) {
        withSettingsStorageLock {
            settingsStorage.encode(PREF_LITE_CANDIDATE_GUIDS, guids.toMutableSet())
        }
    }

    internal fun toggleLiteCandidateGuid(guid: String): Set<String> = withSettingsStorageLock {
        val candidates = settingsStorage.decodeStringSet(PREF_LITE_CANDIDATE_GUIDS)
            ?.toMutableSet()
            ?: mutableSetOf()
        if (!candidates.add(guid)) candidates.remove(guid)
        settingsStorage.encode(PREF_LITE_CANDIDATE_GUIDS, candidates)
        candidates.toSet()
    }

    internal fun retainLiteCandidateGuids(validGuids: Set<String>): Set<String> =
        withSettingsStorageLock {
            val current = settingsStorage.decodeStringSet(PREF_LITE_CANDIDATE_GUIDS)
                ?.toSet()
                .orEmpty()
            val retained = current.intersect(validGuids)
            if (retained != current) {
                settingsStorage.encode(PREF_LITE_CANDIDATE_GUIDS, retained.toMutableSet())
            }
            retained
        }

    internal fun removeLiteCandidateReferences(guids: Collection<String>) {
        if (guids.isEmpty()) return
        withSettingsStorageLock {
            val candidates = settingsStorage.decodeStringSet(PREF_LITE_CANDIDATE_GUIDS)
                ?.toMutableSet()
                ?: return@withSettingsStorageLock
            if (candidates.removeAll(guids.toSet())) {
                settingsStorage.encode(PREF_LITE_CANDIDATE_GUIDS, candidates)
            }
        }
    }

    private fun clearLiteCandidateReferences() {
        withSettingsStorageLock {
            settingsStorage.remove(PREF_LITE_CANDIDATE_GUIDS)
        }
    }

    private fun liteExcludedProfileKey(subscriptionId: String): String =
        "$PREF_LITE_EXCLUDED_PROFILE_IDENTITIES_PREFIX${getSubscriptionId(subscriptionId)}"

    internal fun decodeLiteExcludedProfileIdentities(subscriptionId: String): Set<String> =
        withSettingsStorageLock {
            settingsStorage.decodeStringSet(liteExcludedProfileKey(subscriptionId))
                ?.toSet()
                .orEmpty()
        }

    private fun addLiteExcludedProfileIdentity(subscriptionId: String, identity: String) {
        if (identity.isBlank()) return
        withSettingsStorageLock {
            val key = liteExcludedProfileKey(subscriptionId)
            val identities = settingsStorage.decodeStringSet(key)?.toMutableSet() ?: mutableSetOf()
            if (identities.add(identity)) {
                requireStorageWrite(
                    settingsStorage.encode(key, identities),
                    "Failed to remember the deleted subscription profile",
                )
            }
        }
    }

    private fun clearLiteExcludedProfileIdentities(subscriptionId: String) {
        withSettingsStorageLock {
            settingsStorage.remove(liteExcludedProfileKey(subscriptionId))
        }
    }

    private fun selectReplacementAfterRemoval(removedGuids: Set<String>) {
        val selectedGuid = getSelectServer() ?: return
        if (selectedGuid !in removedGuids) return

        val replacementGuid = decodeAllServerList().firstOrNull { guid ->
            guid !in removedGuids && decodeServerConfig(guid) != null
        }
        if (replacementGuid == null) {
            mainStorage.remove(KEY_SELECTED_SERVER)
        } else {
            requireStorageWrite(
                mainStorage.encode(KEY_SELECTED_SERVER, replacementGuid),
                "Failed to select a replacement profile",
            )
        }
    }

    private fun removeServerReferencesFromAllIndexes(guids: Set<String>) {
        if (guids.isEmpty()) return
        mainStorage.allKeys()
            ?.asSequence()
            ?.filter { it.startsWith(KEY_SUB_SERVER_PREFIX) }
            ?.forEach { key ->
                val json = mainStorage.decodeString(key) ?: return@forEach
                val serverIds = JsonUtil.fromJsonSafe(json, Array<String>::class.java)
                    ?.toMutableList()
                    ?: return@forEach
                if (serverIds.removeAll(guids)) {
                    requireStorageWrite(
                        mainStorage.encode(key, JsonUtil.toJson(serverIds)),
                        "Failed to remove profile references",
                    )
                }
            }
    }

    private fun removeProfilePayloads(guids: Collection<String>) {
        if (guids.isEmpty()) return
        val keys = guids.toTypedArray()
        profileFullStorage.removeValuesForKeys(keys)
        serverAffStorage.removeValuesForKeys(keys)
        serverRawStorage.removeValuesForKeys(keys)
    }

    private fun requireStorageWrite(success: Boolean, message: String) {
        if (!success) throw ProfileStorageException(message)
    }

    private fun persistServerList(serverList: List<String>, subscriptionId: String): Boolean {
        return mainStorage.encode(serverListKey(subscriptionId), JsonUtil.toJson(serverList))
    }

    private fun persistSubsList(subsList: List<String>): Boolean {
        return mainStorage.encode(KEY_SUB_IDS, JsonUtil.toJson(subsList))
    }

    private fun serverListKey(subscriptionId: String): String {
        return "$KEY_SUB_SERVER_PREFIX${getSubscriptionId(subscriptionId)}"
    }

    /**
     * Returns every server referenced outside the target group, or null if the raw indexes
     * cannot provide a complete view.
     */
    private fun decodeServersReferencedByOtherGroups(subscriptionId: String): Set<String>? {
        val targetKey = serverListKey(subscriptionId)
        val keys = mainStorage.allKeys() ?: return null
        if (targetKey !in keys) return null

        val referencedServers = mutableSetOf<String>()
        for (key in keys) {
            if (!key.startsWith(KEY_SUB_SERVER_PREFIX) || key == targetKey) continue

            val json = mainStorage.decodeString(key)
            if (json.isNullOrBlank()) return null
            val serverIds = JsonUtil.fromJsonSafe(json, Array<String>::class.java) ?: return null
            referencedServers.addAll(serverIds)
        }
        return referencedServers
    }

    //endregion

    /**
     * Initializes MMKV with best-effort recovery so a damaged store is not silently discarded.
     */
    fun initialize(context: Context) {
        val logLevel = if (BuildConfig.DEBUG) {
            MMKVLogLevel.LevelDebug
        } else {
            MMKVLogLevel.LevelInfo
        }
        MMKV.initialize(
            context,
            context.filesDir.resolve("mmkv").absolutePath,
            null,
            logLevel,
            recoveryHandler
        )
    }

    private fun recoverFromStorageError(mmapID: String, error: String): MMKVRecoverStrategic {
        Log.e(TAG, "MMKV $error failed for $mmapID; attempting data recovery")
        return MMKVRecoverStrategic.OnErrorRecover
    }

    //region Server

    /**
     * Reads the legacy server list from KEY_ANG_CONFIGS for migration.
     * This method is for migration purposes only.
     *
     * @return The JSON string of legacy server list, or null if not exists.
     */
    fun readLegacyServerList(): String? {
        return mainStorage.decodeString(KEY_ANG_CONFIGS)
    }


    /**
     * Gets the selected server GUID.
     *
     * @return The selected server GUID.
     */
    fun getSelectServer(): String? {
        return mainStorage.decodeString(KEY_SELECTED_SERVER)
    }

    /**
     * Sets the selected server GUID.
     *
     * @param guid The server GUID.
     */
    fun setSelectServer(guid: String) {
        withProfileIndexLock {
            mainStorage.encode(KEY_SELECTED_SERVER, guid)
        }
    }

    /** Atomically selects a profile only while it is still published and decodable. */
    fun setSelectServerIfExists(guid: String): Boolean = withProfileIndexLock {
        if (decodeServerConfig(guid) == null || guid !in decodeAllServerList()) {
            return@withProfileIndexLock false
        }
        mainStorage.encode(KEY_SELECTED_SERVER, guid)
    }

    /** Compare-and-set selection used by long-running probes so they cannot override a user choice. */
    fun setSelectServerIfCurrentAndExists(expectedGuid: String?, newGuid: String): Boolean =
        withProfileIndexLock {
            if (getSelectServer() != expectedGuid ||
                decodeServerConfig(newGuid) == null ||
                newGuid !in decodeAllServerList()
            ) {
                return@withProfileIndexLock false
            }
            mainStorage.encode(KEY_SELECTED_SERVER, newGuid)
        }

    /**
     * Encodes the server list for a given subscription.
     * Saves to the subscription's serverList (including default subscription for ungrouped servers).
     *
     * @param serverList The list of server GUIDs.
     * @param subscriptionId The subscription ID.
     */
    fun encodeServerList(serverList: MutableList<String>, subscriptionId: String) {
        withProfileIndexLock {
            persistServerList(serverList, subscriptionId)
        }
    }


    /**
     * Decodes the server list for a given subscription.
     * If subscriptionId is empty, returns ungrouped servers.
     * Otherwise, returns servers from the specified subscription's serverList.
     *
     * @param subscriptionId The subscription ID.
     * @return The list of server GUIDs.
     */
    fun decodeServerList(subscriptionId: String): MutableList<String> {
        val json = mainStorage.decodeString(serverListKey(subscriptionId))
        return if (json.isNullOrBlank()) {
            mutableListOf()
        } else {
            JsonUtil.fromJsonSafe(json, Array<String>::class.java)?.toMutableList() ?: mutableListOf()
        }
    }

    /**
     * Decodes all server list (merged from all subscriptions including default subscription).
     * Use this when you need the complete server list.
     *
     * @return The list of all server GUIDs.
     */
    fun decodeAllServerList(): MutableList<String> {
        val allServers = mutableListOf<String>()
        val subsList = decodeSubsList()

        // If DEFAULT_SUBSCRIPTION_ID is not in the subscriptions list, add its servers
        if (!subsList.contains(DEFAULT_SUBSCRIPTION_ID)) {
            allServers.addAll(decodeServerList(DEFAULT_SUBSCRIPTION_ID))
        }

        // Add servers from all subscriptions
        subsList.forEach { guid ->
            allServers.addAll(decodeServerList(guid))
        }

        return allServers
    }


    /**
     * Decodes the server configuration.
     *
     * @param guid The server GUID.
     * @return The server configuration.
     */
    fun decodeServerConfig(guid: String): ProfileItem? {
        if (guid.isBlank()) {
            return null
        }
        val json = profileFullStorage.decodeString(guid)
        if (json.isNullOrBlank()) {
            return null
        }
        return JsonUtil.fromJsonSafe(json, ProfileItem::class.java)
    }


    /**
     * Encodes the server configuration.
     *
     * @param guid The server GUID.
     * @param config The server configuration.
     * @return The server GUID.
     */
    fun encodeServerConfig(guid: String, config: ProfileItem): String {
        val key = guid.ifBlank { Utils.getUuid() }
        withProfileIndexLock {
            requireStorageWrite(
                profileFullStorage.encode(key, JsonUtil.toJson(config)),
                "Failed to save profile payload",
            )

            // Use default subscription for servers without subscription
            val subId = getSubscriptionId(config.subscriptionId)
            val serverList = decodeServerList(subId)

            if (!serverList.contains(key)) {
                serverList.add(0, key)
                requireStorageWrite(
                    persistServerList(serverList, subId),
                    "Failed to publish profile index",
                )
                if (getSelectServer().isNullOrBlank()) {
                    requireStorageWrite(
                        mainStorage.encode(KEY_SELECTED_SERVER, key),
                        "Failed to update selected profile",
                    )
                }
            }
        }

        return key
    }

    /**
     * Saves a profile batch before publishing its group index and removing replaced payloads.
     *
     * @param profiles Generated GUIDs and parsed profiles, in insertion order.
     * @param rawConfigs Optional raw configuration payloads keyed by profile GUID.
     * @param subscriptionId The destination subscription ID.
     * @param append Whether to append to the existing group index.
     */
    internal fun saveServerProfiles(
        profiles: Map<String, ProfileItem>,
        rawConfigs: Map<String, String>,
        subscriptionId: String,
        append: Boolean,
        expectedSubscriptionUrl: String? = null,
    ): ServerProfileSaveResult = withProfileIndexLock {
            // A network update may finish after its subscription was deleted or edited. Reject
            // that stale batch before it can recreate a hidden group or revive removed profiles.
            if (!SubscriptionWriteGuard.allows(
                    expectedUrl = expectedSubscriptionUrl,
                    currentUrl = decodeSubscription(subscriptionId)?.url,
                    isIndexed = subscriptionId in decodeSubsList(),
                )
            ) {
                return@withProfileIndexLock ServerProfileSaveResult(
                    profileCount = 0,
                    committed = false,
                )
            }
            // Re-read exclusions while holding the same inter-process lock used by deletion.
            // This closes the race where a refresh parsed a node just before the user deleted it.
            val excludedIdentities = if (
                decodeSubscription(subscriptionId)?.url?.isNotBlank() == true
            ) {
                decodeLiteExcludedProfileIdentities(subscriptionId)
            } else {
                emptySet()
            }
            val acceptedProfiles = if (excludedIdentities.isEmpty()) {
                profiles
            } else {
                profiles.filter { (guid, profile) ->
                    ProfileReplacement.stableIdentity(profile, rawConfigs[guid]) !in excludedIdentities
                }
            }
            val acceptedRawConfigs = rawConfigs.filterKeys(acceptedProfiles::containsKey)

            // A valid response may contain only profiles that the user explicitly deleted.
            // Publish that empty replacement under the same URL CAS and profile-index lock so
            // stale supplier nodes disappear, while a deleted/edited subscription remains safe.
            if (!append && acceptedProfiles.isEmpty()) {
                val removedGuids = removeServersViaSubidLocked(subscriptionId)
                removeLiteCandidateReferences(removedGuids)
                return@withProfileIndexLock ServerProfileSaveResult(
                    profileCount = 0,
                    committed = true,
                )
            }

            val replacedServers = if (append) {
                emptyList()
            } else {
                decodeServerList(subscriptionId).toList()
            }
            val previousSelection = getSelectServer()
            val selectedProfile = if (!append &&
                previousSelection != null &&
                previousSelection in replacedServers
            ) {
                decodeServerConfig(previousSelection)
            } else {
                null
            }
            val replacementSelection = ProfileReplacement.findSelectedReplacement(
                profiles = acceptedProfiles,
                currentSelection = previousSelection,
                selectedProfile = selectedProfile,
            )

            acceptedProfiles.forEach { (guid, profile) ->
                requireStorageWrite(
                    profileFullStorage.encode(guid, JsonUtil.toJson(profile)),
                    "Failed to save profile payload",
                )
                acceptedRawConfigs[guid]?.let { raw ->
                    requireStorageWrite(
                        serverRawStorage.encode(guid, raw),
                        "Failed to save raw profile payload",
                    )
                }
            }

            val serverList = if (append) {
                decodeServerList(subscriptionId)
            } else {
                mutableListOf()
            }
            val indexedServers = serverList.toHashSet()
            acceptedProfiles.keys.forEach { guid ->
                if (indexedServers.add(guid)) {
                    serverList.add(0, guid)
                }
            }
            requireStorageWrite(
                persistServerList(serverList, subscriptionId),
                "Failed to publish profile index",
            )
            replacementSelection?.let { guid ->
                requireStorageWrite(
                    mainStorage.encode(KEY_SELECTED_SERVER, guid),
                    "Failed to update selected profile",
                )
            }
            if (replacedServers.isEmpty()) {
                return@withProfileIndexLock ServerProfileSaveResult(
                    profileCount = acceptedProfiles.size,
                    committed = true,
                )
            }

            val protectedServer = replacementSelection ?: previousSelection
            val referencedByOtherGroups = decodeServersReferencedByOtherGroups(subscriptionId)
            val removablePayloads = ProfileReplacement.findRemovablePayloads(
                replacedServers = replacedServers,
                replacementServers = acceptedProfiles.keys,
                protectedServer = protectedServer,
                serversReferencedByOtherGroups = referencedByOtherGroups,
            )
            removeProfilePayloads(removablePayloads)
            ServerProfileSaveResult(
                profileCount = acceptedProfiles.size,
                committed = true,
            )
        }

    /**
     * Removes the server configuration.
     *
     * @param guid The server GUID.
     */
    fun removeServer(guid: String, rememberSubscriptionExclusion: Boolean = false) {
        if (guid.isBlank()) {
            return
        }

        val removedGuids = setOf(guid)
        withProfileIndexLock {
            val exclusion = if (rememberSubscriptionExclusion) {
                decodeServerConfig(guid)
                    ?.takeIf { profile ->
                        profile.subscriptionId.isNotBlank() &&
                            decodeSubscription(profile.subscriptionId)?.url?.isNotBlank() == true
                    }
                    ?.let { profile ->
                        profile.subscriptionId to ProfileReplacement.stableIdentity(
                            profile,
                            decodeServerRaw(guid),
                        )
                    }
            } else {
                null
            }
            exclusion?.let { (subscriptionId, identity) ->
                // Lock order is always profile index -> settings. Refresh uses the same order.
                addLiteExcludedProfileIdentity(subscriptionId, identity)
            }
            // A GUID is a global profile identity. Remove every stale index reference even if
            // its payload is damaged and can no longer tell us which subscription owned it.
            removeServerReferencesFromAllIndexes(removedGuids)
            selectReplacementAfterRemoval(removedGuids)
            removeProfilePayloads(removedGuids)
        }
        removeLiteCandidateReferences(removedGuids)
    }

    /**
     * Removes the server configurations via subscription ID.
     *
     * @param subscriptionId The subscription ID.
     */
    fun removeServerViaSubid(subscriptionId: String?) {
        val subId = getSubscriptionId(subscriptionId)
        val removedGuids = withProfileIndexLock { removeServersViaSubidLocked(subId) }
        removeLiteCandidateReferences(removedGuids)
    }

    /** Must be called while [withProfileIndexLock] is held. */
    private fun removeServersViaSubidLocked(subscriptionId: String): Set<String> {
        val subId = getSubscriptionId(subscriptionId)
        val serverList = decodeServerList(subId).toSet()

        // Remove the group key itself so a deleted subscription cannot leave a hidden index.
        mainStorage.remove(serverListKey(subId))
        if (serverList.isEmpty()) return emptySet()

        val stillReferenced = decodeAllServerList().toSet()
        val fullyRemoved = serverList - stillReferenced
        selectReplacementAfterRemoval(fullyRemoved)
        removeProfilePayloads(fullyRemoved)
        return fullyRemoved
    }

    /**
     * Removes multiple server configurations from a subscription.
     *
     * @param guids The list of server GUIDs.
     * @param subscriptionId The subscription ID.
     */
    fun removeServers(guids: List<String>, subscriptionId: String) {
        if (guids.isEmpty()) return
        val subId = getSubscriptionId(subscriptionId)
        val removedGuids: Set<String> = withProfileIndexLock {
            val serverList = decodeServerList(subId)
            if (!serverList.removeAll(guids.toSet())) {
                return@withProfileIndexLock emptySet()
            }
            requireStorageWrite(
                persistServerList(serverList, subId),
                "Failed to update subscription profile index",
            )
            val stillReferenced = decodeAllServerList().toSet()
            val fullyRemoved = guids.toSet() - stillReferenced
            selectReplacementAfterRemoval(fullyRemoved)
            removeProfilePayloads(fullyRemoved)
            fullyRemoved
        }
        removeLiteCandidateReferences(removedGuids)
    }

    /**
     * Decodes the server affiliation information.
     *
     * @param guid The server GUID.
     * @return The server affiliation information.
     */
    fun decodeServerAffiliationInfo(guid: String): ServerAffiliationInfo? {
        if (guid.isBlank()) {
            return null
        }
        val json = serverAffStorage.decodeString(guid)
        if (json.isNullOrBlank()) {
            return null
        }
        return JsonUtil.fromJsonSafe(json, ServerAffiliationInfo::class.java)
    }

    /**
     * Encodes the server test delay only while the profile still exists.
     *
     * The existence check and affiliation write share the profile-index lock with deletion, so a
     * late probe result cannot recreate affiliation data after its profile has been removed.
     *
     * @param guid The server GUID.
     * @param testResult The test delay in milliseconds.
     * @return Whether the profile still existed and the delay was written.
     */
    fun encodeServerTestDelayIfProfileExists(guid: String, testResult: Long): Boolean =
        withProfileIndexLock {
            if (guid.isBlank() || decodeServerConfig(guid) == null) {
                return@withProfileIndexLock false
            }
            val aff = decodeServerAffiliationInfo(guid) ?: ServerAffiliationInfo()
            aff.testDelayMillis = testResult
            serverAffStorage.encode(guid, JsonUtil.toJson(aff))
        }

    /**
     * Clears all test delay results.
     *
     * @param keys The list of server GUIDs.
     */
    fun clearAllTestDelayResults(keys: List<String>?) {
        withProfileIndexLock {
            keys?.forEach { key ->
                if (decodeServerConfig(key) == null) return@forEach
                decodeServerAffiliationInfo(key)?.let { aff ->
                    aff.testDelayMillis = 0
                    serverAffStorage.encode(key, JsonUtil.toJson(aff))
                }
            }
        }
    }

    /**
     * Removes all server configurations.
     *
     * @return The number of server configurations removed.
     */
    fun removeAllServer(): Int {
        val count = withProfileIndexLock {
            val storedCount = profileFullStorage.allKeys()?.count() ?: 0
            profileFullStorage.clearAll()
            serverAffStorage.clearAll()
            serverRawStorage.clearAll()
            mainStorage.allKeys()
                ?.filter { it.startsWith(KEY_SUB_SERVER_PREFIX) }
                ?.takeIf { it.isNotEmpty() }
                ?.let { mainStorage.removeValuesForKeys(it.toTypedArray()) }
            mainStorage.remove(KEY_SELECTED_SERVER)
            storedCount
        }
        clearLiteCandidateReferences()
        return count
    }

    /**
     * Removes invalid server configurations.
     *
     * @param guid The server GUID.
     * @return The number of server configurations removed.
     */
    fun removeInvalidServer(guid: String): Int {
        var count = 0
        if (guid.isNotEmpty()) {
            decodeServerAffiliationInfo(guid)?.let { aff ->
                if (aff.testDelayMillis < 0L) {
                    removeServer(guid)
                    count++
                }
            }
        } else {
            serverAffStorage.allKeys()?.forEach { key ->
                decodeServerAffiliationInfo(key)?.let { aff ->
                    if (aff.testDelayMillis < 0L) {
                        removeServer(key)
                        count++
                    }
                }
            }
        }
        return count
    }

    /**
     * Encodes the raw server configuration.
     *
     * @param guid The server GUID.
     * @param config The raw server configuration.
     */
    fun encodeServerRaw(guid: String, config: String) {
        serverRawStorage.encode(guid, config)
    }

    /**
     * Decodes the raw server configuration.
     *
     * @param guid The server GUID.
     * @return The raw server configuration.
     */
    fun decodeServerRaw(guid: String): String? {
        return serverRawStorage.decodeString(guid)
    }

    /**
     * Removes profile payloads that are provably absent from their raw SUB_SERVERS_* index.
     *
     * SUB_IDS and SUB are intentionally ignored: either store can be missing after MMKV
     * recovery while the group indexes still identify live profiles. If any group index or
     * profile payload needed for a decision is unreadable, that data is preserved.
     *
     * @return The number of profile payloads removed, or null if cleanup could not run safely.
     */
    internal fun removeOrphanedServerProfiles(): Int? = synchronized(mainStorage) {
        mainStorage.lock()
        try {
            val indexedServersBySubscription = mainStorage.allKeys().orEmpty()
                .asSequence()
                .filter { key -> key.startsWith(KEY_SUB_SERVER_PREFIX) }
                .associate { key ->
                    val subscriptionId = key.removePrefix(KEY_SUB_SERVER_PREFIX)
                    val json = mainStorage.decodeString(key)
                    val serverIds = if (json.isNullOrBlank()) {
                        null
                    } else {
                        JsonUtil.fromJsonSafe(json, Array<String>::class.java)?.toSet()
                    }
                    subscriptionId to serverIds
                }

            val profiles = profileFullStorage.allKeys().orEmpty().map { guid ->
                StoredProfileReference(
                    guid = guid,
                    subscriptionId = decodeServerConfig(guid)?.subscriptionId,
                )
            }
            val orphans = OrphanProfileCleaner.findOrphans(
                profiles = profiles,
                indexedServersBySubscription = indexedServersBySubscription,
                selectedServer = getSelectServer(),
            ) ?: return@synchronized null

            if (orphans.isNotEmpty()) {
                val keys = orphans.toTypedArray()
                profileFullStorage.removeValuesForKeys(keys)
                serverAffStorage.removeValuesForKeys(keys)
                serverRawStorage.removeValuesForKeys(keys)
            }
            orphans.size
        } finally {
            mainStorage.unlock()
        }
    }

    //endregion

    //region Subscriptions

    private fun getSubscriptionId(subscriptionId: String?): String {
        return subscriptionId?.ifEmpty { DEFAULT_SUBSCRIPTION_ID } ?: DEFAULT_SUBSCRIPTION_ID
    }

    /**
     * Initializes the subscription list.
     */
    private fun initSubsList() = withProfileIndexLock {
        val subsList = decodeSubsList()
        if (subsList.isNotEmpty()) return@withProfileIndexLock

        subStorage.allKeys()?.forEach { key -> subsList.add(key) }
        if (subsList.isNotEmpty()) {
            requireStorageWrite(persistSubsList(subsList), "Failed to initialize subscription index")
        }
    }

    /**
     * Decodes the subscriptions.
     *
     * @return The list of subscriptions.
     */
    fun decodeSubscriptions(): List<SubscriptionCache> {
        initSubsList()

        val subscriptions = mutableListOf<SubscriptionCache>()
        decodeSubsList().forEach { key ->
            val json = subStorage.decodeString(key)
            if (!json.isNullOrBlank()) {
                val item = JsonUtil.fromJsonSafe(json, SubscriptionItem::class.java) ?: SubscriptionItem()
                subscriptions.add(SubscriptionCache(key, item))
            }
        }
        return subscriptions
    }

    /**
     * Removes the subscription.
     *
     * @param subid The subscription ID.
     */
    fun removeSubscription(subid: String) {
        val removedGuids = withProfileIndexLock {
            val subsList = decodeSubsList()
            if (subsList.remove(subid)) {
                requireStorageWrite(
                    persistSubsList(subsList),
                    "Failed to update subscription index",
                )
            }
            subStorage.remove(subid)
            removeServersViaSubidLocked(subid)
        }
        removeLiteCandidateReferences(removedGuids)
        clearLiteExcludedProfileIdentities(subid)
    }

    /**
     * Encodes the subscription.
     *
     * @param guid The subscription GUID.
     * @param subItem The subscription item.
     */
    fun encodeSubscription(guid: String, subItem: SubscriptionItem) {
        val key = guid.ifBlank { Utils.getUuid() }
        withProfileIndexLock {
            requireStorageWrite(
                subStorage.encode(key, JsonUtil.toJson(subItem)),
                "Failed to save subscription",
            )

            val subsList = decodeSubsList()
            if (!subsList.contains(key)) {
                subsList.add(key)
                requireStorageWrite(
                    persistSubsList(subsList),
                    "Failed to publish subscription index",
                )
            }
        }
    }

    /**
     * Publishes a newly imported URL subscription only when that URL is not already present.
     *
     * The duplicate check and both storage writes share the cross-process profile-index lock so
     * concurrent external intents cannot create two groups for the same subscription URL.
     *
     * @return Whether a new subscription was published.
     */
    fun encodeSubscriptionIfUrlAbsent(subItem: SubscriptionItem): Boolean = withProfileIndexLock {
        val subsList = decodeSubsList()
        // Preserve subscriptions from older installations whose index has not been initialized.
        subStorage.allKeys().orEmpty().forEach { key ->
            if (key !in subsList) subsList.add(key)
        }
        if (subsList.any { key -> decodeSubscription(key)?.url == subItem.url }) {
            return@withProfileIndexLock false
        }

        val key = Utils.getUuid()
        requireStorageWrite(
            subStorage.encode(key, JsonUtil.toJson(subItem)),
            "Failed to save imported subscription",
        )
        subsList.add(key)
        requireStorageWrite(
            persistSubsList(subsList),
            "Failed to publish imported subscription",
        )
        true
    }

    /**
     * Updates only the current subscription timestamp when the network request still belongs to
     * the same published URL. A deleted or edited subscription is never recreated from stale data.
     */
    fun updateSubscriptionLastUpdatedIfUrlMatches(
        subscriptionId: String,
        expectedUrl: String,
        timestamp: Long,
    ): Boolean = withProfileIndexLock {
        val current = decodeSubscription(subscriptionId)
        if (!SubscriptionWriteGuard.allows(
                expectedUrl = expectedUrl,
                currentUrl = current?.url,
                isIndexed = subscriptionId in decodeSubsList(),
            )
        ) {
            return@withProfileIndexLock false
        }

        current!!.lastUpdated = timestamp
        subStorage.encode(subscriptionId, JsonUtil.toJson(current))
    }

    /**
     * Decodes the subscription.
     *
     * @param subscriptionId The subscription ID.
     * @return The subscription item.
     */
    fun decodeSubscription(subscriptionId: String): SubscriptionItem? {
        val json = subStorage.decodeString(subscriptionId) ?: return null
        return JsonUtil.fromJsonSafe(json, SubscriptionItem::class.java)
    }

    /**
     * Encodes the subscription list.
     *
     * @param subsList The list of subscription IDs.
     */
    fun encodeSubsList(subsList: MutableList<String>) {
        withProfileIndexLock {
            requireStorageWrite(persistSubsList(subsList), "Failed to save subscription index")
        }
    }

    /**
     * Decodes the subscription list.
     *
     * @return The list of subscription IDs.
     */
    fun decodeSubsList(): MutableList<String> {
        val json = mainStorage.decodeString(KEY_SUB_IDS)
        return if (json.isNullOrBlank()) {
            mutableListOf()
        } else {
            JsonUtil.fromJsonSafe(json, Array<String>::class.java)?.toMutableList() ?: mutableListOf()
        }
    }

    //endregion

    //region Asset

    /**
     * Decodes the asset URLs.
     *
     * @return The list of asset URLs.
     */
    fun decodeAssetUrls(): List<AssetUrlCache> {
        val assetUrlItems = mutableListOf<AssetUrlCache>()
        assetStorage.allKeys()?.forEach { key ->
            val json = assetStorage.decodeString(key)
            if (!json.isNullOrBlank()) {
                val item = JsonUtil.fromJsonSafe(json, AssetUrlItem::class.java) ?: AssetUrlItem()
                assetUrlItems.add(AssetUrlCache(key, item))
            }
        }
        return assetUrlItems.sortedBy { it.assetUrl.addedTime }
    }

    /**
     * Removes the asset URL.
     *
     * @param assetid The asset ID.
     */
    fun removeAssetUrl(assetid: String) {
        assetStorage.remove(assetid)
    }

    /**
     * Encodes the asset.
     *
     * @param assetid The asset ID.
     * @param assetItem The asset item.
     */
    fun encodeAsset(assetid: String, assetItem: AssetUrlItem) {
        val key = assetid.ifBlank { Utils.getUuid() }
        assetStorage.encode(key, JsonUtil.toJson(assetItem))
    }

    /**
     * Decodes the asset.
     *
     * @param assetid The asset ID.
     * @return The asset item.
     */
    fun decodeAsset(assetid: String): AssetUrlItem? {
        val json = assetStorage.decodeString(assetid) ?: return null
        return JsonUtil.fromJsonSafe(json, AssetUrlItem::class.java)
    }

    //endregion

    //region Routing

    /**
     * Decodes the routing rulesets.
     *
     * @return The list of routing rulesets.
     */
    fun decodeRoutingRulesets(): MutableList<RulesetItem>? {
        val ruleset = settingsStorage.decodeString(PREF_ROUTING_RULESET)
        if (ruleset.isNullOrEmpty()) return null
        return JsonUtil.fromJsonSafe(ruleset, Array<RulesetItem>::class.java)?.toMutableList() ?: mutableListOf()
    }

    /**
     * Encodes the routing rulesets.
     *
     * @param rulesetList The list of routing rulesets.
     */
    fun encodeRoutingRulesets(rulesetList: MutableList<RulesetItem>?) {
        if (rulesetList.isNullOrEmpty())
            encodeSettings(PREF_ROUTING_RULESET, "")
        else
            encodeSettings(PREF_ROUTING_RULESET, JsonUtil.toJson(rulesetList))
    }

    //endregion

    //region settings
    /**
     * Encodes the settings.
     *
     * @param key The settings key.
     * @param value The settings value.
     * @return Whether the encoding was successful.
     */
    fun encodeSettings(key: String, value: String?): Boolean {
        return settingsStorage.encode(key, value)
    }

    /**
     * Encodes the settings.
     *
     * @param key The settings key.
     * @param value The settings value.
     * @return Whether the encoding was successful.
     */
    fun encodeSettings(key: String, value: Int): Boolean {
        return settingsStorage.encode(key, value)
    }

    /**
     * Encodes the settings.
     *
     * @param key The settings key.
     * @param value The settings value.
     * @return Whether the encoding was successful.
     */
    fun encodeSettings(key: String, value: Long): Boolean {
        return settingsStorage.encode(key, value)
    }

    /**
     * Encodes the settings.
     *
     * @param key The settings key.
     * @param value The settings value.
     * @return Whether the encoding was successful.
     */
    fun encodeSettings(key: String, value: Float): Boolean {
        return settingsStorage.encode(key, value)
    }

    /**
     * Encodes the settings.
     *
     * @param key The settings key.
     * @param value The settings value.
     * @return Whether the encoding was successful.
     */
    fun encodeSettings(key: String, value: Boolean): Boolean {
        return settingsStorage.encode(key, value)
    }

    /**
     * Encodes the settings.
     *
     * @param key The settings key.
     * @param value The settings value.
     * @return Whether the encoding was successful.
     */
    fun encodeSettings(key: String, value: MutableSet<String>): Boolean {
        return settingsStorage.encode(key, value)
    }

    /**
     * Decodes the settings string.
     *
     * @param key The settings key.
     * @return The settings value.
     */
    fun decodeSettingsString(key: String): String? {
        return settingsStorage.decodeString(key)
    }

    /**
     * Decodes the settings string.
     *
     * @param key The settings key.
     * @param defaultValue The default value.
     * @return The settings value.
     */
    fun decodeSettingsString(key: String, defaultValue: String?): String? {
        return settingsStorage.decodeString(key, defaultValue)
    }

    /**
     * Decodes the settings integer.
     *
     * @param key The settings key.
     * @param defaultValue The default value.
     * @return The settings value.
     */
    fun decodeSettingsInt(key: String, defaultValue: Int): Int {
        return settingsStorage.decodeInt(key, defaultValue)
    }

    /**
     * Decodes the settings long.
     *
     * @param key The settings key.
     * @param defaultValue The default value.
     * @return The settings value.
     */
    fun decodeSettingsLong(key: String, defaultValue: Long): Long {
        return settingsStorage.decodeLong(key, defaultValue)
    }

    /**
     * Decodes the settings float.
     *
     * @param key The settings key.
     * @param defaultValue The default value.
     * @return The settings value.
     */
    fun decodeSettingsFloat(key: String, defaultValue: Float): Float {
        return settingsStorage.decodeFloat(key, defaultValue)
    }

    /**
     * Decodes the settings boolean.
     *
     * @param key The settings key.
     * @return The settings value.
     */
    fun decodeSettingsBool(key: String): Boolean {
        return settingsStorage.decodeBool(key, false)
    }

    /**
     * Decodes the settings boolean.
     *
     * @param key The settings key.
     * @param defaultValue The default value.
     * @return The settings value.
     */
    fun decodeSettingsBool(key: String, defaultValue: Boolean): Boolean {
        return settingsStorage.decodeBool(key, defaultValue)
    }

    /**
     * Decodes the settings string set.
     *
     * @param key The settings key.
     * @return The settings value.
     */
    fun decodeSettingsStringSet(key: String): MutableSet<String>? {
        return settingsStorage.decodeStringSet(key)
    }


    /**
     * Encodes the start on boot setting.
     *
     * @param startOnBoot Whether to start on boot.
     */
    fun encodeStartOnBoot(startOnBoot: Boolean) {
        encodeSettings(PREF_IS_BOOTED, startOnBoot)
    }

    /**
     * Decodes the start on boot setting.
     *
     * @return Whether to start on boot.
     */
    fun decodeStartOnBoot(): Boolean {
        return decodeSettingsBool(PREF_IS_BOOTED, false)
    }

    //endregion

    //region WebDAV

    /**
     * Encodes the WebDAV config as JSON into storage.
     */
    fun encodeWebDavConfig(config: WebDavConfig): Boolean {
        return mainStorage.encode(KEY_WEBDAV_CONFIG, JsonUtil.toJson(config))
    }

    /**
     * Decodes the WebDAV config from storage.
     */
    fun decodeWebDavConfig(): WebDavConfig? {
        val json = mainStorage.decodeString(KEY_WEBDAV_CONFIG) ?: return null
        return JsonUtil.fromJsonSafe(json, WebDavConfig::class.java)
    }

    //endregion

    //region Compose helpers for Settings

    /**
     * MMKV-backed String state, auto-persists and notifies on change.
     */
    @Composable
    fun rememberMmkvString(
        key: String,
        default: String = ""
    ): MutableState<String> {
        val state = remember(key) {
            mutableStateOf(decodeSettingsString(key, default) ?: default)
        }

        LaunchedEffect(key) {
            snapshotFlow { state.value }
                .drop(1)
                .distinctUntilChanged()
                .collectLatest { value ->
                    encodeSettings(key, value)
                    SettingsChangeManager.notifySettingChanged(key)
                }
        }
        return state
    }

    /**
     * MMKV-backed Boolean state, auto-persists and notifies on change.
     */
    @Composable
    fun rememberMmkvBool(
        key: String,
        default: Boolean = false
    ): MutableState<Boolean> {
        val state = remember(key) {
            mutableStateOf(decodeSettingsBool(key, default))
        }

        LaunchedEffect(key) {
            snapshotFlow { state.value }
                .drop(1)
                .distinctUntilChanged()
                .collectLatest { value ->
                    encodeSettings(key, value)
                    SettingsChangeManager.notifySettingChanged(key)
                }
        }
        return state
    }

    //endregion
}
