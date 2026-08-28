package com.v2ray.ang.handler

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.util.LogUtil
import java.util.concurrent.TimeUnit

object SubscriptionUpdater {

    // -------------------------------------------------------------------------
    // Public API — the only methods external callers should ever use
    // -------------------------------------------------------------------------

    /**
     * Sync all subscription tasks with current settings.
     *
     * Startup/boot callers should use the default mode so existing periodic work is kept.
     * Use forceReschedule=true only when the next run time needs to be recalculated from
     * the latest persisted subscription state (for example after a manual refresh).
     * Call from: MainActivity.onCreate(), BootReceiver.onReceive().
     */
    fun sync(
        context: Context = AngApplication.application,
        forceReschedule: Boolean = false
    ) {
        val existingWorkPolicy =
            if (forceReschedule) {
                ExistingPeriodicWorkPolicy.REPLACE
            } else {
                ExistingPeriodicWorkPolicy.KEEP
            }

        MmkvManager.decodeSubscriptions()
            .filter { it.subscription.autoUpdate && it.subscription.url.isNotEmpty() }
            .forEach { sub ->
                scheduleOne(
                    context = context,
                    subId = sub.guid,
                    existingWorkPolicy = existingWorkPolicy
                )
            }
        LogUtil.i(
            AppConfig.TAG,
            "SubscriptionUpdater: sync complete forceReschedule=$forceReschedule"
        )
    }

    /**
     * Sync a single subscription's task.
     * Call from: SubEditActivity after saving, after a manual update (to reset the timer).
     */
    fun syncOne(context: Context = AngApplication.application, subId: String) {
        scheduleOne(
            context = context,
            subId = subId,
            existingWorkPolicy = ExistingPeriodicWorkPolicy.REPLACE
        )
    }

    /**
     * Cancel the auto-update task for a single subscription.
     * Call from: when a subscription is deleted.
     */
    fun cancelOne(context: Context = AngApplication.application, subId: String) {
        WorkManager.getInstance(context)
            .cancelUniqueWork(taskName(subId))
    }

    /**
     * Update the last updated timestamp and reschedule the task.
     * This is used to reset the periodic timer and prevent rapid rescheduling loops.
     */
    fun updateLastUpdatedAndReschedule(context: Context = AngApplication.application, subId: String) {
        val subItem = MmkvManager.decodeSubscription(subId) ?: return
        subItem.lastUpdated = System.currentTimeMillis()
        MmkvManager.encodeSubscription(subId, subItem)
        syncOne(context, subId)
    }

    // -------------------------------------------------------------------------
    // Internal scheduling logic
    // -------------------------------------------------------------------------

    private fun taskName(subId: String) = "${AppConfig.SUBSCRIPTION_UPDATE_TASK_NAME}_$subId"

    private fun scheduleOne(
        context: Context,
        subId: String,
        existingWorkPolicy: ExistingPeriodicWorkPolicy
    ) {
        val subItem = MmkvManager.decodeSubscription(subId) ?: return
        val workManager = WorkManager.getInstance(context)
        if (!subItem.autoUpdate) {
            cancelOne(context, subId)
            LogUtil.d(AppConfig.TAG, "SubscriptionUpdater: cancelled task for $subId")
            return
        }

        if (subItem.url.isEmpty()) {
            LogUtil.i(AppConfig.TAG, "SubscriptionUpdater: URL is empty for $subId, skip")
            return
        }

        val intervalMinutes = maxOf(
            AppConfig.SUBSCRIPTION_MIN_INTERVAL_MINUTES,
            subItem.updateInterval
        )

        // Base initial delay on the last successful update time persisted in subscription.
        val lastUpdated = subItem.lastUpdated
        val intervalMillis = intervalMinutes * 60 * 1000L
        val now = System.currentTimeMillis()
        var initialDelayMillis = if (lastUpdated <= 0L) {
            0L
        } else {
            maxOf(0L, lastUpdated + intervalMillis - now)
        }

        // Add a small floor to initial delay to prevent rapid rescheduling loops.
        if (existingWorkPolicy == ExistingPeriodicWorkPolicy.REPLACE && initialDelayMillis < 5000L) {
            initialDelayMillis = 5000L
        }

        val request = PeriodicWorkRequestBuilder<UpdateTask>(intervalMinutes, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(workDataOf(KEY_SUB_ID to subId))
            .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
            .addTag(AppConfig.SUBSCRIPTION_UPDATE_TASK_NAME)
            .build()

        workManager.enqueueUniquePeriodicWork(
            taskName(subId),
            existingWorkPolicy,
            request
        )

        LogUtil.i(
            AppConfig.TAG,
            "SubscriptionUpdater: scheduled [$subId] interval=${intervalMinutes}min " +
                    "initialDelay=${initialDelayMillis / 1000}s policy=$existingWorkPolicy"
        )
    }

    // -------------------------------------------------------------------------
    // Worker
    // -------------------------------------------------------------------------

    private const val KEY_SUB_ID = "subId"

    class UpdateTask(context: Context, params: WorkerParameters) :
        CoroutineWorker(context, params) {

        override suspend fun doWork(): Result {
            val subId = inputData.getString(KEY_SUB_ID)
            LogUtil.i(AppConfig.TAG, "SubscriptionUpdater worker starting: $subId")

            if (subId.isNullOrEmpty()) {
                LogUtil.w(AppConfig.TAG, "SubscriptionUpdater: missing subId in worker input")
                return Result.success()
            }

            val subscription = MmkvManager.decodeSubscription(subId)
                ?: return Result.success()
            // Cancellation of unique work is asynchronous. Re-check the persisted switch so a
            // worker that was already starting cannot perform one final update after opt-out.
            if (!subscription.enabled || !subscription.autoUpdate || subscription.url.isBlank()) {
                return Result.success()
            }

            return runCatching {
                val update = AngConfigManager.updateConfigViaSub(
                    SubscriptionCache(subId, subscription)
                )
                if (update.successCount == 0) {
                    // A successful download may legitimately accept no profiles when the user
                    // deleted every server from this subscription. Let the normal periodic
                    // schedule handle the next refresh instead of entering WorkManager backoff.
                    LogUtil.w(AppConfig.TAG, "SubscriptionUpdater: no profiles accepted for $subId")
                }
                Result.success()
            }.getOrElse {
                // Periodic work should not create a retry storm on a transient remote failure.
                LogUtil.w(AppConfig.TAG, "SubscriptionUpdater: scheduled update failed for $subId")
                Result.success()
            }
        }
    }
}
