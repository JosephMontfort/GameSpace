/*
 * Copyright (C) 2026 The XPerience Project
 * SPDX-License-Identifier: Apache-2.0
 */
package mx.xperience.gamespace.sysfs

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.RandomAccessFile

/**
 * Simple SysFS writer utility.
 * All writes are best-effort and fail silently if the node does not exist.
 */
object SysFsManager {

    private const val TAG = "SysFsManager"

    fun writeInt(path: String, value: Int) {
        executeSu(path, value.toString())
    }

    fun writeLong(path: String, value: Long) {
        executeSu(path, value.toString())
    }

    fun tryReadFileAsLong(path: String): Long {
        return try {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                RandomAccessFile(file, "r").use { raf ->
                    raf.readLine()?.trim()?.toLongOrNull() ?: 0L
                }
            } else {
                0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    // Injected Root Executor
    fun executeSu(path: String, value: String) {
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "echo $value > $path")).waitFor()
        } catch (e: Exception) { e.printStackTrace() }
    }

}