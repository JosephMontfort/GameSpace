package mx.xperience.gamespace.controller

import android.animation.ValueAnimator
import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView

import mx.xperience.gamespace.R
import mx.xperience.gamespace.utils.FPSMonitor
import mx.xperience.gamespace.utils.PerformanceManager
import mx.xperience.gamespace.utils.SysfsController
import mx.xperience.gamespace.view.WaveView

class PerformanceController(private val context: Context) {

    var onRequestShowTrigger: (() -> Unit)? = null

    enum class PerformanceMode {
        POWER_SAVING, BALANCED, PERFORMANCE, TURBO
    }

    private data class BatteryStats(
        val level: Int, val status: Int, val temperature: Int,
        val voltage: Int, val health: Int, val plugged: Int, val timeRemaining: Long
    )

    private val handler = Handler(Looper.getMainLooper())
    private val perfManager = PerformanceManager(context)
    private val fpsMonitor = FPSMonitor()
    private val sysfsController = SysfsController()

    private var currentGame: String? = null
    private var currentMode = PerformanceMode.BALANCED

    private var fpsText: TextView? = null
    private lateinit var panelView: View

    lateinit var windowParams: WindowManager.LayoutParams
        private set
    lateinit var triggerWindowParams: WindowManager.LayoutParams

    private val prefs: SharedPreferences by lazy {
        val directBootContext = context.createDeviceProtectedStorageContext()
        directBootContext.getSharedPreferences("gamespace_prefs", Context.MODE_PRIVATE)
    }

    private var cpuLittleMaxLimit: Long = 0L
    private var cpuBigMaxLimit: Long = 0L

    fun bindOverlay(view: View) {
        panelView = view
        fpsText = view.findViewById(R.id.fps_counter)
        
        // Safely bind buttons if they exist in dynamic overlays
        view.findViewById<TextView>(R.id.btn_power_saving)?.setOnClickListener { setMode(PerformanceMode.POWER_SAVING, true) }
        view.findViewById<TextView>(R.id.btn_balanced)?.setOnClickListener { setMode(PerformanceMode.BALANCED, true) }
        view.findViewById<TextView>(R.id.btn_performance)?.setOnClickListener { setMode(PerformanceMode.PERFORMANCE, true) }
        view.findViewById<TextView>(R.id.btn_turbo)?.setOnClickListener { setMode(PerformanceMode.TURBO, true) }

        windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = 24
            y = 50
        }
    }

    fun startForegroundMonitoring(onPackage: (String) -> Unit) {
        handler.post(object : Runnable {
            override fun run() {
                getForegroundPackage()?.let(onPackage)
                handler.postDelayed(this, 1000)
            }
        })
    }

    fun onPanelOpened() {
        fpsMonitor.start()
        startFpsUpdates()
    }

    fun onGameEnter(pkg: String) {
        if (pkg == currentGame) return
        currentGame = pkg
        updateGameName(pkg)
        setMode(PerformanceMode.PERFORMANCE)
    }

    fun onGameExit() {
        if (currentGame == null) return
        fpsMonitor.stop()
        currentGame = null
        panelView.findViewById<TextView>(R.id.game_name)?.text = ""
        setMode(PerformanceMode.BALANCED)
        stopFpsUpdates()
    }

    private fun setMode(mode: PerformanceMode, fromUser: Boolean = false) {
        if (mode == currentMode) return
        currentMode = mode
        perfManager.applyMode(mode)
        updatePerformanceUI()
        showModeChangeAnimation(mode)
    }

    private fun startFpsUpdates() {
        handler.post(object : Runnable {
            override fun run() {
                cpuUpdateUI()
                
                val currentFps = fpsMonitor.getCurrentFps()
                fpsText?.text = currentFps.toString()
                
                val fpsWave = panelView.findViewById<WaveView>(R.id.fps_chart)
                fpsWave?.setWaveAmplitude((currentFps / 120f).coerceIn(0.1f, 1.0f))
                fpsWave?.setWaveColor(Color.parseColor("#00FFFF"))
                
                gpuUpdateUI()
                updateRamUI()
                updateBatteryInfo()
                handler.postDelayed(this, 1000)
            }
        })
    }

    private fun stopFpsUpdates() {
        handler.removeCallbacksAndMessages(null)
    }

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

    private fun updateRamUI() {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val totalGB = memoryInfo.totalMem / (1024.0 * 1024 * 1024)
        val usedGB = (memoryInfo.totalMem - memoryInfo.availMem) / (1024.0 * 1024 * 1024)
        val percent = ((usedGB / totalGB) * 100).toInt()

        panelView.findViewById<TextView>(R.id.ram_info)?.text = String.format("%.1f/%.1f GB", usedGB, totalGB)
        val ramPercent = panelView.findViewById<TextView>(R.id.ram_percent)
        ramPercent?.text = "$percent%"
        panelView.findViewById<ProgressBar>(R.id.ram_progress)?.progress = percent

        when {
            percent > 90 -> ramPercent?.setTextColor(Color.parseColor("#FF4500"))
            percent > 75 -> ramPercent?.setTextColor(Color.parseColor("#FFA500"))
            else -> ramPercent?.setTextColor(Color.parseColor("#FFD700"))
        }
    }

    private fun gpuUpdateUI() {
        val gpuFreq = sysfsController.getGpuFreq()
        panelView.findViewById<TextView>(R.id.gpu_val)?.text = gpuFreq.first.toString() + " MHz"
        panelView.findViewById<TextView>(R.id.gpu_temp)?.text = gpuFreq.second

        val gpuWave = panelView.findViewById<WaveView>(R.id.gpu_chart)
        gpuWave?.setWaveAmplitude((gpuFreq.first / 1000f).coerceIn(0.2f, 0.8f))
        gpuWave?.setWaveColor(Color.parseColor("#f74a7b"))
    }

    private fun cpuUpdateUI() {
        if (cpuLittleMaxLimit == 0L || cpuBigMaxLimit == 0L) {
            val limits = sysfsController.getCpuClusterMaxLimits()
            cpuLittleMaxLimit = limits.first
            cpuBigMaxLimit = limits.second
        }
        val freqs = sysfsController.getCpuClusterFreqs()
        val temp = sysfsController.getCpuTemperature()

        panelView.findViewById<TextView>(R.id.cpu_little_val)?.text = String.format("%.2f", freqs.first / 1000000.0) + " GHz"
        panelView.findViewById<TextView>(R.id.cpu_little_temp)?.text = temp
        val littleWave = panelView.findViewById<WaveView>(R.id.cpu_little_chart)
        if (cpuLittleMaxLimit > 0) littleWave?.setWaveAmplitude((freqs.first.toFloat() / cpuLittleMaxLimit.toFloat()).coerceIn(0.1f, 1.0f))
        littleWave?.setWaveColor(Color.parseColor("#00FF41"))

        panelView.findViewById<TextView>(R.id.cpu_big_val)?.text = String.format("%.2f", freqs.second / 1000000.0) + " GHz"
        panelView.findViewById<TextView>(R.id.cpu_big_temp)?.text = temp
        val bigWave = panelView.findViewById<WaveView>(R.id.cpu_big_chart)
        if (cpuBigMaxLimit > 0) bigWave?.setWaveAmplitude((freqs.second.toFloat() / cpuBigMaxLimit.toFloat()).coerceIn(0.1f, 1.0f))
        bigWave?.setWaveColor(Color.parseColor("#FF00FF"))
    }

    private fun updateGameName(pkg: String) {
        val gameNameView = panelView.findViewById<TextView>(R.id.game_name) ?: return
        try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(pkg, 0)
            val label = pm.getApplicationLabel(appInfo).toString()
            gameNameView.text = label
        } catch (_: Exception) {
            gameNameView.text = pkg
        }
    }

    private fun updatePerformanceUI() {
        val btnPowerSaving = panelView.findViewById<TextView>(R.id.btn_power_saving)
        val btnBalanced = panelView.findViewById<TextView>(R.id.btn_balanced)
        val btnPerformance = panelView.findViewById<TextView>(R.id.btn_performance)
        val btnTurbo = panelView.findViewById<TextView>(R.id.btn_turbo)

        btnPowerSaving?.background = context.getDrawable(R.drawable.bg_mode_normal)
        btnBalanced?.background = context.getDrawable(R.drawable.bg_mode_normal)
        btnPerformance?.background = context.getDrawable(R.drawable.bg_mode_normal)
        btnTurbo?.background = context.getDrawable(R.drawable.bg_mode_normal)

        btnPowerSaving?.setTextColor(Color.parseColor("#888888"))
        btnBalanced?.setTextColor(Color.parseColor("#888888"))
        btnPerformance?.setTextColor(Color.parseColor("#888888"))
        btnTurbo?.setTextColor(Color.parseColor("#888888"))

        when (currentMode) {
            PerformanceMode.POWER_SAVING -> {
                btnPowerSaving?.background = context.getDrawable(R.drawable.bg_mode_selected)
                btnPowerSaving?.setTextColor(Color.parseColor("#00FFFF"))
            }
            PerformanceMode.BALANCED -> {
                btnBalanced?.background = context.getDrawable(R.drawable.bg_mode_selected)
                btnBalanced?.setTextColor(Color.parseColor("#00FF41"))
            }
            PerformanceMode.PERFORMANCE -> {
                btnPerformance?.background = context.getDrawable(R.drawable.bg_mode_selected)
                btnPerformance?.setTextColor(Color.parseColor("#FF00FF"))
            }
            PerformanceMode.TURBO -> {
                btnTurbo?.background = context.getDrawable(R.drawable.bg_mode_selected)
                btnTurbo?.setTextColor(Color.parseColor("#FFA500"))
            }
        }
    }

    private fun showModeChangeAnimation(mode: PerformanceMode) {
        val panelBackground = panelView.findViewById<View>(R.id.panel_background) ?: return
        val pulseColor = when (mode) {
            PerformanceMode.POWER_SAVING -> Color.parseColor("#00FFFF")
            PerformanceMode.BALANCED -> Color.parseColor("#00FF41")
            PerformanceMode.PERFORMANCE -> Color.parseColor("#FF00FF")
            PerformanceMode.TURBO -> Color.parseColor("#FFA500")
        }

        panelBackground.animate().scaleX(1.05f).scaleY(1.05f).setDuration(200).withEndAction {
            panelBackground.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
        }.start()
    }

    private fun getBatteryStats(): BatteryStats {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        var status = BatteryManager.BATTERY_STATUS_UNKNOWN
        var temperature = 0
        var voltage = 0
        var health = BatteryManager.BATTERY_HEALTH_UNKNOWN
        var plugged = 0
        var timeRemaining = -1L

        intent?.let {
            status = it.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            temperature = it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10
            voltage = it.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
            health = it.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
            plugged = it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            timeRemaining = batteryManager.computeChargeTimeRemaining()
        }
        return BatteryStats(level, status, temperature, voltage, health, plugged, timeRemaining)
    }

    private fun updateBatteryInfo() {
        val batteryInfo = panelView.findViewById<TextView>(R.id.battery_info) ?: return
        val stats = getBatteryStats()

        val text = buildString {
            append("${stats.temperature}°C\n")
            append("${stats.level}%")
            when (stats.status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> {
                    append(" ⚡")
                    if (stats.timeRemaining > 0) {
                        append(" ${stats.timeRemaining / 3600000}h${(stats.timeRemaining % 3600000) / 60000}m")
                    }
                }
                BatteryManager.BATTERY_STATUS_DISCHARGING -> {
                    if (stats.timeRemaining > 0) {
                        append(" ${stats.timeRemaining / 3600000}h${(stats.timeRemaining % 3600000) / 60000}m")
                    }
                }
                BatteryManager.BATTERY_STATUS_FULL -> append(" Full")
                else -> {}
            }
        }
        batteryInfo.text = text
        batteryInfo.setTextColor(Color.parseColor(if (stats.status == BatteryManager.BATTERY_STATUS_CHARGING) "#00FFFF" else if (stats.level >= 50) "#00FF41" else if (stats.level >= 20) "#FFA500" else "#FF4500"))
    }
}
