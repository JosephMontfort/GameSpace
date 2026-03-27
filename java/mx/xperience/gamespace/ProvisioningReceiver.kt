/*
 * Copyright (C) 2026 The XPerience Project
 * English comments for code compliance.
 */
package mx.xperience.gamespace

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

class ProvisioningReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Renamed to 'receivedAction' to avoid shadowing issues with Intent.action
        val receivedAction = intent.action
        Log.d("GameSpace", "Received provisioning check: $receivedAction")

        val isProvisioned = Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.DEVICE_PROVISIONED, 0
        ) != 0

        // If provisioned and we receive a valid trigger, start the engine
        if (isProvisioned) {
            val serviceIntent = Intent(context, GameSpaceService::class.java).apply {
                // Using the setter explicitly is safer in Kotlin blocks
                setAction("mx.xperience.gamespace.START")
            }

            try {
                // Since we are coreApp and system UID, we have higher priority
                context.startForegroundService(serviceIntent)
                Log.d("GameSpace", "GameSpaceService auto-started after provisioning/unlock.")
            } catch (e: Exception) {
                Log.e("GameSpace", "Failed to auto-start: ${e.message}")
            }
        }
    }
}
