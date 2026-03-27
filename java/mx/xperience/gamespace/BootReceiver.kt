/*
 * Copyright (C) 2026 The XPerience Project
 * SPDX-License-Identifier: Apache-2.0
 */
package mx.xperience.gamespace

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("GameSpace", "🔄 BootReceiver recibido: ${intent.action}")

        // Check if the device has finished the SetupWizard
        // 0 = Not provisioned, 1 = Provisioned
        val isProvisioned = Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.DEVICE_PROVISIONED, 0
        ) != 0

        if (!isProvisioned) {
            Log.w("GameSpace", "⚠️ SetupWizard in progress. Skipping service start to prevent crash.")
            return
        }

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {

                val serviceIntent = Intent(context, GameSpaceService::class.java)

                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    Log.d("GameSpace", "✅ GameSpace iniciado al arrancar")
                } catch (e: Exception) {
                    Log.e("GameSpace", "❌ Error starting service: ${e.message}")
                }
            }
        }
    }
}
