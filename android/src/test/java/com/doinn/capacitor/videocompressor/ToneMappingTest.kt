package com.doinn.capacitor.videocompressor

import android.media.MediaFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToneMappingTest {

    private val api31 = 31

    @Test
    fun requestsSdrForPqAndHlgOnApi31() {
        assertTrue(ToneMapping.shouldRequestSdr(api31, MediaFormat.COLOR_TRANSFER_ST2084))
        assertTrue(ToneMapping.shouldRequestSdr(api31, MediaFormat.COLOR_TRANSFER_HLG))
    }

    @Test
    fun doesNotRequestSdrForSdrInput() {
        assertFalse(ToneMapping.shouldRequestSdr(api31, MediaFormat.COLOR_TRANSFER_SDR_VIDEO))
        assertFalse(ToneMapping.shouldRequestSdr(api31, null))
    }

    @Test
    fun doesNotRequestSdrBeforeApi31() {
        assertFalse(ToneMapping.shouldRequestSdr(30, MediaFormat.COLOR_TRANSFER_ST2084))
    }
}
