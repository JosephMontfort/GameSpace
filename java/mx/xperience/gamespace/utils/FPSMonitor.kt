package mx.xperience.gamespace.utils

import android.content.Context
import android.view.Choreographer
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * FPS monitor using kernel sysfs and Choreographer fallback.
 */
class FPSMonitor(context: Context) {

    private val fpsValue = AtomicInteger(0)

    private val fpsPaths = arrayOf(
        "/sys/class/drm/card0/sde_crtc_fps",
        "/sys/class/graphics/fb0/fps"
    )

    init {
        Choreographer.getInstance().postFrameCallback(object :
        Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                fpsValue.incrementAndGet()
                Choreographer.getInstance().postFrameCallback(this)
            }
        })
    }

    fun getCurrentFps(): Int {
        readKernelFps()?.let { return it }
        val fps = fpsValue.getAndSet(0)
        return if (fps > 0) fps else 60
    }

    private fun readKernelFps(): Int? {
        for (path in fpsPaths) {
            try {
                val file = File(path)
                if (file.exists()) {
                    return file.readText().trim().toInt()
                }
            } catch (_: Exception) {
            }
        }
        return null
    }
}
