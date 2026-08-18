package com.adblive.app.xposed

import android.content.ContentValues
import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.util.concurrent.atomic.AtomicBoolean

object SettingsGuard {
    private const val KEY = "adb_wifi_enabled"
    private val registeredSys = AtomicBoolean(false)

    fun hookSystemServer(lpparam: LoadPackageParam) {
        if (!registeredSys.compareAndSet(false, true)) return
        Entry.log("SettingsGuard mounting system_server guards")
        hookGlobalPutInt()
        hookGlobalPutString()
        hookTransportCall(lpparam.classLoader)
    }

    fun hookSettings(lpparam: LoadPackageParam) {
        Entry.log("SettingsGuard mounting Settings guards")
        hookTransportCall(lpparam.classLoader)
    }

    private fun hookGlobalPutInt() {
        val clazz = try {
            XposedHelpers.findClass("android.provider.Settings\$Global", null)
        } catch (t: Throwable) { Entry.log("SettingsGuard findClass failed: " + t.message); return }
        for (sig in arrayOf(
            arrayOf(ContentResolver::class.java, String::class.java, Int::class.javaPrimitiveType),
            arrayOf(ContentResolver::class.java, String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
        )) {
            try {
                XposedHelpers.findAndHookMethod(clazz, "putInt", *sig, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val name = param.args[1] as? String ?: return
                        val value = param.args[2] as? Int ?: return
                        if (name == KEY && value == 0) {
                            Entry.log("SettingsGuard blocked putInt(" + KEY + "=0)")
                            param.setResult(true)
                        }
                    }
                })
            } catch (_: Throwable) { }
        }
    }

    private fun hookGlobalPutString() {
        val clazz = try {
            XposedHelpers.findClass("android.provider.Settings\$Global", null)
        } catch (_: Throwable) { return }
        for (sig in arrayOf(
            arrayOf(ContentResolver::class.java, String::class.java, String::class.java),
            arrayOf(ContentResolver::class.java, String::class.java, String::class.java, Int::class.javaPrimitiveType),
        )) {
            try {
                XposedHelpers.findAndHookMethod(clazz, "putString", *sig, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val name = param.args[1] as? String ?: return
                        val value = param.args[2] as? String ?: return
                        if (name == KEY && isOff(value)) {
                            Entry.log("SettingsGuard blocked putString(" + KEY + ")")
                            param.setResult(true)
                        }
                    }
                })
            } catch (_: Throwable) { }
        }
    }

    private fun hookTransportCall(classLoader: ClassLoader?) {
        try {
            val transport = XposedHelpers.findClass("android.content.ContentProvider\$Transport", classLoader)
            XposedHelpers.findAndHookMethod(transport, "call",
                android.content.AttributionSource::class.java,
                String::class.java, String::class.java, String::class.java, Bundle::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val authority = param.args[1] as? String ?: return
                            val method = param.args[2] as? String ?: return
                            val name = param.args[3] as? String ?: return
                            val extras = param.args[4] as? Bundle ?: return
                            if (authority == "settings" && method == "PUT_global" && name == KEY && isOff(extras.getString("value"))) {
                                Entry.log("SettingsGuard blocked Transport.call(" + KEY + ")")
                                param.setResult(null)
                            }
                        } catch (_: Throwable) { }
                    }
                })
            Entry.log("SettingsGuard hooked Transport.call")
        } catch (t: Throwable) {
            Entry.log("SettingsGuard hook Transport.call failed: " + t.message)
        }
    }

    private fun isOff(value: String?): Boolean {
        val v = (value ?: return false).trim()
        return v == "0" || v.equals("false", true) || v.equals("disable", true)
    }
}
