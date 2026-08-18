package com.adblive.app

import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
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
    private lateinit var tvPort: TextView
    private lateinit var chipXposed: TextView
    private lateinit var chipRoot: TextView
    private lateinit var swGuard: MaterialSwitch
    private lateinit var swAdb: MaterialSwitch
    private lateinit var cardXposed: MaterialCardView
    private lateinit var cardRoot: MaterialCardView
    private lateinit var cardGuard: MaterialCardView
    private lateinit var cardAdb: MaterialCardView
    private lateinit var icShield: ImageView
    private lateinit var icRoot: ImageView
    private lateinit var icAdb: ImageView
    private lateinit var progress: ProgressBar
    private lateinit var btnRefresh: ImageButton
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var toolbar: Toolbar

    private val handler = Handler(Looper.getMainLooper())
    private var rootOk = false
    private var xposedOk = false
    private var guardOn = false
    private var adbOn = false
    private var adbObserver: ContentObserver? = null
    private var refreshing = false
    private var ipText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        tvXposed = findViewById(R.id.tvXposed)
        tvRoot = findViewById(R.id.tvRoot)
        tvGuard = findViewById(R.id.tvGuard)
        tvAdb = findViewById(R.id.tvAdb)
        tvLog = findViewById(R.id.tvLog)
        tvIp = findViewById(R.id.tvIp)
        tvPort = findViewById(R.id.tvPort)
        chipXposed = findViewById(R.id.chipXposed)
        chipRoot = findViewById(R.id.chipRoot)
        swGuard = findViewById(R.id.swGuard)
        swAdb = findViewById(R.id.swAdb)
        cardXposed = findViewById(R.id.cardXposed)
        cardRoot = findViewById(R.id.cardRoot)
        cardGuard = findViewById(R.id.cardGuard)
        cardAdb = findViewById(R.id.cardAdb)
        icShield = findViewById(R.id.icShield)
        icRoot = findViewById(R.id.icRoot)
        icAdb = findViewById(R.id.icAdb)
        progress = findViewById(R.id.progress)
        btnRefresh = findViewById(R.id.btnRefresh)
        swipeRefresh = findViewById(R.id.swipeRefresh)

        swGuard.setOnCheckedChangeListener { _, checked -> if (swGuard.isPressed) toggleGuard(checked) }
        swAdb.setOnCheckedChangeListener { _, checked -> if (swAdb.isPressed) toggleAdb(checked) }
        btnRefresh.setOnClickListener { refresh() }
        swipeRefresh.setOnRefreshListener { refresh() }
        swipeRefresh.setColorSchemeColors(getColor(R.color.teal))

        tvIp.setOnClickListener { copyIp() }
    }

    override fun onResume() {
        super.onResume()
        val uri = Settings.Global.getUriFor("adb_wifi_enabled")
        adbObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) { refresh() }
        }.also { contentResolver.registerContentObserver(uri, false, it) }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        adbObserver?.let { contentResolver.unregisterContentObserver(it) }
        adbObserver = null
    }

    private fun appendLog(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val cur = tvLog.text.toString()
        val next = if (cur.isBlank()) "[" + ts + "] " + msg
                   else cur + System.lineSeparator() + "[" + ts + "] " + msg
        tvLog.text = next
    }

    private fun getLocalIp(): String {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { !it.isLoopback && it.isUp }
                .flatMap { it.inetAddresses.asSequence() }
                .filter { !it.isLoopbackAddress && !it.hostAddress.contains(":") }
                .map { it.hostAddress }
                .firstOrNull() ?: ""
        } catch (_: Exception) { "" }
    }

    private fun copyIp() {
        if (ipText.isEmpty()) return
        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("ip", ipText))
        appendLog("IP copied: " + ipText)
    }

    private fun refresh() {
        if (refreshing) return
        refreshing = true
        btnRefresh.isEnabled = false
        progress.visibility = View.VISIBLE
        Thread {
            val ip = getLocalIp()
            rootOk = ShellUtils.probeRoot()
            xposedOk = XposedStatus.isActive(this)
            guardOn = AdbGuardManager.isScriptDeployed() && AdbGuardManager.isGuardRunning()
            val adbR = ShellUtils.executeSu("getprop service.adb.tcp.port")
            adbOn = adbR.isSuccess() && adbR.output.trim() == "5555"

            runOnUiThread {
                progress.visibility = View.GONE
                btnRefresh.isEnabled = true
                swipeRefresh.isRefreshing = false
                refreshing = false

                ipText = ip
                tvIp.text = ip.ifEmpty { "--" }

                // ADB
                tvAdb.text = if (adbOn) getString(R.string.adb_active) else getString(R.string.adb_inactive)
                swAdb.isChecked = adbOn
                cardAdb.strokeColor = getColor(if (adbOn) R.color.card_border_on else R.color.card_border_off)
                tintCircle(icAdb, adbOn)

                // Guard
                tvGuard.text = if (guardOn) getString(R.string.guard_active) else getString(R.string.guard_inactive)
                swGuard.isChecked = guardOn
                cardGuard.strokeColor = getColor(if (guardOn) R.color.card_border_on else R.color.card_border_off)

                // Xposed
                tvXposed.text = if (xposedOk) getString(R.string.shield_active) else getString(R.string.shield_inactive)
                setChipText(chipXposed, if (xposedOk) "ON" else "OFF", xposedOk)
                cardXposed.strokeColor = getColor(if (xposedOk) R.color.card_border_on else R.color.card_border_off)
                tintCircle(icShield, xposedOk)

                // Root
                tvRoot.text = if (rootOk) getString(R.string.root_active) else getString(R.string.root_inactive)
                setChipText(chipRoot, if (rootOk) "OK" else "--", rootOk)
                cardRoot.strokeColor = getColor(if (rootOk) R.color.card_border_on else R.color.card_border_off)
                tintCircle(icRoot, rootOk)
            }
        }.start()
    }

    private fun tintCircle(iv: ImageView, active: Boolean) {
        iv.setBackgroundResource(if (active) R.drawable.bg_icon_circle_on else R.drawable.bg_icon_circle_off)
        iv.setColorFilter(ContextCompat.getColor(this,
            if (active) R.color.on_teal_container else R.color.text_secondary))
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
            runOnUiThread {
                tvGuard.text = if (guardOn) getString(R.string.guard_active) else getString(R.string.guard_inactive)
                swGuard.isChecked = guardOn
                cardGuard.strokeColor = getColor(if (guardOn) R.color.card_border_on else R.color.card_border_off)
            }
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
            runOnUiThread {
                tvAdb.text = if (adbOn) getString(R.string.adb_active) else getString(R.string.adb_inactive)
                swAdb.isChecked = adbOn
                cardAdb.strokeColor = getColor(if (adbOn) R.color.card_border_on else R.color.card_border_off)
                tintCircle(icAdb, adbOn)
            }
        }.start()
    }
}

