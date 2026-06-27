/*
 * Copyright (C) 2026 The XPerience Project
 * SPDX-License-Identifier: Apache-2.0
 */
package mx.xperience.gamespace.view

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * A ScrollView that caps its own height at a fraction of the display height.
 *
 * When used inside a MATCH_PARENT overlay window, a plain ScrollView with
 * wrap_content height will expand to full content size and never actually
 * scroll — because no ancestor view constrains its height.
 *
 * This view caps itself at [maxHeightFraction] × display height during
 * onMeasure, forcing the system to scroll content beyond that cap.
 * Default fraction = 0.88 (88% of screen height), leaving equal visible
 * margins above and below in landscape.
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : ScrollView(context, attrs, defStyle) {

    var maxHeightFraction: Float = 0.88f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val displayH = context.resources.displayMetrics.heightPixels
        val maxH = (displayH * maxHeightFraction).toInt()
        val cappedSpec = MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, cappedSpec)
    }
}
