package com.adblive.app.util

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.File

object AdbGuardManager {
    private const val TAG = "ADBLive_Guard"
    private const val SERVICE_DIR = "/data/adb/service.d"
    private const val SCRIPT_NAME = "99_adblive_guard.sh"
    private const val SCRIPT_PATH = "$SERVICE_DIR/$SCRIPT_NAME"
    private const val PORT_FILE = "/data/adb/adblive_guard_port"
    private const val PID_FILE = "/data/local/tmp/adblive_guard.pid"
    private const val DISABLED_FILE = "/data/adb/adblive_guard_disabled"

    fun isGuardRunning(): Boolean {
        val r = ShellUtils.executeSu("cat " + PID_FILE + " 2>/dev/null")
        val pid = r.output.trim()
        if (pid.isEmpty()) return false
        val alive = ShellUtils.executeSu("kill -0 " + pid + " 2>&1; echo EXIT=$?")
        return alive.output.contains("EXIT=0")
    }

    fun isScriptDeployed(): Boolean {
        val r = ShellUtils.executeSu("test -f " + SCRIPT_PATH + " && echo yes")
        return r.isSuccess() && r.output.contains("yes")
    }

    fun deployAndStart(context: Context): Boolean {
        return try {
            val script = context.assets.open("watchdog.sh").bufferedReader().use { it.readText() }
            val b64 = Base64.encodeToString(script.toByteArray(), Base64.NO_WRAP)
            val port = readCurrentPort()
            val cmd = "rm -f " + DISABLED_FILE + " && " +
                "mkdir -p " + SERVICE_DIR + " && " +
                "echo " + port + " > " + PORT_FILE + " && chmod 644 " + PORT_FILE + " && " +
                "echo " + b64 + " | base64 -d > " + SCRIPT_PATH + " && " +
                "chmod 755 " + SCRIPT_PATH + " && " +
                "setsid sh " + SCRIPT_PATH + " >/dev/null 2>&1 & " +
                "echo deployed"
            val r = ShellUtils.executeSu(cmd, 3000)
            r.isSuccess() && r.output.contains("deployed")
        } catch (t: Throwable) {
            Log.e(TAG, "deploy failed: " + t.message)
            false
        }
    }

    fun stopAndRemove(): Boolean {
        val r = ShellUtils.executeSu(
            "touch " + DISABLED_FILE + " && " +
            "kill $(cat " + PID_FILE + " 2>/dev/null) 2>/dev/null; " +
            "pkill -f " + SCRIPT_NAME + " 2>/dev/null; " +
            "rm -f " + SCRIPT_PATH
        )
        return r.isSuccess()
    }

    fun readCurrentPort(): Int {
        val r = ShellUtils.executeSu("getprop service.adb.tcp.port")
        val p = r.output.trim().toIntOrNull() ?: 0
        return if (p in 1024..65535) p else 5555
    }
}
