/*
 * Copyright (C) 2026 The XPerience Project
 * SPDX-License-Identifier: Apache-2.0
 */
package mx.xperience.gamespace.utils

import android.view.Choreographer
import java.io.File
import kotlin.math.roundToInt

class FPSMonitor {

    private val advancedFpsPath =
    "/sys/class/drm/sde-crtc-0/measured_fps"

    // === Fallbacks ===
    private val fallbackPaths = arrayOf(
        "/sys/class/graphics/fb0/fps",
        "/sys/class/drm/card0/sde_crtc_fps"
    )

    // === Choreographer fallback ===
    private var lastFrameTimeNs = 0L
    private var frameCount = 0
    private var choreoFps = 60

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (lastFrameTimeNs > 0) {
                frameCount++
                val delta = frameTimeNanos - lastFrameTimeNs

                if (delta >= 1_000_000_000L) {
                    val fps =
                    (frameCount * 1_000_000_000.0 / delta).roundToInt()

                    choreoFps = fps.coerceIn(30, 240)

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
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun stop() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    /**
     * Returns REAL FPS.
     * Priority: Advanced sysfs → fallback sysfs → Choreographer.
     */
    fun getCurrentFps(): Int {
        readFps(advancedFpsPath)?.let { return it }

        for (path in fallbackPaths) {
            readFps(path)?.let { return it }
        }

        return choreoFps
    }

    private fun readFps(path: String): Int? {
        return try {
            val file = File(path)
            if (!file.exists()) return null

                val text = file.readText().trim()
            var value = 0f
            if (text.contains("fps:")) {
                value = text.substringAfter("fps:").trim().substringBefore(" ").toFloatOrNull() ?: 0f
            } else {
                value = text.toFloatOrNull() ?: 0f
            }
            var intValue = value.roundToInt()
            
            // Compensate for 240Hz TE heartbeat signals
            if (intValue > 120) intValue /= 2
            
            if (intValue in 10..120) intValue else null
        } catch (_: Exception) {
            null
        }
    }
}
