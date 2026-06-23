/*
 * Copyright (C) 2026 The XPerience Project
 * SPDX-License-Identifier: Apache-2.0
 */

package mx.xperience.gamespace.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * A scrolling history graph that plots real data values instead of a fake sine wave.
 *
 * Usage: call [pushValue] with a normalized 0..1 float each time a new sample arrives.
 * The view fills its own height with the filled area, so the parent FrameLayout just
 * needs to clip the wave below the text — achieved by placing WaveView at
 * android:layout_gravity="bottom" with a fixed height in the card.
 */
class WaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── paint ──────────────────────────────────────────────────────────────
    private val linePaint = Paint().apply {
        color = Color.parseColor("#4a9cf7")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        isAntiAlias = true
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // ── data ───────────────────────────────────────────────────────────────
    // Circular buffer; one sample per horizontal pixel column
    private val maxSamples = 200
    private val history = ArrayDeque<Float>(maxSamples)

    // Current value 0..1; we keep the last pushed value for smooth redraw
    private var latestNorm = 0f

    // ── public API ─────────────────────────────────────────────────────────

    /**
     * Push a new normalized value [0..1].
     * Call this on the main thread once per stats update tick.
     */
    fun pushValue(normalized: Float) {
        latestNorm = normalized.coerceIn(0f, 1f)
        if (history.size >= maxSamples) history.removeFirst()
        history.addLast(latestNorm)
        invalidate()
    }

    /** Legacy compatibility shim — converts 0..1 amplitude into a data push. */
    fun setWaveAmplitude(targetAmplitude: Float) {
        pushValue(targetAmplitude)
    }

    fun setWaveColor(color: Int) {
        linePaint.color = color
        // Rebuild gradient on next draw
        fillPaint.shader = null
        invalidate()
    }

    // ── drawing ────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildGradient()
    }

    private fun rebuildGradient() {
        if (width == 0 || height == 0) return
        fillPaint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            intArrayOf(
                (linePaint.color and 0x00FFFFFF) or 0x55000000, // ~33% alpha at top
                (linePaint.color and 0x00FFFFFF) or 0x11000000  // ~7% alpha at bottom
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (history.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()
        val samples = history.toList()
        val count = samples.size

        // Gradient needs colour; rebuild if shader not yet set
        if (fillPaint.shader == null) rebuildGradient()

        val path = Path()
        val step = if (count > 1) w / (count - 1) else w

        // Map sample → y: value=1 → y=0 (top), value=0 → y=h (bottom)
        // Leave a small top margin so the peak never clips off the view
        val topPad = 4f
        fun sampleToY(v: Float) = topPad + (1f - v) * (h - topPad)

        path.moveTo(0f, sampleToY(samples[0]))
        for (i in 1 until count) {
            val x = i * step
            // Simple cubic bezier for smoothness
            val prevX = (i - 1) * step
            val cpX = (prevX + x) / 2f
            path.cubicTo(
                cpX, sampleToY(samples[i - 1]),
                cpX, sampleToY(samples[i]),
                x, sampleToY(samples[i])
            )
        }

        // Close bottom for fill
        val fillPath = Path(path)
        fillPath.lineTo(w, h)
        fillPath.lineTo(0f, h)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)
    }
}
