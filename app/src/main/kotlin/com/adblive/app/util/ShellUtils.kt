package com.adblive.app.util

import android.util.Log
import java.util.concurrent.TimeUnit

object ShellUtils {
    private const val TAG = "ADBLive_Shell"
    private val SU_PATHS = listOf(
        listOf("/system/bin/su", "-c"),
        listOf("su", "-c"),
        listOf("/data/adb/ksu/bin/su", "-c"),
        listOf("/data/adb/magisk/su", "-c"),
    )

    @Volatile private var workingSuPath: List<String>? = null

    // 部分厂商 ROM 的 libc 在进程启动时读 persist.vendor.* 被 SELinux 拒绝，
    // 会往 stderr 打 "Access denied finding property ..."。executeSu 把 stderr
    // 合并进 stdout，调用方若用 trim()=="5555" 会把“已打开”误判成“关闭”。
    private val LIBC_PROP_NOISE = Regex(
        """(?:libc:\s*)?Access denied finding\s*property\s*"[^"]*"\s*""",
        RegexOption.IGNORE_CASE
    )

    data class Result(val exitCode: Int, val output: String) {
        fun isSuccess() = exitCode == 0

        /** 去掉厂商 libc 噪音后的最后一个有效字段，用于解析 getprop / settings / pid。 */
        fun scalar(): String {
            val stripped = LIBC_PROP_NOISE.replace(output, "\n")
            val last = stripped.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("libc:", ignoreCase = true) }
                .lastOrNull()
                ?: return ""
            return last.split(Regex("\\s+")).last()
        }
    }

    fun executeSu(command: String, timeoutMs: Long = 2000): Result {
        val cached = workingSuPath
        if (cached != null) {
            val r = runCommand(cached + command, timeoutMs)
            if (r != null && !r.output.contains("not found")) {
                workingSuPath = cached
                return r
            }
        }
        for (suPath in SU_PATHS) {
            val r = runCommand(suPath + command, timeoutMs) ?: continue
            if (!r.output.contains("not found")) {
                workingSuPath = suPath
                return r
            }
        }
        return Result(-1, "su not found")
    }

    private fun runCommand(cmd: List<String>, timeoutMs: Long): Result? {
        return try {
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) { proc.destroyForcibly(); return null }
            val out = proc.inputStream.bufferedReader().readText()
            Result(proc.exitValue(), out)
        } catch (_: Exception) { null }
    }

    fun probeRoot(): Boolean {
        val r = executeSu("id")
        return r.isSuccess() && r.output.contains("uid=0")
    }

    fun suHidden(): Boolean {
        return try {
            val f = java.io.File("/system/bin/su")
            !f.exists() || !f.canExecute()
        } catch (_: Exception) {
            false
        }
    }
}
