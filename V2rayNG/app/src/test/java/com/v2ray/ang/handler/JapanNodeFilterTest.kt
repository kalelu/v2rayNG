package com.v2ray.ang.handler

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JapanNodeFilterTest {

    @Test
    fun `accepts names containing the required Japanese locations`() {
        assertTrue(JapanNodeFilter.accepts("01 日本 高速"))
        assertTrue(JapanNodeFilter.accepts("专线-东京-02"))
    }

    @Test
    fun `rejects empty names and other spellings or locations`() {
        assertFalse(JapanNodeFilter.accepts(null))
        listOf("", "   ", "美国", "新加坡", "Tokyo", "東京").forEach { name ->
            assertFalse("Unexpected accepted name: $name", JapanNodeFilter.accepts(name))
        }
    }

    @Test
    fun `verified subscription refresh may publish an empty filtered replacement`() {
        assertTrue(
            JapanNodeFilter.shouldCommit(
                acceptedCount = 0,
                expectedSubscriptionUrl = "https://example.com/subscription",
            ),
        )
    }

    @Test
    fun `empty local import never replaces existing profiles`() {
        assertFalse(JapanNodeFilter.shouldCommit(acceptedCount = 0, expectedSubscriptionUrl = null))
        assertFalse(JapanNodeFilter.shouldCommit(acceptedCount = 0, expectedSubscriptionUrl = ""))
        assertFalse(JapanNodeFilter.shouldCommit(acceptedCount = 0, expectedSubscriptionUrl = "   "))
    }

    @Test
    fun `matching local import is committed`() {
        assertTrue(JapanNodeFilter.shouldCommit(acceptedCount = 1, expectedSubscriptionUrl = null))
    }
}
