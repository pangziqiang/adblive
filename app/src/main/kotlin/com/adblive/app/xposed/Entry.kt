package com.adblive.app.xposed

import android.content.Context
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import com.adblive.app.util.XposedStatus
import java.io.File

class Entry : IXposedHookLoadPackage {
    companion object {
        const val MODULE_PACKAGE = "com.adblive.app"
        const val TAG = "ADBLive"
        const val SHIELD_ARMED_FILE = "/data/system/adblive_shield_armed"
        fun log(msg: String) = XposedBridge.log("[$TAG] $msg")

        @Volatile private var shieldDisabledCache = false
        @Volatile private var lastShieldCheckMs = 0L
        private const val SHIELD_CHECK_TTL_MS = 5000L

        @Volatile private var appGoneCache = false
        @Volatile private var lastAppGoneCheckMs = 0L
        private const val APP_GONE_CHECK_TTL_MS = 10000L
        @Volatile private var appGoneCleanupDone = false

        fun refreshShieldState() {
            val armed = try { File(SHIELD_ARMED_FILE).exists() } catch (_: Throwable) { false }
            shieldDisabledCache = !armed
            lastShieldCheckMs = System.currentTimeMillis()
        }

        private fun isAppGone(): Boolean {
            val now = System.currentTimeMillis()
            if (now - lastAppGoneCheckMs > APP_GONE_CHECK_TTL_MS) {
                lastAppGoneCheckMs = now
                appGoneCache = try {
                    !File("/data/data/com.adblive.app").exists() &&
                    !File("/data/user/0/com.adblive.app").exists()
                } catch (_: Throwable) { false }
                if (appGoneCache && !appGoneCleanupDone) {
                    appGoneCleanupDone = true
                    cleanupSystemShieldResidue()
                }
            }
            return appGoneCache
        }

        // system_server runs as system uid: can delete files inside /data/system.
        // /data/adb and /data/local/tmp residue is cleaned by the watchdog app_gone path.
        private fun cleanupSystemShieldResidue() {
            for (f in arrayOf(
                "/data/system/adblive_shield_armed",
                "/data/system/adblive_shield_off",
            )) {
                try { File(f).delete() } catch (_: Throwable) { }
            }
            log("app uninstalled, cleaned shield residue")
        }

        /** 事件驱动：包被卸载瞬间清盾文件，消除轮询窗口（守护未运行时兜底）。 */
        private fun hookPackageRemoved(lpparam: LoadPackageParam) {
            try {
                val pmsClass = XposedHelpers.findClass("com.android.server.pm.PackageManagerService", lpparam.classLoader)
                hookAllPms(pmsClass, "removePackageData")
                hookAllPms(pmsClass, "deletePackage")
                hookAllPms(pmsClass, "deletePackageAsUser")
            } catch (t: Throwable) {
                log("hookPackageRemoved failed: " + t.message)
            }
        }

        private fun hookAllPms(pmsClass: Class<*>, methodName: String) {
            try {
                XposedBridge.hookAllMethods(pmsClass, methodName, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pkg = param.args?.getOrNull(0) as? String
                        if (pkg == MODULE_PACKAGE) {
                            log(methodName + " intercepted, cleaning shield residue")
                            removeShieldFiles()
                        }
                    }
                })
                log("hooked PMS method: " + methodName)
            } catch (t: Throwable) {
                log("hook " + methodName + " failed: " + t.message)
            }
        }

        private fun removeShieldFiles() {
            for (f in arrayOf(
                "/data/system/adblive_shield_armed",
                "/data/system/adblive_shield_off",
            )) {
                try { File(f).delete() } catch (_: Throwable) { }
            }
            log("removeShieldFiles done, armed_exists=" + File("/data/system/adblive_shield_armed").exists())
        }

        fun isShieldDisabled(): Boolean {
            val now = System.currentTimeMillis()
            if (now - lastShieldCheckMs > SHIELD_CHECK_TTL_MS) {
                refreshShieldState()
            }
            return shieldDisabledCache || isAppGone()
        }
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        refreshShieldState()
        if (isShieldDisabled()) log("shield not armed (hooks stay mounted, runtime-gated)")

        if (lpparam.packageName == MODULE_PACKAGE) {
            log("Module loaded into own process")
            try {
                val atClass = lpparam.classLoader.loadClass("android.app.ActivityThread")
                val currentAT = atClass.getMethod("currentActivityThread").invoke(null)
                val appCtx = atClass.getMethod("getApplication").invoke(currentAT) as? Context
                if (appCtx != null) {
                    XposedStatus.init(appCtx)
                    log("Context obtained, marking active")
                }
            } catch (t: Throwable) {
                log("Context acquisition failed: " + t.message)
            }
            XposedStatus.markActive()
            log("Module marked as active")
        }

        val proc = lpparam.processName ?: return
        when {
            proc == "system_server"
                || proc.endsWith(":system_server")
                || (lpparam.packageName == "android" && proc != "system_server") -> {
                log("Hooking system_server proc=" + proc)
                KillGuard.hook(lpparam)
                SettingsGuard.hookSystemServer(lpparam)
                hookPackageRemoved(lpparam)
            }
            lpparam.packageName == "com.android.settings" -> {
                log("Hooking Settings proc=" + proc)
                SettingsGuard.hookSettings(lpparam)
            }
        }
    }
}
