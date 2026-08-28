package com.v2ray.ang.handler

/** Pure policy shared by subscription profile commits and timestamp compare-and-set writes. */
internal object SubscriptionWriteGuard {

    fun allows(
        expectedUrl: String?,
        currentUrl: String?,
        isIndexed: Boolean,
    ): Boolean = expectedUrl == null || (isIndexed && currentUrl == expectedUrl)
}
