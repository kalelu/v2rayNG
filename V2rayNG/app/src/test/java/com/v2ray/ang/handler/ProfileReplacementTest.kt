package com.v2ray.ang.handler

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileReplacementTest {

    @Test
    fun `stable identity ignores display and subscription metadata`() {
        val original = profile(
            remarks = "Tokyo A",
            server = "jp.example.com",
            port = "443",
            password = "secret",
        ).apply {
            subscriptionId = "subscription-a"
            addedTime = 1L
        }
        val refreshed = original.copy(
            remarks = "Tokyo renamed",
            subscriptionId = "subscription-b",
            addedTime = 999L,
        )

        assertEquals(
            ProfileReplacement.stableIdentity(original),
            ProfileReplacement.stableIdentity(refreshed),
        )
    }

    @Test
    fun `stable identity changes with connection credentials`() {
        val original = profile(server = "jp.example.com", port = "443", password = "secret")

        assertNotEquals(
            ProfileReplacement.stableIdentity(original),
            ProfileReplacement.stableIdentity(original.copy(server = "other.example.com")),
        )
        assertNotEquals(
            ProfileReplacement.stableIdentity(original),
            ProfileReplacement.stableIdentity(original.copy(password = "rotated")),
        )
    }

    @Test
    fun `custom stable identity includes the raw connection payload`() {
        val custom = profile(configType = EConfigType.CUSTOM)

        assertNotEquals(
            ProfileReplacement.stableIdentity(custom, "{\"password\":\"first\"}"),
            ProfileReplacement.stableIdentity(custom, "{\"password\":\"second\"}"),
        )
    }

    @Test
    fun `structured stable identity ignores an unrelated raw payload`() {
        val structured = profile(configType = EConfigType.VMESS, password = "secret")

        assertEquals(
            ProfileReplacement.stableIdentity(structured, "first"),
            ProfileReplacement.stableIdentity(structured, "second"),
        )
    }

    @Test
    fun `custom stable identity ignores JSON formatting key order and remarks`() {
        val custom = profile(configType = EConfigType.CUSTOM)
        val first = """{"remarks":"Tokyo","outbounds":[{"port":443,"password":"secret"}]}"""
        val second = """
            {
              "outbounds": [
                { "password": "secret", "port": 443 }
              ],
              "remarks": "Renamed"
            }
        """.trimIndent()

        assertEquals(
            ProfileReplacement.stableIdentity(custom, first),
            ProfileReplacement.stableIdentity(custom, second),
        )
    }

    @Test
    fun `prefers a full match over a remarks-only match`() {
        val profiles = linkedMapOf(
            "remarks" to profile(remarks = "selected", server = "other"),
            "full" to profile(remarks = "selected", server = "host", port = "443", password = "secret"),
        )

        val result = ProfileReplacement.findSelectedReplacement(
            profiles = profiles,
            currentSelection = "old",
            selectedProfile = profile(
                remarks = " selected ",
                server = "HOST",
                port = "443",
                password = "secret",
            ),
        )

        assertEquals("full", result)
    }

    @Test
    fun `uses a remarks match when no full match exists`() {
        val profiles = linkedMapOf(
            "other" to profile(remarks = "other", server = "host"),
            "remarks" to profile(remarks = "selected", server = "other"),
        )

        val result = ProfileReplacement.findSelectedReplacement(
            profiles = profiles,
            currentSelection = "old",
            selectedProfile = profile(remarks = "selected", server = "host"),
        )

        assertEquals("remarks", result)
    }

    @Test
    fun `matches endpoint and password when remarks are unavailable`() {
        val profiles = linkedMapOf(
            "endpoint" to profile(server = "host", port = "443", password = "secret"),
            "other" to profile(server = "other", port = "443", password = "secret"),
        )

        val result = ProfileReplacement.findSelectedReplacement(
            profiles = profiles,
            currentSelection = "old",
            selectedProfile = profile(server = "HOST", port = "443", password = "secret"),
        )

        assertEquals("endpoint", result)
    }

    @Test
    fun `falls back to the first replacement profile`() {
        val profiles = linkedMapOf(
            "first" to profile(server = "first"),
            "second" to profile(server = "second"),
        )

        val result = ProfileReplacement.findSelectedReplacement(
            profiles = profiles,
            currentSelection = "old",
            selectedProfile = profile(server = "unmatched"),
        )

        assertEquals("first", result)
    }

    @Test
    fun `selects the first profile when there is no current selection`() {
        val result = ProfileReplacement.findSelectedReplacement(
            profiles = linkedMapOf(
                "first" to profile(server = "first"),
                "second" to profile(server = "second"),
            ),
            currentSelection = null,
            selectedProfile = null,
        )

        assertEquals("first", result)
    }

    @Test
    fun `keeps an existing selection when it is outside the replaced group`() {
        val result = ProfileReplacement.findSelectedReplacement(
            profiles = mapOf("candidate" to profile(server = "host")),
            currentSelection = "other-group",
            selectedProfile = null,
        )

        assertNull(result)
    }

    @Test
    fun `removes only unreferenced superseded payloads`() {
        val result = ProfileReplacement.findRemovablePayloads(
            replacedServers = listOf("orphan", "selected", "replacement", "cross-group"),
            replacementServers = setOf("replacement"),
            protectedServer = "selected",
            serversReferencedByOtherGroups = setOf("cross-group"),
        )

        assertEquals(setOf("orphan"), result)
    }

    @Test
    fun `keeps all payloads when another group index is unreadable`() {
        val result = ProfileReplacement.findRemovablePayloads(
            replacedServers = listOf("candidate"),
            replacementServers = emptySet(),
            protectedServer = null,
            serversReferencedByOtherGroups = null,
        )

        assertEquals(emptySet<String>(), result)
    }

    private fun profile(
        configType: EConfigType = EConfigType.VMESS,
        remarks: String = "",
        server: String = "",
        port: String = "",
        password: String = "",
    ) = ProfileItem.create(configType).apply {
        this.remarks = remarks
        this.server = server
        this.serverPort = port
        this.password = password
    }
}
