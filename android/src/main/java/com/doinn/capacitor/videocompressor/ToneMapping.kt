package com.doinn.capacitor.videocompressor

import android.media.MediaFormat

/**
 * Decides when to ask the decoder for SDR output.
 *
 * The pipeline re-encodes to 8-bit SDR AVC with no tone mapping of its own, so
 * HDR input (PQ or HLG) looks washed out or dark unless the decoder converts it.
 * `KEY_COLOR_TRANSFER_REQUEST` exists from API 31; decoders that do not support
 * it ignore the request.
 */
internal object ToneMapping {

    private const val MIN_SDK = 31

    /**
     * @param sdkInt the device API level.
     * @param colorTransfer `MediaFormat.KEY_COLOR_TRANSFER` of the input track, if present.
     */
    fun shouldRequestSdr(sdkInt: Int, colorTransfer: Int?): Boolean =
        sdkInt >= MIN_SDK &&
            (colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084 || colorTransfer == MediaFormat.COLOR_TRANSFER_HLG)
}
