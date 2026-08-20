package com.adblive.app.util

object ShieldStateFile {
    // /data/system/ 是 system_server 唯一能读且 root 可写的位置（/data/adb 和 /data/local/tmp 均被 SELinux 拒读）
    private const val FILE = "/data/system/adblive_shield_off"
    private const val LEGACY_FILE = "/data/local/tmp/adblive_shield_off"

    fun exists(): Boolean {
        val r = ShellUtils.executeSu("test -f " + FILE + " && echo yes")
        return r.isSuccess() && r.output.contains("yes")
    }

    fun arm(): Boolean {
        // arm blocking -> remove kill-switch file
        val r = ShellUtils.executeSu("rm -f " + FILE + " " + LEGACY_FILE)
        return r.isSuccess()
    }

    fun disarm(): Boolean {
        // disarm blocking -> create kill-switch file
        val r = ShellUtils.executeSu(
            "touch " + FILE + " && chmod 644 " + FILE + " && " +
            "touch " + LEGACY_FILE + " && chmod 666 " + LEGACY_FILE
        )
        return r.isSuccess()
    }
}
