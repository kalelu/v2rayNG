package com.v2ray.ang.lite

import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager

object LitePreferences {
    @Synchronized
    fun initializeDefaults() {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_LITE_INITIALIZED, false)) return

        MmkvManager.encodeStartOnBoot(true)
        MmkvManager.encodeSettings(AppConfig.PREF_SPEED_ENABLED, false)
        MmkvManager.encodeSettings(AppConfig.PREF_REAL_PING_CONCURRENCY, "4")
        MmkvManager.encodeSettings(AppConfig.PREF_LITE_AUTO_OPTIMIZE, true)
        MmkvManager.encodeSettings(AppConfig.PREF_LITE_INITIALIZED, true)
    }

    @Synchronized
    fun candidateGuids(): Set<String> =
        MmkvManager.decodeLiteCandidateGuids()

    @Synchronized
    fun setCandidateGuids(guids: Set<String>) {
        MmkvManager.replaceLiteCandidateGuids(guids)
    }

    @Synchronized
    fun toggleCandidate(guid: String): Set<String> {
        return MmkvManager.toggleLiteCandidateGuid(guid)
    }

    @Synchronized
    fun removeCandidate(guid: String) {
        MmkvManager.removeLiteCandidateReferences(setOf(guid))
    }

    @Synchronized
    fun retainCandidates(validGuids: Set<String>): Set<String> {
        return MmkvManager.retainLiteCandidateGuids(validGuids)
    }

    fun autoOptimizeEnabled(): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_LITE_AUTO_OPTIMIZE, true)

    fun setAutoOptimizeEnabled(enabled: Boolean) {
        MmkvManager.encodeSettings(AppConfig.PREF_LITE_AUTO_OPTIMIZE, enabled)
    }
}
