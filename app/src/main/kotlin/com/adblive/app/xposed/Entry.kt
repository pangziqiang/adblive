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
        const val SHIELD_OFF_FILE = "/data/system/adblive_shield_off"
        fun log(msg: String) = XposedBridge.log("[$TAG] $msg")

        // Layer 1: time-based cache, re-reads shield file every 5s
        // Ensures hooks stop intercepting after app uninstall or shield toggle
        @Volatile private var shieldDisabledCache = false
        @Volatile private var lastShieldCheckMs = 0L
        private const val SHIELD_CHECK_TTL_MS = 5000L

        fun refreshShieldState() {
            shieldDisabledCache = try { File(SHIELD_OFF_FILE).exists() } catch (_: Throwable) { false }
            lastShieldCheckMs = System.currentTimeMillis()
        }

        fun isShieldDisabled(): Boolean {
            val now = System.currentTimeMillis()
            if (now - lastShieldCheckMs > SHIELD_CHECK_TTL_MS) {
                refreshShieldState()
            }
            return shieldDisabledCache
        }
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        refreshShieldState()
        if (isShieldDisabled()) log("shield disabled via kill-switch (hooks stay mounted, runtime-gated)")

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

        when {
            lpparam.processName == "system_server"
                || lpparam.processName.endsWith(":system_server")
                || (lpparam.packageName == "android" && lpparam.processName != "system_server") -> {
                log("Hooking system_server proc=" + lpparam.processName)
                KillGuard.hook(lpparam)
                SettingsGuard.hookSystemServer(lpparam)
                // Removed hookPackageRemovedCleanup: system_server uid can't su,
                // watchdog handles uninstall cleanup via app_gone()
            }
            lpparam.packageName == "com.android.settings" -> {
                log("Hooking Settings proc=" + lpparam.processName)
                SettingsGuard.hookSettings(lpparam)
            }
        }
    }
}

