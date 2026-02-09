/*
 * Copyright (C) 2026 The XPerience Project
 * SPDX-License-Identifier: Apache-2.0
 */
package mx.xperience.gamespace

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import mx.xperience.gamespace.controller.PerformanceController
import mx.xperience.gamespace.utils.GameDetector

/**
 * Main GameSpace foreground service.
 * Responsible for overlay lifecycle and service persistence.
 */
class GameSpaceService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var triggerView: View

    private lateinit var controller: PerformanceController
    private lateinit var gameDetector: GameDetector

    override fun onCreate() {
        super.onCreate()

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        controller = PerformanceController(this)
        gameDetector = GameDetector(this)

        controller.onRequestShowTrigger = { showTrigger() }

        createNotificationChannel()
        startForeground(1001, buildNotification())

        initOverlay()
        initTrigger()

        controller.startForegroundMonitoring { packageName ->
            if (gameDetector.isGame(packageName)) {
                controller.onGameEnter(packageName)
                triggerView.visibility = View.VISIBLE
            } else {
                controller.onGameExit()
                triggerView.visibility = View.GONE
                overlayView.visibility = View.GONE
                controller.onGameExit()
            }
        }

    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Inflates and attaches the main GameSpace panel overlay.
     */
    private fun initOverlay() {
        overlayView = LayoutInflater.from(this)
            .inflate(R.layout.overlay_game_panel_original, null)

        controller.bindOverlay(overlayView)
        windowManager.addView(overlayView, controller.windowParams)

        // Root captures outside touches
        overlayView.setOnClickListener {
            overlayView.visibility = View.GONE
            triggerView.visibility = View.VISIBLE
        }

        // Prevent clicks inside panel from closing it
        overlayView.findViewById<View>(R.id.panel_background)
        ?.setOnClickListener {
            // Consume click, do nothing
        }

        overlayView.visibility = View.GONE
    }

    /**
     * Inflates and attaches the floating trigger overlay.
     */
    private fun initTrigger() {
        triggerView = LayoutInflater.from(this)
            .inflate(R.layout.overlay_game_trigger, null)

        windowManager.addView(triggerView, controller.triggerWindowParams)
        triggerView.visibility = View.GONE

        triggerView.setOnClickListener {
            overlayView.visibility = View.VISIBLE
            triggerView.visibility = View.GONE
            controller.onPanelOpened()
        }

    }

    private fun showTrigger() {
        triggerView?.apply {
            visibility = View.VISIBLE
            alpha = 0f
            animate()
            .alpha(1f)
            .setDuration(200)
            .start()
        }
    }

    override fun onDestroy() {
        controller.release()

        if (::overlayView.isInitialized) {
            windowManager.removeView(overlayView)
        }
        if (::triggerView.isInitialized) {
            windowManager.removeView(triggerView)
        }

        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "gamespace",
                "GameSpace Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, "gamespace")
            .setContentTitle("GameSpace")
            .setContentText("Running")
            .setSmallIcon(R.drawable.ic_game_controller)
            .build()
    }
}
