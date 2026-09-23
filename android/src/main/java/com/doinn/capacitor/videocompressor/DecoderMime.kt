package com.doinn.capacitor.videocompressor

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat

/**
 * Picks the decoder MIME type for a video track.
 *
 * Most devices have no Dolby Vision decoder, but several Dolby Vision profiles
 * carry a backward-compatible base layer that a standard decoder can read
 * (the same mapping ExoPlayer uses). Profiles without one, such as profile 5,
 * would decode with wrong colors, so they are rejected.
 */
internal object DecoderMime {

    /**
     * @param trackMime MIME type reported by the extractor for the track.
     * @param profile `MediaFormat.KEY_PROFILE` of the track, if present.
     * @return the MIME type to create the decoder with, or null when the track
     *   cannot be decoded with a standard decoder.
     */
    fun resolve(trackMime: String, profile: Int?): String? {
        if (trackMime != MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION) return trackMime

        return when (profile) {
            CodecProfileLevel.DolbyVisionProfileDvheDtr,
            CodecProfileLevel.DolbyVisionProfileDvheSt -> MediaFormat.MIMETYPE_VIDEO_HEVC
            CodecProfileLevel.DolbyVisionProfileDvavSe -> MediaFormat.MIMETYPE_VIDEO_AVC
            CodecProfileLevel.DolbyVisionProfileDvav110 -> MediaFormat.MIMETYPE_VIDEO_AV1
            else -> null
        }
    }
}
