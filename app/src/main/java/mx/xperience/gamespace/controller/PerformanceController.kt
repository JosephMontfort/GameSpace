package mx.xperience.gamespace.controller

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
    var onRequestCollapse: (() -> Unit)? = null

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

    // BUG FIX: statsRunnable was posted inside startFpsUpdates() but stopFpsUpdates()
    // called removeCallbacksAndMessages(null) which also wiped the foreground monitoring
    // runnable, stopping package detection. Use a dedicated runnable reference instead.
    private val statsRunnable = object : Runnable {
        override fun run() {
            cpuUpdateUI()

            val currentFps = fpsMonitor.getCurrentFps()
            fpsText?.text = currentFps.toString()

            // pushValue drives the real scrolling history graph
            panelView.findViewById<WaveView>(R.id.fps_chart)?.let { wave ->
                // Normalize to 0..1 against 120 fps ceiling; never floor at 0.1
                // to allow the graph to actually reach bottom on low fps
                wave.pushValue((currentFps / 120f).coerceIn(0f, 1f))
                wave.setWaveColor(Color.parseColor("#00FFFF"))
            }

            gpuUpdateUI()
            updateRamUI()
            updateBatteryInfo()
            handler.postDelayed(this, 1000)
        }
    }

    fun bindOverlay(view: View) {
        panelView = view
        fpsText = view.findViewById(R.id.fps_counter)

        view.findViewById<TextView>(R.id.btn_power_saving)?.setOnClickListener { setMode(PerformanceMode.POWER_SAVING, true) }
        view.findViewById<TextView>(R.id.btn_balanced)?.setOnClickListener    { setMode(PerformanceMode.BALANCED, true) }
        view.findViewById<TextView>(R.id.btn_performance)?.setOnClickListener { setMode(PerformanceMode.PERFORMANCE, true) }
        view.findViewById<TextView>(R.id.btn_turbo)?.setOnClickListener       { setMode(PerformanceMode.TURBO, true) }

        windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = 0; y = 0
        }

        panelView.setOnClickListener { onRequestCollapse?.invoke() }
        panelView.findViewById<View>(R.id.panel_background)?.setOnClickListener { }
    }

    fun startForegroundMonitoring(onPackage: (String) -> Unit) {
        // Kept as its own separate runnable — not mixed with stats runnable
        handler.post(object : Runnable {
            override fun run() {
                getForegroundPackage()?.let(onPackage)
                handler.postDelayed(this, 1000)
            }
        })
    }

    fun updatePanelGravity() {
        val dm      = context.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val density = dm.density

        val dotX    = triggerWindowParams.x
        val dotY    = triggerWindowParams.y
        val dotSize = (48 * density).toInt()

        val isLeft   = dotX + dotSize / 2 < screenW / 2
        val isBottom = dotY + dotSize / 2 > screenH / 2

        val panelBg = panelView.findViewById<View>(R.id.panel_background) ?: return
        val params  = panelBg.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return

        // Equal margin on all sides — MaxHeightScrollView caps height via onMeasure
        val edgeMargin = (16 * density).toInt()

        params.gravity = (if (isBottom) Gravity.BOTTOM else Gravity.TOP) or
                         (if (isLeft)   Gravity.START  else Gravity.END)

        if (isLeft) { params.leftMargin  = edgeMargin; params.rightMargin = 0 }
        else        { params.leftMargin  = 0;           params.rightMargin = edgeMargin }
        params.topMargin    = edgeMargin
        params.bottomMargin = edgeMargin

        panelBg.layoutParams = params
    }

    fun onPanelOpened() {
        updatePanelGravity()
        fpsMonitor.start()
        handler.post(statsRunnable)
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
        stopStatsUpdates()
    }

    private fun setMode(mode: PerformanceMode, fromUser: Boolean = false) {
        if (mode == currentMode && fromUser) return   // BUG FIX: allow forced re-apply on enter
        currentMode = mode
        perfManager.applyMode(mode)
        updatePerformanceUI()
        showModeChangeAnimation(mode)
    }

    private fun stopStatsUpdates() {
        // BUG FIX: only remove the stats runnable, not ALL callbacks
        // (which would kill the foreground monitoring runnable too)
        handler.removeCallbacks(statsRunnable)
        fpsMonitor.stop()
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
        stopStatsUpdates()
        handler.removeCallbacksAndMessages(null) // safe here — service is dying
        perfManager.release()
    }

    private fun updateRamUI() {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val totalGB = memoryInfo.totalMem / (1024.0 * 1024 * 1024)
        val usedGB  = (memoryInfo.totalMem - memoryInfo.availMem) / (1024.0 * 1024 * 1024)
        val percent = ((usedGB / totalGB) * 100).toInt()
        panelView.findViewById<TextView>(R.id.ram_info)?.text =
            String.format("%.1f/%.1f GB", usedGB, totalGB)
        val ramPercent = panelView.findViewById<TextView>(R.id.ram_percent)
        ramPercent?.text = "$percent%"
        panelView.findViewById<ProgressBar>(R.id.ram_progress)?.progress = percent
        ramPercent?.setTextColor(Color.parseColor(when {
            percent > 90 -> "#FF4500"
            percent > 75 -> "#FFA500"
            else         -> "#FFD700"
        }))
    }

    private fun gpuUpdateUI() {
        val gpuFreq = sysfsController.getGpuFreq()
        panelView.findViewById<TextView>(R.id.gpu_val)?.text  = "${gpuFreq.first} MHz"
        panelView.findViewById<TextView>(R.id.gpu_temp)?.text = gpuFreq.second
        panelView.findViewById<WaveView>(R.id.gpu_chart)?.let { wave ->
            // Normalize GPU freq against 1000 MHz ceiling
            wave.pushValue((gpuFreq.first / 1000f).coerceIn(0f, 1f))
            wave.setWaveColor(Color.parseColor("#f74a7b"))
        }
    }

    private fun cpuUpdateUI() {
        if (cpuLittleMaxLimit == 0L || cpuBigMaxLimit == 0L) {
            val limits = sysfsController.getCpuClusterMaxLimits()
            cpuLittleMaxLimit = limits.first
            cpuBigMaxLimit    = limits.second
        }
        val freqs = sysfsController.getCpuClusterFreqs()
        val temp  = sysfsController.getCpuTemperature()

        panelView.findViewById<TextView>(R.id.cpu_little_val)?.text =
            String.format("%.2f", freqs.first / 1_000_000.0) + " GHz"
        panelView.findViewById<TextView>(R.id.cpu_little_temp)?.text = temp
        panelView.findViewById<WaveView>(R.id.cpu_little_chart)?.let { wave ->
            if (cpuLittleMaxLimit > 0)
                wave.pushValue((freqs.first.toFloat() / cpuLittleMaxLimit).coerceIn(0f, 1f))
            wave.setWaveColor(Color.parseColor("#00FF41"))
        }

        panelView.findViewById<TextView>(R.id.cpu_big_val)?.text =
            String.format("%.2f", freqs.second / 1_000_000.0) + " GHz"
        panelView.findViewById<TextView>(R.id.cpu_big_temp)?.text = temp
        panelView.findViewById<WaveView>(R.id.cpu_big_chart)?.let { wave ->
            if (cpuBigMaxLimit > 0)
                wave.pushValue((freqs.second.toFloat() / cpuBigMaxLimit).coerceIn(0f, 1f))
            wave.setWaveColor(Color.parseColor("#FF00FF"))
        }
    }

    private fun updateGameName(pkg: String) {
        val gameNameView = panelView.findViewById<TextView>(R.id.game_name) ?: return
        try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(pkg, 0)
            gameNameView.text = pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            gameNameView.text = pkg
        }
    }

    private fun updatePerformanceUI() {
        listOf(
            R.id.btn_power_saving to PerformanceMode.POWER_SAVING,
            R.id.btn_balanced     to PerformanceMode.BALANCED,
            R.id.btn_performance  to PerformanceMode.PERFORMANCE,
            R.id.btn_turbo        to PerformanceMode.TURBO
        ).forEach { (id, mode) ->
            val btn = panelView.findViewById<TextView>(id) ?: return@forEach
            val isActive = (mode == currentMode)
            btn.background = context.getDrawable(
                if (isActive) R.drawable.bg_mode_selected else R.drawable.bg_mode_normal
            )
            btn.setTextColor(Color.parseColor(if (isActive) when (mode) {
                PerformanceMode.POWER_SAVING -> "#00FFFF"
                PerformanceMode.BALANCED     -> "#00FF41"
                PerformanceMode.PERFORMANCE  -> "#FF00FF"
                PerformanceMode.TURBO        -> "#FFA500"
            } else "#888888"))
        }
    }

    private fun showModeChangeAnimation(mode: PerformanceMode) {
        val bg = panelView.findViewById<View>(R.id.panel_background) ?: return
        bg.animate().scaleX(1.04f).scaleY(1.04f).setDuration(150).withEndAction {
            bg.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
        }.start()
    }

    private fun getBatteryStats(): BatteryStats {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        var status = BatteryManager.BATTERY_STATUS_UNKNOWN
        var temperature = 0; var voltage = 0
        var health = BatteryManager.BATTERY_HEALTH_UNKNOWN
        var plugged = 0; var timeRemaining = -1L
        intent?.let {
            status      = it.getIntExtra(BatteryManager.EXTRA_STATUS,      BatteryManager.BATTERY_STATUS_UNKNOWN)
            temperature = it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10
            voltage     = it.getIntExtra(BatteryManager.EXTRA_VOLTAGE,     0)
            health      = it.getIntExtra(BatteryManager.EXTRA_HEALTH,      BatteryManager.BATTERY_HEALTH_UNKNOWN)
            plugged     = it.getIntExtra(BatteryManager.EXTRA_PLUGGED,     0)
            timeRemaining = bm.computeChargeTimeRemaining()
        }
        return BatteryStats(level, status, temperature, voltage, health, plugged, timeRemaining)
    }

    private fun updateBatteryInfo() {
        val batteryInfo = panelView.findViewById<TextView>(R.id.battery_info) ?: return
        val stats = getBatteryStats()
        val text = buildString {
            append("${stats.temperature}°C  ")
            append("${stats.level}%")
            when (stats.status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> {
                    append(" ⚡")
                    if (stats.timeRemaining > 0) {
                        val h = stats.timeRemaining / 3_600_000
                        val m = (stats.timeRemaining % 3_600_000) / 60_000
                        append(" ${h}h${m}m")
                    }
                }
                BatteryManager.BATTERY_STATUS_DISCHARGING -> {
                    if (stats.timeRemaining > 0) {
                        val h = stats.timeRemaining / 3_600_000
                        val m = (stats.timeRemaining % 3_600_000) / 60_000
                        append(" ${h}h${m}m")
                    }
                }
                BatteryManager.BATTERY_STATUS_FULL -> append(" Full")
                else -> {}
            }
        }
        batteryInfo.text = text
        batteryInfo.setTextColor(Color.parseColor(
            when {
                stats.status == BatteryManager.BATTERY_STATUS_CHARGING -> "#00FFFF"
                stats.level >= 50 -> "#00FF41"
                stats.level >= 20 -> "#FFA500"
                else              -> "#FF4500"
            }
        ))
    }

    fun createTriggerParams(): WindowManager.LayoutParams {
        val dm       = context.resources.displayMetrics
        val viewSize = (48 * dm.density).toInt()
        val screenW  = dm.widthPixels
        val screenH  = dm.heightPixels
        val defaultY = (screenH * 0.3f).toInt()  // 30% down from top

        // Clamp saved position to current screen so a stale landscape X doesn't
        // misplace the dot in portrait (and vice versa). If saved X is clearly
        // off-screen for the current orientation, discard it and use the default.
        val rawSavedX = prefs.getInt("trigger_x", screenW - viewSize)
        val rawSavedY = prefs.getInt("trigger_y", defaultY)

        // Snap saved X to nearest edge (dot should always be on an edge, never floating)
        val savedX = when {
            rawSavedX > screenW - viewSize - viewSize -> screenW - viewSize  // was on right edge
            rawSavedX < viewSize                      -> 0                   // was on left edge
            else                                      -> screenW - viewSize  // stale/unknown → right edge
        }
        val savedY = rawSavedY.coerceIn(0, screenH - viewSize)

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = savedX
            y = savedY
        }
    }

    fun enableTriggerDrag(triggerView: View, windowManager: WindowManager) {
        var initialX = 0; var initialY = 0
        var initialTouchX = 0f; var initialTouchY = 0f
        var isDragging = false
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        // FIX: do NOT cache screenW/screenH here. The trigger is set up once but the
        // game can run in landscape — the cached portrait dimensions make the right-edge
        // clamp stop at the landscape centre (portrait width ≈ landscape height) and the
        // dot can never reach the actual right edge in landscape.
        // Read fresh from resources on every MOVE and UP event instead.

        triggerView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = triggerWindowParams.x; initialY = triggerWindowParams.y
                    initialTouchX = event.rawX;       initialTouchY = event.rawY
                    isDragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (!isDragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop))
                        isDragging = true
                    if (isDragging) {
                        // Fresh metrics every move so landscape width is correct
                        val dm = context.resources.displayMetrics
                        triggerWindowParams.x = (initialX + dx).toInt()
                            .coerceIn(0, dm.widthPixels - triggerView.width)
                        triggerWindowParams.y = (initialY + dy).toInt()
                            .coerceIn(0, dm.heightPixels - triggerView.height)
                        windowManager.updateViewLayout(triggerView, triggerWindowParams)
                        true
                    } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        val dm = context.resources.displayMetrics
                        val screenW = dm.widthPixels
                        // Snap to nearest horizontal edge
                        val isLeft = triggerWindowParams.x + triggerView.width / 2 < screenW / 2
                        triggerWindowParams.x = if (isLeft) 0 else screenW - triggerView.width
                        windowManager.updateViewLayout(triggerView, triggerWindowParams)
                        prefs.edit()
                            .putInt("trigger_x", triggerWindowParams.x)
                            .putInt("trigger_y", triggerWindowParams.y)
                            .apply()
                        true
                    } else false
                }
                else -> false
            }
        }
    }
}
