package org.localwake.app

import android.app.Application

/** Compatibility bootstrap only. The unified manifest uses PhoneUseApp. */
class LocalWakeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        UnlockTimeRuntime.initialize(this)
    }
}
