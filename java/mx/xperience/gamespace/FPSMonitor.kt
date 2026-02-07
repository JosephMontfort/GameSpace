package mx.xperience.gamespace

import android.view.Choreographer

class FPSMonitor(private val onFpsUpdated: (Int) -> Unit) : Choreographer.FrameCallback {
    private var frameCount = 0
    private var lastTime = 0L
    private var isRunning = false

    fun start() {
        if (isRunning) return
            isRunning = true
            Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        isRunning = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRunning) return

            frameCount++
            if (lastTime == 0L) lastTime = frameTimeNanos

                val elapsed = frameTimeNanos - lastTime
                if (elapsed >= 1_000_000_000L) { // 1 segundo
                    onFpsUpdated(frameCount)
                    frameCount = 0
                    lastTime = frameTimeNanos
                }
                Choreographer.getInstance().postFrameCallback(this)
    }
}
