package com.doinn.capacitor.videocompressor

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@CapacitorPlugin(name = "VideoCompressor")
class VideoCompressorPlugin : Plugin() {

    companion object {
        private const val TAG = "VideoCompressorPlugin"
    }

    private var compressor: VideoCompressor? = null
    private var compressionJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    override fun load() {
        compressor = VideoCompressor(context)
        Log.d(TAG, "VideoCompressorPlugin loaded")
    }

    @PluginMethod
    fun compressVideo(call: PluginCall) {
        val filePath = call.getString("filePath")
        if (filePath.isNullOrEmpty()) {
            call.reject("filePath is required", "INVALID_ARGUMENT")
            return
        }

        val quality = call.getString("quality", "medium") ?: "medium"
        val maxWidth = call.getInt("maxWidth")
        val maxHeight = call.getInt("maxHeight")
        val videoBitrate = call.getInt("videoBitrate")
        val audioBitrate = call.getInt("audioBitrate")

        // Build options from quality preset, then override with explicit values
        val baseOptions = CompressionOptions.fromQuality(quality)
        val options = CompressionOptions(
            maxWidth = maxWidth ?: baseOptions.maxWidth,
            maxHeight = maxHeight ?: baseOptions.maxHeight,
            videoBitrate = videoBitrate ?: baseOptions.videoBitrate,
            audioBitrate = audioBitrate ?: baseOptions.audioBitrate
        )

        val comp = compressor ?: run {
            call.reject("Compressor not initialized", "NOT_INITIALIZED")
            return
        }

        // Set up progress callback (throttled in VideoCompressor, but emit on main thread)
        comp.onProgress = { progress ->
            mainHandler.post {
                val event = JSObject()
                event.put("progress", progress.toDouble())
                notifyListeners("compressionProgress", event)
            }
        }

        // Run compression on a coroutine
        compressionJob = scope.launch {
            try {
                Log.d(TAG, "Starting compression: filePath=$filePath, quality=$quality")
                val result = comp.compress(filePath, options)

                val response = JSObject().apply {
                    put("compressedPath", result.compressedPath)
                    put("originalSize", result.originalSize)
                    put("compressedSize", result.compressedSize)
                    put("duration", result.duration)
                    put("width", result.width)
                    put("height", result.height)
                }

                mainHandler.post {
                    call.resolve(response)
                }
            } catch (e: CancelledException) {
                Log.d(TAG, "Compression cancelled")
                mainHandler.post {
                    call.reject("Compression cancelled", "CANCELLED")
                }
            } catch (e: FileNotFoundException) {
                Log.e(TAG, "File not found: ${e.message}")
                mainHandler.post {
                    call.reject(e.message ?: "File not found", "FILE_NOT_FOUND")
                }
            } catch (e: CompressionException) {
                Log.e(TAG, "Compression failed: ${e.message}", e)
                mainHandler.post {
                    call.reject(e.message ?: "Compression failed", "COMPRESSION_FAILED")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during compression", e)
                mainHandler.post {
                    call.reject(
                        e.message ?: "Unknown error during compression",
                        "COMPRESSION_FAILED"
                    )
                }
            }
        }
    }

    @PluginMethod
    fun cancelCompression(call: PluginCall) {
        Log.d(TAG, "Cancel compression requested")
        compressor?.cancel()
        compressionJob?.cancel()
        call.resolve()
    }
}
