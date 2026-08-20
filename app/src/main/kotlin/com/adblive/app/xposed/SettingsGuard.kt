package com.adblive.app.xposed

import android.content.ContentResolver
import android.os.Bundle
import android.os.Binder
import android.os.UserHandle
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.util.concurrent.atomic.AtomicBoolean

object SettingsGuard {
    private const val KEY = "adb_wifi_enabled"
    private const val OUR_PACKAGE = "com.adblive.app"
    private const val BYPASS_FILE = "/data/local/tmp/adblive_bypass"
    private val registeredSys = AtomicBoolean(false)
    private var ourUid = 0

    private fun shouldBlock(): Boolean {
        if (Entry.isShieldDisabled()) return false
        if (isCallerOurs()) return false
        if (hasBypass()) return false
        return true
    }

    private fun isCallerOurs(): Boolean {
        if (ourUid == 0) return false
        val uid = Binder.getCallingUid()
        return uid == ourUid
    }

    private fun hasBypass(): Boolean {
        return try {
            java.io.File(BYPASS_FILE).exists()
        } catch (_: Throwable) { false }
    }

    fun hookSystemServer(lpparam: LoadPackageParam) {
        if (!registeredSys.compareAndSet(false, true)) return
        try {
            val appCtx = de.robv.android.xposed.XposedHelpers.callStaticMethod(
                de.robv.android.xposed.XposedHelpers.findClass("android.app.ActivityThread", null),
                "currentApplication") as? android.content.Context
            ourUid = appCtx?.packageManager?.getApplicationInfo(OUR_PACKAGE, 0)?.uid ?: 0
        } catch (_: Throwable) { }
        Entry.log("SettingsGuard ourUid=" + ourUid)
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
                        if (!shouldBlock()) return
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
                        if (!shouldBlock()) return
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
            val sigs = arrayOf(
                arrayOf(String::class.java, String::class.java, Bundle::class.java),
                arrayOf(String::class.java, String::class.java, String::class.java, Bundle::class.java),
                arrayOf(android.content.AttributionSource::class.java, String::class.java, String::class.java, String::class.java, Bundle::class.java),
            )
            for (sig in sigs) {
                try {
                    XposedHelpers.findAndHookMethod(transport, "call", *sig, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                if (!shouldBlock()) return
                                val argc = param.args.size
                                val method: String = when (argc) {
                                    3 -> param.args[1] as? String ?: return
                                    4 -> param.args[1] as? String ?: return
                                    else -> param.args[2] as? String ?: return
                                }
                                val name: String? = when (argc) {
                                    3 -> null
                                    4 -> param.args[2] as? String
                                    else -> param.args[3] as? String
                                }
                                val extras: Bundle? = when (argc) {
                                    3 -> param.args[2] as? Bundle
                                    4 -> param.args[3] as? Bundle
                                    else -> param.args[4] as? Bundle
                                }
                                if (method == "PUT_global" && name == KEY && isOff(extras?.getString("value"))) {
                                    Entry.log("SettingsGuard blocked Transport.call(" + KEY + ") argc=" + argc)
                                    param.throwable = SecurityException("adb_wifi_enabled is protected by ADBLive shield")
                                }
                            } catch (_: Throwable) { }
                        }
                    })
                } catch (_: Throwable) { }
            }
            Entry.log("SettingsGuard hooked Transport.call variants")
        } catch (t: Throwable) {
            Entry.log("SettingsGuard hook Transport.call failed: " + t.message)
        }
    }

    private fun isOff(value: String?): Boolean {
        val v = (value ?: return false).trim()
        return v == "0" || v.equals("false", true) || v.equals("disable", true) ||
            v.equals("off", true) || v.equals("no", true)
    }
}
