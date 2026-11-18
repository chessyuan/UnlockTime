package com.phoneuse

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.phoneuse.app.AppGraph
import com.phoneuse.i18n.Lang
import com.phoneuse.unified.AndroidDeviceAccess
import com.phoneuse.unified.OwnActivityInputGuard
import org.localwake.app.UnlockTimeRuntime
import org.osmdroid.config.Configuration
import java.io.File

class PhoneUseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        UnlockTimeRuntime.initialize(this)
        updateLanguage()
        // OSM tile policy: identify the app; keep tiles in the cache dir (no storage permission needed).
        Configuration.getInstance().apply {
            userAgentValue = "UnlockTime/${BuildConfig.VERSION_NAME}"
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
        AndroidDeviceAccess.initialize(this)
        val ownActivityInput = OwnActivityInputGuard()
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPreStarted(activity: Activity) {
                // API 29+, minSdk 31: revoke before our UI can appear in an in-flight capture.
                // Starting from an already-visible chat and screen-free chat have no lease to revoke.
                AndroidDeviceAccess.ownActivityStarting()
                ownActivityInput.beforeStart(activity)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = ownActivityInput.stopped(activity)
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = ownActivityInput.stopped(activity)
        })
        com.phoneuse.unified.UnifiedStartup.install(this)
        AppGraph.init(this)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        updateLanguage()
    }

    /** Retains the original saved language; a new installation defaults to English. */
    private fun updateLanguage() {
        Lang.applyPreference(getSharedPreferences("app_settings", MODE_PRIVATE).getString("language", null))
    }
}
