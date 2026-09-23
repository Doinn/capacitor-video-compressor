package com.doinn.capacitor.videocompressor

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Hardware-accelerated video compressor using MediaCodec Surface-to-Surface pipeline.
 *
 * This keeps all frame data on the GPU — critical for low-end devices (Samsung Galaxy A03, 2GB RAM).
 * Video and audio tracks are processed sequentially to avoid exceeding hardware codec limits.
 */
class VideoCompressor(private val context: Context) {

    companion object {
        private const val TAG = "VideoCompressor"
        private const val MIME_AVC = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val MIME_AAC = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val I_FRAME_INTERVAL = 2
        private const val FRAME_RATE = 30
        private const val TIMEOUT_US = 10_000L
        private const val PROGRESS_INTERVAL_MS = 500L
    }

    @Volatile
    private var isCancelled = false

    var onProgress: ((Float) -> Unit)? = null

    fun cancel() {
        isCancelled = true
    }

    suspend fun compress(inputPath: String, options: CompressionOptions): CompressionResult =
        withContext(Dispatchers.Default) {
            isCancelled = false

            val inputFile = resolveInputFile(inputPath)
            val originalSize = getFileSize(inputPath)
            val outputFile = File(
                context.cacheDir,
                "${CompressedCacheCleaner.FILE_PREFIX}${System.currentTimeMillis()}${CompressedCacheCleaner.FILE_SUFFIX}"
            )

            Log.d(TAG, "Starting compression: $inputPath -> ${outputFile.absolutePath}")
            Log.d(TAG, "Options: ${options.maxWidth}x${options.maxHeight}, video=${options.videoBitrate}bps, audio=${options.audioBitrate}bps")

            try {
                // Retrieve total duration for progress tracking
                val totalDurationUs = getTotalDuration(inputPath)
                Log.d(TAG, "Total duration: ${totalDurationUs / 1_000_000.0}s")

                // Pre-scan to determine track count so DeferredMuxer knows when to start
                val hasAudio = hasAudioTrack(inputPath)
                val expectedTracks = if (hasAudio) 2 else 1
                Log.d(TAG, "Expected tracks: $expectedTracks (hasAudio=$hasAudio)")

                // DeferredMuxer buffers video data until all tracks are added,
                // then auto-starts the underlying MediaMuxer and flushes.
                val rawMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val muxer = DeferredMuxer(rawMuxer, expectedTracks)

                try {
                    // Phase 1: Transcode video track
                    Log.d(TAG, "Phase 1: Transcoding video track")
                    transcodeVideo(inputPath, muxer, options, totalDurationUs)

                    if (isCancelled) throw CancelledException()

                    // Phase 2: Handle audio track (passthrough or re-encode)
                    Log.d(TAG, "Phase 2: Processing audio track")
                    processAudio(inputPath, muxer, options, totalDurationUs)

                    muxer.stop()
                } finally {
                    muxer.release()
                }

                val compressedSize = outputFile.length()
                val (outputWidth, outputHeight) = getVideoDimensions(outputFile.absolutePath)

                Log.d(TAG, "Compression complete: ${originalSize / 1024}KB -> ${compressedSize / 1024}KB " +
                    "(${String.format("%.1f", originalSize.toFloat() / compressedSize)}x reduction)")

                CompressionResult(
                    compressedPath = outputFile.absolutePath,
                    originalSize = originalSize,
                    compressedSize = compressedSize,
                    duration = totalDurationUs / 1_000_000.0,
                    width = outputWidth,
                    height = outputHeight
                )
            } catch (e: CancelledException) {
                outputFile.delete()
                throw e
            } catch (e: Exception) {
                outputFile.delete()
                Log.e(TAG, "Compression failed", e)
                throw CompressionException("Compression failed: ${e.message}", e)
            }
        }

    // ── Video Transcoding (OpenGL intermediary) ────────────────────────

    private fun transcodeVideo(
        inputPath: String,
        muxer: DeferredMuxer,
        options: CompressionOptions,
        totalDurationUs: Long
    ): TrackResult {
        val extractor = MediaExtractor()
        try {
            setDataSource(extractor, inputPath)
            val videoTrackIndex = findTrack(extractor, "video/")
                ?: throw CompressionException("No video track found")

            extractor.selectTrack(videoTrackIndex)
            val inputFormat = extractor.getTrackFormat(videoTrackIndex)

            // Resolved before creating any codec so an unsupported track fails fast.
            val trackMime = inputFormat.getString(MediaFormat.KEY_MIME)!!
            val trackProfile = if (inputFormat.containsKey(MediaFormat.KEY_PROFILE)) {
                inputFormat.getInteger(MediaFormat.KEY_PROFILE)
            } else {
                null
            }
            val decoderMime = DecoderMime.resolve(trackMime, trackProfile)
                ?: throw CompressionException("Unsupported video codec: $trackMime (profile=$trackProfile)")
            if (decoderMime != trackMime) {
                Log.d(TAG, "Decoding $trackMime (profile=$trackProfile) base layer as $decoderMime")
                inputFormat.setString(MediaFormat.KEY_MIME, decoderMime)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    inputFormat.removeKey(MediaFormat.KEY_PROFILE)
                    inputFormat.removeKey(MediaFormat.KEY_LEVEL)
                }
            }

            val inputWidth = inputFormat.getInteger(MediaFormat.KEY_WIDTH)
            val inputHeight = inputFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = getRotation(inputFormat)

            val (outputWidth, outputHeight) = OutputDimensions.calculate(
                inputWidth, inputHeight, rotation, options.maxWidth, options.maxHeight
            )

            Log.d(TAG, "Video: ${inputWidth}x${inputHeight} (rot=$rotation) -> ${outputWidth}x${outputHeight}")

            // No setOrientationHint — the SurfaceTexture transform matrix already
            // handles rotation during GLES rendering. The encoder receives
            // display-oriented frames, so no player-side rotation is needed.

            // Configure encoder
            val encoderFormat = MediaFormat.createVideoFormat(MIME_AVC, outputWidth, outputHeight).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, options.videoBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                    setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
                }
            }

            // Created inside the try so a failure at any step (for example a device
            // without a Main10 HEVC or AV1 decoder) still releases what was started.
            var encoderRef: MediaCodec? = null
            var encoderInputSurfaceRef: Surface? = null
            var rendererRef: TextureRenderer? = null
            var surfaceTextureRef: SurfaceTexture? = null
            var decoderOutputSurfaceRef: Surface? = null
            var decoderRef: MediaCodec? = null
            var muxerTrack = -1

            try {
                val encoder = MediaCodec.createEncoderByType(MIME_AVC).also { encoderRef = it }
                encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val encoderInputSurface = encoder.createInputSurface().also { encoderInputSurfaceRef = it }
                encoder.start()

                // OpenGL intermediary: decoder → SurfaceTexture → GLES → encoder Surface.
                // Direct Surface-to-Surface doesn't work on some low-end SoCs (Galaxy A03)
                // because the encoder never consumes frames from its input Surface.
                val renderer = TextureRenderer(encoderInputSurface, outputWidth, outputHeight).also { rendererRef = it }
                val surfaceTexture = SurfaceTexture(renderer.textureId).also { surfaceTextureRef = it }
                surfaceTexture.setDefaultBufferSize(inputWidth, inputHeight)
                val decoderOutputSurface = Surface(surfaceTexture).also { decoderOutputSurfaceRef = it }

                // Frame-available synchronization
                val frameLock = Object()
                var frameAvailable = false
                surfaceTexture.setOnFrameAvailableListener(
                    { synchronized(frameLock) { frameAvailable = true; frameLock.notifyAll() } },
                    Handler(Looper.getMainLooper())
                )

                // Configure decoder → outputs to SurfaceTexture (not directly to encoder)
                val colorTransfer = inputFormat.getIntegerSafe(MediaFormat.KEY_COLOR_TRANSFER, -1).takeIf { it >= 0 }
                val requestSdr = ToneMapping.shouldRequestSdr(Build.VERSION.SDK_INT, colorTransfer)
                if (requestSdr) {
                    inputFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                }
                val decoder = MediaCodec.createDecoderByType(decoderMime).also { decoderRef = it }
                decoder.configure(inputFormat, decoderOutputSurface, null, 0)
                if (requestSdr) {
                    val granted = decoder.inputFormat.getIntegerSafe(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, 0) ==
                        MediaFormat.COLOR_TRANSFER_SDR_VIDEO
                    Log.d(TAG, "HDR input (transfer=$colorTransfer), decoder SDR tone mapping ${if (granted) "enabled" else "unsupported"}")
                }
                decoder.start()

                val decInfo = MediaCodec.BufferInfo()
                val encInfo = MediaCodec.BufferInfo()
                var inputDone = false
                var decoderDone = false
                var encoderDone = false
                var lastProgressTime = 0L
                var framesRendered = 0
                var framesMuxed = 0
                var lastVideoTimestampUs = -1L

                while (!encoderDone) {
                    if (isCancelled) throw CancelledException()

                    // Feed input to decoder
                    if (!inputDone) {
                        val inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                        if (inputBufIndex >= 0) {
                            val inputBuf = decoder.getInputBuffer(inputBufIndex)!!
                            val sampleSize = extractor.readSampleData(inputBuf, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inputBufIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inputBufIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    // Drain decoder → render each frame through OpenGL to encoder
                    if (!decoderDone) {
                        val decoderStatus = decoder.dequeueOutputBuffer(decInfo, TIMEOUT_US)
                        if (decoderStatus >= 0) {
                            val isEos = decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            if (!isEos) {
                                decoder.releaseOutputBuffer(decoderStatus, true)
                                // Wait for SurfaceTexture to receive the frame
                                synchronized(frameLock) {
                                    val deadline = System.currentTimeMillis() + 2000
                                    while (!frameAvailable) {
                                        val remaining = deadline - System.currentTimeMillis()
                                        if (remaining <= 0) throw CompressionException("Timeout waiting for decoded frame")
                                        frameLock.wait(remaining)
                                    }
                                    frameAvailable = false
                                }
                                // Push frame through OpenGL to encoder's input Surface
                                renderer.drawFrame(surfaceTexture, decInfo.presentationTimeUs * 1000)
                                framesRendered++
                            } else {
                                decoder.releaseOutputBuffer(decoderStatus, false)
                                decoderDone = true
                                encoder.signalEndOfInputStream()
                                Log.d(TAG, "Decoder EOS — $framesRendered frames pushed via OpenGL")
                            }
                        }
                    }

                    // Drain all available encoder output
                    drainEncoder@ while (true) {
                        val encoderStatus = encoder.dequeueOutputBuffer(encInfo, TIMEOUT_US)
                        when {
                            encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                muxerTrack = muxer.addTrack(encoder.outputFormat)
                                Log.d(TAG, "Video track added to muxer: $muxerTrack")
                            }
                            encoderStatus >= 0 -> {
                                val isEos = encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0

                                if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                    encInfo.size = 0
                                }

                                // Same EOS guard as audio: don't mux EOS-flagged buffers,
                                // and enforce monotonic timestamps.
                                if (!isEos && encInfo.size > 0 && muxerTrack >= 0 &&
                                    encInfo.presentationTimeUs > lastVideoTimestampUs) {
                                    val encodedData = encoder.getOutputBuffer(encoderStatus)
                                        ?: throw CompressionException("Encoder output buffer null")
                                    encodedData.position(encInfo.offset)
                                    encodedData.limit(encInfo.offset + encInfo.size)
                                    muxer.writeSampleData(muxerTrack, encodedData, encInfo)
                                    lastVideoTimestampUs = encInfo.presentationTimeUs
                                    framesMuxed++

                                    val now = System.currentTimeMillis()
                                    if (totalDurationUs > 0 && now - lastProgressTime >= PROGRESS_INTERVAL_MS) {
                                        val videoProgress = encInfo.presentationTimeUs.toFloat() / totalDurationUs
                                        onProgress?.invoke((videoProgress * 0.85f).coerceIn(0f, 0.85f))
                                        lastProgressTime = now
                                    }
                                }

                                encoder.releaseOutputBuffer(encoderStatus, false)

                                if (isEos) {
                                    encoderDone = true
                                    break@drainEncoder
                                }
                            }
                            else -> break@drainEncoder
                        }
                    }
                }

                Log.d(TAG, "Video transcode done: $framesRendered rendered, $framesMuxed muxed")
            } finally {
                // Release each resource independently — if one throws (common on
                // devices where a codec errored), the others still get cleaned up.
                // Leaking a hardware codec instance is fatal on most devices (only 2-4 slots).
                tryQuietly { decoderRef?.stop() }
                tryQuietly { decoderRef?.release() }
                tryQuietly { encoderRef?.stop() }
                tryQuietly { encoderRef?.release() }
                tryQuietly { decoderOutputSurfaceRef?.release() }
                tryQuietly { surfaceTextureRef?.release() }
                tryQuietly { rendererRef?.close() }
                tryQuietly { encoderInputSurfaceRef?.release() }
            }

            return TrackResult(muxerTrack)
        } finally {
            extractor.release()
        }
    }

    // ── Audio Processing ───────────────────────────────────────────────

    private fun processAudio(
        inputPath: String,
        muxer: DeferredMuxer,
        options: CompressionOptions,
        totalDurationUs: Long
    ): TrackResult {
        val extractor = MediaExtractor()
        try {
            setDataSource(extractor, inputPath)
            val audioTrackIndex = findTrack(extractor, "audio/")

            if (audioTrackIndex == null) {
                Log.d(TAG, "No audio track found, skipping audio")
                onProgress?.invoke(1.0f)
                return TrackResult(-1)
            }

            extractor.selectTrack(audioTrackIndex)
            val inputFormat = extractor.getTrackFormat(audioTrackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: ""
            val bitrate = inputFormat.getIntegerSafe(MediaFormat.KEY_BIT_RATE, 0)

            Log.d(TAG, "Audio track: mime=$mime, bitrate=$bitrate")

            // If already AAC and bitrate is at or below target, passthrough (mux directly)
            val shouldPassthrough = mime == MIME_AAC && (bitrate <= 0 || bitrate <= options.audioBitrate)

            return if (shouldPassthrough) {
                Log.d(TAG, "Audio passthrough (already AAC, bitrate OK)")
                audioPassthrough(extractor, muxer, inputFormat, totalDurationUs)
            } else {
                Log.d(TAG, "Audio re-encoding to AAC @ ${options.audioBitrate}bps")
                reencodeAudio(extractor, muxer, inputFormat, options, totalDurationUs)
            }
        } finally {
            extractor.release()
        }
    }

    private fun audioPassthrough(
        extractor: MediaExtractor,
        muxer: DeferredMuxer,
        inputFormat: MediaFormat,
        totalDurationUs: Long
    ): TrackResult {
        // addTrack may trigger DeferredMuxer to start (if this is the last expected track)
        val muxerTrack = muxer.addTrack(inputFormat)

        val bufferSize = 256 * 1024
        val buffer = ByteBuffer.allocate(bufferSize)
        val bufferInfo = MediaCodec.BufferInfo()
        var lastProgressTime = 0L

        while (true) {
            if (isCancelled) throw CancelledException()

            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break

            bufferInfo.offset = 0
            bufferInfo.size = sampleSize
            bufferInfo.presentationTimeUs = extractor.sampleTime
            bufferInfo.flags = extractorToCodecFlags(extractor.sampleFlags)

            muxer.writeSampleData(muxerTrack, buffer, bufferInfo)

            // Emit progress for audio phase (0.85..1.0)
            val now = System.currentTimeMillis()
            if (totalDurationUs > 0 && now - lastProgressTime >= PROGRESS_INTERVAL_MS) {
                val audioProgress = bufferInfo.presentationTimeUs.toFloat() / totalDurationUs
                val totalProgress = 0.85f + (audioProgress * 0.15f).coerceIn(0f, 0.15f)
                onProgress?.invoke(totalProgress)
                lastProgressTime = now
            }

            extractor.advance()
        }

        onProgress?.invoke(1.0f)
        return TrackResult(muxerTrack)
    }

    private fun reencodeAudio(
        extractor: MediaExtractor,
        muxer: DeferredMuxer,
        inputFormat: MediaFormat,
        options: CompressionOptions,
        totalDurationUs: Long
    ): TrackResult {
        val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = inputFormat.getIntegerSafe(MediaFormat.KEY_CHANNEL_COUNT, 1)

        // Configure AAC encoder
        val encoderFormat = MediaFormat.createAudioFormat(MIME_AAC, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, options.audioBitrate)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        // Created inside the try so a failed decoder setup still releases the
        // already-started encoder.
        var encoderRef: MediaCodec? = null
        var decoderRef: MediaCodec? = null

        var muxerTrack = -1
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var decoderDone = false
        var eosSignaled = false
        var encoderDone = false
        var lastProgressTime = 0L
        var lastAudioTimestampUs = -1L

        try {
            val encoder = MediaCodec.createEncoderByType(MIME_AAC).also { encoderRef = it }
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            // Configure decoder
            val decoderMime = inputFormat.getString(MediaFormat.KEY_MIME)!!
            val decoder = MediaCodec.createDecoderByType(decoderMime).also { decoderRef = it }
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            while (!encoderDone) {
                if (isCancelled) throw CancelledException()

                // Feed input to decoder
                if (!inputDone) {
                    val inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inputBufIndex >= 0) {
                        val inputBuf = decoder.getInputBuffer(inputBufIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuf, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inputBufIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(
                                inputBufIndex, 0, sampleSize,
                                extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                    }
                }

                // Drain decoder -> feed encoder
                if (!decoderDone) {
                    val decoderBufInfo = MediaCodec.BufferInfo()
                    val decoderStatus = decoder.dequeueOutputBuffer(decoderBufInfo, TIMEOUT_US)
                    if (decoderStatus >= 0) {
                        val decodedData = decoder.getOutputBuffer(decoderStatus)

                        if (decoderBufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            decoderDone = true
                        } else if (decodedData != null && decoderBufInfo.size > 0) {
                            // Feed decoded PCM to encoder
                            val encInputIdx = encoder.dequeueInputBuffer(TIMEOUT_US)
                            if (encInputIdx >= 0) {
                                val encInputBuf = encoder.getInputBuffer(encInputIdx)!!
                                encInputBuf.clear()
                                val bytesToCopy = minOf(decoderBufInfo.size, encInputBuf.remaining())
                                decodedData.position(decoderBufInfo.offset)
                                decodedData.limit(decoderBufInfo.offset + bytesToCopy)
                                encInputBuf.put(decodedData)
                                encoder.queueInputBuffer(
                                    encInputIdx, 0, bytesToCopy,
                                    decoderBufInfo.presentationTimeUs, 0
                                )
                            }
                        }
                        decoder.releaseOutputBuffer(decoderStatus, false)
                    }
                }

                // Signal EOS to encoder once decoder is done. Retry each loop
                // iteration — on some SoCs (MediaTek) the encoder may not have
                // a free input buffer immediately when the decoder finishes.
                if (decoderDone && !eosSignaled) {
                    val encInputIdx = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (encInputIdx >= 0) {
                        encoder.queueInputBuffer(
                            encInputIdx, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        eosSignaled = true
                    }
                }

                // Drain encoder output -> mux (DeferredMuxer auto-starts when all tracks added)
                val encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        muxerTrack = muxer.addTrack(encoder.outputFormat)
                        Log.d(TAG, "Audio track added to muxer: $muxerTrack")
                    }
                    encoderStatus >= 0 -> {
                        val isEos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }

                        // Don't mux EOS-flagged buffers — some encoders (Huawei Kirin)
                        // emit EOS with size > 0 and timestamp 0, causing out-of-order
                        // errors in MPEG4Writer. Also guard against any non-monotonic
                        // timestamps which crash the muxer on older Android versions.
                        if (!isEos && bufferInfo.size > 0 && muxerTrack >= 0 &&
                            bufferInfo.presentationTimeUs > lastAudioTimestampUs) {
                            val encodedData = encoder.getOutputBuffer(encoderStatus)
                                ?: throw CompressionException("Audio encoder output buffer null")
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(muxerTrack, encodedData, bufferInfo)
                            lastAudioTimestampUs = bufferInfo.presentationTimeUs

                            val now = System.currentTimeMillis()
                            if (totalDurationUs > 0 && now - lastProgressTime >= PROGRESS_INTERVAL_MS) {
                                val audioProgress = bufferInfo.presentationTimeUs.toFloat() / totalDurationUs
                                val totalProgress = 0.85f + (audioProgress * 0.15f).coerceIn(0f, 0.15f)
                                onProgress?.invoke(totalProgress)
                                lastProgressTime = now
                            }
                        } else if (!isEos && bufferInfo.size > 0 && muxerTrack >= 0) {
                            Log.w(TAG, "Skipping out-of-order audio sample: " +
                                "pts=${bufferInfo.presentationTimeUs} <= last=$lastAudioTimestampUs")
                        }

                        encoder.releaseOutputBuffer(encoderStatus, false)

                        if (isEos) {
                            encoderDone = true
                        }
                    }
                }
            }
        } finally {
            tryQuietly { decoderRef?.stop() }
            tryQuietly { decoderRef?.release() }
            tryQuietly { encoderRef?.stop() }
            tryQuietly { encoderRef?.release() }
        }

        onProgress?.invoke(1.0f)
        return TrackResult(muxerTrack)
    }

    // ── Helper Methods ─────────────────────────────────────────────────

    private fun setDataSource(extractor: MediaExtractor, inputPath: String) {
        if (inputPath.startsWith("content://")) {
            extractor.setDataSource(context, Uri.parse(inputPath), null)
        } else {
            // Strip file:// prefix if present
            val cleanPath = if (inputPath.startsWith("file://")) {
                inputPath.removePrefix("file://")
            } else {
                inputPath
            }
            extractor.setDataSource(cleanPath)
        }
    }

    private fun findTrack(extractor: MediaExtractor, mimePrefix: String): Int? {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return i
        }
        return null
    }

    private fun getTotalDuration(inputPath: String): Long {
        val retriever = MediaMetadataRetriever()
        try {
            if (inputPath.startsWith("content://")) {
                retriever.setDataSource(context, Uri.parse(inputPath))
            } else {
                val cleanPath = if (inputPath.startsWith("file://")) {
                    inputPath.removePrefix("file://")
                } else {
                    inputPath
                }
                retriever.setDataSource(cleanPath)
            }
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            return durationMs * 1000 // Convert ms -> us
        } finally {
            retriever.release()
        }
    }

    private fun getFileSize(inputPath: String): Long {
        return if (inputPath.startsWith("content://")) {
            context.contentResolver.openFileDescriptor(Uri.parse(inputPath), "r")?.use {
                it.statSize
            } ?: 0L
        } else {
            val cleanPath = if (inputPath.startsWith("file://")) {
                inputPath.removePrefix("file://")
            } else {
                inputPath
            }
            File(cleanPath).length()
        }
    }

    private fun getVideoDimensions(filePath: String): Pair<Int, Int> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(filePath)
            val width = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: 0
            return Pair(width, height)
        } finally {
            retriever.release()
        }
    }

    private fun getRotation(format: MediaFormat): Int {
        return try {
            if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                format.getInteger(MediaFormat.KEY_ROTATION)
            } else {
                0
            }
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Convert MediaExtractor.SAMPLE_FLAG_* to MediaCodec.BUFFER_FLAG_*.
     */
    private fun extractorToCodecFlags(sampleFlags: Int): Int {
        var flags = 0
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        return flags
    }

    private fun hasAudioTrack(inputPath: String): Boolean {
        val extractor = MediaExtractor()
        try {
            setDataSource(extractor, inputPath)
            return findTrack(extractor, "audio/") != null
        } finally {
            extractor.release()
        }
    }

    private fun resolveInputFile(inputPath: String): String {
        if (inputPath.startsWith("content://")) return inputPath

        val cleanPath = if (inputPath.startsWith("file://")) {
            inputPath.removePrefix("file://")
        } else {
            inputPath
        }

        if (!File(cleanPath).exists()) {
            throw FileNotFoundException("Source file not found: $cleanPath")
        }
        return cleanPath
    }

    private inline fun tryQuietly(block: () -> Unit) {
        try { block() } catch (e: Exception) {
            Log.w(TAG, "Cleanup failed (non-fatal): ${e.message}")
        }
    }

    private fun MediaFormat.getIntegerSafe(key: String, default: Int): Int {
        return try {
            if (containsKey(key)) getInteger(key) else default
        } catch (e: Exception) {
            default
        }
    }

    // ── OpenGL Texture Renderer ────────────────────────────────────────

    /**
     * Renders decoded video frames through OpenGL ES to the encoder's input Surface.
     *
     * Some devices (notably Samsung Galaxy A03 and other low-end SoCs) don't
     * properly consume frames when the decoder writes directly to the encoder's
     * input Surface. Routing through an explicit EGL + GLES render pass ensures
     * each frame is reliably submitted to the encoder via eglSwapBuffers().
     *
     * Flow: decoder → SurfaceTexture → OES texture → GLES quad → encoder Surface
     */
    private class TextureRenderer(
        outputSurface: Surface,
        private val width: Int,
        private val height: Int
    ) : AutoCloseable {

        val textureId: Int
        private val eglDisplay: android.opengl.EGLDisplay
        private val eglContext: android.opengl.EGLContext
        private val eglSurface: android.opengl.EGLSurface
        private val program: Int
        private val aPositionLoc: Int
        private val aTexCoordLoc: Int
        private val uSTMatrixLoc: Int
        private val vertices: FloatBuffer
        private val stMatrix = FloatArray(16)

        companion object {
            private val QUAD = floatArrayOf(
                // X,    Y,   Z,   U,   V
                -1f, -1f, 0f, 0f, 0f,
                 1f, -1f, 0f, 1f, 0f,
                -1f,  1f, 0f, 0f, 1f,
                 1f,  1f, 0f, 1f, 1f,
            )
            private const val FLOAT_SZ = 4
            private const val STRIDE = 5 * FLOAT_SZ

            private const val VS = """
                uniform mat4 uSTMatrix;
                attribute vec4 aPosition;
                attribute vec4 aTexCoord;
                varying vec2 vTexCoord;
                void main() {
                    gl_Position = aPosition;
                    vTexCoord = (uSTMatrix * aTexCoord).xy;
                }"""

            private const val FS = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 vTexCoord;
                uniform samplerExternalOES sTexture;
                void main() {
                    gl_FragColor = texture2D(sTexture, vTexCoord);
                }"""
        }

        init {
            vertices = ByteBuffer.allocateDirect(QUAD.size * FLOAT_SZ)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            vertices.put(QUAD).position(0)

            // Built in locals so a failure at any step can destroy what was
            // already created; the caller never gets a renderer to close.
            var display = EGL14.EGL_NO_DISPLAY
            var context = EGL14.EGL_NO_CONTEXT
            var surface = EGL14.EGL_NO_SURFACE
            val prog: Int
            val tex = IntArray(1)
            try {
                // ── EGL ──
                display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
                val ver = IntArray(2)
                check(EGL14.eglInitialize(display, ver, 0, ver, 1)) { "eglInitialize failed" }

                val cfgAttr = intArrayOf(
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE
                )
                val cfgs = arrayOfNulls<android.opengl.EGLConfig>(1)
                val nCfg = IntArray(1)
                check(EGL14.eglChooseConfig(display, cfgAttr, 0, cfgs, 0, 1, nCfg, 0))

                context = EGL14.eglCreateContext(
                    display, cfgs[0]!!, EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
                )
                check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

                surface = EGL14.eglCreateWindowSurface(
                    display, cfgs[0]!!, outputSurface, intArrayOf(EGL14.EGL_NONE), 0
                )
                check(surface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
                check(EGL14.eglMakeCurrent(display, surface, surface, context))

                // ── GLES ──
                prog = buildProgram(VS, FS)
                GLES20.glGenTextures(1, tex, 0)
            } catch (e: Throwable) {
                destroyEgl(display, context, surface)
                throw e
            }

            eglDisplay = display
            eglContext = context
            eglSurface = surface
            program = prog
            aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
            aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
            uSTMatrixLoc = GLES20.glGetUniformLocation(program, "uSTMatrix")

            textureId = tex[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            Matrix.setIdentityM(stMatrix, 0)
        }

        fun drawFrame(st: SurfaceTexture, presentationTimeNs: Long) {
            st.updateTexImage()
            st.getTransformMatrix(stMatrix)

            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)

            vertices.position(0)
            GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, STRIDE, vertices)
            GLES20.glEnableVertexAttribArray(aPositionLoc)
            vertices.position(3)
            GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, STRIDE, vertices)
            GLES20.glEnableVertexAttribArray(aTexCoordLoc)

            GLES20.glUniformMatrix4fv(uSTMatrixLoc, 1, false, stMatrix, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentationTimeNs)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }

        override fun close() {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            GLES20.glDeleteProgram(program)
            destroyEgl(eglDisplay, eglContext, eglSurface)
        }

        /** Releases whichever EGL objects were created; the no-object sentinels are skipped. */
        private fun destroyEgl(
            display: android.opengl.EGLDisplay,
            context: android.opengl.EGLContext,
            surface: android.opengl.EGLSurface
        ) {
            if (display == EGL14.EGL_NO_DISPLAY) return
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }

        private fun buildProgram(vs: String, fs: String): Int {
            val v = loadShader(GLES20.GL_VERTEX_SHADER, vs)
            val f = loadShader(GLES20.GL_FRAGMENT_SHADER, fs)
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, v)
            GLES20.glAttachShader(p, f)
            GLES20.glLinkProgram(p)
            val s = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, s, 0)
            check(s[0] == GLES20.GL_TRUE) { "glLinkProgram: ${GLES20.glGetProgramInfoLog(p)}" }
            return p
        }

        private fun loadShader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val c = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, c, 0)
            check(c[0] == GLES20.GL_TRUE) { "glCompileShader: ${GLES20.glGetShaderInfoLog(s)}" }
            return s
        }
    }

    // ── Deferred Muxer ─────────────────────────────────────────────────

    /**
     * Wraps MediaMuxer to defer start() until all expected tracks are added.
     *
     * MediaMuxer.addTrack() can only be called before start(). In our two-phase
     * pipeline (video first, then audio), the video phase would start the muxer
     * before the audio track is added. DeferredMuxer solves this by buffering
     * writeSampleData() calls until all tracks are registered, then auto-starting
     * the muxer and flushing the buffer.
     */
    private class DeferredMuxer(
        private val muxer: MediaMuxer,
        private val expectedTrackCount: Int
    ) {
        private data class PendingSample(
            val trackIndex: Int,
            val data: ByteArray,
            val presentationTimeUs: Long,
            val flags: Int
        )

        private var addedTracks = 0
        private var started = false
        private val pendingSamples = mutableListOf<PendingSample>()

        fun addTrack(format: MediaFormat): Int {
            val trackIndex = muxer.addTrack(format)
            addedTracks++
            if (addedTracks >= expectedTrackCount) {
                startAndFlush()
            }
            return trackIndex
        }

        fun writeSampleData(trackIndex: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            if (started) {
                muxer.writeSampleData(trackIndex, buffer, info)
            } else {
                val data = ByteArray(info.size)
                val savedPos = buffer.position()
                buffer.position(info.offset)
                buffer.get(data, 0, info.size)
                buffer.position(savedPos)
                pendingSamples.add(PendingSample(trackIndex, data, info.presentationTimeUs, info.flags))
            }
        }

        private fun startAndFlush() {
            muxer.start()
            started = true
            val flushInfo = MediaCodec.BufferInfo()
            for (sample in pendingSamples) {
                val buf = ByteBuffer.wrap(sample.data)
                flushInfo.set(0, sample.data.size, sample.presentationTimeUs, sample.flags)
                muxer.writeSampleData(sample.trackIndex, buf, flushInfo)
            }
            Log.d(TAG, "DeferredMuxer: flushed ${pendingSamples.size} buffered samples")
            pendingSamples.clear()
        }

        fun setOrientationHint(degrees: Int) = muxer.setOrientationHint(degrees)
        fun stop() {
            if (started) {
                muxer.stop()
            } else {
                Log.w(TAG, "DeferredMuxer.stop() skipped — muxer was never started " +
                    "(added $addedTracks/$expectedTrackCount tracks)")
            }
        }
        fun release() {
            try {
                muxer.release()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Muxer release failed (non-fatal): ${e.message}")
            }
        }
    }

    // ── Data Classes ───────────────────────────────────────────────────

    private data class TrackResult(val muxerTrack: Int)
}

// ── Public Data Types ──────────────────────────────────────────────────

data class CompressionOptions(
    val maxWidth: Int = 1280,
    val maxHeight: Int = 720,
    val videoBitrate: Int = 1_500_000,
    val audioBitrate: Int = 128_000
) {
    companion object {
        fun fromQuality(quality: String): CompressionOptions = when (quality) {
            "low" -> CompressionOptions(
                maxWidth = 854, maxHeight = 480,
                videoBitrate = 800_000, audioBitrate = 96_000
            )
            "high" -> CompressionOptions(
                maxWidth = 1920, maxHeight = 1080,
                videoBitrate = 3_000_000, audioBitrate = 192_000
            )
            else -> CompressionOptions() // "medium" — default
        }
    }
}

data class CompressionResult(
    val compressedPath: String,
    val originalSize: Long,
    val compressedSize: Long,
    val duration: Double,
    val width: Int,
    val height: Int
)

class CompressionException(message: String, cause: Throwable? = null) : Exception(message, cause)
class CancelledException : Exception("Compression was cancelled")
class FileNotFoundException(message: String) : Exception(message)
