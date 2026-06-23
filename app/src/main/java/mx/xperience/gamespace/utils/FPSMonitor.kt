package mx.xperience.gamespace.utils

import android.view.Choreographer
import mx.xperience.gamespace.sysfs.SysFsManager
import kotlin.math.roundToInt

class FPSMonitor {

    private val advancedFpsPath = "/sys/class/drm/sde-crtc-0/measured_fps"
    private val fallbackPaths = arrayOf(
        "/sys/class/graphics/fb0/fps",
        "/sys/class/drm/card0/sde_crtc_fps"
    )

    private var choreoFps = 60
    private var lastFrameTimeNs = 0L
    private var frameCount = 0
    private val fpsHistory = mutableListOf<Int>()

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (lastFrameTimeNs > 0) {
                frameCount++
                val delta = frameTimeNanos - lastFrameTimeNs
                if (delta >= 1_000_000_000L) {
                    val fps = (frameCount * 1_000_000_000.0 / delta).roundToInt()
                    choreoFps = fps.coerceIn(10, 120)
                    frameCount = 0
                    lastFrameTimeNs = frameTimeNanos
                }
            } else {
                lastFrameTimeNs = frameTimeNanos
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() {
        lastFrameTimeNs = 0
        frameCount = 0
        fpsHistory.clear()
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun stop() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    fun getCurrentFps(): Int {
        var rawFps = readFpsViaRoot(advancedFpsPath)
        
        if (rawFps == null) {
            for (path in fallbackPaths) {
                rawFps = readFpsViaRoot(path)
                if (rawFps != null) break
            }
        }
        
        val currentFps = rawFps ?: choreoFps

        // Smooth Rolling Average
        fpsHistory.add(currentFps)
        if (fpsHistory.size > 3) fpsHistory.removeAt(0)
        
        return fpsHistory.average().roundToInt()
    }

    private fun readFpsViaRoot(path: String): Int? {
        return try {
            // Read hardware node safely via the Root Pipe, bypassing SELinux blocks
            val text = SysFsManager.readAsRoot(path).trim()
            if (text.isEmpty()) return null

            var value = 0f
            if (text.contains("fps:")) {
                value = text.substringAfter("fps:").trim().substringBefore(" ").toFloatOrNull() ?: 0f
            } else {
                value = text.toFloatOrNull() ?: 0f
            }
            
            var intValue = value.roundToInt()
            
            // Divide out the 240Hz hardware touch pulse
            if (intValue >= 230) {
                intValue /= 2
            }
            
            // Hard clamp to panel limit
            if (intValue > 120) {
                intValue = 120
            }

            if (intValue >= 10) intValue else null
        } catch (_: Exception) {
            null
        }
    }
}
