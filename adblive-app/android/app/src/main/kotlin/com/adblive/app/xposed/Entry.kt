package com.adblive.app.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * ADBLive Shield — Xposed 模块入口
 *
 * 与 ADB-Guard 不同，本模块采用「哨兵」模式：
 * - Hook 注册到统一的 GuardRegistry
 * - 每个 Guard 独立管理自己的生命周期
 * - 支持运行时动态启停（通过文件标记）
 */
class Entry : IXposedHookLoadPackage {

    companion object {
        const val TAG = "ADBLive-Shield"
        private const val KILL_SWITCH = "/data/local/tmp/adblive_shield_off"

        fun log(msg: String) = XposedBridge.log("[$TAG] $msg")
        fun isDisabled(): Boolean = try {
            java.io.File(KILL_SWITCH).exists()
        } catch (_: Exception) { false }
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (isDisabled()) {
            log("Kill switch active — skipping all hooks")
            return
        }

        val process = lpparam.processName
        val pkg = lpparam.packageName

        log("handleLoadPackage process=$process pkg=$pkg")

        when {
            // system_server 主进程 或 LSPosed 2.x 注入点
            process == "system_server"
                || process.endsWith(":system_server")
                || (pkg == "android" && process != "system_server") -> {

                log("→ mounting guards into system_server")

                // Settings 写入拦截
                SettingsInterceptor.install(lpparam.classLoader)

                // 进程保护
                ProcessInterceptor.install(lpparam.classLoader)
            }

            // Settings 应用
            pkg == "com.android.settings" -> {
                log("→ mounting guard into Settings app")
                SettingsInterceptor.installProvider(lpparam.classLoader)
            }

            // SettingsProvider
            pkg == "com.android.providers.settings" -> {
                log("→ mounting guard into SettingsProvider")
                SettingsInterceptor.installProvider(lpparam.classLoader)
            }
        }
    }
}

