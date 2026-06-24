/*
 * Copyright (C) 2026 The XPerience Project
 * SPDX-License-Identifier: Apache-2.0
 */
package mx.xperience.gamespace

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private var gameAdapter: GameAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Edge-to-edge insets
        val mainLayout = findViewById<View>(R.id.main_container)
        ViewCompat.setOnApplyWindowInsetsListener(mainLayout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(this, "Please grant Display Over Other Apps permission", Toast.LENGTH_LONG).show()
            return
        }
        startService(Intent(this, GameSpaceService::class.java))
        setupGameGrid()
    }

    private fun setupGameGrid() {
        val recyclerView = findViewById<RecyclerView>(R.id.game_recycler_view)
        recyclerView.layoutManager = GridLayoutManager(this, 3)

        val pm = packageManager
        val manualGames = getManualGames()

        val games = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { app ->
                val isGame = app.category == ApplicationInfo.CATEGORY_GAME ||
                        (app.flags and ApplicationInfo.FLAG_IS_GAME) != 0
                val isManual = manualGames.contains(app.packageName)
                val isNotMe  = app.packageName != packageName
                (isGame || isManual) && isNotMe
            }
            .map { app ->
                GameModel(
                    name        = pm.getApplicationLabel(app).toString(),
                    packageName = app.packageName,
                    icon        = pm.getApplicationIcon(app)
                )
            }
            .sortedBy { it.name.lowercase() }

        // Update game count label
        findViewById<TextView>(R.id.game_count_label)?.text =
            "${games.size} game${if (games.size != 1) "s" else ""}"

        gameAdapter = GameAdapter(
            games.toMutableList(),
            onClick      = { pkg -> launchGame(pkg) },
            onLongClick  = { pkg ->
                if (getManualGames().contains(pkg)) showRemoveDialog(pkg)
                else Toast.makeText(this, getString(R.string.system_games_no_remove), Toast.LENGTH_SHORT).show()
            },
            onAddClick   = {
                Toast.makeText(this, getString(R.string.open_app_picker_dialog), Toast.LENGTH_SHORT).show()
                showAddGameDialog()
            }
        )
        recyclerView.adapter = gameAdapter
    }

    private fun launchGame(pkg: String) {
        showOptimizationToast()
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "cmd game mode performance $pkg")).waitFor()
        } catch (e: Exception) { e.printStackTrace() }

        Handler(Looper.getMainLooper()).postDelayed({
            packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
        }, 500)
    }

    private fun showOptimizationToast() {
        val layout = layoutInflater.inflate(R.layout.layout_opt_toast, null)
        layout.findViewById<TextView>(R.id.opt_message)?.text =
            getString(R.string.optimizing_cpu, getCpuCoreType())

        val toast = Toast(applicationContext)
        toast.duration = Toast.LENGTH_SHORT
        @Suppress("DEPRECATION") toast.view = layout
        toast.setGravity(Gravity.BOTTOM, 0, 100)
        toast.show()
    }

    private fun getCpuCoreType(): String {
        val board    = Build.BOARD.lowercase()
        val hardware = Build.HARDWARE.lowercase()
        return when {
            board == "sun"        || hardware == "sun"        -> "Oryon"
            board == "pineapple"  || hardware == "pineapple"  -> "Cortex-X4"
            board == "kalama"     || hardware == "kalama"     -> "Cortex-X3"
            board == "taro"       || hardware == "taro"       -> "Cortex-X2"
            board == "lahaina"    || hardware == "lahaina"    -> "Cortex-X1"
            board == "yupik"      || hardware == "yupik"      -> "Cortex-A78"
            // Dimensity
            board.contains("mt6991") || hardware.contains("mt6991") -> "Cortex-X925"
            board.contains("mt6989") || hardware.contains("mt6989") -> "Cortex-X4"
            board.contains("mt6985") || hardware.contains("mt6985") -> "Cortex-X3"
            board.contains("mt6983") || hardware.contains("mt6983") -> "Cortex-X2"
            board.contains("mt6895") || board.contains("mt6893")    -> "Cortex-A78"
            // Exynos
            board == "s5e9955" || hardware == "s5e9955" -> "Cortex-X5"
            board == "s5e9945" || hardware == "s5e9945" -> "Cortex-X4"
            board == "s5e9925" || hardware == "s5e9925" -> "Cortex-X2"
            board == "exynos2100" || hardware == "exynos2100" -> "Cortex-X1"
            board == "s5e8845"  || board == "s5e8835"          -> "Cortex-A78"
            // Tensor
            board == "franklin"  -> "Cortex-X925"
            board in listOf("tokay","comet","caiman","komodo") -> "Cortex-X4"
            board in listOf("shiba","husky","akita") || hardware.contains("zuma") -> "Cortex-X3"
            board in listOf("cheetah","panther","lynx") || hardware.contains("cloudripper") -> "Cortex-X1"
            board in listOf("oriole","raven","bluejay") || hardware.contains("whitechapel") -> "Cortex-X1"
            // garnet (Snapdragon 7s Gen 2)
            board == "garnet"    || hardware == "garnet"    -> "Cortex-A78"
            else -> searchInCpuInfo()
        }
    }

    private fun searchInCpuInfo(): String = try {
        val info = java.io.File("/proc/cpuinfo").readText()
        when {
            info.contains("Oryon",  ignoreCase = true) -> "Oryon"
            info.contains("Kryo",   ignoreCase = true) -> "Kryo"
            info.contains("Cortex", ignoreCase = true) -> "Cortex"
            else -> "Generic CPU"
        }
    } catch (_: Exception) { "Unknown CPU" }

    private fun getManualGames(): Set<String> =
        getSharedPreferences("gamespace_prefs", Context.MODE_PRIVATE)
            .getStringSet("manual_games", emptySet()) ?: emptySet()

    private fun addManualGame(pkg: String) {
        val prefs = getSharedPreferences("gamespace_prefs", Context.MODE_PRIVATE)
        val set   = prefs.getStringSet("manual_games", emptySet())?.toMutableSet() ?: mutableSetOf()
        set.add(pkg)
        prefs.edit().putStringSet("manual_games", set).commit()
        restartGameSpaceService()
    }

    private fun removeManualGame(pkg: String) {
        val prefs = getSharedPreferences("gamespace_prefs", Context.MODE_PRIVATE)
        val set   = getManualGames().toMutableSet()
        if (set.remove(pkg)) {
            prefs.edit().putStringSet("manual_games", set).commit()
            Toast.makeText(this, getString(R.string.game_removed), Toast.LENGTH_SHORT).show()
            setupGameGrid()
        }
        restartGameSpaceService()
    }

    private fun showAddGameDialog() {
        val pm = packageManager
        val manualGames = getManualGames()

        val available = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { app ->
                app.packageName != packageName &&
                pm.getLaunchIntentForPackage(app.packageName) != null &&
                !manualGames.contains(app.packageName)
            }
            .map { app ->
                GameModel(
                    name        = pm.getApplicationLabel(app).toString(),
                    packageName = app.packageName,
                    icon        = pm.getApplicationIcon(app)
                )
            }
            .sortedBy { it.name.lowercase() }

        val adapter = object : android.widget.ArrayAdapter<GameModel>(
            this, R.layout.item_app_picker, available
        ) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_app_picker, parent, false)
                val item = getItem(position)!!
                view.findViewById<android.widget.ImageView>(R.id.app_icon)?.setImageDrawable(item.icon)
                view.findViewById<android.widget.TextView>(R.id.app_name)?.text = item.name
                return view
            }
        }

        android.app.AlertDialog.Builder(this, android.app.AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(R.string.select_app)
            .setAdapter(adapter) { _, which ->
                addManualGame(available[which].packageName)
                setupGameGrid()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showRemoveDialog(pkg: String) {
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.remove_game_title)
            .setMessage(R.string.remove_game_dialog)
            .setPositiveButton(R.string.btn_remove) { _, _ -> removeManualGame(pkg) }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun restartGameSpaceService() {
        val intent = Intent(this, GameSpaceService::class.java)
        stopService(intent)
        startService(intent)
    }
}
