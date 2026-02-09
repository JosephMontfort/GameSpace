/*
 * Copyright (C) 2026 The XPerience Project
 * SPDX-License-Identifier: Apache-2.0
 *
 * For some reason idk why the in game detection using
 * Android apis arent working as expected so use this until i fix them
 */
package mx.xperience.gamespace.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log

class GameDetector(private val context: Context) {

    companion object {
        private const val TAG = "GameDetector"

        // Prefijos de juegos (como Dynamic Island)
        private val GAME_PREFIXES = setOf(
            "com.kurogame",
            "com.mihoyo",
            "com.hoyoverse",
            "com.tencent",
            "com.supercell",
            "com.gameloft",
            "com.ea",
            "com.activision",
            "com.roblox",
            "com.epicgames",
            "com.netease",
            "com.playrix",
            "com.king",
            "com.igg",
            "com.mojang",
            "com.mobilelegends",
            "com.blizzard",
            "com.riotgames",
            "com.pearlabyss",
            "com.krafton",
            "com.levelinfinite",
            "com.zynga",
            "com.nintendo",
            "com.square_enix",
            "com.bandai",
            "com.capcom",
            "com.ubisoft",
            "com.netmarble",
            "com.ncsoft",
            "com.nexon"
        )

        // Paquetes exactos para verificación rápida
        private val EXACT_GAMES = setOf(
            "com.kurogame.wutheringwaves.global",
            "com.miHoYo.GenshinImpact",
            "com.miHoYo.hkrpg",
            "com.activision.callofduty.shooter",
            "com.epicgames.fortnite",
            "com.roblox.client",
            "com.mojang.minecraftpe",
            "com.supercell.clashofclans",
            "com.supercell.clashroyale"
        )

        // Prefijos de sistema (NO son juegos)
        private val SYSTEM_PREFIXES = setOf(
            "android",
            "com.android",
            "com.google.android",
            "com.qualcomm",
            "com.samsung",
            "com.oneplus",
            "com.xiaomi",
            "com.huawei",
            "com.lge",
            "com.motorola",
            "com.asus",
            "com.oppo",
            "com.vivo",
            "com.realme"
        )
    }

    data class GameInfo(
        val packageName: String,
        val name: String,
        val icon: Drawable?,
        val isSystemApp: Boolean = false
    )

    /**
     * Verifica si un paquete es un juego
     * Usa el MISMO sistema que Dynamic Island (prefijos)
     */
    fun isGame(packageName: String): Boolean {
        // 1. Verificar si es paquete del sistema
        if (isSystemPackage(packageName)) {
            return false
        }

        // 2. Verificar paquetes exactos
        if (EXACT_GAMES.contains(packageName)) {
            Log.d(TAG, "✅ Juego exacto: $packageName")
            return true
        }

        // 3. Verificar por prefijos (MÉTODO QUE FUNCIONA)
        val isGameByPrefix = GAME_PREFIXES.any { prefix ->
            packageName.startsWith("$prefix.")
        }

        if (isGameByPrefix) {
            Log.d(TAG, "✅ Juego por prefijo: $packageName")
            return true
        }

        // 4. Heurística adicional
        val lowerPackage = packageName.lowercase()
        if (lowerPackage.contains("game") ||
            lowerPackage.contains("play") ||
            lowerPackage.contains("gaming") ||
            lowerPackage.contains("arcade") ||
            lowerPackage.contains("casino")) {
            Log.d(TAG, "⚠️ Posible juego por nombre: $packageName")
            return true
            }

            return false
    }

    private fun isSystemPackage(packageName: String): Boolean {
        return SYSTEM_PREFIXES.any { packageName.startsWith("$it.") }
    }

    /**
     * Detecta todos los juegos instalados
     */
    fun detectGames(): List<GameInfo> {
        val games = mutableListOf<GameInfo>()

        try {
            val pm = context.packageManager
            val packages = pm.getInstalledPackages(PackageManager.GET_META_DATA)

            Log.d(TAG, "📦 Analizando ${packages.size} paquetes instalados...")

            for (pkg in packages) {
                val packageName = pkg.packageName

                if (isGame(packageName)) {
                    try {
                        val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            pm.getApplicationInfo(
                                packageName,
                                PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                        }

                        val game = GameInfo(
                            packageName = packageName,
                            name = pm.getApplicationLabel(appInfo).toString(),
                                            icon = try {
                                                pm.getApplicationIcon(packageName)
                                            } catch (e: Exception) {
                                                null
                                            },
                                            isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        )

                        games.add(game)
                        Log.d(TAG, "🎯 Juego: ${game.name} (${game.packageName})")

                    } catch (e: PackageManager.NameNotFoundException) {
                        // Ignorar y continuar
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error detectando juegos: ${e.message}")
        }

        Log.d(TAG, "✅ Total juegos detectados: ${games.size}")
        return games
    }

    /**
     * Verifica si hay juegos instalados
     */
    fun hasGames(): Boolean {
        return detectGames().isNotEmpty()
    }

    /**
     * Obtiene información de un juego específico
     */
    fun getGameInfo(packageName: String): GameInfo? {
        return detectGames().find { it.packageName == packageName }
    }
}
