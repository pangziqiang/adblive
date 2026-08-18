package com.adblive.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.adblive.app.util.AdbGuardManager
import com.adblive.app.util.ShellUtils

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "ADBLive_Boot"
        private const val PREFS = "adblive_guard"
        private const val KEY_GUARD_ENABLED = "guard_enabled"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        // Only auto-start if user previously enabled the guard.
        val wasEnabled = try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_GUARD_ENABLED, false)
        } catch (_: Throwable) { false }

        if (!wasEnabled) {
            Log.d(TAG, "boot: guard not previously enabled, skip")
            return
        }

        Thread {
            try {
                val hasRoot = ShellUtils.probeRoot()
                Log.d(TAG, "boot: root=" + hasRoot)
                if (!hasRoot) return@Thread

                // Re-deploy & start watchdog; also ensure ADB is on fixed port.
                val deployed = AdbGuardManager.deployAndStart(context)
                Log.d(TAG, "boot: guard deployed=" + deployed)

                // Make sure wireless ADB enabled + port 5555.
                ShellUtils.executeSu("setprop service.adb.tcp.port 5555")
                ShellUtils.executeSu("settings put global adb_wifi_enabled 1")
                ShellUtils.executeSu("getprop service.adb.tcp.port")
            } catch (t: Throwable) {
                Log.e(TAG, "boot handler failed: " + t.message)
            }
        }.start()
    }
}
