/*
 * Copyright (C) 2026 The XPerience Project
 */
package mx.xperience.gamespace.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import mx.xperience.gamespace.R
import mx.xperience.gamespace.utils.FPSMonitor
import mx.xperience.gamespace.utils.SysfsController

class StatOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var statView: View

    private val handler = Handler(Looper.getMainLooper())
    private val fpsMonitor = FPSMonitor()
    private val sysfsController = SysfsController()

    // Referencias del layout overlay_stat_bar.xml
    private lateinit var tvFps: TextView
    private lateinit var tvCpu: TextView
    private lateinit var tvGpu: TextView

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateStats()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        statView = LayoutInflater.from(this).inflate(R.layout.overlay_stat_bar, null)

        // Asignar IDs (Asegúrate que existan en tu overlay_stat_bar.xml)
        tvFps = statView.findViewById(R.id.overlay_fps)
        tvCpu = statView.findViewById(R.id.overlay_cpu)
        tvGpu = statView.findViewById(R.id.overlay_gpu)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 50 // Ajustar para que no tape la cámara/notch
        }

        windowManager.addView(statView, params)

        fpsMonitor.start()
        handler.post(updateRunnable)
    }

    private fun updateStats() {
        // Usamos la lógica que ya tienes en tus utilidades
        val currentFps = fpsMonitor.getCurrentFps()

        when {
            currentFps >= 90 -> tvFps.setTextColor(Color.parseColor("#00FFFF")) // Cyan neón
            currentFps >= 60 -> tvFps.setTextColor(Color.parseColor("#00FF41")) // Verde neón
            else -> tvFps.setTextColor(Color.RED)
        }

        tvFps.text = "$currentFps FPS"

        // Obtenemos temperaturas/frecuencias de tu SysfsController
        tvCpu.text = "CPU: ${sysfsController.getCpuTemperature()}"

        // Tu SysfsController devuelve un Pair(Freq, Temp) para la GPU
        val gpuData = sysfsController.getGpuFreq()
        tvGpu.text = "GPU: ${gpuData.second}"
    }

    override fun onDestroy() {
        fpsMonitor.stop()
        handler.removeCallbacks(updateRunnable)
        if (::statView.isInitialized) windowManager.removeView(statView)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
