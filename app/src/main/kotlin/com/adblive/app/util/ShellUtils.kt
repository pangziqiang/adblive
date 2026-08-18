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

    data class Result(val exitCode: Int, val output: String) {
        fun isSuccess() = exitCode == 0
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
}
