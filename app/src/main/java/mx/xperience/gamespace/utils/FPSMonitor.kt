package mx.xperience.gamespace.utils

import mx.xperience.gamespace.sysfs.SysFsManager
import kotlin.math.roundToInt

class FPSMonitor {
    private var lastFlips = 0L
    private var lastTimeMs = 0L
    private val fpsHistory = mutableListOf<Int>()

    fun start() {
        lastFlips = getSurfaceFlingerFlips()
        lastTimeMs = System.currentTimeMillis()
        fpsHistory.clear()
    }

    fun stop() {}

    fun getCurrentFps(): Int {
        val now = System.currentTimeMillis()
        val flips = getSurfaceFlingerFlips()

        if (flips > 0) {
            val deltaFlips = flips - lastFlips
            val deltaMs = now - lastTimeMs
            
            if (deltaMs > 0 && lastFlips > 0) {
                var fps = ((deltaFlips * 1000.0) / deltaMs).roundToInt()
                fps = fps.coerceIn(0, 120)
                
                fpsHistory.add(fps)
                if (fpsHistory.size > 3) fpsHistory.removeAt(0)
                
                lastFlips = flips
                lastTimeMs = now
                return fpsHistory.average().roundToInt()
            }
            lastFlips = flips
            lastTimeMs = now
        }
        return 0
    }

    private fun getSurfaceFlingerFlips(): Long {
        val out = SysFsManager.readAsRoot("service call SurfaceFlinger 1013")
        try {
            if (out.contains("Result: Parcel(")) {
                val hexStr = out.substringAfter("Parcel(").substringBefore(")").split(" ").filter { it.isNotBlank() }.getOrNull(1)
                if (hexStr != null) {
                    return hexStr.toLong(16)
                }
            }
        } catch (e: Exception) {}
        return 0L
    }
}
