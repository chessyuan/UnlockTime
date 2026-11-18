// Wake flags adapted from OpenClaw MobileUiWakeActivity. Copyright (c) 2026 OpenClaw Foundation. MIT.
package org.localwake.app.execution

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred
import org.localwake.app.AppLanguage
import com.phoneuse.R
import java.util.concurrent.ConcurrentHashMap

internal object WakeSignals {
    class Signal {
        val resumed = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
    }
    val pending = ConcurrentHashMap<String, Signal>()
}

class WakeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var signal: WakeSignals.Signal? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.current(newBase).wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        signal = WakeSignals.pending[intent.getStringExtra("wake_token").orEmpty()]
        if (signal == null) { finish(); return }
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(TextView(this).apply {
            text = getString(R.string.wake_activity_title, getString(R.string.app_name))
            setPadding(32, 80, 32, 32)
        })
        handler.postDelayed({ finish() }, 2_000)
    }
    override fun onResume() {
        super.onResume()
        signal?.resumed?.complete(Unit)
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isDeviceLocked && keyguard.isKeyguardLocked) {
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { finish() }
                override fun onDismissCancelled() { finish() }
                override fun onDismissError() { finish() }
            })
        } else handler.postDelayed({ finish() }, 350)
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        signal?.finished?.complete(Unit)
        super.onDestroy()
    }
}
