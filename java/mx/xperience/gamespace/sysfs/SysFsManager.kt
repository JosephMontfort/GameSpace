package mx.xperience.gamespace.sysfs

import android.util.Log
import java.io.File

/**
 * Simple SysFS writer utility.
 * All writes are best-effort and fail silently if the node does not exist.
 */
object SysFsManager {

    private const val TAG = "SysFsManager"

    fun write(path: String, value: String) {
        try {
            val file = File(path)
            if (!file.exists()) return
            file.writeText(value)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write $value to $path", e)
        }
    }

    fun writeInt(path: String, value: Int) {
        write(path, value.toString())
    }

    fun writeLong(path: String, value: Long) {
        write(path, value.toString())
    }
}
