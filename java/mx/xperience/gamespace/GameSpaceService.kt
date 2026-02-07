package mx.xperience.gamespace

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service // IMPORTACIÓN EXPLÍCITA
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import mx.xperience.gamespace.utils.GameDetector
import mx.xperience.gamespace.utils.PerformanceManager

// Forzamos la herencia explicita de android.app.Service
class GameSpaceService : android.app.Service() {

    private enum class PerformanceMode {
        POWER_SAVING, BALANCED, PERFORMANCE, TURBO
    }

    private var currentMode = PerformanceMode.BALANCED
    private var wifiLock: WifiManager.WifiLock? = null

    private lateinit var windowManager: WindowManager
    // private lateinit var gameManager: GameManager // Comentado hasta que confirmemos imports
    private lateinit var gameDetector: GameDetector
    private lateinit var perfManager: PerformanceManager

    private var triggerView: View? = null
    private var panelView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isGaming = false
    private var isPanelOpen = false

    // Monitor de FPS
    private val fpsMonitor = FPSMonitor { fps ->
        // Usamos post para asegurar que corra en UI Thread
        mainHandler.post {
            try {
                // Verificamos que la vista y el ID existan antes de asignar
                panelView?.findViewById<TextView>(R.id.fps_counter)?.text = "$fps"
            } catch (e: Exception) {
                // Evitar crash si la vista no está lista
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Casteo explicito del servicio
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        perfManager = PerformanceManager(this)
        gameDetector = GameDetector(this)

        showTrigger()

        // Inicialización segura del GameManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // gameManager = getSystemService(Context.GAME_SERVICE) as GameManager
        }

        // Iniciar servicio en primer plano
        try {
            startForeground(1001, createNotification())
        } catch (e: Exception) {
            e.printStackTrace()
        }

        mainHandler.post(gameCheckRunnable)

        startStatsUpdate()
    }

    private val statsUpdateRunnable = object : Runnable {
        override fun run() {
            if (isGaming) { // Solo si hay un juego detectado
                updateOverlayUI()
            }
            mainHandler.postDelayed(this, 1000) // Actualizar cada segundo
        }
    }

    private fun updateOverlayUI() {
        panelView?.let { view ->
            val fpsTxt = view.findViewById<TextView>(R.id.fps_value)
            val batteryTxt = view.findViewById<TextView>(R.id.battery_info)

            // Usamos el Manager para obtener datos reales
            val fps = perfManager.getHardwareFps()
            val battery = perfManager.getBatteryInfo()

            fpsTxt?.text = if (fps > 0) fps.toString() else "--"
            batteryTxt?.text = battery
        }
    }

    private fun startStatsUpdate() {
        mainHandler.post(statsUpdateRunnable)
        perfManager.setGamingMode(true) // Activar optimizaciones al iniciar
    }

    private val gameCheckRunnable = object : Runnable {
        override fun run() {
            checkCurrentTask()
            mainHandler.postDelayed(this, 2500)
        }
    }

    private fun checkCurrentTask() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        // Verificación nula segura
        val runningProcesses = am.runningAppProcesses ?: return

        val runningApp = runningProcesses.firstOrNull {
            it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }?.processName ?: ""

        if (gameDetector.getGameInfo(runningApp) != null) {
            if (!isGaming) startGamingMode(runningApp)
        } else {
            if (isGaming) stopGamingMode()
        }
    }

    private fun showTrigger() {
        // 1. Eliminar panel si existe
        removePanel()

        // 2. Configurar la pestaña (Trigger)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            100, // Altura pequeña
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START // Ocupa el borde izquierdo
        params.y = 200 // Posición vertical

        // Inflamos una vista simple para el trigger (puedes crear un XML para esto)
        triggerView = View(this).apply {
            setBackgroundColor(Color.parseColor("#00FF41")) // Verde gaming
            // Al tocar la pestaña, abrimos el panel
            setOnClickListener { showPanel() }
        }

        windowManager.addView(triggerView, params)
        isPanelOpen = false
    }

    private fun showPanel() {
        // 1. Quitar la pestaña
        removeTrigger()

        // 2. Configurar el Panel Completo
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, // Para detectar toques fuera
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, // CLAVE para tocar fuera
            PixelFormat.TRANSLUCENT
        )

        val layoutInflater = LayoutInflater.from(this)
        panelView = layoutInflater.inflate(R.layout.overlay_game_panel, null)

        // Contenedor principal del XML (ajusta el ID según tu layout)
        val mainContainer = panelView?.findViewById<View>(R.id.panel_background)

        // Lógica para cerrar al tocar fuera
        panelView?.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE || event.action == MotionEvent.ACTION_DOWN) {
                // Si tocamos el FrameLayout vacío (fuera del cardview), cerramos
                showTrigger()
                true
            } else false
        }

        windowManager.addView(panelView, params)
        isPanelOpen = true
    }

    private fun removeTrigger() {
        triggerView?.let {
            if (it.isAttachedToWindow) windowManager.removeView(it)
                triggerView = null
        }
    }

    private fun removePanel() {
        panelView?.let {
            if (it.isAttachedToWindow) windowManager.removeView(it)
                panelView = null
        }
    }

    // iniciamos el performance
    ///
    private fun initPerformanceControls() {
        // Referencias a los botones
        val btnPowerSaving = mainPanelView?.findViewById<TextView>(R.id.btn_power_saving)
        val btnBalanced = mainPanelView?.findViewById<TextView>(R.id.btn_balanced)
        val btnPerformance = mainPanelView?.findViewById<TextView>(R.id.btn_performance)
        val btnTurbo = mainPanelView?.findViewById<TextView>(R.id.btn_turbo)

        // Configurar listeners
        btnPowerSaving?.setOnClickListener {
            setPerformanceMode(PerformanceMode.POWER_SAVING)
        }

        btnBalanced?.setOnClickListener {
            setPerformanceMode(PerformanceMode.BALANCED)
        }

        btnPerformance?.setOnClickListener {
            setPerformanceMode(PerformanceMode.PERFORMANCE)
        }

        btnTurbo?.setOnClickListener {
            if (!isTurboActive) {
                setPerformanceMode(PerformanceMode.TURBO)
            } else {
                // Si ya está activo, mostrar tiempo restante
                val remainingTime = turboEndTime - System.currentTimeMillis()
                if (remainingTime > 0) {
                    val minutes = (remainingTime / 60000)
                    val seconds = (remainingTime % 60000) / 1000
                    Toast.makeText(
                        this,
                        "Turbo active: ${minutes}m ${seconds}s remaining",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        // Long press para cancelar turbo
        btnTurbo?.setOnLongClickListener {
            if (isTurboActive) {
                cancelTurboMode()
                true
            } else {
                false
            }
        }

        // Aplicar modo actual
        updatePerformanceUI()
    }

    /**
     * Set performance mode with game-specific settings
     */
    private fun setPerformanceMode(mode: PerformanceMode) {
        currentPerformanceMode = mode
        isTurboActive = false // Cancel turbo if switching modes

        // Show animation
        showModeChangeAnimation(mode)

        // Apply performance settings
        applyPerformanceSettings(mode)

        // Update UI
        updatePerformanceUI()

        // Save preference
        savePerformancePreference(mode)

        // Save game-specific setting if a game is running
        currentGamePackage?.let { packageName ->
            saveGameSettings(packageName, mode)
        }

        Log.d("GameSpaceService", "Performance mode set to: $mode")
    }

    private fun toggleWifiOptimization(enable: Boolean) {
        val wifiManager = getSystemService(Context.WIFI_SERVICE) as WifiManager

        if (enable) {
            // Creamos el lock para latencia baja (Android 10+)
            if (wifiLock == null) {
                wifiLock = wifiManager.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "GameSpace:LowLatency"
                )
            }
            wifiLock?.acquire()

            // Desactivamos el escaneo en segundo plano para evitar "lag spikes"
            try {
                Settings.Global.putInt(contentResolver, "wifi_scan_always_enabled", 0)
            } catch (e: Exception) {
                Log.e("GameSpace", "Error desactivando scan: ${e.message}")
            }
        } else {
            if (wifiLock?.isHeld == true) wifiLock?.release()
                Settings.Global.putInt(contentResolver, "wifi_scan_always_enabled", 1)
        }
    }

    private fun setupPanelListeners(view: View) {
        val btnPerf = view.findViewById<TextView>(R.id.btn_performance)
        val btnTurbo = view.findViewById<TextView>(R.id.btn_turbo)
        val btnBalanced = view.findViewById<TextView>(R.id.btn_balanced) // Si tienes este ID

        btnPerf?.setOnClickListener {
            applyPerformanceMode(PerformanceMode.PERFORMANCE)
            updateButtonVisuals(btnPerf, "PERF ACTIVE")
        }

        btnTurbo?.setOnClickListener {
            applyPerformanceMode(PerformanceMode.TURBO)
            Toast.makeText(this, "🔥 Modo TURBO: CPU al máximo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyPerformanceMode(mode: PerformanceMode) {
        currentMode = mode
        when (mode) {
            PerformanceMode.PERFORMANCE -> {
                toggleWifiOptimization(true)
                // Aquí llamarías a la optimización de CPU de tu código
                Log.d("GameSpace", "Modo Rendimiento Activado")
            }
            PerformanceMode.TURBO -> {
                toggleWifiOptimization(true)
                // Forzar frecuencias (requiere root o firma de sistema avanzada)
                Log.d("GameSpace", "Modo Turbo: Overclocking simulado")
            }
            else -> toggleWifiOptimization(false)
        }
    }

    /// finisi perf codes

    private fun startGamingMode(packageName: String) {
        isGaming = true
        /* if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            gameManager.setGameMode(packageName, GameManager.GAME_MODE_PERFORMANCE)
        }
        */
        showOverlay(packageName)
        fpsMonitor.start()
    }

    private fun stopGamingMode() {
        isGaming = false
        removeOverlay()
        fpsMonitor.stop()
    }

    private fun showOverlay(packageName: String) {
        if (panelView != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 50
        }

        // Usamos el contexto del servicio (this) explícitamente
        panelView = LayoutInflater.from(this).inflate(R.layout.overlay_game_panel_original, null)

        val gameInfo = gameDetector.getGameInfo(packageName)
        val gameName = gameInfo?.name ?: "Game Mode"

        panelView?.findViewById<TextView>(R.id.game_name)?.text = gameName

        try {
            windowManager.addView(panelView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeOverlay() {
        panelView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // Vista ya removida
            }
            panelView = null
        }
    }

    private fun createNotification(): Notification {
        val channelId = "gamespace_active"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "GameSpace Active", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("GameSpace Engine")
            .setContentText("Optimizando hardware...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        removeTrigger()
        removePanel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

}
