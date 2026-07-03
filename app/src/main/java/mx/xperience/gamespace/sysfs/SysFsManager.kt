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

    /**
     * Writes to a sysfs node using a one-shot `su -c` process.
     *
     * The persistent shell (used for reads) proved unreliable for writes on
     * some devices/su implementations — piping "echo ... > path\n" into a
     * long-lived shell's stdin doesn't always commit for certain sysfs nodes,
     * especially thermal/vendor nodes. GarnetForge (verified working for the
     * same class of writes) uses one-shot `su -c` execution instead, so this
     * mirrors that approach: printf the value via a fresh su process, then
     * read the node back to confirm the value actually stuck.
     *
     * The persistent shell is untouched and still used for all reads.
     */
    @Synchronized
    fun executeSu(path: String, value: String) {
        try {
            val cmd = "printf '%s' \"$value\" > \"$path\" 2>/dev/null"
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            proc.waitFor()

            // Verify: read the node back and confirm it matches what we wrote.
            // Some nodes normalize the value (e.g. trailing newline stripped,
            // or the driver rounds/clamps) so we log a mismatch rather than
            // treat it as a hard failure.
            val verifyProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat \"$path\" 2>/dev/null"))
            val actual = verifyProc.inputStream.bufferedReader().readText().trim()
            verifyProc.waitFor()

            if (actual != value.trim()) {
                Log.w(TAG, "Write verify mismatch on $path: wrote '$value', read back '$actual'")
            }
        } catch (e: Exception) {
            Log.e(TAG, "executeSu (one-shot) failed for $path: ${e.message}")
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
