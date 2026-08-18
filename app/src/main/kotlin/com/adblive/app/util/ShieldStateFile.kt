package com.adblive.app.util

object ShieldStateFile {
    private const val FILE = "/data/local/tmp/adblive_shield_off"

    fun exists(): Boolean {
        val r = ShellUtils.executeSu("test -f " + FILE + " && echo yes")
        return r.isSuccess() && r.output.contains("yes")
    }

    fun enable() {
        // enable blocking -> remove kill-switch file
        ShellUtils.executeSu("rm -f " + FILE)
    }

    fun disable() {
        // disable blocking -> create kill-switch file
        ShellUtils.executeSu("touch " + FILE + " && chmod 666 " + FILE)
    }
}
