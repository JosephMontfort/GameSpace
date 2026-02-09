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
}
