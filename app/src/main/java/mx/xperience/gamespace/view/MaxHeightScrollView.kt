/*
 * Copyright (C) 2026 The XPerience Project
 * SPDX-License-Identifier: Apache-2.0
 */
package mx.xperience.gamespace.view

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * A ScrollView that caps its own height to fit between the status bar and
 * the bottom of the screen, with a small bottom margin.
 *
 * displayMetrics.heightPixels includes the status bar area, so a plain
 * fraction of that value lets the panel overlap the status bar in landscape.
 * We read the real status bar height via the "status_bar_height" dimen
 * resource and subtract it (plus a small bottom pad) from the available space.
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : ScrollView(context, attrs, defStyle) {

    /** Extra bottom padding below the panel (dp). */
    var bottomPadDp: Float = 12f

    private fun statusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else 0
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density   = resources.displayMetrics.density
        val displayH  = resources.displayMetrics.heightPixels
        val sbHeight  = statusBarHeight()
        val bottomPad = (bottomPadDp * density).toInt()

        // Available height = full display minus status bar minus bottom pad
        val maxH = (displayH - sbHeight - bottomPad).coerceAtLeast(200)
        val cappedSpec = MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, cappedSpec)
    }
}
