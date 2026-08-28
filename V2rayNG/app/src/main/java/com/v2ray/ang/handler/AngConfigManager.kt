package com.v2ray.ang.handler

import android.content.Context
import android.graphics.Bitmap
import android.text.TextUtils
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.dto.SubscriptionUpdateResult
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.fmt.CustomFmt
import com.v2ray.ang.fmt.Hysteria2Fmt
import com.v2ray.ang.fmt.ShadowsocksFmt
import com.v2ray.ang.fmt.SocksFmt
import com.v2ray.ang.fmt.TrojanFmt
import com.v2ray.ang.fmt.V2rayNFmt
import com.v2ray.ang.fmt.VlessFmt
import com.v2ray.ang.fmt.VmessFmt
import com.v2ray.ang.fmt.WireguardFmt
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.QRCodeDecoder
import com.v2ray.ang.util.Utils
import java.net.URI

object AngConfigManager {

    private data class ParsedProfile(
        val profile: ProfileItem,
        val rawConfig: String? = null,
    )

    private enum class ProfileCommitState {
        NO_VALID_PROFILES,
        STALE_SUBSCRIPTION,
        APPLIED,
    }

    private data class ProfileCommitResult(
        val configCount: Int = 0,
        val state: ProfileCommitState = ProfileCommitState.NO_VALID_PROFILES,
    ) {
        val applied: Boolean get() = state == ProfileCommitState.APPLIED
        val shouldTryNextParser: Boolean get() = state == ProfileCommitState.NO_VALID_PROFILES
    }

    // Parser mapping for different config types (lazy initialized)
    private val configFmtParsers: Map<String, (String) -> ProfileItem?> by lazy {
        mapOf(
            EConfigType.VMESS.protocolScheme to VmessFmt::parse,
            EConfigType.SHADOWSOCKS.protocolScheme to ShadowsocksFmt::parse,
            EConfigType.SOCKS.protocolScheme to SocksFmt::parse,
            AppConfig.SOCKS4 to SocksFmt::parse,
            AppConfig.SOCKS5 to SocksFmt::parse,
            EConfigType.TROJAN.protocolScheme to TrojanFmt::parse,
            EConfigType.VLESS.protocolScheme to VlessFmt::parse,
            EConfigType.WIREGUARD.protocolScheme to WireguardFmt::parse,
            EConfigType.HYSTERIA2.protocolScheme to Hysteria2Fmt::parse,
            AppConfig.HY2 to Hysteria2Fmt::parse,
            AppConfig.V2RAYNFMTS to V2rayNFmt::parse
        )
    }

    /**
     * Shares the configuration to the clipboard.
     *
     * @param context The context.
     * @param guid The GUID of the configuration.
     * @return The result code.
     */
    fun share2Clipboard(context: Context, guid: String): Int {
        try {
            val conf = shareConfig(guid)
            if (TextUtils.isEmpty(conf)) {
                return -1
            }

            Utils.setClipboard(context, conf)

        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to share config to clipboard: ${e.javaClass.simpleName}")
            return -1
        }
        return 0
    }

    /**
     * Shares non-custom configurations to the clipboard.
     *
     * @param context The context.
     * @param serverList The list of server GUIDs.
     * @return The number of configurations shared.
     */
    fun shareNonCustomConfigsToClipboard(context: Context, serverList: List<String>): Int {
        try {
            val sb = StringBuilder()
            for (guid in serverList) {
                val url = shareConfig(guid)
                if (TextUtils.isEmpty(url)) {
                    continue
                }
                sb.append(url)
                sb.appendLine()
            }
            if (sb.count() > 0) {
                Utils.setClipboard(context, sb.toString())
            }
            return sb.lines().count() - 1
        } catch (e: Exception) {
            LogUtil.e(
                AppConfig.TAG,
                "Failed to share non-custom configs to clipboard: ${e.javaClass.simpleName}",
            )
            return -1
        }
    }

    /**
     * Shares the configuration as a QR code.
     *
     * @param guid The GUID of the configuration.
     * @return The QR code bitmap.
     */
    fun share2QRCode(guid: String): Bitmap? {
        try {
            val conf = shareConfig(guid)
            if (TextUtils.isEmpty(conf)) {
                return null
            }
            return QRCodeDecoder.createQRCode(conf)

        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to share config as QR code: ${e.javaClass.simpleName}")
            return null
        }
    }

    /**
     * Shares the full content of the configuration to the clipboard.
     *
     * @param context The context.
     * @param guid The GUID of the configuration.
     * @return The result code.
     */
    fun shareFullContent2Clipboard(context: Context, guid: String?): Int {
        try {
            if (guid == null) return -1
            val result = CoreConfigManager.getV2rayConfig(context, guid)
            if (result.status) {
                Utils.setClipboard(context, result.content)
            } else {
                return -1
            }
        } catch (e: Exception) {
            LogUtil.e(
                AppConfig.TAG,
                "Failed to share full content to clipboard: ${e.javaClass.simpleName}",
            )
            return -1
        }
        return 0
    }

    /**
     * Shares the configuration.
     *
     * @param guid The GUID of the configuration.
     * @return The configuration string.
     */
    private fun shareConfig(guid: String): String {
        try {
            val config = MmkvManager.decodeServerConfig(guid) ?: return ""

            return config.configType.protocolScheme + when (config.configType) {
                EConfigType.VMESS -> VmessFmt.toUri(config)
                EConfigType.SHADOWSOCKS -> ShadowsocksFmt.toUri(config)
                EConfigType.SOCKS -> SocksFmt.toUri(config)
                EConfigType.VLESS -> VlessFmt.toUri(config)
                EConfigType.TROJAN -> TrojanFmt.toUri(config)
                EConfigType.WIREGUARD -> WireguardFmt.toUri(config)
                EConfigType.HYSTERIA2 -> Hysteria2Fmt.toUri(config)
                else -> {}
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to share config for GUID: $guid (${e.javaClass.simpleName})")
            return ""
        }
    }

    /**
     * Imports a batch of configurations.
     *
     * @param server The server string.
     * @param subid The subscription ID.
     * @param append Whether to append the configurations.
     * @return A pair containing the number of configurations and subscriptions imported.
     */
    fun importBatchConfig(server: String?, subid: String, append: Boolean): Pair<Int, Int> {
        return try {
            var configResult = parseBatchConfig(Utils.decode(server), subid, append)
            if (configResult.shouldTryNextParser) {
                configResult = parseBatchConfig(server, subid, append)
            }
            if (configResult.shouldTryNextParser) {
                configResult = parseCustomConfigServer(server, subid, append)
            }

            var countSub = parseBatchSubscription(server)
            if (countSub <= 0) {
                countSub = parseBatchSubscription(Utils.decode(server))
            }
            if (countSub > 0) {
                updateConfigViaSubAll()
            }

            configResult.configCount to countSub
        } catch (e: ProfileStorageException) {
            LogUtil.e(AppConfig.TAG, "Failed to store imported profiles: ${e.javaClass.simpleName}")
            0 to 0
        }
    }

    /**
     * Parses a batch of subscriptions.
     *
     * @param servers The servers string.
     * @return The number of subscriptions parsed.
     */
    private fun parseBatchSubscription(servers: String?): Int {
        try {
            if (servers == null) {
                return 0
            }

            var count = 0
            servers.lines()
                .distinct()
                .forEach { str ->
                    if (Utils.isValidSubUrl(str)) {
                        count += importUrlAsSubscription(str)
                    }
                }
            return count
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to parse batch subscription: ${e.javaClass.simpleName}")
        }
        return 0
    }

    /**
     * Parses a batch of configurations.
     *
     * @param servers The servers string.
     * @param subid The subscription ID.
     * @param append Whether to append the configurations.
     * @return The number of configurations parsed.
     */
    private fun parseBatchConfig(
        servers: String?,
        subid: String,
        append: Boolean,
        expectedSubscriptionUrl: String? = null,
    ): ProfileCommitResult {
        try {
            if (servers == null) {
                return ProfileCommitResult()
            }
            val subItem = MmkvManager.decodeSubscription(subid)

            // Parse all configs first (no I/O during parsing)
            val configs = mutableListOf<ProfileItem>()
            servers.lines()
                .distinct()
                .reversed()
                .forEach {
                    val config = parseConfig(it, subid, subItem)
                    if (config != null) {
                        configs.add(config)
                    }
                }

            return if (configs.isNotEmpty()) {
                commitProfiles(
                    configs = configs.map(::ParsedProfile),
                    subid = subid,
                    append = append,
                    expectedSubscriptionUrl = expectedSubscriptionUrl,
                )
            } else {
                ProfileCommitResult()
            }
        } catch (e: ProfileStorageException) {
            throw e
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to parse batch config: ${e.javaClass.simpleName}")
        }
        return ProfileCommitResult()
    }

    /**
     * Commits parsed profiles before removing the profiles they replace.
     *
     * @param configs The parsed profiles to save.
     * @param subid The subscription ID.
     * @param append Whether to append to the existing server list.
     */
    private fun commitProfiles(
        configs: List<ParsedProfile>,
        subid: String,
        append: Boolean,
        expectedSubscriptionUrl: String? = null,
    ): ProfileCommitResult {
        val currentSubscriptionUrl = MmkvManager.decodeSubscription(subid)
            ?.url
            ?.takeIf { it.isNotBlank() }
        val subscriptionHasRemoteSource = currentSubscriptionUrl != null
        val excludedIdentities = if (subscriptionHasRemoteSource) {
            MmkvManager.decodeLiteExcludedProfileIdentities(subid)
        } else {
            emptySet()
        }
        val acceptedConfigs = configs.filterNot { parsed ->
            ProfileReplacement.stableIdentity(parsed.profile, parsed.rawConfig) in excludedIdentities
        }
        val reusableGuids = if (append) {
            emptyMap()
        } else {
            MmkvManager.decodeServerList(subid)
                .mapNotNull { guid ->
                    MmkvManager.decodeServerConfig(guid)?.let { profile ->
                        ProfileReplacement.stableIdentity(
                            profile,
                            MmkvManager.decodeServerRaw(guid),
                        ) to guid
                    }
                }
                .groupByTo(linkedMapOf(), keySelector = { it.first }, valueTransform = { it.second })
                .mapValues { (_, guids) -> ArrayDeque(guids) }
        }
        val keyToProfile = linkedMapOf<String, ProfileItem>()
        val rawConfigs = mutableMapOf<String, String>()

        acceptedConfigs.forEach { parsed ->
            val identity = ProfileReplacement.stableIdentity(parsed.profile, parsed.rawConfig)
            val key = reusableGuids[identity]?.removeFirstOrNull() ?: Utils.getUuid()
            keyToProfile[key] = parsed.profile
            parsed.rawConfig?.let { raw -> rawConfigs[key] = raw }
        }

        val saveResult = MmkvManager.saveServerProfiles(
            profiles = keyToProfile,
            rawConfigs = rawConfigs,
            subscriptionId = subid,
            append = append,
            expectedSubscriptionUrl = expectedSubscriptionUrl,
        )
        return ProfileCommitResult(
            configCount = saveResult.profileCount,
            state = if (saveResult.committed) {
                ProfileCommitState.APPLIED
            } else {
                ProfileCommitState.STALE_SUBSCRIPTION
            },
        )
    }

    /**
     * Parses a custom configuration server.
     *
     * @param server The server string.
     * @param subid The subscription ID.
     * @param append Whether to append the configurations.
     * @return The number of configurations parsed.
     */
    private fun parseCustomConfigServer(
        server: String?,
        subid: String,
        append: Boolean,
        expectedSubscriptionUrl: String? = null,
    ): ProfileCommitResult {
        if (server == null) {
            return ProfileCommitResult()
        }
        if (server.contains("inbounds")
            && server.contains("outbounds")
            && server.contains("routing")
        ) {
            try {
                val serverList: Array<Any> =
                    JsonUtil.fromJson(server, Array<Any>::class.java) ?: arrayOf()

                if (serverList.isNotEmpty()) {
                    val configs = serverList.reversed().map { srv ->
                        val config = CustomFmt.parse(JsonUtil.toJson(srv))
                        config.subscriptionId = subid
                        config.description = generateDescription(config)
                        ParsedProfile(
                            profile = config,
                            rawConfig = JsonUtil.toJsonPretty(srv) ?: "",
                        )
                    }
                    return commitProfiles(configs, subid, append, expectedSubscriptionUrl)
                }
            } catch (e: ProfileStorageException) {
                throw e
            } catch (e: Exception) {
                LogUtil.e(
                    AppConfig.TAG,
                    "Failed to parse custom config server JSON array: ${e.javaClass.simpleName}",
                )
            }

            try {
                // For compatibility
                val config = CustomFmt.parse(server)
                config.subscriptionId = subid
                config.description = generateDescription(config)
                return commitProfiles(
                    configs = listOf(ParsedProfile(config, server)),
                    subid = subid,
                    append = append,
                    expectedSubscriptionUrl = expectedSubscriptionUrl,
                )
            } catch (e: ProfileStorageException) {
                throw e
            } catch (e: Exception) {
                LogUtil.e(
                    AppConfig.TAG,
                    "Failed to parse custom config server as single config: ${e.javaClass.simpleName}",
                )
            }
            return ProfileCommitResult()
        } else if (server.startsWith("[Interface]") && server.contains("[Peer]")) {
            try {
                val config = WireguardFmt.parseWireguardConfFile(server)
                config.subscriptionId = subid
                config.description = generateDescription(config)
                return commitProfiles(
                    configs = listOf(ParsedProfile(config, server)),
                    subid = subid,
                    append = append,
                    expectedSubscriptionUrl = expectedSubscriptionUrl,
                )
            } catch (e: ProfileStorageException) {
                throw e
            } catch (e: Exception) {
                LogUtil.e(
                    AppConfig.TAG,
                    "Failed to parse WireGuard config file: ${e.javaClass.simpleName}",
                )
            }
            return ProfileCommitResult()
        } else {
            return ProfileCommitResult()
        }
    }

    /**
     * Parses the configuration from a QR code or string.
     * Only parses and returns ProfileItem, does not save.
     *
     * @param str The configuration string.
     * @param subid The subscription ID.
     * @param subItem The subscription item.
     * @return The parsed ProfileItem or null if parsing fails or filtered out.
     */
    private fun parseConfig(
        str: String?,
        subid: String,
        subItem: SubscriptionItem?
    ): ProfileItem? {
        try {
            if (str == null || TextUtils.isEmpty(str)) {
                return null
            }

            val config = configFmtParsers.firstNotNullOfOrNull { (scheme, parser) ->
                if (str.startsWith(scheme)) parser(str) else null
            }

            if (config == null) {
                return null
            }

            // Apply filter
            if (subItem?.filter.isNotNullEmpty() && config.remarks.isNotNullEmpty()) {
                val matched = Regex(pattern = subItem?.filter.orEmpty())
                    .containsMatchIn(input = config.remarks)
                if (!matched) return null
            }

            config.subscriptionId = subid
            config.description = generateDescription(config)

            if (str.startsWith(AppConfig.V2RAYNFMTS, ignoreCase = true)
                && config.policyGroupSubscriptionId == "self"
            ) {
                config.policyGroupSubscriptionId = subid
            }

            return config
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to parse config: ${e.javaClass.simpleName}")
            return null
        }
    }

    /**
     * Updates the configuration via all subscriptions.
     *
     * @return Detailed result of the subscription update operation.
     */
    fun updateConfigViaSubAll(): SubscriptionUpdateResult {
        return try {
            val subscriptions = MmkvManager.decodeSubscriptions()
            subscriptions.fold(SubscriptionUpdateResult()) { acc, subscription ->
                acc + updateConfigViaSub(subscription)
            }
        } catch (e: Exception) {
            LogUtil.e(
                AppConfig.TAG,
                "Failed to update config via all subscriptions: ${e.javaClass.simpleName}",
            )
            SubscriptionUpdateResult()
        }
    }

    /**
     * Updates the configuration via a subscription.
     *
     * @param it The subscription item.
     * @return Subscription update result.
     */
    fun updateConfigViaSub(it: SubscriptionCache): SubscriptionUpdateResult {
        try {
            // Check if disabled
            if (!it.subscription.enabled) {
                return SubscriptionUpdateResult(skipCount = 1)
            }

            // Validate subscription info
            if (TextUtils.isEmpty(it.guid)
                || TextUtils.isEmpty(it.subscription.remarks)
                || TextUtils.isEmpty(it.subscription.url)
            ) {
                return SubscriptionUpdateResult(skipCount = 1)
            }

            val url = HttpUtil.toIdnUrl(it.subscription.url)
            if (!Utils.isValidUrl(url)) {
                return SubscriptionUpdateResult(failureCount = 1)
            }
            if (!it.subscription.allowInsecureUrl) {
                if (!Utils.isValidSubUrl(url)) {
                    return SubscriptionUpdateResult(failureCount = 1)
                }
            }
            LogUtil.i(AppConfig.TAG, "Updating subscription: ${it.guid}")
            val userAgent = it.subscription.userAgent
            val requestHeaders = it.subscription.requestHeaders
            val proxyUsername = SettingsManager.getSocksUsername()
            val proxyPassword = SettingsManager.getSocksPassword()

            var configText = try {
                val httpPort = SettingsManager.getHttpPort()
                HttpUtil.getUrlContentWithUserAgent(
                    UrlContentRequest(
                        url = url,
                        userAgent = userAgent,
                        requestHeaders = requestHeaders,
                        timeout = 15000,
                        httpPort = httpPort,
                        proxyUsername = proxyUsername,
                        proxyPassword = proxyPassword
                    )
                )
            } catch (e: Exception) {
                // HTTP exceptions frequently embed the full subscription URL (and token).
                LogUtil.e(
                    AppConfig.ANG_PACKAGE,
                    "Update subscription: proxy not ready or other error: ${e.javaClass.simpleName}",
                )
                ""
            }
            if (configText.isEmpty()) {
                configText = try {
                    HttpUtil.getUrlContentWithUserAgent(
                        UrlContentRequest(
                            url = url,
                            userAgent = userAgent,
                            requestHeaders = requestHeaders
                        )
                    )
                } catch (e: Exception) {
                    LogUtil.e(
                        AppConfig.TAG,
                        "Update subscription: Failed to get URL content: ${e.javaClass.simpleName}",
                    )
                    ""
                }
            }
            if (configText.isEmpty()) {
                return SubscriptionUpdateResult(failureCount = 1)
            }

            val selectedBeforeUpdate = MmkvManager.getSelectServer()
            val commitResult = parseConfigViaSub(
                server = configText,
                subid = it.guid,
                append = false,
                expectedSubscriptionUrl = it.subscription.url,
            )
            if (commitResult.applied) {
                // The profile/index commit is already linearized. Reconcile before the timestamp
                // CAS so a concurrent URL edit cannot leave the daemon running a removed node.
                LauncherManager.reconcileSelectionChange(
                    AngApplication.application,
                    selectedBeforeUpdate,
                    forceReload = true,
                )
                val timestampUpdated = MmkvManager.updateSubscriptionLastUpdatedIfUrlMatches(
                    subscriptionId = it.guid,
                    expectedUrl = it.subscription.url,
                    timestamp = System.currentTimeMillis(),
                )
                if (!timestampUpdated) {
                    return SubscriptionUpdateResult(failureCount = 1)
                }
                LogUtil.i(
                    AppConfig.TAG,
                    "Subscription updated: ${it.guid}, ${commitResult.configCount} configs",
                )
                return SubscriptionUpdateResult(
                    configCount = commitResult.configCount,
                    successCount = 1
                )
            } else {
                // Got response but no valid configs parsed
                return SubscriptionUpdateResult(failureCount = 1)
            }
        } catch (e: Exception) {
            LogUtil.e(
                AppConfig.TAG,
                "Failed to update config via subscription: ${e.javaClass.simpleName}",
            )
            return SubscriptionUpdateResult(failureCount = 1)
        }
    }

    /**
     * Removes invalid server configurations for a subscription.
     *
     * @param subId The subscription ID.
     */
    fun removeInvalidServer(subId: String) {
        val serverList = MmkvManager.decodeServerList(subId)
        val invalidServers = serverList.filter {
            val aff = MmkvManager.decodeServerAffiliationInfo(it)
            aff != null && aff.testDelayMillis < 0L
        }
        if (invalidServers.isEmpty()) return

        val selectedBeforeRemoval = MmkvManager.getSelectServer()
        MmkvManager.removeServers(invalidServers, subId)
        LauncherManager.reconcileSelectionChange(
            AngApplication.application,
            selectedBeforeRemoval,
            forceReload = true,
        )
    }

    /**
     * Sorts servers by test results for a subscription.
     *
     * @param subId The subscription ID.
     */
    fun sortByTestResultsForSub(subId: String) {
        val serverList = MmkvManager.decodeServerList(subId)
        if (serverList.isEmpty()) return

        val sorted = serverList
            .map { guid ->
                val delay =
                    MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L
                guid to if (delay <= 0L) Long.MAX_VALUE else delay
            }
            .sortedBy { it.second }
            .map { it.first }
            .toMutableList()
        MmkvManager.encodeServerList(sorted, subId)
    }

    /**
     * Parses the configuration via a subscription.
     *
     * @param server The server string.
     * @param subid The subscription ID.
     * @param append Whether to append the configurations.
     * @return The number of configurations parsed.
     */
    private fun parseConfigViaSub(
        server: String?,
        subid: String,
        append: Boolean,
        expectedSubscriptionUrl: String,
    ): ProfileCommitResult {
        var result = parseBatchConfig(
            Utils.decode(server),
            subid,
            append,
            expectedSubscriptionUrl,
        )
        if (result.shouldTryNextParser) {
            result = parseBatchConfig(server, subid, append, expectedSubscriptionUrl)
        }
        if (result.shouldTryNextParser) {
            result = parseCustomConfigServer(server, subid, append, expectedSubscriptionUrl)
        }
        return result
    }

    /**
     * Imports a URL as a subscription.
     *
     * @param url The URL.
     * @return The number of subscriptions imported.
     */
    private fun importUrlAsSubscription(url: String): Int {
        val uri = URI(Utils.fixIllegalUrl(url))
        val subItem = SubscriptionItem()
        subItem.remarks = uri.fragment ?: "import sub"
        subItem.url = url
        return if (MmkvManager.encodeSubscriptionIfUrlAbsent(subItem)) 1 else 0
    }

    /** Generates a description for the profile.
     *
     * @param profile The profile item.
     * @return The generated description.
     */
    fun generateDescription(profile: ProfileItem): String {
        // Hide xxx:xxx:***/xxx.xxx.xxx.***
        val server = profile.server
        val port = profile.serverPort
        if (server.isNullOrBlank() && port.isNullOrBlank()) return ""

        val addrPart = server?.let {
            if (it.contains(":"))
                it.split(":").take(2).joinToString(":", postfix = ":***")
            else
                it.split('.').dropLast(1).joinToString(".", postfix = ".***")
        } ?: ""

        return "$addrPart : ${port ?: ""}"
    }
}
