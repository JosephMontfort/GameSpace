package mx.xperience.gamespace.utils

import mx.xperience.gamespace.sysfs.SysFsManager

import java.io.File
import java.io.RandomAccessFile

data class StockCpuState(
    val schedutilUp: Map<String, String>,
    val schedutilDown: Map<String, String>,
    val uclampMin: String?,
    val uclampMax: String?
)

class SysfsController {

    private var stockState: StockCpuState? = null

    private fun read(path: String): String? {
        return SysFsManager.readAsRoot(path).takeIf { it.isNotEmpty() }
    }

    private fun exists(path: String): Boolean {
        if (File(path).exists()) return true
        return SysFsManager.readAsRoot("ls $path").isNotEmpty()
    }

    fun executeSu(path: String, value: String) {
        SysFsManager.executeSu(path, value)
    }

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
            executeSu(path, value)
        }
        state.schedutilDown.forEach { (path, value) ->
            executeSu(path, value)
        }
        state.uclampMin?.let { writeUclampMin(it) }
        state.uclampMax?.let { writeUclampMax(it) }
    }

    fun getCpuClusterFreqs(): Pair<Long, Long> {
        val little = SysFsManager.tryReadFileAsLong("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq")
        val big = SysFsManager.tryReadFileAsLong("/sys/devices/system/cpu/cpu4/cpufreq/scaling_cur_freq")
        return Pair(little, big)
    }

    fun getCpuClusterMaxLimits(): Pair<Long, Long> {
        val little = SysFsManager.tryReadFileAsLong("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq")
        val big = SysFsManager.tryReadFileAsLong("/sys/devices/system/cpu/cpu4/cpufreq/cpuinfo_max_freq")
        return Pair(little, big)
    }

    /* ===================== CPU ===================== */

    fun setCpuGovernor(governor: String) {
        val base = "/sys/devices/system/cpu/cpufreq"
        val folders = File(base).listFiles { f -> f.name.startsWith("policy") || f.name.startsWith("cpu") }
        folders?.forEach { policy ->
            val govPath = "${policy.absolutePath}/scaling_governor"
            if (exists(govPath)) {
                executeSu(govPath, governor)
            }
        }
    }

    private fun getAvailableGovernors(): List<String> {
        val path = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_available_governors"
        return read(path)?.split(" ") ?: emptyList()
    }

    private fun getBestGovernor(preferred: String): String {
        val available = getAvailableGovernors()
        return when {
            available.contains(preferred) -> preferred
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
                executeSu("${it.absolutePath}/schedutil/up_rate_limit_us", upUs)
                executeSu("${it.absolutePath}/schedutil/down_rate_limit_us", downUs)
            }
    }

    fun setCpuBoost(enabled: Boolean) {
        when {
            exists("/sys/module/msm_performance/parameters/cpu_boost") ->
                executeSu("/sys/module/msm_performance/parameters/cpu_boost", if (enabled) "1" else "0")

            exists("/sys/module/cpu_boost/parameters/input_boost_enabled") ->
                executeSu("/sys/module/cpu_boost/parameters/input_boost_enabled", if (enabled) "1" else "0")
        }
    }

    fun getCpuTemperature(): String {
        return try {
            val tempPaths = arrayOf(
                "/sys/class/thermal/thermal_zone0/temp",
                "/sys/class/thermal/thermal_zone1/temp",
                "/sys/devices/virtual/thermal/thermal_zone0/temp"
            )
            for (path in tempPaths) {
                val tempValue = SysFsManager.tryReadFileAsLong(path)
                if (tempValue > 0) return String.format("%.1f°C", tempValue / 1000.0)
            }
            "N/A"
        } catch (e: Exception) {
            "N/A"
        }
    }

    /* ===================== UCLAMP ===================== */

    fun setUclamp(min: Int, max: Int) {
        executeSu("/proc/sys/kernel/sched_util_clamp_min", min.toString())
        executeSu("/proc/sys/kernel/sched_util_clamp_max", max.toString())
    }

    fun readUclampMin(): String? = read("/proc/sys/kernel/sched_util_clamp_min")
    fun readUclampMax(): String? = read("/proc/sys/kernel/sched_util_clamp_max")
    fun writeUclampMin(value: String) { executeSu("/proc/sys/kernel/sched_util_clamp_min", value) }
    fun writeUclampMax(value: String) { executeSu("/proc/sys/kernel/sched_util_clamp_max", value) }

    /* ===================== GPU ===================== */

    fun setGpuGovernor(governor: String) {
        when {
            exists("/sys/class/kgsl/kgsl-3d0/devfreq/governor") ->
                executeSu("/sys/class/kgsl/kgsl-3d0/devfreq/governor", governor)
            exists("/sys/class/devfreq/kgsl-3d0/governor") ->
                executeSu("/sys/class/devfreq/kgsl-3d0/governor", governor)
        }
    }

    fun setGpuBoost(enabled: Boolean) {
        if (exists("/sys/class/kgsl/kgsl-3d0/force_bus_on")) {
            executeSu("/sys/class/kgsl/kgsl-3d0/force_bus_on", if (enabled) "1" else "0")
        }
    }

    fun getGpuFreq(): Pair<Int, String> {
        var freq = 0
        var temp = "N/A"
        val qcomFreqPaths = arrayOf(
            "/sys/class/kgsl/kgsl-3d0/gpuclk",
            "/sys/class/kgsl/kgsl-3d0/gpu_clock",
            "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq"
        )
        val qcomTempPaths = arrayOf(
            "/sys/class/kgsl/kgsl-3d0/temp",
            "/sys/class/thermal/thermal_zone11/temp",
            "/sys/class/thermal/thermal_zone12/temp"
        )

        for (path in qcomFreqPaths) {
            val value = SysFsManager.tryReadFileAsLong(path)
            if (value > 0) {
                freq = (value / 1000000).toInt()
                break
            }
        }
        for (tPath in qcomTempPaths) {
            val tempValue = SysFsManager.tryReadFileAsLong(tPath)
            if (tempValue > 0) {
                temp = String.format("%.1f°C", tempValue / 1000.0)
                break
            }
        }
        if (freq == 0) {
            val maliPaths = listOf(
                "/sys/devices/platform/ffe40000.gpu/clock",
                "/sys/devices/platform/gpu.0/clock",
                "/sys/devices/platform/gpu/clock",
                "/sys/class/misc/mali0/device/clock",
                "/sys/devices/platform/14ac0000.mali/devfreq/14ac0000.mali/cur_freq"
            )
            for (path in maliPaths) {
                val value = SysFsManager.tryReadFileAsLong(path)
                if (value > 0) {
                    freq = (value / 1000000).toInt()
                    break
                }
            }
        }
        return Pair(freq, temp)
    }

    /* ===================== SCONFIG ===================== */

    /**
     * Sets the thermal sconfig node — a vendor-specific thermal/scheduling
     * configuration knob. Value 9 unlocks the highest performance thermal
     * profile; 0 is the stock/default profile.
     */
    fun setSconfig(value: Int) {
        val path = "/sys/class/thermal/thermal_message/sconfig"
        if (exists(path)) {
            executeSu(path, value.toString())
        }
    }

    /* ===================== MODES ===================== */
    fun applyEco() {
        setCpuGovernor("powersave")
        setSchedutilRateLimits("2000", "5000")
        setUclamp(0, 614)
        setCpuBoost(false)
        setGpuGovernor("powersave")
        setGpuBoost(false)
        setSconfig(0)
    }

    fun applyBalanced() {
        captureStockState()
        restoreStockState()
        val targetGov = getBestGovernor("walt")
        setCpuGovernor(targetGov)
        setCpuBoost(false)
        setGpuGovernor("msm-adreno-tz")
        setGpuBoost(false)
        setSconfig(0)
    }

    fun applyPerformance() {
        val targetGov = getBestGovernor("walt")
        setCpuGovernor(targetGov)
        if (targetGov == "schedutil" || targetGov == "walt") {
            setSchedutilRateLimits("500", "1000")
        }
        setUclamp(204, 1024)
        setCpuBoost(true)
        setGpuGovernor("performance")
        setGpuBoost(true)
        setSconfig(9)
    }

    fun applyTurbo() {
        val targetGov = getBestGovernor("performance")
        setCpuGovernor(targetGov)
        setSchedutilRateLimits("0", "0")
        setUclamp(409, 1024)
        setCpuBoost(true)
        setGpuGovernor("performance")
        setGpuBoost(true)
        setSconfig(9)
    }
}
