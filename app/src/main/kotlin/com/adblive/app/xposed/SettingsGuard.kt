package com.adblive.app.xposed

import android.content.ContentResolver
import android.os.Bundle
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.util.concurrent.atomic.AtomicBoolean

object SettingsGuard {
    private const val KEY = "adb_wifi_enabled"
    private val registeredSys = AtomicBoolean(false)

    private fun shouldBlock(): Boolean {
        return !Entry.isShieldDisabled()
    }

    fun hookSystemServer(lpparam: LoadPackageParam) {
        if (!registeredSys.compareAndSet(false, true)) return
        Entry.log("SettingsGuard mounting system_server guards")
        hookSettingsProviderPut()
        hookGlobalPutInt()
        hookGlobalPutString()
        hookTransportCall(lpparam.classLoader)
    }

    fun hookSettings(lpparam: LoadPackageParam) {
        Entry.log("SettingsGuard mounting Settings guards")
        hookTransportCall(lpparam.classLoader)
    }

    private fun hookSettingsProviderPut() {
        val clazz = try {
            XposedHelpers.findClass("com.android.providers.settings.SettingsProvider", null)
        } catch (t: Throwable) { Entry.log("SettingsGuard findClass SettingsProvider failed: " + t.message); return }
        var hooked = false
        for (sig in arrayOf(
            arrayOf(String::class.java, String::class.java, String::class.java, String::class.java, Boolean::class.javaPrimitiveType),
            arrayOf(String::class.java, String::class.java, String::class.java, String::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType),
        )) {
            try {
                XposedHelpers.findAndHookMethod(clazz, "put", *sig, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            if (!shouldBlock()) return
                            val name = param.args[1] as? String ?: return
                            val value = param.args[2] as? String ?: return
                            if (name == KEY && isOff(value)) {
                                Entry.log("SettingsGuard blocked SettingsProvider.put(" + KEY + ")")
                                param.throwable = SecurityException("adb_wifi_enabled is protected by ADBLive shield")
                            }
                        } catch (_: Throwable) { }
                    }
                })
                hooked = true
            } catch (_: Throwable) { }
        }
        if (hooked) Entry.log("SettingsGuard hooked SettingsProvider.put") else Entry.log("SettingsGuard SettingsProvider.put hook failed (no matching method)")
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

