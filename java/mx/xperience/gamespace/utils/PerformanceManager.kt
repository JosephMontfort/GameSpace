package mx.xperience.gamespace.utils

import android.content.Context
import android.net.wifi.WifiManager
import android.provider.Settings
import android.util.Log

import java.io.File

import mx.xperience.gamespace.controller.PerformanceController.PerformanceMode
import mx.xperience.gamespace.sysfs.SysFsManager

/**
 * Applies kernel-level performance tuning via SysFS.
 * This class contains all real performance knobs.
 */
class PerformanceManager(private val context: Context) {

    private var wifiLock: WifiManager.WifiLock? = null

    private val sysfs = SysfsController()

    /**
     * Applies low-level optimizations for a given mode.
     */
    fun applyMode(mode: PerformanceMode) {
        when (mode) {
            PerformanceMode.POWER_SAVING -> {
                sysfs.applyEco()
                setGaming(false)
            }
            PerformanceMode.BALANCED -> {
                sysfs.applyBalanced()
                setGaming(false)
            }
            PerformanceMode.PERFORMANCE -> {
                sysfs.applyPerformance()
                setGaming(true)
            }
            PerformanceMode.TURBO -> {
                sysfs.applyTurbo()
                setGaming(true)
            }
        }
    }

    private fun setGaming(enabled: Boolean) {
        try {
            val wifi =
                context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (enabled) {
                wifiLock = wifi.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "GameSpaceLock"
                )
                wifiLock?.acquire()
                Settings.Global.putInt(
                    context.contentResolver,
                    "wifi_scan_always_enabled",
                    0
                )
            } else {
                wifiLock?.release()
                Settings.Global.putInt(
                    context.contentResolver,
                    "wifi_scan_always_enabled",
                    1
                )
            }
        } catch (e: Exception) {
            Log.e("PerformanceManager", e.message ?: "Unknown error")
        }
    }

    /**
     * Returns CPU usage percentage.
     */
    fun getCpuUsage(): Int {
        try {
            val stat = File("/proc/stat").readLines()[0]
            val parts = stat.split("\\s+".toRegex())
            val idle = parts[4].toLong()
            val total = parts.drop(1).map { it.toLong() }.sum()
            return ((1f - idle.toFloat() / total) * 100).toInt()
        } catch (e: Exception) {
            return 0
        }
    }

    fun release() {
        wifiLock?.release()
        sysfs.applyBalanced()
    }
}
