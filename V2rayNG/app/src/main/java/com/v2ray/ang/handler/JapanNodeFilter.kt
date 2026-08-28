package com.v2ray.ang.handler

/** Fixed product policy for profiles imported into the Japan-only node list. */
internal object JapanNodeFilter {

    private val allowedNameParts = listOf("日本", "东京")

    fun accepts(name: String?): Boolean = name != null && allowedNameParts.any(name::contains)

    /**
     * A verified subscription refresh may replace its old list with an empty filtered result.
     * Local imports must never clear an existing list merely because every new profile was filtered.
     */
    fun shouldCommit(acceptedCount: Int, expectedSubscriptionUrl: String?): Boolean =
        acceptedCount > 0 || !expectedSubscriptionUrl.isNullOrBlank()
}
