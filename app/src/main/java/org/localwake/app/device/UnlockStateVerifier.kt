package org.localwake.app.device

import android.app.KeyguardManager
import android.content.Context
import android.os.UserManager

class UnlockStateVerifier(context: Context) {
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val users = context.getSystemService(UserManager::class.java)
    fun userUnlockedSinceBoot() = users.isUserUnlocked
    fun fullyUnlocked() = users.isUserUnlocked && !keyguard.isDeviceLocked && !keyguard.isKeyguardLocked
    fun credentialRequired() = keyguard.isDeviceLocked
}
