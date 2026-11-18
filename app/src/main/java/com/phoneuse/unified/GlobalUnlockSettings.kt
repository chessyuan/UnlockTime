package com.phoneuse.unified

import android.content.Context

/** A native device setting, deliberately absent from all Agent/MCP tool registries. */
class GlobalUnlockSettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    val enabled: Boolean get() = preferences.getBoolean(KEY, true)

    /** Native settings UI must handle a false return as a failed durable change. */
    internal fun setEnabledFromLocalUi(enabled: Boolean): Boolean = preferences.edit().putBoolean(KEY, enabled).commit()

    private companion object {
        const val KEY = "automatic_unlock_enabled"
    }
}
