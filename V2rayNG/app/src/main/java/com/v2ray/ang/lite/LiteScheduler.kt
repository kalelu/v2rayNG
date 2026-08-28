package com.v2ray.ang.lite

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import java.util.concurrent.TimeUnit

object LiteScheduler {
    fun initialize(context: Context) {
        LitePreferences.initializeDefaults()
        val workManager = WorkManager.getInstance(context)

        workManager.enqueueUniqueWork(
            "${AppConfig.LITE_ENERGY_TASK_NAME}_initial",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<LiteEnergyWorker>().build(),
        )
        workManager.enqueueUniquePeriodicWork(
            AppConfig.LITE_ENERGY_TASK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<LiteEnergyWorker>(1L, TimeUnit.HOURS).build(),
        )
        syncAutoOptimize(context)
    }

    fun syncAutoOptimize(context: Context) {
        val workManager = WorkManager.getInstance(context)
        if (!LitePreferences.autoOptimizeEnabled()) {
            workManager.cancelUniqueWork(AppConfig.LITE_OPTIMIZE_TASK_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        workManager.enqueueUniquePeriodicWork(
            AppConfig.LITE_OPTIMIZE_TASK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<LiteOptimizeWorker>(6L, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build(),
        )
    }
}

class LiteEnergyWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runCatching {
        LiteEnergyRepository.captureSample(applicationContext)
        Result.success()
    }.getOrElse {
        LogUtil.e(AppConfig.TAG, "Energy sample failed", it)
        Result.retry()
    }
}

class LiteOptimizeWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!LitePreferences.autoOptimizeEnabled()) return Result.success()
        val candidates = LitePreferences.candidateGuids()
        if (candidates.size < 2) return Result.success()
        return runCatching {
            val result = LiteOptimizer.optimize(
                context = applicationContext,
                requestedGuids = candidates,
                onlyTcp = false,
                // The user can disable automatic optimization while a long probe is running.
                // Re-check immediately before the optimizer performs its selection CAS.
                switchAllowed = LitePreferences::autoOptimizeEnabled,
            )
            LogUtil.i(AppConfig.TAG, "Automatic node optimization: ${result.message}")
            // Unavailable nodes and probe timeouts are business outcomes, not scheduler
            // failures. Retrying them on WorkManager's short backoff would create a battery loop.
            Result.success()
        }.getOrElse {
            LogUtil.e(AppConfig.TAG, "Automatic node optimization failed", it)
            Result.retry()
        }
    }
}
