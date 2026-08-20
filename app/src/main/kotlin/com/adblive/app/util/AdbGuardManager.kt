package com.adblive.app.util

import android.content.Context
import android.util.Base64
import android.util.Log

object AdbGuardManager {
    private const val TAG = "ADBLive_Guard"
    private const val SERVICE_DIR = "/data/adb/service.d"
    private const val SCRIPT_NAME = "99_adblive_guard\\.sh"
    private const val SCRIPT_PATH = "$SERVICE_DIR/$SCRIPT_NAME"
    private const val PORT_FILE = "/data/adb/adblive_guard_port"
    private const val PID_FILE = "/data/local/tmp/adblive_guard.pid"
    private const val DISABLED_FILE = "/data/adb/adblive_guard_disabled"
    private const val BOOT_ENABLED_FILE = "/data/adb/adblive_boot_enabled"
    private const val KEY_USER_DISABLED = "guard_user_disabled"
    private const val USER_DISABLED_ADB_FILE = "/data/adb/adblive_user_disabled_adb"

    private const val STATE_CACHE_TTL_MS = 10_000L
    @Volatile private var cachedRunning: Boolean? = null
    @Volatile private var cachedAtMs = 0L

    fun shouldAutoEnableGuard(context: Context): Boolean {
        return !context.getSharedPreferences("adblive_guard", Context.MODE_PRIVATE)
            .getBoolean(KEY_USER_DISABLED, false)
    }

    fun setGuardEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("adblive_guard", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("guard_enabled", enabled)
            .putBoolean(KEY_USER_DISABLED, !enabled)
            .apply()
    }

    /** Write boot auto-start marker file so the guard knows whether to self-start at boot */
    fun writeBootEnabled(context: Context, enabled: Boolean) {
        ShellUtils.executeSu(
            "echo " + (if (enabled) 1 else 0) + " > " + BOOT_ENABLED_FILE +
            " && chmod 644 " + BOOT_ENABLED_FILE
        )
    }


    fun isScriptDeployed(): Boolean {
        val now = System.currentTimeMillis()
        if (cachedRunning == true && now - cachedAtMs < STATE_CACHE_TTL_MS) return true
        val r = ShellUtils.executeSu("test -f " + SCRIPT_PATH + " && echo yes")
        val deployed = r.isSuccess() && r.output.contains("yes")
        if (deployed) { cachedRunning = true; cachedAtMs = now } else { cachedRunning = null }
        return deployed
    }

    fun invalidateStateCache() {
        cachedRunning = null
        cachedAtMs = 0L
    }

    fun isGuardRunning(): Boolean {
        val r = ShellUtils.executeSu("cat " + PID_FILE + " 2>/dev/null")
        val pid = r.output.trim()
        if (pid.isNotEmpty() && pid.all { it.isDigit() }) {
            val alive = ShellUtils.executeSu("kill -0 " + pid + " 2>&1; echo EXIT=$?")
            if (alive.output.contains("EXIT=0")) return true
        }
        val pg = ShellUtils.executeSu("pgrep -f '99_adblive_guard\\.sh' 2>/dev/null")
        return pg.isSuccess() && pg.output.trim().isNotEmpty()
    }



    /** Write current watchdog script (plus port/boot markers) to disk. Safe to call while guard runs (B5). */
    fun refreshScript(context: Context): Boolean {
        return try {
            val script = context.assets.open("watchdog.sh").bufferedReader().use { it.readText() }
            val b64 = Base64.encodeToString(script.toByteArray(), Base64.NO_WRAP)
            val port = readCurrentPort()
            val tmpB64 = "/data/local/tmp/adblive_b64.tmp"
            val bootEn = context.getSharedPreferences("adblive_guard", Context.MODE_PRIVATE)
                .getBoolean("boot_enabled", true)
            writeBootEnabled(context, bootEn)
            val cmd = "rm -f " + DISABLED_FILE + " && " +
                "mkdir -p " + SERVICE_DIR + " && " +
                "echo " + port + " > " + PORT_FILE + " && chmod 644 " + PORT_FILE + " && " +
                "cat > " + tmpB64 + " << 'B64EOF'\n" + b64 + "\nB64EOF\n" +
                "base64 -d " + tmpB64 + " > " + SCRIPT_PATH + " && " +
                "rm -f " + tmpB64 + " && " +
                "chmod 755 " + SCRIPT_PATH
            val r = ShellUtils.executeSu(cmd, 3000)
            invalidateStateCache()
            r.isSuccess()
        } catch (t: Throwable) {
            Log.e(TAG, "refresh script failed: " + t.message)
            false
        }
    }

    // #10: use temp file for base64 decode to avoid ARG_MAX limit
    fun deployAndStart(context: Context): Boolean {
        return try {
            clearStopSignal(context)
            if (!refreshScript(context)) return false
            val r = ShellUtils.executeSu("setsid sh " + SCRIPT_PATH + " manual >/dev/null 2>&1 & echo started")
            invalidateStateCache()
            r.isSuccess() && r.output.contains("started")
        } catch (t: Throwable) {
            Log.e(TAG, "deploy failed: " + t.message)
            false
        }
    }

    fun stopAndRemove(context: Context): Boolean {
        try {
            val f = java.io.File(context.filesDir, "guard_stop")
            f.parentFile?.mkdirs()
            f.writeText("1")
        } catch (_: Exception) { }
        val pid = ShellUtils.executeSu("cat " + PID_FILE + " 2>/dev/null").output.trim()
        val safePid = if (pid.isNotEmpty() && pid.all { it.isDigit() }) pid else ""
        val r = ShellUtils.executeSu(
            "touch " + DISABLED_FILE + " && " +
            (if (safePid.isNotEmpty()) "kill " + safePid + " 2>/dev/null; " else "") +
            "pkill -f '^sh " + SCRIPT_PATH + "' 2>/dev/null; " +
            "rm -f " + SCRIPT_PATH + " && " +
            "rm -f " + PID_FILE + " && " +
            "rm -f " + PORT_FILE + " && " +
            "rm -rf /data/local/tmp/adblive_guard.lock"
        )
        invalidateStateCache()
        return r.isSuccess()
    }

    private fun clearStopSignal(context: Context) {
        try {
            java.io.File(context.filesDir, "guard_stop").delete()
        } catch (_: Exception) { }
    }

    // #11: also read port from PORT_FILE, not just getprop
    /** Write intent file: tells guard not to restore ADB */
    fun writeUserDisabledAdb() {
        ShellUtils.executeSu("touch " + USER_DISABLED_ADB_FILE)
    }

    /** Delete intent file: guard will resume normal ADB restoration */
    fun clearUserDisabledAdb() {
        ShellUtils.executeSu("rm -f " + USER_DISABLED_ADB_FILE)
    }

    /** Check if user has manually disabled ADB */
    fun isUserDisabledAdb(): Boolean {
        val r = ShellUtils.executeSu("test -f " + USER_DISABLED_ADB_FILE + " && echo yes")
        return r.isSuccess() && r.output.contains("yes")
    }

    fun readCurrentPort(): Int {
        // Try port file first (most reliable if watchdog set it)
        val fileR = ShellUtils.executeSu("cat " + PORT_FILE + " 2>/dev/null")
        val filePort = fileR.output.trim().toIntOrNull()
        if (filePort != null && filePort in 1024..65535) return filePort

        // Fallback to getprop
        val propR = ShellUtils.executeSu("getprop service.adb.tcp.port")
        val propPort = propR.output.trim().toIntOrNull() ?: 0
        return if (propPort in 1024..65535) propPort else 5555
    }
}
