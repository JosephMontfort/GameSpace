/*
 * Copyright (C) 2026 The XPerience Project
 * SPDX-License-Identifier: Apache-2.0
 */
package mx.xperience.gamespace

import android.graphics.drawable.Drawable

data class GameModel(
    val name: String,
    val packageName: String,
    val icon: Drawable
)
