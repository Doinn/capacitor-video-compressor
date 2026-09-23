package com.doinn.capacitor.videocompressor

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DecoderMimeTest {

    @Test
    fun keepsNonDolbyVisionMime() {
        assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, DecoderMime.resolve(MediaFormat.MIMETYPE_VIDEO_AVC, null))
        assertEquals(MediaFormat.MIMETYPE_VIDEO_HEVC, DecoderMime.resolve(MediaFormat.MIMETYPE_VIDEO_HEVC, 1))
    }

    @Test
    fun decodesHevcCompatibleDolbyVisionProfilesAsHevc() {
        assertEquals(
            MediaFormat.MIMETYPE_VIDEO_HEVC,
            DecoderMime.resolve(MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION, CodecProfileLevel.DolbyVisionProfileDvheSt)
        )
        assertEquals(
            MediaFormat.MIMETYPE_VIDEO_HEVC,
            DecoderMime.resolve(MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION, CodecProfileLevel.DolbyVisionProfileDvheDtr)
        )
    }

    @Test
    fun decodesAvcCompatibleDolbyVisionProfileAsAvc() {
        assertEquals(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            DecoderMime.resolve(MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION, CodecProfileLevel.DolbyVisionProfileDvavSe)
        )
    }

    @Test
    fun decodesAv1CompatibleDolbyVisionProfileAsAv1() {
        assertEquals(
            MediaFormat.MIMETYPE_VIDEO_AV1,
            DecoderMime.resolve(MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION, CodecProfileLevel.DolbyVisionProfileDvav110)
        )
    }

    @Test
    fun rejectsDolbyVisionWithoutCompatibleBaseLayer() {
        assertNull(
            DecoderMime.resolve(MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION, CodecProfileLevel.DolbyVisionProfileDvheStn)
        )
    }

    @Test
    fun rejectsDolbyVisionWithUnknownProfile() {
        assertNull(DecoderMime.resolve(MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION, null))
    }
}
