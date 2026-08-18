package com.adblive.app

import android.app.Application
import android.util.Log
import com.adblive.app.util.XposedStatus
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class App : Application() {
    companion object {
        const val TAG = "ADBLive"
    }

    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    override fun onCreate() {
        super.onCreate()
        XposedStatus.init(this)
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            Log.e(TAG, "Uncaught in " + thread.name + ": " + sw.toString())
            try { File(filesDir, "last_crash.txt").writeText(sw.toString()) } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
