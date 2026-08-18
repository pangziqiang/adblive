package com.adblive.app.xposed

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object KillGuard {
    private val registered = AtomicBoolean(false)
    private val commCache = ConcurrentHashMap<Int, Boolean>()
    private const val CACHE_MAX = 64

    fun hook(lpparam: LoadPackageParam) {
        if (!registered.compareAndSet(false, true)) return
        Entry.log("KillGuard loading into " + lpparam.processName)

        val clazz = try {
            XposedHelpers.findClass("android.os.Process", null)
        } catch (t: Throwable) {
            Entry.log("KillGuard findClass failed: " + t.message)
            return
        }

        for (name in listOf("killProcess", "killProcessQuiet")) {
            try {
                val method = clazz.getDeclaredMethod(name, Int::class.javaPrimitiveType)
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            if (Entry.isShieldDisabled()) return
                            val pid = param.args[0] as? Int ?: return
                            if (isAdbd(pid)) {
                                Entry.log("KillGuard blocked " + name + "(pid=" + pid + ")")
                                param.setResult(null)
                            }
                        } catch (_: Throwable) { }
                    }
                })
            } catch (t: Throwable) {
                Entry.log("KillGuard hook " + name + " failed: " + t.message)
            }
        }

        try {
            val method = clazz.getDeclaredMethod("killProcessGroup",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            if (Entry.isShieldDisabled()) return
                            val pid = param.args[1] as? Int ?: return
                            if (isAdbd(pid)) {
                            Entry.log("KillGuard blocked killProcessGroup(pid=" + pid + ")")
                            param.setResult(0)
                        }
                    } catch (_: Throwable) { }
                }
            })
        } catch (t: Throwable) {
            Entry.log("KillGuard hook killProcessGroup failed: " + t.message)
        }
    }

    private fun isAdbd(pid: Int): Boolean {
        if (pid <= 0) return false
        commCache[pid]?.let { return it }
        val result = try {
            File("/proc/" + pid + "/comm").readText().trim() == "adbd"
        } catch (_: Throwable) { false }
        if (result || File("/proc/" + pid).exists()) {
            if (commCache.size >= CACHE_MAX) commCache.clear()
            commCache[pid] = result
        }
        return result
    }
}
