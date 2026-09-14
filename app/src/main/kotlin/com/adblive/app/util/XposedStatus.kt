package com.adblive.app.util

import android.content.Context
import android.util.Log
import java.io.File

object XposedStatus {
    private const val TAG = "ADBLive_XposedStatus"
    private const val SP_NAME = "adblive_xposed_state"
    private const val SP_KEY = "last_inject_ms"
    private const val VALID_MS = 24 * 60 * 60 * 1000L
    private const val MARKER_NAME = "adblive_xposed_marker"
    private const val OUR_PACKAGE = "com.adblive.app"

    private var appContext: Context? = null

    fun init(app: Context) { appContext = app.applicationContext }

    /**
     * 标记模块已注入本进程。
     * 注入时机早于 Application 创建，此时拿不到 Context 是常态，
     * 因此除 SharedPreferences 外，再直接落一个文件标记（无需 Context）。
     */
    fun markActive() {
        val now = System.currentTimeMillis()
        try {
            appContext?.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                ?.edit()?.putLong(SP_KEY, now)?.apply()
        } catch (t: Throwable) {
            Log.w(TAG, "SP write failed: " + t.message)
        }
        try {
            val f = markerFile()
            f.parentFile?.mkdirs()
            f.writeText(now.toString())
            Log.d(TAG, "marker written: " + f.absolutePath)
        } catch (t: Throwable) {
            Log.w(TAG, "marker write failed: " + t.message)
        }
    }

    private fun markerFile(): File {
        val userId = try { android.os.Process.myUid() / 100000 } catch (_: Throwable) { 0 }
        return File("/data/user/" + userId + "/" + OUR_PACKAGE + "/files/" + MARKER_NAME)
    }

    fun isActive(context: Context): Boolean {
        if (mapsHasXposed()) return true
        if (spFresh(context)) return true
        if (markerFresh(context)) return true
        return false
    }

    private fun mapsHasXposed(): Boolean {
        return try {
            File("/proc/self/maps").useLines { lines ->
                lines.any { line ->
                    val l = line.lowercase()
                    l.contains("xposed") || l.contains("lsposed") ||
                        l.contains("edxp") || l.contains("lspatch")
                }
            }
        } catch (_: Throwable) { false }
    }

    private fun spFresh(context: Context): Boolean {
        return try {
            val last = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                .getLong(SP_KEY, 0L)
            last > 0 && (System.currentTimeMillis() - last) < VALID_MS
        } catch (_: Throwable) { false }
    }

    private fun markerFresh(context: Context): Boolean {
        val candidates = arrayOf(markerFile(), File(context.filesDir, MARKER_NAME))
        for (f in candidates) {
            try {
                val t = f.lastModified()
                if (t > 0 && (System.currentTimeMillis() - t) < VALID_MS) return true
            } catch (_: Throwable) { }
        }
        return false
    }
}
