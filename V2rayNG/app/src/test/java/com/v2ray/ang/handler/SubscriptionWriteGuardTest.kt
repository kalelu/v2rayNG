package com.v2ray.ang.handler

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionWriteGuardTest {

    @Test
    fun `local imports do not require a subscription`() {
        assertTrue(
            SubscriptionWriteGuard.allows(
                expectedUrl = null,
                currentUrl = null,
                isIndexed = false,
            ),
        )
    }

    @Test
    fun `network commit accepts the same published URL`() {
        assertTrue(
            SubscriptionWriteGuard.allows(
                expectedUrl = "https://example.com/sub-a",
                currentUrl = "https://example.com/sub-a",
                isIndexed = true,
            ),
        )
    }

    @Test
    fun `network commit rejects an edited URL`() {
        assertFalse(
            SubscriptionWriteGuard.allows(
                expectedUrl = "https://example.com/old",
                currentUrl = "https://example.com/new",
                isIndexed = true,
            ),
        )
    }

    @Test
    fun `network commit rejects a deleted subscription`() {
        assertFalse(
            SubscriptionWriteGuard.allows(
                expectedUrl = "https://example.com/sub",
                currentUrl = null,
                isIndexed = false,
            ),
        )
    }

    @Test
    fun `network commit rejects an unindexed stale payload`() {
        assertFalse(
            SubscriptionWriteGuard.allows(
                expectedUrl = "https://example.com/sub",
                currentUrl = "https://example.com/sub",
                isIndexed = false,
            ),
        )
    }
}
