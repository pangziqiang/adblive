package com.adblive.app.xposed

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * ProcessInterceptor — 阻止系统杀掉 adbd
 */
object ProcessInterceptor {

    private const val ADBD_COMM = "adbd"
    private val cache = ConcurrentHashMap<Int, Boolean>()
    private const val CACHE_LIMIT = 128

    fun install(classLoader: ClassLoader?) {
        val clazz = resolveClass("android.os.Process") ?: run {
            Entry.log("ProcessInterceptor: cannot find android.os.Process")
            return
        }
        hookKill(clazz, "killProcess")
        hookKill(clazz, "killProcessQuiet")
        hookKillGroup(clazz)
    }

    private fun hookKill(clazz: Class<*>, name: String) {
        try {
            val method = clazz.getDeclaredMethod(name, Int::class.javaPrimitiveType)
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val pid = p.args[0] as? Int ?: return
                    if (pid > 0 && looksLikeAdbd(pid)) {
                        Entry.log("ProcessInterceptor: blocked $name(pid=$pid)")
                        p.setResult(null)
                    }
                }
            })
        } catch (e: Throwable) {
            Entry.log("ProcessInterceptor: hook $name failed: ${e.message}")
        }
    }

    private fun hookKillGroup(clazz: Class<*>) {
        try {
            val method = clazz.getDeclaredMethod(
                "killProcessGroup",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val pid = p.args[1] as? Int ?: return
                    if (pid > 0 && looksLikeAdbd(pid)) {
                        Entry.log("ProcessInterceptor: blocked killProcessGroup(pid=$pid)")
                        p.setResult(0)
                    }
                }
            })
        } catch (e: Throwable) {
            Entry.log("ProcessInterceptor: hook killProcessGroup failed: ${e.message}")
        }
    }

    private fun looksLikeAdbd(pid: Int): Boolean {
        cache[pid]?.let { return it }
        val found = try {
            File("/proc/$pid/comm").readText().trim() == ADBD_COMM
        } catch (_: Exception) { false }
        if (cache.size >= CACHE_LIMIT) cache.clear()
        cache[pid] = found
        return found
    }

    private fun resolveClass(name: String): Class<*>? =
        try { XposedHelpers.findClass(name, null) } catch (_: Throwable) { null }
}

