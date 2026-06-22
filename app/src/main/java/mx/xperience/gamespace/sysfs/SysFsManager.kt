package mx.xperience.gamespace.sysfs

import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader
import java.io.RandomAccessFile

object SysFsManager {
    private var suProcess: Process? = null
    private var suOut: DataOutputStream? = null
    private var suIn: BufferedReader? = null

    init {
        try {
            suProcess = Runtime.getRuntime().exec("su")
            suOut = DataOutputStream(suProcess!!.outputStream)
            suIn = BufferedReader(InputStreamReader(suProcess!!.inputStream))
        } catch (e: Exception) { e.printStackTrace() }
    }

    @Synchronized
    fun readAsRoot(path: String): String {
        if (suOut == null || suIn == null) return ""
        return try {
            suOut!!.writeBytes("cat \"$path\" 2>/dev/null\n")
            suOut!!.writeBytes("echo EOF\n")
            suOut!!.flush()
            
            val sb = java.lang.StringBuilder()
            while (true) {
                val line = suIn!!.readLine() ?: break
                if (line == "EOF") break
                if (line.isNotBlank()) sb.append(line)
            }
            sb.toString().trim()
        } catch (e: Exception) {
            ""
        }
    }

    @Synchronized
    fun executeSu(path: String, value: String) {
        try {
            if (suOut != null) {
                suOut!!.writeBytes("echo \"$value\" > \"$path\" 2>/dev/null\n")
                suOut!!.flush()
            } else {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "echo $value > $path"))
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun writeInt(path: String, value: Int) { executeSu(path, value.toString()) }
    fun writeLong(path: String, value: Long) { executeSu(path, value.toString()) }

    fun tryReadFileAsLong(path: String): Long {
        try {
            val file = File(path)
            // Attempt extreme low-latency Java IO first
            if (file.exists() && file.canRead()) {
                RandomAccessFile(file, "r").use { raf ->
                    return raf.readLine()?.trim()?.toLongOrNull() ?: 0L
                }
            }
        } catch (e: Exception) {}
        // Fallback to pipe if SELinux blocks the Java read
        return readAsRoot(path).toLongOrNull() ?: 0L
    }
}
