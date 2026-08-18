package com.adblive.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.adblive.app.util.AdbGuardManager
import com.adblive.app.util.ShellUtils
import com.adblive.app.util.XposedStatus
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private lateinit var tvXposed: TextView
    private lateinit var tvRoot: TextView
    private lateinit var tvGuard: TextView
    private lateinit var tvAdb: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvIp: TextView
    private lateinit var chipXposed: TextView
    private lateinit var chipRoot: TextView
    private lateinit var swGuard: MaterialSwitch
    private lateinit var swAdb: MaterialSwitch
    private lateinit var cardXposed: MaterialCardView
    private lateinit var cardRoot: MaterialCardView
    private lateinit var cardGuard: MaterialCardView
    private lateinit var cardAdb: MaterialCardView
    private lateinit var progress: ProgressBar
    private lateinit var btnRefresh: ImageButton
    private lateinit var swipeRefresh: SwipeRefreshLayout

    private val handler = Handler(Looper.getMainLooper())
    private var rootOk = false
    private var xposedOk = false
    private var guardOn = false
    private var adbOn = false
    private var autoRefreshRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvXposed = findViewById(R.id.tvXposed)
        tvRoot = findViewById(R.id.tvRoot)
        tvGuard = findViewById(R.id.tvGuard)
        tvAdb = findViewById(R.id.tvAdb)
        tvLog = findViewById(R.id.tvLog)
        tvIp = findViewById(R.id.tvIp)
        chipXposed = findViewById(R.id.chipXposed)
        chipRoot = findViewById(R.id.chipRoot)
        swGuard = findViewById(R.id.swGuard)
        swAdb = findViewById(R.id.swAdb)
        cardXposed = findViewById(R.id.cardXposed)
        cardRoot = findViewById(R.id.cardRoot)
        cardGuard = findViewById(R.id.cardGuard)
        cardAdb = findViewById(R.id.cardAdb)
        progress = findViewById(R.id.progress)
        btnRefresh = findViewById(R.id.btnRefresh)
        swipeRefresh = findViewById(R.id.swipeRefresh)

        swGuard.setOnCheckedChangeListener { _, checked -> toggleGuard(checked) }
        swAdb.setOnCheckedChangeListener { _, checked -> toggleAdb(checked) }
        btnRefresh.setOnClickListener { refresh() }
        swipeRefresh.setOnRefreshListener { refresh() }
        swipeRefresh.setColorSchemeColors(getColor(R.color.teal))

        refresh()
        startAutoRefresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        autoRefreshRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun startAutoRefresh() {
        autoRefreshRunnable = object : Runnable {
            override fun run() {
                refresh()
                handler.postDelayed(this, 5000)
            }
        }
        handler.postDelayed(autoRefreshRunnable!!, 5000)
    }

    private fun appendLog(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        tvLog.append("[" + ts + "] " + msg + System.lineSeparator())
    }

    private fun getLocalIp(): String {
        try {
            for (intf in NetworkInterface.getNetworkInterfaces()) {
                if (intf.isLoopback || !intf.isUp) continue
                for (addr in intf.inetAddresses) {
                    if (addr.isLoopbackAddress) continue
                    val ip = addr.hostAddress ?: continue
                    if (ip.contains(":")) continue
                    return ip
                }
            }
        } catch (_: Exception) {}
        return ""
    }

    private fun refresh() {
        progress.visibility = View.VISIBLE
        btnRefresh.isEnabled = false
        swipeRefresh.isRefreshing = false
        Thread {
            val ip = getLocalIp()
            rootOk = ShellUtils.probeRoot()
            xposedOk = XposedStatus.isActive(this)
            guardOn = AdbGuardManager.isScriptDeployed() && AdbGuardManager.isGuardRunning()
            val adbR = ShellUtils.executeSu("getprop service.adb.tcp.port")
            adbOn = adbR.isSuccess() && adbR.output.trim() == "5555"

            if (rootOk && !guardOn) {
                val deployed = AdbGuardManager.deployAndStart(this)
                if (deployed) guardOn = true
            }

            runOnUiThread {
                progress.visibility = View.GONE
                btnRefresh.isEnabled = true

                tvIp.text = if (ip.isNotEmpty()) ip else ""

                tvXposed.text = if (xposedOk) getString(R.string.shield_active) else getString(R.string.shield_inactive)
                setChipText(chipXposed, if (xposedOk) "OK" else "--", xposedOk)
                cardXposed.strokeColor = getColor(if (xposedOk) R.color.card_border_on else R.color.card_border_off)

                tvRoot.text = if (rootOk) getString(R.string.root_active) else getString(R.string.root_inactive)
                setChipText(chipRoot, if (rootOk) "OK" else "--", rootOk)
                cardRoot.strokeColor = getColor(if (rootOk) R.color.card_border_on else R.color.card_border_off)

                tvGuard.text = if (guardOn) getString(R.string.guard_active) else getString(R.string.guard_inactive)
                swGuard.isChecked = guardOn
                cardGuard.strokeColor = getColor(if (guardOn) R.color.card_border_on else R.color.card_border_off)

                tvAdb.text = if (adbOn) getString(R.string.adb_active) else getString(R.string.adb_inactive)
                swAdb.isChecked = adbOn
                cardAdb.strokeColor = getColor(if (adbOn) R.color.card_border_on else R.color.card_border_off)
            }
        }.start()
    }

    private fun setChipText(tv: TextView, text: String, active: Boolean) {
        tv.text = text
        tv.setTextColor(getColor(if (active) R.color.chip_active_text else R.color.chip_inactive_text))
        tv.setBackgroundResource(if (active) R.drawable.bg_chip_active else R.drawable.bg_chip)
    }

    private fun toggleGuard(on: Boolean) {
        Thread {
            if (on) {
                val ok = AdbGuardManager.deployAndStart(this)
                guardOn = ok
                runOnUiThread { appendLog(if (ok) "guard deployed" else "guard deploy failed") }
            } else {
                AdbGuardManager.stopAndRemove()
                guardOn = false
                runOnUiThread { appendLog("guard stopped") }
            }
            runOnUiThread { updateGuardUi() }
        }.start()
    }

    private fun toggleAdb(on: Boolean) {
        Thread {
            if (on) {
                ShellUtils.executeSu("setprop service.adb.tcp.port 5555")
                ShellUtils.executeSu("settings put global adb_wifi_enabled 1")
                ShellUtils.executeSu("stop adbd && start adbd")
                runOnUiThread { appendLog("adb enabling") }
            } else {
                ShellUtils.executeSu("setprop service.adb.tcp.port 0")
                ShellUtils.executeSu("settings put global adb_wifi_enabled 0")
                runOnUiThread { appendLog("adb disabling") }
            }
            Thread.sleep(1000)
            val adbR = ShellUtils.executeSu("getprop service.adb.tcp.port")
            adbOn = adbR.isSuccess() && adbR.output.trim() == "5555"
            runOnUiThread { updateAdbUi() }
        }.start()
    }

    private fun updateGuardUi() {
        tvGuard.text = if (guardOn) getString(R.string.guard_active) else getString(R.string.guard_inactive)
        swGuard.isChecked = guardOn
        cardGuard.strokeColor = getColor(if (guardOn) R.color.card_border_on else R.color.card_border_off)
    }

    private fun updateAdbUi() {
        tvAdb.text = if (adbOn) getString(R.string.adb_active) else getString(R.string.adb_inactive)
        swAdb.isChecked = adbOn
        cardAdb.strokeColor = getColor(if (adbOn) R.color.card_border_on else R.color.card_border_off)
    }
}
