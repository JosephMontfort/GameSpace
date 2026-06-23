package mx.xperience.gamespace.sysfs

import android.util.Log
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader
import java.io.RandomAccessFile

object SysFsManager {

    private const val TAG = "SysFsManager"

    // ── su shell state ─────────────────────────────────────────────────────
    private var suProcess: Process? = null
    private var suOut: DataOutputStream? = null
    private var suIn: BufferedReader? = null

    init { openShell() }

    /**
     * Open (or reopen) a persistent root shell.
     * Called once on init and again whenever a dead shell is detected.
     */
    private fun openShell() {
        try {
            closeShell()
            suProcess = Runtime.getRuntime().exec("su")
            suOut = DataOutputStream(suProcess!!.outputStream)
            suIn  = BufferedReader(InputStreamReader(suProcess!!.inputStream))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open su shell: ${e.message}")
        }
    }

    private fun closeShell() {
        try { suOut?.close() } catch (_: Exception) {}
        try { suIn?.close()  } catch (_: Exception) {}
        try { suProcess?.destroy() } catch (_: Exception) {}
        suOut = null; suIn = null; suProcess = null
    }

    /**
     * Returns true if the su process has already exited (shell is dead).
     */
    private fun isShellDead(): Boolean {
        return try { suProcess?.exitValue(); true } catch (_: IllegalThreadStateException) { false }
    }

    /**
     * Ensure the shell is alive; reopen if not.
     * Must be called inside @Synchronized methods.
     */
    private fun ensureShell() {
        if (suProcess == null || isShellDead()) {
            Log.w(TAG, "su shell dead — reopening")
            openShell()
        }
    }

    // ── public API ─────────────────────────────────────────────────────────

    @Synchronized
    fun readAsRoot(path: String): String {
        ensureShell()
        val out = suOut ?: return ""
        val inp = suIn  ?: return ""
        return try {
            // BUG FIX: quote path AND redirect stderr so bad paths don't pollute stdout
            out.writeBytes("cat \"$path\" 2>/dev/null\n")
            out.writeBytes("echo __EOF__\n")
            out.flush()
            val sb = StringBuilder()
            while (true) {
                val line = inp.readLine() ?: break
                if (line == "__EOF__") break
                if (line.isNotBlank()) sb.append(line)
            }
            sb.toString().trim()
        } catch (e: Exception) {
            Log.e(TAG, "readAsRoot failed for $path: ${e.message}")
            // Shell might have died mid-read; mark for reconnect next call
            closeShell()
            ""
        }
    }

    @Synchronized
    fun executeSu(path: String, value: String) {
        ensureShell()
        val out = suOut ?: return
        try {
            // BUG FIX: original code used echo "$value" > "$path" via the persistent shell,
            // but the fallback spawned a NEW process for each write — inconsistent and leaky.
            // Always use the persistent shell here.
            out.writeBytes("echo \"$value\" > \"$path\" 2>/dev/null\n")
            out.flush()
        } catch (e: Exception) {
            Log.e(TAG, "executeSu failed for $path: ${e.message}")
            closeShell()
        }
    }

    fun writeInt(path: String, value: Int)   { executeSu(path, value.toString()) }
    fun writeLong(path: String, value: Long) { executeSu(path, value.toString()) }

    /**
     * Try direct Java read first (fast, no IPC), fall back to root shell.
     * BUG FIX: original caught all exceptions in the outer try but then
     * called readAsRoot which could also fail silently — now explicit two-stage.
     */
    fun tryReadFileAsLong(path: String): Long {
        // Stage 1: direct read (works when SELinux allows it)
        try {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                RandomAccessFile(file, "r").use { raf ->
                    val v = raf.readLine()?.trim()?.toLongOrNull()
                    if (v != null) return v
                }
            }
        } catch (_: Exception) {}
        // Stage 2: root shell fallback
        return readAsRoot(path).toLongOrNull() ?: 0L
    }
}
