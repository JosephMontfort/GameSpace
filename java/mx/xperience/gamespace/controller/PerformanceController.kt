/*
 * Copyright (C) 2026 The XPerience Project
 * SPDX-License-Identifier: Apache-2.0
 */
package mx.xperience.gamespace.controller

import android.animation.ValueAnimator
import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView

import androidx.cardview.widget.CardView

import mx.xperience.gamespace.R
import mx.xperience.gamespace.utils.FPSMonitor
import mx.xperience.gamespace.utils.PerformanceManager

/**
 * Controls performance modes, foreground detection and overlay updates.
 */
class PerformanceController(private val context: Context) {

    var onRequestShowTrigger: (() -> Unit)? = null

    enum class PerformanceMode {
        POWER_SAVING,
        BALANCED,
        PERFORMANCE,
        TURBO
    }

    private data class BatteryStats(
        val level: Int,
        val status: Int,
        val temperature: Int,
        val voltage: Int,
        val health: Int,
        val plugged: Int,
        val timeRemaining: Long
    )

    private val handler = Handler(Looper.getMainLooper())
    private val perfManager = PerformanceManager(context)
    private val fpsMonitor = FPSMonitor()

    private var currentGame: String? = null
    private var currentMode = PerformanceMode.BALANCED

    private lateinit var fpsText: TextView
    private lateinit var waveView: View

    lateinit var windowParams: WindowManager.LayoutParams
        private set

    lateinit var triggerWindowParams: WindowManager.LayoutParams
        private set

    private lateinit var panelView: View

    //Animation
    private lateinit var btnEco: TextView
    private lateinit var btnBalanced: TextView
    private lateinit var btnPerf: TextView
    private lateinit var btnTurbo: TextView

    /**
     * Binds overlay UI and initializes window parameters.
     */
    fun bindOverlay(view: View) {
        fpsText = view.findViewById(R.id.fps_counter)
        waveView = view.findViewById(R.id.cpu_chart)
        btnEco = view.findViewById(R.id.btn_power_saving)
        btnBalanced = view.findViewById(R.id.btn_balanced)
        btnPerf = view.findViewById(R.id.btn_performance)
        btnTurbo = view.findViewById(R.id.btn_turbo)
        panelView = view
        //touch outside
        view.findViewById<View>(R.id.outside_touch).setOnClickListener {
            panelView.visibility = View.GONE
            onRequestShowTrigger?.invoke()
        }

        //drag
        enableDrag(panelView)
        //bindPerformanceButtons(view)

        windowParams = createOverlayParams(Gravity.START or Gravity.TOP, 10, 50)
        triggerWindowParams = createOverlayParams(Gravity.CENTER_VERTICAL or Gravity.START, 20, 0)

        btnEco.setOnClickListener {
            setMode(PerformanceMode.POWER_SAVING, true)
        }

        btnBalanced.setOnClickListener {
            setMode(PerformanceMode.BALANCED, true)
        }

        btnPerf.setOnClickListener {
            setMode(PerformanceMode.PERFORMANCE, true)
        }

        btnTurbo.setOnClickListener {
            setMode(PerformanceMode.TURBO, true)
        }

    }

    /**
     * Starts monitoring the real foreground application using UsageStats.
     */
    fun startForegroundMonitoring(onPackage: (String) -> Unit) {
        handler.post(object : Runnable {
            override fun run() {
                getForegroundPackage()?.let(onPackage)
                handler.postDelayed(this, 1000)
            }
        })
    }

    private fun closePanelAndShowTrigger() {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        try {
            wm.removeView(panelView)
        } catch (_: Exception) {}

        onRequestShowTrigger?.invoke()
    }

    fun onPanelOpened() {
        fpsMonitor.start()
        startFpsUpdates()
    }
    /**
     * Called when a game enters foreground.
     */
    fun onGameEnter(pkg: String) {
        if (pkg == currentGame) return

        currentGame = pkg
        updateGameName(pkg)
        setMode(PerformanceMode.PERFORMANCE)
    }

    /**
     * Called when leaving a game.
     */
    fun onGameExit() {
        if (currentGame == null) return

        fpsMonitor.stop()
        currentGame = null
        panelView.findViewById<TextView>(R.id.game_name).text = ""
        setMode(PerformanceMode.BALANCED)
        stopFpsUpdates()
    }

    /**
     * Applies a performance mode.
     */
    private fun setMode(mode: PerformanceMode, fromUser: Boolean = false) {
        if (mode == currentMode) return
        currentMode = mode
        perfManager.applyMode(mode)
        updatePerformanceUI()
        showModeChangeAnimation(mode)
    }

    /**
     * Updates FPS and wave visualization.
     */
    private fun startFpsUpdates() {
        handler.post(object : Runnable {
            override fun run() {
                fpsText.text = "${fpsMonitor.getCurrentFps()} FPS"
                updateWave()
                updateRamUI()
                updateBatteryInfo()
                handler.postDelayed(this, 1000)
            }
        })
    }

    private fun stopFpsUpdates() {
        handler.removeCallbacksAndMessages(null)
    }

    private fun updateWave() {
        val usage = perfManager.getCpuUsage().coerceIn(0, 100)
        val scale = 0.6f + (usage / 100f) * 0.6f
        waveView.scaleX = scale
        waveView.scaleY = scale
    }

    /**
     * Proper foreground app detection using UsageStatsManager.
     */
    private fun getForegroundPackage(): String? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()

        val events = usm.queryEvents(now - 2000, now)
        val event = UsageEvents.Event()

        var lastForeground: String? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastForeground = event.packageName
            }
        }

        return lastForeground?.takeIf { it != context.packageName }
    }

    fun release() {
        stopFpsUpdates()
        perfManager.release()
    }

    /**
     * Updates RAM usage UI in the overlay panel.
     */
    private fun updateRamUI() {
        val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val totalGB = memoryInfo.totalMem / (1024.0 * 1024 * 1024)
        val usedGB = (memoryInfo.totalMem - memoryInfo.availMem) / (1024.0 * 1024 * 1024)
        val percent = ((usedGB / totalGB) * 100).toInt()

        val ramInfo = panelView.findViewById<TextView>(R.id.ram_info)
        val ramPercent = panelView.findViewById<TextView>(R.id.ram_percent)
        val ramProgress = panelView.findViewById<ProgressBar>(R.id.ram_progress)

        ramInfo.text = String.format("%.1f/%.1f GB", usedGB, totalGB)
        ramPercent.text = "$percent%"
        ramProgress.progress = percent

        when {
            percent > 90 -> ramPercent.setTextColor(Color.parseColor("#FF4500"))
            percent > 75 -> ramPercent.setTextColor(Color.parseColor("#FFA500"))
            else -> ramPercent.setTextColor(Color.parseColor("#FFD700"))
        }
    }

    private fun createOverlayParams(
        gravity: Int,
        x: Int,
        y: Int
    ): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
            this.x = x
            this.y = y
        }
    }

    private fun enableDrag(view: View) {
        var lastX = 0
        var lastY = 0
        var downX = 0f
        var downY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    lastX = windowParams.x
                    lastY = windowParams.y
                    downX = event.rawX
                    downY = event.rawY
                    true
                }

                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()

                    windowParams.x = lastX + dx
                    windowParams.y = lastY + dy

                    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    wm.updateViewLayout(view, windowParams)
                    true
                }

                else -> false
            }
        }
    }

    private fun updateGameName(pkg: String) {
        val gameNameView =
        panelView.findViewById<TextView>(R.id.game_name)

        try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(pkg, 0)
            val label = pm.getApplicationLabel(appInfo).toString()
            gameNameView.text = label
        } catch (_: Exception) {
            gameNameView.text = pkg
        }
    }

    private fun updateModeUI() {
        val eco = panelView.findViewById<TextView>(R.id.btn_power_saving)
        val balanced = panelView.findViewById<TextView>(R.id.btn_balanced)
        val perf = panelView.findViewById<TextView>(R.id.btn_performance)
        val turbo = panelView.findViewById<TextView>(R.id.btn_turbo)

        val all = listOf(eco, balanced, perf, turbo)

        all.forEach {
            it.setTextColor(Color.parseColor("#888888"))
            it.background = null
        }

        val (btn, color) = when (currentMode) {
            PerformanceMode.POWER_SAVING -> eco to "#00FFFF"
            PerformanceMode.BALANCED -> balanced to "#00FF41"
            PerformanceMode.PERFORMANCE -> perf to "#FF00FF"
            PerformanceMode.TURBO -> turbo to "#FFA500"
        }

        btn.setTextColor(Color.parseColor(color))
        btn.setBackgroundResource(R.drawable.bg_mode_selected)
    }

    private fun updatePerformanceUI() {
        val btnPowerSaving = panelView.findViewById<TextView>(R.id.btn_power_saving)
        val btnBalanced = panelView.findViewById<TextView>(R.id.btn_balanced)
        val btnPerformance = panelView.findViewById<TextView>(R.id.btn_performance)
        val btnTurbo = panelView.findViewById<TextView>(R.id.btn_turbo)
        val modeIndicator = panelView.findViewById<TextView>(R.id.mode_indicator)

        // Reset backgrounds
        btnPowerSaving.background = context.getDrawable(R.drawable.bg_mode_normal)
        btnBalanced.background = context.getDrawable(R.drawable.bg_mode_normal)
        btnPerformance.background = context.getDrawable(R.drawable.bg_mode_normal)
        btnTurbo.background = context.getDrawable(R.drawable.bg_mode_normal)

        // Reset text colors
        btnPowerSaving.setTextColor(Color.parseColor("#888888"))
        btnBalanced.setTextColor(Color.parseColor("#888888"))
        btnPerformance.setTextColor(Color.parseColor("#888888"))
        btnTurbo.setTextColor(Color.parseColor("#888888"))

        when (currentMode) {
            PerformanceMode.POWER_SAVING -> {
                btnPowerSaving.background = context.getDrawable(R.drawable.bg_mode_selected)
                btnPowerSaving.setTextColor(Color.parseColor("#00FFFF"))
                modeIndicator.text = "ECO"
                modeIndicator.setTextColor(Color.parseColor("#00FFFF"))
            }
            PerformanceMode.BALANCED -> {
                btnBalanced.background = context.getDrawable(R.drawable.bg_mode_selected)
                btnBalanced.setTextColor(Color.parseColor("#00FF41"))
                modeIndicator.text = "BALANCED"
                modeIndicator.setTextColor(Color.parseColor("#00FF41"))
            }
            PerformanceMode.PERFORMANCE -> {
                btnPerformance.background = context.getDrawable(R.drawable.bg_mode_selected)
                btnPerformance.setTextColor(Color.parseColor("#FF00FF"))
                modeIndicator.text = "PERF"
                modeIndicator.setTextColor(Color.parseColor("#FF00FF"))
            }
            PerformanceMode.TURBO -> {
                btnTurbo.background = context.getDrawable(R.drawable.bg_mode_selected)
                btnTurbo.setTextColor(Color.parseColor("#FFA500"))
                modeIndicator.text = "TURBO"
                modeIndicator.setTextColor(Color.parseColor("#FFA500"))
            }
        }

    }

    private fun applyUserMode(mode: PerformanceMode) {
        if (mode == currentMode) return

        setMode(mode)
        perfManager.applyMode(mode)
        updatePerformanceUI()
        showModePulse(mode)
    }

    private fun showModeChangeAnimation(mode: PerformanceMode) {
        val panelBackground =
        panelView.findViewById<androidx.cardview.widget.CardView>(R.id.panel_background)

        val pulseColor = when (mode) {
            PerformanceMode.POWER_SAVING -> Color.parseColor("#00FFFF")
            PerformanceMode.BALANCED -> Color.parseColor("#00FF41")
            PerformanceMode.PERFORMANCE -> Color.parseColor("#FF00FF")
            PerformanceMode.TURBO -> Color.parseColor("#FFA500")
        }

        panelBackground.animate()
        .scaleX(1.05f)
        .scaleY(1.05f)
        .setDuration(200)
        .withEndAction {
            panelBackground.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(200)
            .start()
        }
        .start()

        val borderAnimator = ValueAnimator.ofArgb(
            Color.TRANSPARENT,
            Color.argb(
                120,
                Color.red(pulseColor),
                       Color.green(pulseColor),
                       Color.blue(pulseColor)
            )
        )

        borderAnimator.duration = 500
        borderAnimator.addUpdateListener {
            panelBackground.setCardBackgroundColor(it.animatedValue as Int)
        }
        borderAnimator.start()
    }

    private fun getBatteryStats(): BatteryStats {
        val batteryManager =
        context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

        val level =
        batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        val intent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        var status = BatteryManager.BATTERY_STATUS_UNKNOWN
        var temperature = 0
        var voltage = 0
        var health = BatteryManager.BATTERY_HEALTH_UNKNOWN
        var plugged = 0
        var timeRemaining = -1L

        intent?.let {
            status = it.getIntExtra(
                BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN
            )
            temperature = it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
            voltage = it.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
            health = it.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
            plugged = it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            timeRemaining = batteryManager.computeChargeTimeRemaining()
        }

        return BatteryStats(
            level,
            status,
            temperature,
            voltage,
            health,
            plugged,
            timeRemaining
        )
    }

    private fun updateBatteryInfo() {
        val batteryInfo =
        panelView.findViewById<TextView>(R.id.battery_info)

        val stats = getBatteryStats()

        val text = buildString {
            append("${stats.level}%")

            when (stats.status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> {
                    append(" ⚡")
                    if (stats.timeRemaining > 0) {
                        val h = stats.timeRemaining / 3600000
                        val m = (stats.timeRemaining % 3600000) / 60000
                        append(" ${h}h${m}m")
                    }
                }

                BatteryManager.BATTERY_STATUS_DISCHARGING -> {
                    if (stats.timeRemaining > 0) {
                        val h = stats.timeRemaining / 3600000
                        val m = (stats.timeRemaining % 3600000) / 60000
                        append(" ${h}h${m}m")
                    }
                }

                BatteryManager.BATTERY_STATUS_FULL -> append(" Full")
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> append(" Not charging")
                else -> append(" Unknown")
            }
        }

        batteryInfo.text = text

        val color = when {
            stats.status == BatteryManager.BATTERY_STATUS_CHARGING ->
            "#00FFFF"
            stats.level >= 50 -> "#00FF41"
            stats.level >= 20 -> "#FFA500"
            else -> "#FF4500"
        }

        batteryInfo.setTextColor(Color.parseColor(color))
    }

    private fun showModePulse(mode: PerformanceMode) {
        val panel = panelView.findViewById<View>(R.id.panel_background)

        val color = when (mode) {
            PerformanceMode.POWER_SAVING -> Color.parseColor("#00FFFF")
            PerformanceMode.BALANCED -> Color.parseColor("#00FF41")
            PerformanceMode.PERFORMANCE -> Color.parseColor("#FF00FF")
            PerformanceMode.TURBO -> Color.parseColor("#FFA500")
        }

        panel.animate()
        .scaleX(1.04f)
        .scaleY(1.04f)
        .setDuration(120)
        .withEndAction {
            panel.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(120)
            .start()
        }
        .start()
    }
}
