/*
 * Copyright (C) 2026 The XPerience Project
 * SPDX-License-Identifier: Apache-2.0
 */
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
 */
class PerformanceManager(private val context: Context) {

    private var wifiLock: WifiManager.WifiLock? = null
    private val sysfs = SysfsController()

    fun applyMode(mode: PerformanceMode) {
        when (mode) {
            PerformanceMode.POWER_SAVING  -> { sysfs.applyEco();         setGaming(false) }
            PerformanceMode.BALANCED      -> { sysfs.applyBalanced();     setGaming(false) }
            PerformanceMode.PERFORMANCE   -> { sysfs.applyPerformance();  setGaming(true)  }
            PerformanceMode.TURBO         -> { sysfs.applyTurbo();        setGaming(true)  }
        }
    }

    private fun setGaming(enabled: Boolean) {
        try {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (enabled) {
                // BUG FIX: wifiLock was acquired without checking if already held.
                // Calling acquire() twice on the same lock causes an IllegalStateException.
                if (wifiLock == null) {
                    @Suppress("DEPRECATION") // WIFI_MODE_FULL_HIGH_PERF is fine for root apps
                    wifiLock = wifi.createWifiLock(
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                        "GameSpaceLock"
                    )
                }
                if (wifiLock?.isHeld == false) {
                    wifiLock?.acquire()
                }
                Settings.Global.putInt(context.contentResolver, "wifi_scan_always_enabled", 0)
            } else {
                if (wifiLock?.isHeld == true) {
                    wifiLock?.release()
                }
                Settings.Global.putInt(context.contentResolver, "wifi_scan_always_enabled", 1)
            }
        } catch (e: Exception) {
            Log.e("PerformanceManager", e.message ?: "Unknown error")
        }
    }

    /**
     * BUG FIX: getCpuUsage() read /proc/stat only once and computed usage
     * relative to time=0, always returning ~100% at startup.
     * Correct approach is two reads delta-ed across a short interval.
     * This is a one-shot helper now kept for any future use; the overlay
     * uses real freq values instead so the bug doesn't surface in UI.
     */
    private data class CpuSnapshot(val idle: Long, val total: Long)

    private var lastCpuSnapshot: CpuSnapshot? = null

    fun getCpuUsage(): Int {
        fun snapshot(): CpuSnapshot? = try {
            val parts = File("/proc/stat").readLines()[0]
                .split("\\s+".toRegex()).drop(1).map { it.toLong() }
            CpuSnapshot(idle = parts[3], total = parts.sum())
        } catch (_: Exception) { null }

        val now = snapshot() ?: return 0
        val prev = lastCpuSnapshot
        lastCpuSnapshot = now

        if (prev == null) return 0           // first call: no delta yet
        val deltaTotal = now.total - prev.total
        val deltaIdle  = now.idle  - prev.idle
        if (deltaTotal == 0L) return 0
        return ((1f - deltaIdle.toFloat() / deltaTotal) * 100).toInt().coerceIn(0, 100)
    }

    fun release() {
        if (wifiLock?.isHeld == true) wifiLock?.release()
        sysfs.applyBalanced()
    }
}
