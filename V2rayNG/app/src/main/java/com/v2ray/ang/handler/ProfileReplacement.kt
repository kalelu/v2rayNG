package com.v2ray.ang.handler

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal object ProfileReplacement {

    /**
     * Stable, privacy-preserving identity for one effective connection profile.
     *
     * Subscription refreshes recreate [ProfileItem] instances and may change display metadata.
     * Hashing [ProfileItem.duplicateIdentity] keeps local choices attached to the same effective
     * endpoint without persisting credentials in the exclusion index. CUSTOM profiles keep their
     * connection details in a separate raw payload, so that payload participates in their hash.
     */
    fun stableIdentity(profile: ProfileItem, rawConfig: String? = null): String {
        val structuredIdentity = JsonUtil.toJson(profile.duplicateIdentity())
        val canonical = if (profile.configType == EConfigType.CUSTOM && !rawConfig.isNullOrBlank()) {
            JsonUtil.toJson(
                listOf(
                    structuredIdentity,
                    canonicalCustomConfig(rawConfig),
                )
            )
        } else {
            structuredIdentity
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    /** Normalizes JSON formatting and key order while ignoring the display-only root remark. */
    private fun canonicalCustomConfig(rawConfig: String): String {
        val parsed = runCatching { JsonParser.parseString(rawConfig) }.getOrNull()
            ?: return rawConfig.trim()
        if (parsed.isJsonObject) {
            parsed.asJsonObject.remove("remarks")
        }
        return JsonUtil.toJson(sortJsonKeys(parsed))
    }

    private fun sortJsonKeys(element: JsonElement): JsonElement = when {
        element.isJsonObject -> JsonObject().apply {
            element.asJsonObject.entrySet()
                .sortedBy { it.key }
                .forEach { (key, value) -> add(key, sortJsonKeys(value)) }
        }

        element.isJsonArray -> JsonArray().apply {
            element.asJsonArray.forEach { value -> add(sortJsonKeys(value)) }
        }

        else -> element
    }

    /**
     * Finds the profile that should become selected after publishing a replacement batch.
     * The first profile becomes selected when the store has no current selection.
     */
    fun findSelectedReplacement(
        profiles: Map<String, ProfileItem>,
        currentSelection: String?,
        selectedProfile: ProfileItem?,
    ): String? {
        if (profiles.isEmpty()) return null
        if (currentSelection.isNullOrBlank()) return profiles.keys.first()
        if (selectedProfile == null) return null

        if (selectedProfile.remarks.isNotBlank()) {
            profiles.entries.firstOrNull { (_, candidate) ->
                isSameText(candidate.remarks, selectedProfile.remarks) &&
                        isSameText(candidate.server, selectedProfile.server) &&
                        isSameText(candidate.serverPort, selectedProfile.serverPort) &&
                        isSameText(candidate.password, selectedProfile.password)
            }?.key?.let { return it }

            profiles.entries.firstOrNull { (_, candidate) ->
                isSameText(candidate.remarks, selectedProfile.remarks)
            }?.key?.let { return it }
        }

        profiles.entries.firstOrNull { (_, candidate) ->
            isSameText(candidate.server, selectedProfile.server) &&
                    isSameText(candidate.serverPort, selectedProfile.serverPort) &&
                    isSameText(candidate.password, selectedProfile.password)
        }?.key?.let { return it }

        profiles.entries.firstOrNull { (_, candidate) ->
            isSameText(candidate.server, selectedProfile.server) &&
                    isSameText(candidate.serverPort, selectedProfile.serverPort)
        }?.key?.let { return it }

        profiles.entries.firstOrNull { (_, candidate) ->
            isSameText(candidate.server, selectedProfile.server)
        }?.key?.let { return it }

        return profiles.keys.firstOrNull()
    }

    /**
     * Finds replaced payloads that are safe to remove.
     *
     * A null cross-group reference set means that at least one raw group index could not
     * be read. In that case deletion fails closed.
     */
    fun findRemovablePayloads(
        replacedServers: Collection<String>,
        replacementServers: Set<String>,
        protectedServer: String?,
        serversReferencedByOtherGroups: Set<String>?,
    ): Set<String> {
        if (serversReferencedByOtherGroups == null) return emptySet()

        return replacedServers.filterTo(linkedSetOf()) { guid ->
            guid != protectedServer &&
                    guid !in replacementServers &&
                    guid !in serversReferencedByOtherGroups
        }
    }

    private fun isSameText(left: String?, right: String?): Boolean {
        if (left.isNullOrBlank() || right.isNullOrBlank()) return false
        return left.trim().equals(right.trim(), ignoreCase = true)
    }
}
