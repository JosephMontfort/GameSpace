/*
 * Copyright (C) 2026 The XPerience Project
 */

package mx.xperience.gamespace.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

class WaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val wavePaint = Paint().apply {
        color = Color.parseColor("#4a9cf7") // 00FF41 Color verde neón
        style = Paint.Style.STROKE // Solo el borde de la onda
        strokeWidth = 3f // Grosor de la línea
        isAntiAlias = true // Bordes suaves
    }

    private val fillPaint = Paint().apply {
        color = Color.parseColor("#334a9cf7") // 3300FF41Relleno verde translúcido
        style = Paint.Style.FILL // Relleno de la onda
        isAntiAlias = true
    }

    private val path = Path()
    private var phase = 0f // Para animar el movimiento de la onda
    private var amplitude = 0.5f // Amplitud de la onda (0.0 a 1.0)

    init {
        // Habilitar la animación de dibujo (necesario para invalidate())
        setWillNotDraw(false)
    }

    fun setWaveAmplitude(targetAmplitude: Float) {
        // Asegurarse de que la amplitud esté entre 0 y 1
        this.amplitude = targetAmplitude
        invalidate() // Redibujar la vista
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()

        path.reset() // Limpiar la forma anterior

        // Empezar la onda fuera de la vista a la izquierda
        path.moveTo(-width, height * (1 - amplitude))

        // Dibujar la onda
        for (i in 0..width.toInt() + width.toInt()) {
            val x = i.toFloat()
            // Función seno para la forma de la onda
            val y = height * (1 - amplitude) + (height / 2 * amplitude * sin((x + phase) * (2 * Math.PI) / width * 3)).toFloat()
            path.lineTo(x, y)
        }

        // Cierra la forma para el relleno
        path.lineTo(width, height)
        path.lineTo(0f, height)
        path.close()

        canvas.drawPath(path, fillPaint) // Dibujar el relleno
        canvas.drawPath(path, wavePaint) // Dibujar la línea de la onda

        // Animar la fase para que la onda se mueva
        phase += 2f // Ajusta este valor para la velocidad de la onda
        if (phase > width * 3) { // Si la fase se sale, reiniciarla
            phase = 0f
        }
        invalidate() // Pide un nuevo dibujo para la animación
    }

    fun setWaveColor(color: Int) {
        wavePaint.color = color
        invalidate()
    }
}