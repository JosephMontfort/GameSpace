/*
 * Copyright (C) 2026 The XPerience Project
 * SPDX-License-Identifier: Apache-2.0
 */
package mx.xperience.gamespace

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Vinculamos el botón de tu layout (activity_main.xml)
        val btnStart = findViewById<Button>(R.id.btn_start_gs)

        btnStart.setOnClickListener {
            if (checkOverlayPermission()) {
                startGameSpaceService()
            }
        }
    }

    private fun startGameSpaceService() {
        val serviceIntent = Intent(this, GameSpaceService::class.java)

        try {
            // Iniciamos el servicio en modo Foreground (obligatorio para Android moderno)
            startForegroundService(serviceIntent)

            Toast.makeText(this, "GameSpace Engine Iniciado 🚀", Toast.LENGTH_SHORT).show()

            // Opcional: Minimizar la app al iniciar el servicio para ir al home
            moveTaskToBack(true)

        } catch (e: Exception) {
            Toast.makeText(this, "Error al iniciar: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }

    /**
     * Verifica si tenemos permiso para dibujar la "Dynamic Island".
     * Si eres System App con privapp-permissions bien configurado, esto siempre devolverá true.
     */
    private fun checkOverlayPermission(): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Falta permiso de Superposición", Toast.LENGTH_LONG).show()

            // Abrir configuración si falta el permiso (útil para debug)
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return false
        }
        return true
    }
}
