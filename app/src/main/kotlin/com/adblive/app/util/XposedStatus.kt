package com.adblive.app.util

import android.content.Context
import android.util.Log
import java.io.File

object XposedStatus {
    private const val TAG = "ADBLive_XposedStatus"
    private const val SP_NAME = "adblive_xposed_state"
    private const val SP_KEY = "last_inject_ms"
    private const val SP_VALID_MS = 24 * 60 * 60 * 1000L // 1 day, not 7

    private var appContext: Context? = null

    fun init(app: Context) { appContext = app.applicationContext }

    fun markActive() {
        val now = System.currentTimeMillis()
        try {
            appContext?.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                ?.edit()?.putLong(SP_KEY, now)?.apply()
            Log.d(TAG, "marker written via SharedPreferences")
        } catch (t: Throwable) {
            Log.w(TAG, "SP write failed: " + t.message)
        }
    }

    fun isActive(context: Context): Boolean {
        // Primary check: /proc/self/maps (reliable, only in injected process)
        try {
            File("/proc/self/maps").useLines { lines ->
                if (lines.any { it.lowercase().let { l -> l.contains("xposed") || l.contains("lsposed") || l.contains("edxp") } }) return true
            }
        } catch (_: Throwable) { }

        // Fallback: SP marker (for cases where maps check fails but module was recently active)
        // #7: reduced from 7 days to 1 day to reduce reinstall false positives
        try {
            val last = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                .getLong(SP_KEY, 0L)
            if (last > 0 && (System.currentTimeMillis() - last) < SP_VALID_MS) {
                return true
            }
        } catch (_: Throwable) { }

        return false
    }

    fun reset(context: Context) {
        try {
            context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                .edit().remove(SP_KEY).apply()
        } catch (_: Throwable) { }
    }
}

