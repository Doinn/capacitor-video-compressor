import Foundation
import Capacitor

@objc(VideoCompressorPlugin)
public class VideoCompressorPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "VideoCompressorPlugin"
    public let jsName = "VideoCompressor"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "compressVideo", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "cancelCompression", returnType: CAPPluginReturnPromise)
    ]

    private let implementation = VideoCompressor()

    override public func load() {
        implementation.onProgress = { [weak self] progress in
            self?.notifyListeners("compressionProgress", data: ["progress": progress])
        }
    }

    @objc func compressVideo(_ call: CAPPluginCall) {
        guard let filePath = call.getString("filePath") else {
            call.reject("Missing required parameter: filePath", "MISSING_PARAM")
            return
        }

        let quality = call.getString("quality") ?? "medium"
        let deleteOriginal = call.getBool("deleteOriginal") ?? false
        let maxDuration = call.getDouble("maxDuration") // nil = no limit

        // Build options from preset, allow per-field overrides
        let options: CompressionOptions
        if let maxWidth = call.getInt("maxWidth"),
           let maxHeight = call.getInt("maxHeight"),
           let videoBitrate = call.getInt("videoBitrate"),
           let audioBitrate = call.getInt("audioBitrate") {
            options = CompressionOptions(
                quality: quality,
                maxWidth: maxWidth,
                maxHeight: maxHeight,
                videoBitrate: videoBitrate,
                audioBitrate: audioBitrate,
                deleteOriginal: deleteOriginal,
                maxDuration: maxDuration
            )
        } else {
            options = CompressionOptions.fromPreset(quality, deleteOriginal: deleteOriginal, maxDuration: maxDuration)
        }

        Task {
            do {
                let result = try await implementation.compress(inputPath: filePath, options: options)
                call.resolve([
                    "compressedPath": result.compressedPath,
                    "originalSize": result.originalSize,
                    "compressedSize": result.compressedSize,
                    "duration": result.duration,
                    "width": result.width,
                    "height": result.height
                ])
            } catch let error as CompressionError {
                call.reject(error.localizedDescription, error.errorDescription)
            } catch {
                call.reject("Compression failed: \(error.localizedDescription)", "COMPRESSION_FAILED")
            }
        }
    }

    @objc func cancelCompression(_ call: CAPPluginCall) {
        implementation.cancel()
        call.resolve()
    }
}
