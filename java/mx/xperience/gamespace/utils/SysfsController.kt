/*
 * Copyright (C) 2026 The XPerience Project
 * SPDX-License-Identifier: Apache-2.0
 */
package mx.xperience.gamespace.utils

import java.io.File


data class StockCpuState(
    val schedutilUp: Map<String, String>,
    val schedutilDown: Map<String, String>,
    val uclampMin: String?,
    val uclampMax: String?
)

class SysfsController {

    private var stockState: StockCpuState? = null

    private fun read(path: String): String? {
        return try {
            val file = File(path)
            if (!file.exists() || !file.canRead()) null
            else file.readText().trim()
        } catch (_: Exception) {
            null
        }
    }

    private fun write(path: String, value: String): Boolean {
        return try {
            val file = File(path)
            if (!file.exists() || !file.canWrite()) return false
            file.writeText(value)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun exists(path: String): Boolean =
        File(path).exists()

    fun captureStockState() {
        if (stockState != null) return

        val up = mutableMapOf<String, String>()
        val down = mutableMapOf<String, String>()

        File("/sys/devices/system/cpu/cpufreq")
            .listFiles { f -> f.name.startsWith("policy") }
            ?.forEach { policy ->
                val upPath = "${policy.path}/schedutil/up_rate_limit_us"
                val downPath = "${policy.path}/schedutil/down_rate_limit_us"

                read(upPath)?.let { up[upPath] = it }
                read(downPath)?.let { down[downPath] = it }
            }

        stockState = StockCpuState(
            schedutilUp = up,
            schedutilDown = down,
            uclampMin = readUclampMin(),
            uclampMax = readUclampMax()
        )
    }

    fun restoreStockState() {
        val state = stockState ?: return

        state.schedutilUp.forEach { (path, value) ->
            write(path, value)
        }

        state.schedutilDown.forEach { (path, value) ->
            write(path, value)
        }

        state.uclampMin?.let { writeUclampMin(it) }
        state.uclampMax?.let { writeUclampMax(it) }
    }

    /* ===================== CPU ===================== */

    fun setCpuGovernor(governor: String) {
        val base = "/sys/devices/system/cpu/cpufreq"
        val folders = File(base).listFiles { f -> f.name.startsWith("policy") || f.name.startsWith("cpu") }

        folders?.forEach { policy ->
            val govPath = "${policy.absolutePath}/scaling_governor"
            if (exists(govPath)) {
                write(govPath, governor)
            }
        }
    }

    private fun getAvailableGovernors(): List<String> {
        val path = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_available_governors"
        return read(path)?.split(" ") ?: emptyList()
    }

    /**
     * Retorna el mejor gobernador disponible según una lista de prioridades.
     * Para kernels modernos (5.10+), intentamos buscar 'walt', 'schedutil'
     */
    private fun getBestGovernor(preferred: String): String {
        val available = getAvailableGovernors()

        return when {
            // Si el preferido existe, úsalo
            available.contains(preferred) -> preferred
            // Fallbacks comunes según rendimiento
            available.contains("walt") -> "walt"
            available.contains("schedutil") -> "schedutil"
            available.contains("interactive") -> "interactive"
            else -> available.firstOrNull() ?: "performance"
        }
    }

    fun setSchedutilRateLimits(upUs: String, downUs: String) {
        File("/sys/devices/system/cpu/cpufreq")
        .listFiles { f -> f.name.startsWith("policy") }
        ?.forEach {
            write("${it.absolutePath}/schedutil/up_rate_limit_us", upUs)
            write("${it.absolutePath}/schedutil/down_rate_limit_us", downUs)
        }
    }

    fun setCpuBoost(enabled: Boolean) {
        when {
            exists("/sys/module/msm_performance/parameters/cpu_boost") ->
                write(
                    "/sys/module/msm_performance/parameters/cpu_boost",
                    if (enabled) "1" else "0"
                )

            exists("/sys/module/cpu_boost/parameters/input_boost_enabled") ->
                write(
                    "/sys/module/cpu_boost/parameters/input_boost_enabled",
                    if (enabled) "1" else "0"
                )
        }
    }

    /* ===================== UCLAMP ===================== */

    fun setUclamp(min: Int, max: Int) {
        write("/proc/sys/kernel/sched_util_clamp_min", min.toString())
        write("/proc/sys/kernel/sched_util_clamp_max", max.toString())
    }

    fun setUclampTopApp(min: Int, max: Int) {
        write("/proc/sys/kernel/sched_util_clamp_min_rt_default", min.toString())
        write("/proc/sys/kernel/sched_util_clamp_max_rt_default", max.toString())
    }

    fun readUclampMin(): String? =
        read("/proc/sys/kernel/sched_util_clamp_min")

    fun readUclampMax(): String? =
        read("/proc/sys/kernel/sched_util_clamp_max")

    fun writeUclampMin(value: String) {
        write("/proc/sys/kernel/sched_util_clamp_min", value)
    }

    fun writeUclampMax(value: String) {
        write("/proc/sys/kernel/sched_util_clamp_max", value)
    }

    /* ===================== GPU ===================== */

    fun setGpuGovernor(governor: String) {
        when {
            exists("/sys/class/kgsl/kgsl-3d0/devfreq/governor") ->
                write("/sys/class/kgsl/kgsl-3d0/devfreq/governor", governor)

            exists("/sys/class/devfreq/kgsl-3d0/governor") ->
                write("/sys/class/devfreq/kgsl-3d0/governor", governor)
        }
    }

    fun setGpuBoost(enabled: Boolean) {
        if (exists("/sys/class/kgsl/kgsl-3d0/force_bus_on")) {
            write(
                "/sys/class/kgsl/kgsl-3d0/force_bus_on",
                if (enabled) "1" else "0"
            )
            write(
                "/sys/class/kgsl/kgsl-3d0/force_clk_on",
                if (enabled) "1" else "0"
            )
        }
    }

    /* ===================== MODES ===================== */

    fun applyEco() {
        setCpuGovernor("powersave")
        setSchedutilRateLimits("2000", "5000")
        setUclamp(0, 60)
        setCpuBoost(false)
        setGpuGovernor("powersave")
        setGpuBoost(false)
    }

    fun applyBalanced() {
        captureStockState()
        restoreStockState()
        val targetGov = getBestGovernor("walt")
        setCpuGovernor(targetGov)
        setCpuBoost(false)
        setGpuGovernor("msm-adreno-tz")
        setGpuBoost(false)
    }

    fun applyPerformance() {
        val targetGov = getBestGovernor("walt")
        setCpuGovernor(targetGov)

        if (targetGov == "schedutil" || targetGov == "walt") {
            setSchedutilRateLimits("500", "1000")
        }
        setUclamp(20, 100)
        setCpuBoost(true)
        setGpuGovernor("performance")
        setGpuBoost(true)
    }

    fun applyTurbo() {
        val targetGov = getBestGovernor("performance")
        setCpuGovernor(targetGov)
        setSchedutilRateLimits("0", "0")
        setUclamp(40, 100)
        setCpuBoost(true)
        setGpuGovernor("performance")
        setGpuBoost(true)
    }
}
