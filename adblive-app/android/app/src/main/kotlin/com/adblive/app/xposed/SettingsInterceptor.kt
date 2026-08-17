package com.adblive.app.xposed

import android.content.ContentValues
import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SettingsInterceptor — 拦截所有 adb_wifi_enabled 写入路径
 *
 * 覆盖 7 条写入通道：
 * 1. Settings.Global.putInt (2 种签名)
 * 2. Settings.Global.putString (2 种签名)
 * 3. ContentProvider.Transport.call (shell settings put 走这里)
 * 4. SettingsProvider.call (3/4 参数版)
 * 5. SettingsProvider.update / insert (直接 CRUD)
 *
 * 与 ADB-Guard 不同，我们把所有拦截逻辑集中到一个类，
 * 并使用 AtomicBoolean 防止重复注册。
 */
object SettingsInterceptor {

    private const val KEY = "adb_wifi_enabled"
    private val providerHooked = AtomicBoolean(false)

    // ── system_server 入口 ──────────────────────────────

    fun install(classLoader: ClassLoader?) {
        hookGlobalPutInt()
        hookGlobalPutString()
        hookTransportCall(classLoader)
        hookSettingsProvider(classLoader)
    }

    // ── Settings / SettingsProvider 入口 ────────────────

    fun installProvider(classLoader: ClassLoader?) {
        if (!providerHooked.compareAndSet(false, true)) return
        hookTransportCall(classLoader)
        hookSettingsProvider(classLoader)
    }

    // ── Settings.Global.putInt ──────────────────────────

    private fun hookGlobalPutInt() {
        val clazz = resolveClass("android.provider.Settings\$Global") ?: return
        val sigs = arrayOf(
            arrayOf(ContentResolver::class.java, String::class.java, Int::class.javaPrimitiveType),
            arrayOf(ContentResolver::class.java, String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
        )
        for (sig in sigs) {
            try {
                XposedHelpers.findAndHookMethod(clazz, "putInt", *sig, object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        if (isTarget(p.args[1]) && p.args[2] == 0) {
                            Entry.log("SettingsInterceptor: blocked putInt($KEY=0)")
                            p.setResult(true)
                        }
                    }
                })
            } catch (_: Throwable) { }
        }
    }

    // ── Settings.Global.putString ───────────────────────

    private fun hookGlobalPutString() {
        val clazz = resolveClass("android.provider.Settings\$Global") ?: return
        val sigs = arrayOf(
            arrayOf(ContentResolver::class.java, String::class.java, String::class.java),
            arrayOf(ContentResolver::class.java, String::class.java, String::class.java, Int::class.javaPrimitiveType),
        )
        for (sig in sigs) {
            try {
                XposedHelpers.findAndHookMethod(clazz, "putString", *sig, object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        if (isTarget(p.args[1]) && isOff(p.args[2])) {
                            Entry.log("SettingsInterceptor: blocked putString($KEY)")
                            p.setResult(true)
                        }
                    }
                })
            } catch (_: Throwable) { }
        }
    }

    // ── ContentProvider.Transport.call ──────────────────
    // shell "settings put global adb_wifi_enabled 0" 走这里

    private fun hookTransportCall(classLoader: ClassLoader?) {
        try {
            val transport = resolveClass("android.content.ContentProvider\$Transport", classLoader)
                ?: return
            XposedHelpers.findAndHookMethod(
                transport, "call",
                android.content.AttributionSource::class.java,
                String::class.java, String::class.java, String::class.java, Bundle::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        try {
                            val authority = p.args[1] as? String ?: return
                            val method = p.args[2] as? String ?: return
                            val name = p.args[3] as? String ?: return
                            val extras = p.args[4] as? Bundle ?: return
                            if (authority == "settings"
                                && method == "PUT_global"
                                && name == KEY
                                && isOff(extras.getString("value"))) {
                                Entry.log("SettingsInterceptor: blocked Transport.call($KEY)")
                                p.setResult(null)
                            }
                        } catch (_: Throwable) { }
                    }
                })
            return
        } catch (_: Throwable) { }

        // fallback: SettingsProvider 自身
        hookSettingsProvider(classLoader)
    }

    // ── SettingsProvider.call / update / insert ─────────

    private fun hookSettingsProvider(classLoader: ClassLoader?) {
        val clazz = findProvider(classLoader) ?: return

        // call(String, String, Bundle) — 3 参数
        try {
            XposedHelpers.findAndHookMethod(
                clazz, "call",
                String::class.java, String::class.java, Bundle::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        try {
                            val method = p.args[0] as? String ?: return
                            val name = p.args[1] as? String ?: return
                            val extras = p.args[2] as? Bundle ?: return
                            if (method == "PUT_global" && name == KEY && isOff(extras.getString("value"))) {
                                Entry.log("SettingsInterceptor: blocked call(3-arg)")
                                p.setResult(null)
                            }
                        } catch (_: Throwable) { }
                    }
                })
        } catch (_: Throwable) { }

        // call(String, String, String, Bundle) — 4 参数
        try {
            XposedHelpers.findAndHookMethod(
                clazz, "call",
                String::class.java, String::class.java, String::class.java, Bundle::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        try {
                            val method = p.args[1] as? String ?: return
                            val name = p.args[2] as? String ?: return
                            val extras = p.args[3] as? Bundle ?: return
                            if (method == "PUT_global" && name == KEY && isOff(extras.getString("value"))) {
                                Entry.log("SettingsInterceptor: blocked call(4-arg)")
                                p.setResult(null)
                            }
                        } catch (_: Throwable) { }
                    }
                })
        } catch (_: Throwable) { }

        // update / insert
        hookCrud(clazz)
    }

    private fun hookCrud(clazz: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                clazz, "update",
                Uri::class.java, ContentValues::class.java,
                String::class.java, Array<String>::class.java,
                blockCrud("update", 0))
        } catch (_: Throwable) { }

        try {
            XposedHelpers.findAndHookMethod(
                clazz, "insert",
                Uri::class.java, ContentValues::class.java,
                blockCrud("insert", null))
        } catch (_: Throwable) { }
    }

    private fun blockCrud(tag: String, result: Any?) = object : XC_MethodHook() {
        override fun beforeHookedMethod(p: MethodHookParam) {
            try {
                val values = p.args[1] as? ContentValues ?: return
                if (KEY == values.getAsString("name") && isOff(values.getAsString("value"))) {
                    Entry.log("SettingsInterceptor: blocked $tag($KEY)")
                    p.setResult(result)
                }
            } catch (_: Throwable) { }
        }
    }

    // ── helpers ─────────────────────────────────────────

    private fun isTarget(name: Any?): Boolean = name == KEY

    private fun isOff(value: Any?): Boolean {
        val v = (value as? String)?.trim() ?: return false
        return v == "0" || v.equals("false", true) || v.equals("disable", true)
    }

    private fun resolveClass(name: String, cl: ClassLoader? = null): Class<*>? {
        return try { XposedHelpers.findClass(name, cl) } catch (_: Throwable) { null }
    }

    private fun findProvider(cl: ClassLoader?): Class<*>? {
        val name = "com.android.providers.settings.SettingsProvider"
        for (loader in listOf(cl, ClassLoader.getSystemClassLoader(), javaClass.classLoader)) {
            try { if (loader != null) return Class.forName(name, false, loader) } catch (_: Throwable) { }
        }
        return resolveClass(name)
    }
}

