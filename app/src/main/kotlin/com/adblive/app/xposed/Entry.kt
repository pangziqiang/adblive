package com.adblive.app.xposed

import android.content.Context
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import com.adblive.app.util.XposedStatus

class Entry : IXposedHookLoadPackage {
    companion object {
        const val MODULE_PACKAGE = "com.adblive.app"
        const val TAG = "ADBLive"
        fun log(msg: String) = XposedBridge.log("[$TAG] $msg")
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
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
            }
            lpparam.packageName == "com.android.settings" -> {
                log("Hooking Settings proc=" + lpparam.processName)
                SettingsGuard.hookSettings(lpparam)
            }
            lpparam.packageName == "com.android.providers.settings" -> {
                log("Hooking SettingsProvider proc=" + lpparam.processName)
                SettingsGuard.hookSettings(lpparam)
            }
        }
    }
}
