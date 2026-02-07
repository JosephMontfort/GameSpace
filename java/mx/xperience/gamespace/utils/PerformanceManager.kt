package mx.xperience.gamespace.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.io.File

class PerformanceManager(private val context: Context) {

    enum class PerformanceMode { POWER_SAVING, BALANCED, PERFORMANCE, TURBO }

    private var wifiLock: WifiManager.WifiLock? = null

        // Rutas de sistema para FPS (Hardware)
        private val FPS_PATHS = arrayOf(
            "/sys/class/drm/card0/sde_crtc_fps",
            "/sys/class/graphics/fb0/fps",
            "/sys/class/drm/sde-crtc-0/measured_fps"
        )

        /**
         * Lee los FPS directamente de los archivos de kernel
         */
        fun getHardwareFps(): Int {
            for (path in FPS_PATHS) {
                try {
                    val file = File(path)
                    if (file.exists()) {
                        val fps = file.readText().trim().toIntOrNull() ?: 0
                        if (fps > 0) return fps
                    }
                } catch (e: Exception) { continue }
            }
            return 0
        }

        /**
         * Obtiene el estado detallado de la batería
         */
        fun getBatteryInfo(): String {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: 0
            val temp = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10
            return "$level% ${temp}°C"
        }

        /**
         * Aplica optimizaciones de red y energía
         */
        fun setGamingMode(enabled: Boolean) {
            try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

                if (enabled) {
                    // Bloqueo de WiFi para baja latencia
                    if (wifiLock == null) {
                        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "GameSpaceLock")
                    }
                    wifiLock?.acquire()

                    // Desactivar escaneo WiFi en segundo plano (Requiere WRITE_SECURE_SETTINGS)
                    if (hasSecureSettingsPermission()) {
                        Settings.Global.putInt(context.contentResolver, "wifi_scan_always_enabled", 0)
                    }
                } else {
                    wifiLock?.release()
                    if (hasSecureSettingsPermission()) {
                        Settings.Global.putInt(context.contentResolver, "wifi_scan_always_enabled", 1)
                    }
                }
            } catch (e: Exception) {
                Log.e("PerformanceManager", "Error en optimización: ${e.message}")
            }
        }

        private fun hasSecureSettingsPermission(): Boolean {
            return context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        }
}
