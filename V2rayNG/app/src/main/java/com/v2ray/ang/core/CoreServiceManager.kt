package com.v2ray.ang.core

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.system.OsConstants
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.contracts.IDialerService
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.OutboundTrafficStat
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.BrowserDialerMode
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.lite.LiteOptimizer
import com.v2ray.ang.lite.LitePreferences
import com.v2ray.ang.service.DialerNativeService
import com.v2ray.ang.service.DialerWebviewService
import com.v2ray.ang.service.NetworkMonitor
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import com.v2ray.ang.extension.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.ProcessFinder
import java.lang.ref.SoftReference
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal data class CoreHealthMeasurement(
    val measuredGuid: String,
    val delayMillis: Long,
) {
    fun isStillCurrent(activeGuid: String?, selectedGuid: String?): Boolean =
        measuredGuid == activeGuid && measuredGuid == selectedGuid
}

object CoreServiceManager {

    private const val HEALTH_CHECK_INTERVAL_MS = 75_000L
    private const val HEALTH_CHECK_SCREEN_OFF_INTERVAL_MS = 5L * 60L * 1000L
    private const val HEALTH_FAILURE_THRESHOLD = 2
    private const val FAILOVER_COOLDOWN_MS = 5L * 60L * 1000L
    private const val FAILOVER_MEASUREMENT_TIMEOUT_MS = 90_000L

    private val coreController: CoreController = CoreNativeManager.newCoreController(CoreCallback())
    private val controlReceiver = ReceiveMessageHandler(acceptControl = true)
    private val systemReceiver = ReceiveMessageHandler(acceptControl = false)
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentConfig: ProfileItem? = null
    private var currentGuid: String? = null
    private var processFinder: XrayProcessFinder? = null
    private var browserDialer: IDialerService? = null
    private var networkMonitor: NetworkMonitor? = null
    private var healthMonitorJob: Job? = null
    private var lastFailoverAttemptAt = 0L
    @Volatile
    private var healthCheckIntervalMs = HEALTH_CHECK_INTERVAL_MS
    private val coreStateLock = ReentrantLock()
    private val isReloading = AtomicBoolean(false)

    /** Tun descriptor the core was started with, null in the proxy only and root run modes. */
    private var currentVpnInterface: ParcelFileDescriptor? = null

    var serviceControl: SoftReference<ServiceControl>? = null
        set(value) {
            field = value
            val service = value?.get()?.getService()
            CoreNativeManager.initCoreEnv(service)
            if (service != null && processFinder == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                processFinder = XrayProcessFinder(service)
                coreController.registerProcessFinder(processFinder)
            }
        }

    /**
     * Checks if the V2Ray service is running.
     * @return True if the service is running, false otherwise.
     */
    fun isRunning() = coreController.isRunning

    /**
     * Gets the name of the currently running server.
     * @return The name of the running server.
     */
    fun getRunningServerName() = currentConfig?.remarks.orEmpty()

    /**
     * Refer to the official documentation for [registerReceiver](https://developer.android.com/reference/androidx/core/content/ContextCompat#registerReceiver(android.content.Context,android.content.BroadcastReceiver,android.content.IntentFilter,int):
     * `registerReceiver(Context, BroadcastReceiver, IntentFilter, int)`.
     * Starts the V2Ray core service.
     */
    fun startCoreLoop(vpnInterface: ParcelFileDescriptor?): Boolean {
        return coreStateLock.withLock {
            if (isRunning()) {
                LogUtil.w(AppConfig.TAG, "StartCore-Manager: Core already running")
                return@withLock false
            }

            val service = getService()
            if (service == null) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Service is null")
                return@withLock false
            }

            try {
                doStartCoreLoop(service, vpnInterface)
                true
            } catch (e: Exception) {
                // Native/config exceptions may include proxy credentials or the complete JSON.
                val message = "Core start failed: ${e.javaClass.simpleName}"
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: $message")
                MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, message)
                NotificationManager.cancelNotification()
                false
            }
        }
    }

    @Throws(Exception::class)
    private fun doStartCoreLoop(service: Service, vpnInterface: ParcelFileDescriptor?) {
        ContextCompat.registerReceiver(
            service,
            controlReceiver,
            IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val systemFilter = IntentFilter(Intent.ACTION_SCREEN_ON).apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(
            service,
            systemReceiver,
            systemFilter,
            ContextCompat.RECEIVER_EXPORTED,
        )

        currentVpnInterface = vpnInterface
        launchCore(service, vpnInterface)
        startNetworkMonitor(service)
        startHealthMonitor(service)
    }

    @Throws(Exception::class)
    private fun launchCore(
        service: Service,
        vpnInterface: ParcelFileDescriptor?,
        isReload: Boolean = false,
        requestedGuid: String? = null,
    ) {
        val guid = requestedGuid ?: MmkvManager.getSelectServer() ?: error("No server selected")
        val config = MmkvManager.decodeServerConfig(guid) ?: error("Failed to decode server config")

        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Starting core loop for $guid")
        val result = CoreConfigManager.getV2rayConfig(service, guid)
        if (!result.status) {
            error(result.errorMessage.ifBlank { "Failed to get V2Ray config" })
        }

        var tunFd = vpnInterface?.fd ?: 0
        val dialerMode = BrowserDialerMode.from(config.browserDialerMode)
        val dialerAddr = if (dialerMode != null) {
            "127.0.0.1:${Utils.findRandomFreePort()}"
        } else {
            ""
        }
        if (SettingsManager.isUsingHevTun()) {
            tunFd = 0
        }

        NotificationManager.showNotification(config)
        if (dialerAddr.isNotNullEmpty()) {
            CoreNativeManager.reconcileBrowserDialer(dialerAddr)
        }
        coreController.startLoop(result.content, tunFd)

        if (!isRunning()) {
            error("Core failed to start")
        }

        currentConfig = config
        currentGuid = guid

        if (browserDialer != null) {
            browserDialer!!.stop()
            browserDialer = null
        }
        when (dialerMode) {
            BrowserDialerMode.OKHTTP -> {
                browserDialer = DialerNativeService()
                browserDialer!!.start(service, dialerAddr)
            }

            BrowserDialerMode.WEBVIEW -> {
                browserDialer = DialerWebviewService()
                browserDialer!!.start(service, dialerAddr)
            }

            else -> {}
        }

        if (!isReload) {
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, "")
        }
        NotificationManager.startSpeedNotification()
        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core started successfully")
    }

    /**
     * Stops the V2Ray core service.
     * Unregisters broadcast receivers, stops notifications, and shuts down plugins.
     * @return True if the core was stopped successfully, false otherwise.
     */
    fun stopCoreLoop(): Boolean {
        return coreStateLock.withLock {
            val service = getService() ?: return@withLock false

        healthMonitorJob?.cancel()
        healthMonitorJob = null
        networkMonitor?.unregister()
        networkMonitor = null
        currentVpnInterface = null
        currentGuid = null
        NotificationManager.stopSpeedNotification()

        if (isRunning()) {
            try {
                // Stop is deliberately awaited. Starting another core while a detached stop is
                // still releasing ports can tear down the new session or leave it unbound.
                coreController.stopLoop()
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to stop V2Ray loop", e)
            }
        }

        // Close existing browser dialer
        CoreNativeManager.reconcileBrowserDialer("")
        if (browserDialer != null) {
            browserDialer!!.stop()
            browserDialer = null
        }

        MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_STOP_SUCCESS, "")
        NotificationManager.cancelNotification()

        try {
            service.unregisterReceiver(controlReceiver)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to unregister control receiver", e)
        }
        try {
            service.unregisterReceiver(systemReceiver)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to unregister system receiver", e)
        }

            true
        }
    }

    /**
     * Subscribes to upstream network changes for whichever run mode is active.
     * All three services share this manager, so the tunnel recovers from a handover in proxy only
     * and root mode as well, not just behind the VPN interface.
     */
    private fun startNetworkMonitor(service: Service) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (networkMonitor != null) return

        val connectivity = service.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        networkMonitor = NetworkMonitor(
            connectivity = connectivity,
            onUnderlyingNetworksChanged = { networks -> serviceControl?.get()?.setUnderlyingNetworks(networks) },
            onHandover = { reloadCore() },
        ).also { it.register() }
    }

    /**
     * Restarts the core in place after the upstream network changed: the service, the notification
     * and the VPN interface all stay up, so nothing of this is visible.
     *
     * The config is rebuilt on purpose, outbound server domains are resolved while building it and
     * an address resolved on a network that is gone can be unusable on the new one.
     *
     * @return True if the core is running again.
     */
    private fun reloadCore(): Boolean {
        if (!isReloading.compareAndSet(false, true)) return false
        val service = getService()
        if (service == null) {
            isReloading.set(false)
            return false
        }
        val previousGuid = currentGuid
        val targetGuid = MmkvManager.getSelectServer()
        if (targetGuid == null) {
            isReloading.set(false)
            managerScope.launch { serviceControl?.get()?.stopService() }
            return false
        }
        var recovered = false
        var shouldStopService = false
        var success = false

        try {
            success = coreStateLock.withLock {
                val target = targetGuid ?: return@withLock false
                val tunFd = currentVpnInterface

                LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core reload start...")
                if (isRunning()) coreController.stopLoop()
                launchCore(service, tunFd, isReload = true, requestedGuid = target)

                LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core reload finished")
                MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_RUNNING, "")
                true
            }
        } catch (error: Exception) {
            val message = "Core reload failed: ${error.javaClass.simpleName}"
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: $message")
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, message)

            recovered = coreStateLock.withLock {
                recoverCoreAfterReloadFailure(
                    service = service,
                    previousGuid = previousGuid,
                    failedTargetGuid = targetGuid,
                )
            }
            shouldStopService = !recovered
        } finally {
            isReloading.set(false)
        }

        if (shouldStopService) {
            // Leave no wedged foreground service/TUN behind. A later user start then gets a
            // clean service instance and an unlocked start state.
            managerScope.launch { serviceControl?.get()?.stopService() }
        } else if ((success || recovered) && MmkvManager.getSelectServer() != currentGuid) {
            // A selection change arrived while this reload was in flight. Coalesce it into one
            // follow-up reload instead of losing the broadcast to the single-flight guard.
            managerScope.launch { reloadCore() }
        }
        return success
    }

    private fun recoverCoreAfterReloadFailure(
        service: Service,
        previousGuid: String?,
        failedTargetGuid: String?,
    ): Boolean {
        val selectedNow = MmkvManager.getSelectServer()
        val recoveryGuid = when {
            selectedNow != null &&
                selectedNow != failedTargetGuid &&
                MmkvManager.decodeServerConfig(selectedNow) != null -> selectedNow

            previousGuid != null && MmkvManager.decodeServerConfig(previousGuid) != null ->
                when {
                    selectedNow == previousGuid -> previousGuid
                    selectedNow == failedTargetGuid &&
                        MmkvManager.setSelectServerIfCurrentAndExists(
                            failedTargetGuid,
                            previousGuid,
                        ) -> previousGuid
                    else -> MmkvManager.getSelectServer()
                        ?.takeIf { MmkvManager.decodeServerConfig(it) != null }
                }

            else -> null
        } ?: return false

        return try {
            if (isRunning()) coreController.stopLoop()
            launchCore(
                service = service,
                vpnInterface = currentVpnInterface,
                isReload = true,
                requestedGuid = recoveryGuid,
            )
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_RUNNING, "")
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: Recovered core with $recoveryGuid")
            true
        } catch (recoveryError: Exception) {
            LogUtil.e(
                AppConfig.TAG,
                "StartCore-Manager: Core recovery failed: ${recoveryError.javaClass.simpleName}",
            )
            false
        }
    }

    /**
     * Keeps the active connection from black-holing indefinitely when its proxy endpoint dies.
     * The monitor lives inside the already-running foreground daemon, so it does not rely on a
     * background foreground-service launch that Android 12+ may reject.
     */
    private fun startHealthMonitor(service: Service) {
        healthMonitorJob?.cancel()
        val powerManager = service.getSystemService(Context.POWER_SERVICE) as? PowerManager
        healthCheckIntervalMs = if (powerManager?.isInteractive == false) {
            HEALTH_CHECK_SCREEN_OFF_INTERVAL_MS
        } else {
            HEALTH_CHECK_INTERVAL_MS
        }
        healthMonitorJob = managerScope.launch {
            var consecutiveFailures = 0
            delay(healthCheckIntervalMs)
            while (isActive) {
                if (!isRunning() || isReloading.get() || !LitePreferences.autoOptimizeEnabled()) {
                    consecutiveFailures = 0
                    delay(healthCheckIntervalMs)
                    continue
                }

                val measurement = measureCurrentProxyHealth()
                if (measurement == null) {
                    delay(healthCheckIntervalMs)
                    continue
                }
                if (!measurement.isStillCurrent(currentGuid, MmkvManager.getSelectServer())) {
                    consecutiveFailures = 0
                    delay(healthCheckIntervalMs)
                    continue
                }
                if (measurement.delayMillis >= 0L) {
                    consecutiveFailures = 0
                    MmkvManager.encodeServerTestDelayIfProfileExists(
                        measurement.measuredGuid,
                        measurement.delayMillis,
                    )
                } else {
                    consecutiveFailures += 1
                }

                val now = System.currentTimeMillis()
                if (consecutiveFailures >= HEALTH_FAILURE_THRESHOLD &&
                    now - lastFailoverAttemptAt >= FAILOVER_COOLDOWN_MS
                ) {
                    lastFailoverAttemptAt = now
                    consecutiveFailures = 0
                    attemptHealthFailover(service)
                }
                delay(healthCheckIntervalMs)
            }
        }
    }

    private fun measureCurrentProxyHealth(): CoreHealthMeasurement? {
        if (!coreStateLock.tryLock()) return null
        return try {
            if (!isRunning()) return null
            val measuredGuid = currentGuid ?: return null
            val primary = runCatching {
                coreController.measureDelay(SettingsManager.getDelayTestUrl())
            }.getOrElse {
                LogUtil.w(
                    AppConfig.TAG,
                    "Health probe failed on primary URL: ${it.javaClass.simpleName}",
                )
                -1L
            }
            if (primary >= 0L) return CoreHealthMeasurement(measuredGuid, primary)
            val fallback = runCatching {
                coreController.measureDelay(SettingsManager.getDelayTestUrl(true))
            }.getOrElse {
                LogUtil.w(
                    AppConfig.TAG,
                    "Health probe failed on fallback URL: ${it.javaClass.simpleName}",
                )
                -1L
            }
            CoreHealthMeasurement(measuredGuid, fallback)
        } finally {
            coreStateLock.unlock()
        }
    }

    private suspend fun attemptHealthFailover(service: Service) {
        if (!LitePreferences.autoOptimizeEnabled()) return
        val previousGuid = currentGuid ?: return
        if (MmkvManager.getSelectServer() != previousGuid) return
        val alternatives = LitePreferences.candidateGuids().filter { guid ->
            guid != previousGuid && MmkvManager.decodeServerConfig(guid) != null
        }
        if (alternatives.isEmpty()) {
            LogUtil.w(AppConfig.TAG, "Health failover skipped: no marked alternative node")
            return
        }

        val result = LiteOptimizer.optimize(
            context = service,
            requestedGuids = alternatives,
            onlyTcp = false,
            requestServiceReloadOnSwitch = false,
            timeoutMillis = FAILOVER_MEASUREMENT_TIMEOUT_MS,
            expectedCurrentGuid = previousGuid,
            switchAllowed = LitePreferences::autoOptimizeEnabled,
        )
        LogUtil.i(AppConfig.TAG, "Health failover result: ${result.message}")
        if (!result.switched) return

        if (!reloadCore()) {
            LogUtil.e(AppConfig.TAG, "Health failover reload failed; recovery was attempted")
        }
    }

    /**
     * Queries and resets all outbound traffic counters in one core call.
     * Go side format: tag,direction,value;tag,direction,value;
     */
    fun queryAllOutboundTrafficStats(): List<OutboundTrafficStat> {
        if (!coreStateLock.tryLock()) return emptyList()
        val payload = try {
            // The stats manager is gone once the core stops, querying it then reaches into freed state.
            if (!isRunning()) return emptyList()
            coreController.queryAllOutboundTrafficStats()
        } finally {
            coreStateLock.unlock()
        }

        val result = ArrayList<OutboundTrafficStat>()

        payload.split(';').forEach { entry ->
            if (entry.isBlank()) return@forEach

            val parts = entry.split(',', limit = 3)
            if (parts.size != 3) return@forEach

            val value = parts[2].toLongOrNull() ?: return@forEach

            result.add(
                OutboundTrafficStat(
                    tag = parts[0],
                    direction = parts[1],
                    value = value,
                )
            )
        }
//        LogUtil.d(AppConfig.TAG, "Queried outbound traffic stats: $result")
        return result
    }

    /**
     * Measures the connection delay for the current V2Ray configuration.
     * Tests with primary URL first, then falls back to alternative URL if needed.
     * Also fetches remote IP information if the delay test was successful.
     */
    private fun measureV2rayDelay() {
        if (!isRunning()) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val service = getService() ?: return@launch
            var time = -1L
            var errorStr = ""

            if (!coreStateLock.tryLock()) return@launch
            try {
                try {
                    if (isRunning()) {
                        time = coreController.measureDelay(SettingsManager.getDelayTestUrl())
                    }
                } catch (e: Exception) {
                    LogUtil.e(
                        AppConfig.TAG,
                        "StartCore-Manager: Failed to measure delay: ${e.javaClass.simpleName}",
                    )
                    errorStr = e.javaClass.simpleName
                }
                if (time == -1L && isRunning()) {
                    try {
                        time = coreController.measureDelay(SettingsManager.getDelayTestUrl(true))
                    } catch (e: Exception) {
                        LogUtil.e(
                            AppConfig.TAG,
                            "StartCore-Manager: Failed to measure delay: ${e.javaClass.simpleName}",
                        )
                        errorStr = e.javaClass.simpleName
                    }
                }
            } finally {
                coreStateLock.unlock()
            }

            val result = ConnectionTestResult(
                delayMillis = time,
                errorMessage = errorStr,
            )
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_RESULT, result)

            // Only fetch IP info if the delay test was successful
            if (time >= 0) {
                SpeedtestManager.getRemoteIPInfo()?.let { ip ->
                    MessageHelper.sendMsg2UI(
                        service,
                        AppConfig.MSG_MEASURE_DELAY_RESULT,
                        result.copy(
                            country = ip.country,
                            ipAddress = ip.ipAddress,
                        ),
                    )
                }
            }
        }
    }

    /**
     * Gets the current service instance.
     * @return The current service instance, or null if not available.
     */
    private fun getService(): Service? {
        return serviceControl?.get()?.getService()
    }

    /**
     * Core callback handler implementation for handling V2Ray core events.
     * Handles startup, shutdown, socket protection, and status emission.
     */
    private class CoreCallback : CoreCallbackHandler {
        /**
         * Called when V2Ray core starts up.
         * @return 0 for success, any other value for failure.
         */
        override fun startup(): Long {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: CoreCallback startup")
            return 0
        }

        /**
         * Called when V2Ray core shuts down.
         * @return 0 for success, any other value for failure.
         */
        override fun shutdown(): Long {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: CoreCallback shutdown")
            return 0
        }

        /**
         * Called when V2Ray core emits status information.
         * @param l Status code.
         * @param s Status message.
         * @return Always returns 0.
         */
        override fun onEmitStatus(l: Long, s: String?): Long {
            // Native status text is unconstrained and may echo configuration fragments.
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: CoreCallback status code=$l")
            return 0
        }
    }

    /**
     * Process finder implementation for Xray core.
     * Uses ConnectivityManager to find the owning UID of a connection based on network parameters.
     */
    private class XrayProcessFinder(context: Context) : ProcessFinder {
        private val cm: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)

        override fun findProcessByConnection(network: String, srcIP: String, srcPort: Long, destIP: String, destPort: Long): Long {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1L
            if (cm == null) return -1L
            val proto = when (network) {
                "tcp" -> OsConstants.IPPROTO_TCP
                "udp" -> OsConstants.IPPROTO_UDP
                else -> return -1L
            }

            if (destIP.isBlank() || destPort == 0L) {
                LogUtil.d(AppConfig.TAG, "ProcessFinder: incomplete $network connection metadata")
                return -1L
            }

            return try {
                val uid = cm.getConnectionOwnerUid(
                    proto,
                    InetSocketAddress(srcIP, srcPort.toInt()),
                    InetSocketAddress(destIP, destPort.toInt())
                ).toLong()
                LogUtil.d(AppConfig.TAG, "ProcessFinder: resolved uid=$uid for $network connection")

                uid
            } catch (_: Exception) {
                -1L
            }
        }
    }

    /**
     * Broadcast receiver for handling messages sent to the service.
     * Handles registration, service control, and screen events.
     */
    private class ReceiveMessageHandler(
        private val acceptControl: Boolean,
    ) : BroadcastReceiver() {
        /**
         * Handles received broadcast messages.
         * Processes service control messages and screen state changes.
         * @param ctx The context in which the receiver is running.
         * @param intent The intent being received.
         */
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val serviceControl = serviceControl?.get() ?: return
            if (acceptControl) when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_REGISTER_CLIENT -> {
                    if (isRunning()) {
                        MessageHelper.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_RUNNING, "")
                    } else {
                        MessageHelper.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_NOT_RUNNING, "")
                    }
                }

                AppConfig.MSG_UNREGISTER_CLIENT -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_START -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_STOP -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Stop service")
                    val pendingResult = goAsync()
                    managerScope.launch {
                        try {
                            serviceControl.stopService()
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }

                AppConfig.MSG_STATE_RESTART -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Reload core in place")
                    if (isOrderedBroadcast) resultCode = Activity.RESULT_OK

                    val pendingResult = goAsync()
                    managerScope.launch {
                        try {
                            reloadCore()
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }

                AppConfig.MSG_MEASURE_DELAY -> {
                    measureV2rayDelay()
                }
            }

            if (!acceptControl) when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Screen off")
                    healthCheckIntervalMs = HEALTH_CHECK_SCREEN_OFF_INTERVAL_MS
                    NotificationManager.stopSpeedNotification()
                }

                Intent.ACTION_SCREEN_ON -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Screen on")
                    healthCheckIntervalMs = HEALTH_CHECK_INTERVAL_MS
                    NotificationManager.startSpeedNotification()
                }
            }
        }
    }
}
