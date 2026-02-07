package mx.xperience.gamespace.controller

import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import mx.xperience.gamespace.R
import mx.xperience.gamespace.utils.FPSMonitor
import mx.xperience.gamespace.utils.PerformanceManager

/**
 * Controls performance modes, foreground detection and overlay updates.
 */
class PerformanceController(private val context: Context) {

    enum class PerformanceMode {
        POWER_SAVING,
        BALANCED,
        PERFORMANCE,
        TURBO
    }

    private val handler = Handler(Looper.getMainLooper())
    private val perfManager = PerformanceManager(context)
    private val fpsMonitor = FPSMonitor(context)

    private var currentGame: String? = null
    private var currentMode = PerformanceMode.BALANCED

    private lateinit var fpsText: TextView
    private lateinit var waveView: View

    lateinit var windowParams: WindowManager.LayoutParams
        private set

    lateinit var triggerWindowParams: WindowManager.LayoutParams
        private set

    private lateinit var panelView: View

    /**
     * Binds overlay UI and initializes window parameters.
     */
    fun bindOverlay(view: View) {
        fpsText = view.findViewById(R.id.fps_counter)
        waveView = view.findViewById(R.id.cpu_chart)
        panelView = view

        windowParams = createOverlayParams(Gravity.TOP or Gravity.END, 20, 200)
        triggerWindowParams = createOverlayParams(Gravity.CENTER_VERTICAL or Gravity.START, 20, 0)
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

    /**
     * Called when a game enters foreground.
     */
    fun onGameEnter(pkg: String) {
        if (pkg == currentGame) return

        currentGame = pkg
        setMode(PerformanceMode.PERFORMANCE)
        startFpsUpdates()
    }

    /**
     * Called when leaving a game.
     */
    fun onGameExit() {
        if (currentGame == null) return

        currentGame = null
        setMode(PerformanceMode.BALANCED)
        stopFpsUpdates()
    }

    /**
     * Applies a performance mode.
     */
    private fun setMode(mode: PerformanceMode) {
        if (mode == currentMode) return
        currentMode = mode
        perfManager.applyMode(mode)
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
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
            this.x = x
            this.y = y
        }
    }
}
