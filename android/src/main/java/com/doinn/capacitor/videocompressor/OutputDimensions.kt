package com.doinn.capacitor.videocompressor

/**
 * Output size calculation for the transcoded video, kept free of Android
 * runtime calls so it can be unit tested on the JVM.
 */
internal object OutputDimensions {

    /**
     * Calculate output dimensions maintaining aspect ratio.
     *
     * Constraints are applied in display orientation: when rotation is 90 or
     * 270 the input dimensions are swapped, and the bounds follow the video's
     * orientation. The SurfaceTexture transform matrix handles rotation during
     * GLES rendering.
     *
     * Always rounds to multiples of 16 for hardware encoder compatibility,
     * without exceeding the bounds.
     */
    fun calculate(
        inputWidth: Int,
        inputHeight: Int,
        rotation: Int,
        maxWidth: Int,
        maxHeight: Int
    ): Pair<Int, Int> {
        val (displayW, displayH) = if (rotation == 90 || rotation == 270) {
            inputHeight to inputWidth
        } else {
            inputWidth to inputHeight
        }

        // Presets describe landscape bounds; portrait video gets them swapped so
        // it is limited by the same long and short edges as landscape video.
        val (boundW, boundH) = if (displayH > displayW) {
            minOf(maxWidth, maxHeight) to maxOf(maxWidth, maxHeight)
        } else {
            maxOf(maxWidth, maxHeight) to minOf(maxWidth, maxHeight)
        }

        if (displayW <= boundW && displayH <= boundH) {
            return Pair(alignWithin(displayW, boundW), alignWithin(displayH, boundH))
        }

        val widthRatio = boundW.toFloat() / displayW
        val heightRatio = boundH.toFloat() / displayH
        val scale = minOf(widthRatio, heightRatio)

        return Pair(alignWithin((displayW * scale).toInt(), boundW), alignWithin((displayH * scale).toInt(), boundH))
    }

    /** Rounds to a multiple of 16, stepping down when rounding up would exceed [bound]. */
    private fun alignWithin(value: Int, bound: Int): Int {
        val rounded = roundTo16(value)
        return if (rounded > bound && rounded - 16 >= 16) rounded - 16 else rounded
    }

    /**
     * Round to nearest multiple of 16 (minimum 16).
     * Many hardware H.264 encoders on low-end devices require 16-aligned dimensions.
     */
    fun roundTo16(value: Int): Int {
        val rounded = (value + 8) / 16 * 16
        return maxOf(rounded, 16)
    }
}
