package com.adblive.app.util

object ShieldStateFile {
    private const val ARMED_FILE = "/data/system/adblive_shield_armed"

    fun exists(): Boolean {
        val r = ShellUtils.executeSu("test -f " + ARMED_FILE + " && echo yes")
        return r.isSuccess() && r.output.contains("yes")
    }

    fun arm(): Boolean {
        val r = ShellUtils.executeSu("touch " + ARMED_FILE + " && chmod 644 " + ARMED_FILE)
        return r.isSuccess()
    }

    fun disarm(): Boolean {
        val r = ShellUtils.executeSu("rm -f " + ARMED_FILE)
        return r.isSuccess()
    }
}
