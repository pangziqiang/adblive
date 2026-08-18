package com.adblive.app.xposed

import android.content.Context
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import com.adblive.app.util.XposedStatus
import java.io.File

class Entry : IXposedHookLoadPackage {
    companion object {
        const val MODULE_PACKAGE = "com.adblive.app"
        const val TAG = "ADBLive"
        const val SHIELD_OFF_FILE = "/data/local/tmp/adblive_shield_off"
        fun log(msg: String) = XposedBridge.log("[$TAG] $msg")

        fun isShieldDisabled(): Boolean {
            return try { File(SHIELD_OFF_FILE).exists() } catch (_: Throwable) { false }
        }
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        val shieldOff = isShieldDisabled()
        if (shieldOff) log("shield disabled via kill-switch, skip mounting")

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
            shieldOff -> { /* hooks skipped */ }
            lpparam.processName == "system_server"
                || lpparam.processName.endsWith(":system_server")
                || (lpparam.packageName == "android" && lpparam.processName != "system_server") -> {
                log("Hooking system_server proc=" + lpparam.processName)
                KillGuard.hook(lpparam)
                SettingsGuard.hookSystemServer(lpparam)
            }
            lpparam.packageName == "com.android.settings" -> {
                log("Hooking Settings proc=" + lpparam.processName)
                SettingsGuard.hookSettings(lpparam)
            }
        }
    }
}
