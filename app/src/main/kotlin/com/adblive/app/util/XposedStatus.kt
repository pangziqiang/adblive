package com.adblive.app.util

import android.content.Context
import android.util.Log
import java.io.File

object XposedStatus {
    private const val TAG = "ADBLive_XposedStatus"
    private const val SP_NAME = "adblive_xposed_state"
    private const val SP_KEY = "last_inject_ms"
    private const val MARKER_PATH = "/data/local/tmp/adblive_xposed_active"

    private var appContext: Context? = null

    fun init(app: Context) { appContext = app.applicationContext }

    fun markActive() {
        val now = System.currentTimeMillis()
        val ctx = appContext
        if (ctx != null) {
            try {
                ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                    .edit().putLong(SP_KEY, now).apply()
                Log.d(TAG, "marker written via SharedPreferences")
            } catch (t: Throwable) {
                Log.w(TAG, "SP write failed: " + t.message)
                writeMarkerFile(now)
            }
        } else {
            writeMarkerFile(now)
        }
    }

    private fun writeMarkerFile(now: Long) {
        try {
            val f = File(MARKER_PATH)
            f.writeText(now.toString())
            f.setReadable(true, false)
            Log.d(TAG, "marker written to /data/local/tmp")
        } catch (t: Throwable) {
            Log.w(TAG, "/data/local/tmp write failed: " + t.message)
        }
    }

    fun isActive(context: Context): Boolean {
        // Check SharedPreferences first
        try {
            val last = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                .getLong(SP_KEY, 0L)
            if (last > 0 && (System.currentTimeMillis() - last) < 7 * 24 * 60 * 60 * 1000L) {
                return true
            }
        } catch (_: Throwable) { }

        // Check file marker
        try {
            val f = File(MARKER_PATH)
            if (f.exists() && f.canRead()) {
                val ageMs = System.currentTimeMillis() - f.lastModified()
                if (ageMs in 0..(24 * 60 * 60 * 1000L)) return true
            }
        } catch (_: Throwable) { }

        // Check /proc/self/maps for LSPosed
        try {
            File("/proc/self/maps").useLines { lines ->
                if (lines.any { it.lowercase().let { l -> l.contains("xposed") || l.contains("lsposed") || l.contains("edxp") } }) return true
            }
        } catch (_: Throwable) { }

        return false
    }

    fun reset(context: Context) {
        try {
            context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                .edit().remove(SP_KEY).apply()
        } catch (_: Throwable) { }
        try { File(MARKER_PATH).delete() } catch (_: Throwable) { }
    }
}
