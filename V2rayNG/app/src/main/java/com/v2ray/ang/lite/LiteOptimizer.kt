package com.v2ray.ang.lite

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.service.RealPingWorkerService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

data class LiteOptimizeResult(
    val selectedGuid: String? = null,
    val switched: Boolean = false,
    val successful: Boolean = false,
    val message: String,
)

object LiteOptimizer {
    private val optimizationMutex = Mutex()

    suspend fun optimize(
        context: Context,
        requestedGuids: Collection<String>,
        onlyTcp: Boolean,
        requestServiceReloadOnSwitch: Boolean = true,
        timeoutMillis: Long = 3L * 60L * 1000L,
        expectedCurrentGuid: String? = null,
        switchAllowed: () -> Boolean = { true },
    ): LiteOptimizeResult = optimizationMutex.withLock {
        val observedSelection = MmkvManager.getSelectServer()
        if (expectedCurrentGuid != null && observedSelection != expectedCurrentGuid) {
            return@withLock LiteOptimizeResult(
                message = "当前节点已发生变化，本次优选未切换",
            )
        }
        val selectionAtStart = expectedCurrentGuid ?: observedSelection
        val guids = requestedGuids.distinct().filter {
            MmkvManager.decodeServerConfig(it) != null
        }
        if (guids.isEmpty()) {
            return@withLock LiteOptimizeResult(message = "请先在节点页勾选参与优选的节点")
        }

        MmkvManager.clearAllTestDelayResults(guids)
        val measurements = awaitMeasurement(context, guids, onlyTcp, timeoutMillis)
            ?: return@withLock LiteOptimizeResult(message = "节点优选超时，请稍后重试")

        val result = selectBest(guids, measurements, selectionAtStart, switchAllowed)
        if (result.switched && requestServiceReloadOnSwitch) {
            LauncherManager.restartService(context)
        }
        if (result.successful) {
            MmkvManager.encodeSettings(AppConfig.PREF_LITE_LAST_OPTIMIZE, System.currentTimeMillis())
        }
        result
    }

    private suspend fun awaitMeasurement(
        context: Context,
        guids: List<String>,
        onlyTcp: Boolean,
        timeoutMillis: Long,
    ): Map<String, Long>? {
        val completed = CompletableDeferred<Unit>()
        val measurements = ConcurrentHashMap<String, Long>()
        val worker = RealPingWorkerService(
            context = context.applicationContext,
            guids = guids,
            onlyTcp = onlyTcp,
        ) { event ->
            when (event) {
                is RealPingEvent.Result -> {
                    // A node may be deleted while its probe is running. Never recreate its
                    // affiliation record or allow that stale result to win selection.
                    if (MmkvManager.encodeServerTestDelayIfProfileExists(
                            event.guid,
                            event.delayMillis,
                        )
                    ) {
                        measurements[event.guid] = event.delayMillis
                    }
                }

                is RealPingEvent.Finish -> completed.complete(Unit)
                is RealPingEvent.Progress -> Unit
            }
        }
        worker.start()
        return try {
            val finished = withTimeoutOrNull(timeoutMillis) { completed.await() } != null
            if (finished) measurements.toMap() else null
        } finally {
            worker.cancel()
        }
    }

    private fun selectBest(
        guids: List<String>,
        measurements: Map<String, Long>,
        expectedCurrentGuid: String?,
        switchAllowed: () -> Boolean,
    ): LiteOptimizeResult {
        val measured = guids.mapNotNull { guid ->
            if (MmkvManager.decodeServerConfig(guid) == null) return@mapNotNull null
            val delay = measurements[guid] ?: 0L
            if (delay > 0L) guid to delay else null
        }
        val best = measured.minByOrNull { it.second }
            ?: return LiteOptimizeResult(message = "候选节点当前都不可用")

        val currentGuid = MmkvManager.getSelectServer()
        if (currentGuid != expectedCurrentGuid) {
            return LiteOptimizeResult(message = "当前节点已发生变化，本次优选未切换")
        }
        val currentDelay = measured.firstOrNull { it.first == currentGuid }?.second
        val shouldSwitch = when {
            currentGuid == null -> true
            currentGuid !in guids -> true
            currentDelay == null -> true
            currentGuid == best.first -> false
            else -> currentDelay - best.second >= max(30L, currentDelay / 5L)
        }

        if (shouldSwitch && !switchAllowed()) {
            return LiteOptimizeResult(message = "自动优选已关闭，本次未切换节点")
        }
        if (shouldSwitch && !MmkvManager.setSelectServerIfCurrentAndExists(currentGuid, best.first)) {
            return LiteOptimizeResult(message = "候选节点已发生变化，请重新优选")
        }
        val message = when {
            shouldSwitch -> "已切换到更优节点 · ${best.second} ms"
            currentGuid == best.first -> "当前节点已经是最优 · ${best.second} ms"
            else -> "差距不大，保持当前节点以减少断流"
        }
        return LiteOptimizeResult(
            selectedGuid = if (shouldSwitch) best.first else currentGuid,
            switched = shouldSwitch,
            successful = true,
            message = message,
        )
    }
}
