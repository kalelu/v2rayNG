package com.v2ray.ang

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat
import androidx.work.Configuration
import androidx.work.WorkManager
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.lite.LiteScheduler
import com.v2ray.ang.ui.compose.ThemeManager

class AngApplication : Application() {
    companion object {
        lateinit var application: AngApplication
    }

    /**
     * Attaches the base context to the application.
     * @param base The base context.
     */
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base?.let(ContextCompat::getContextForLanguage))
        application = this
    }

    private val workManagerConfiguration: Configuration = Configuration.Builder().build()

    /**
     * Initializes the application.
     */
    override fun onCreate() {
        super.onCreate()

        MmkvManager.initialize(this)

        AppLocaleManager.initialize(this)

        val mainProcess = isMainProcess()
        if (mainProcess) {
            WorkManager.initialize(this, workManagerConfiguration)
        }

        // Ensure critical preference defaults are present in MMKV early
        SettingsManager.initApp(this)

        // Initialize theme state from MMKV
        ThemeManager.refresh()

        // Every Android process creates the Application. WorkManager and periodic scheduling are
        // owned by the main app process; the VPN daemon remains independent.
        if (mainProcess) {
            LiteScheduler.initialize(this)
        }
    }

    private fun isMainProcess(): Boolean {
        val currentProcessName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            val pid = Process.myPid()
            (getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?.runningAppProcesses
                ?.firstOrNull { it.pid == pid }
                ?.processName
        }
        return currentProcessName == packageName
    }
}
