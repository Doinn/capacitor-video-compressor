import Foundation
import AVFoundation

enum CompressionError: Error, LocalizedError {
    case fileNotFound
    case unsupportedFormat
    case compressionFailed(String)
    case cancelled

    var errorDescription: String? {
        switch self {
        case .fileNotFound:
            return "FILE_NOT_FOUND"
        case .unsupportedFormat:
            return "UNSUPPORTED_FORMAT"
        case .compressionFailed(let message):
            return "COMPRESSION_FAILED: \(message)"
        case .cancelled:
            return "CANCELLED"
        }
    }
}

struct CompressionOptions {
    let quality: String
    let maxWidth: Int
    let maxHeight: Int
    let videoBitrate: Int
    let audioBitrate: Int
    let deleteOriginal: Bool
    let maxDuration: Double? // seconds — nil means no limit

    static func fromPreset(_ quality: String, deleteOriginal: Bool = false, maxDuration: Double? = nil) -> CompressionOptions {
        switch quality {
        case "low":
            return CompressionOptions(
                quality: quality,
                maxWidth: 854, maxHeight: 480,
                videoBitrate: 800_000, audioBitrate: 96_000,
                deleteOriginal: deleteOriginal,
                maxDuration: maxDuration
            )
        case "high":
            return CompressionOptions(
                quality: quality,
                maxWidth: 1920, maxHeight: 1080,
                videoBitrate: 3_000_000, audioBitrate: 192_000,
                deleteOriginal: deleteOriginal,
                maxDuration: maxDuration
            )
        default: // "medium"
            return CompressionOptions(
                quality: "medium",
                maxWidth: 1280, maxHeight: 720,
                videoBitrate: 1_500_000, audioBitrate: 128_000,
                deleteOriginal: deleteOriginal,
                maxDuration: maxDuration
            )
        }
    }
}

struct CompressionResult {
    let compressedPath: String
    let originalSize: Int64
    let compressedSize: Int64
    let duration: Double
    let width: Int
    let height: Int
}

class VideoCompressor {
    private var exportSession: AVAssetExportSession?
    private var progressTimer: Timer?
    var onProgress: ((Float) -> Void)?

    private let logPrefix = "[VideoCompressor]"

    func compress(inputPath: String, options: CompressionOptions) async throws -> CompressionResult {
        let fileURL: URL
        if inputPath.hasPrefix("file://") {
            guard let url = URL(string: inputPath) else {
                throw CompressionError.fileNotFound
            }
            fileURL = url
        } else {
            fileURL = URL(fileURLWithPath: inputPath)
        }

        guard FileManager.default.fileExists(atPath: fileURL.path) else {
            print("\(logPrefix) File not found: \(fileURL.path)")
            throw CompressionError.fileNotFound
        }

        let originalAttributes = try FileManager.default.attributesOfItem(atPath: fileURL.path)
        let originalSize = originalAttributes[.size] as? Int64 ?? 0
        print("\(logPrefix) Source file size: \(originalSize) bytes (\(String(format: "%.1f", Double(originalSize) / 1_048_576.0)) MB)")

        let asset = AVURLAsset(url: fileURL, options: [AVURLAssetPreferPreciseDurationAndTimingKey: true])

        // Determine the best export preset based on target resolution
        let presetName = exportPreset(for: options)
        print("\(logPrefix) Using export preset: \(presetName)")

        guard let session = AVAssetExportSession(asset: asset, presetName: presetName) else {
            print("\(logPrefix) Could not create export session for preset: \(presetName)")
            throw CompressionError.unsupportedFormat
        }

        let outputFileName = "compressed_\(UUID().uuidString).mp4"
        let outputURL = FileManager.default.temporaryDirectory.appendingPathComponent(outputFileName)

        // Remove any existing file at output path
        if FileManager.default.fileExists(atPath: outputURL.path) {
            try? FileManager.default.removeItem(at: outputURL)
        }

        session.outputURL = outputURL
        session.outputFileType = .mp4
        session.shouldOptimizeForNetworkUse = true

        // Load source duration
        let duration = try await loadDuration(asset: asset)
        var durationSec = CMTimeGetSeconds(duration)

        // Optionally trim to maxDuration
        if let maxDuration = options.maxDuration, durationSec > maxDuration {
            let trimEnd = CMTimeMakeWithSeconds(maxDuration, preferredTimescale: duration.timescale)
            session.timeRange = CMTimeRange(start: .zero, duration: trimEnd)
            durationSec = maxDuration
            print("\(logPrefix) Trimming video to \(String(format: "%.1f", maxDuration))s (original: \(String(format: "%.1f", CMTimeGetSeconds(duration)))s)")
        }

        // Use fileLengthLimit to constrain output bitrate
        if durationSec > 0 {
            let targetBytes = Int64(durationSec * Double(options.videoBitrate + options.audioBitrate) / 8.0)
            // Add 10% headroom for container overhead
            session.fileLengthLimit = Int64(Double(targetBytes) * 1.1)
            print("\(logPrefix) File length limit set to \(session.fileLengthLimit) bytes for \(String(format: "%.1f", durationSec))s video")
        }

        exportSession = session
        startProgressPolling(session: session)

        print("\(logPrefix) Starting export...")
        await session.export()
        stopProgressPolling()

        switch session.status {
        case .completed:
            print("\(logPrefix) Export completed successfully")
        case .cancelled:
            // Clean up partial output
            try? FileManager.default.removeItem(at: outputURL)
            throw CompressionError.cancelled
        case .failed:
            let errorMsg = session.error?.localizedDescription ?? "Unknown error"
            print("\(logPrefix) Export failed: \(errorMsg)")
            // Clean up partial output
            try? FileManager.default.removeItem(at: outputURL)
            throw CompressionError.compressionFailed(errorMsg)
        default:
            let errorMsg = "Unexpected export status: \(session.status.rawValue)"
            print("\(logPrefix) \(errorMsg)")
            try? FileManager.default.removeItem(at: outputURL)
            throw CompressionError.compressionFailed(errorMsg)
        }

        // Read compressed file attributes
        let compressedAttributes = try FileManager.default.attributesOfItem(atPath: outputURL.path)
        let compressedSize = compressedAttributes[.size] as? Int64 ?? 0

        // Get output video dimensions
        let (width, height) = await outputDimensions(asset: AVURLAsset(url: outputURL))

        let ratio = originalSize > 0 ? Double(originalSize) / Double(max(compressedSize, 1)) : 0
        print("\(logPrefix) Compression complete: \(originalSize) -> \(compressedSize) bytes (ratio: \(String(format: "%.1f", ratio))x)")

        // Optionally delete original
        if options.deleteOriginal {
            try? FileManager.default.removeItem(at: fileURL)
            print("\(logPrefix) Original file deleted")
        }

        exportSession = nil

        return CompressionResult(
            compressedPath: outputURL.path,
            originalSize: originalSize,
            compressedSize: compressedSize,
            duration: durationSec,
            width: width,
            height: height
        )
    }

    func cancel() {
        print("\(logPrefix) Cancel requested")
        exportSession?.cancelExport()
    }

    // MARK: - Private helpers

    private func exportPreset(for options: CompressionOptions) -> String {
        if options.maxHeight <= 480 {
            return AVAssetExportPresetLowQuality
        } else if options.maxHeight <= 720 {
            return AVAssetExportPresetMediumQuality
        } else {
            return AVAssetExportPresetHighestQuality
        }
    }

    private func loadDuration(asset: AVURLAsset) async throws -> CMTime {
        if #available(iOS 15.0, *) {
            return try await asset.load(.duration)
        } else {
            return asset.duration
        }
    }

    private func outputDimensions(asset: AVURLAsset) async -> (Int, Int) {
        do {
            let tracks: [AVAssetTrack]
            if #available(iOS 15.0, *) {
                tracks = try await asset.loadTracks(withMediaType: .video)
            } else {
                tracks = asset.tracks(withMediaType: .video)
            }
            guard let videoTrack = tracks.first else { return (0, 0) }

            let size: CGSize
            if #available(iOS 15.0, *) {
                size = try await videoTrack.load(.naturalSize)
            } else {
                size = videoTrack.naturalSize
            }
            return (Int(size.width), Int(size.height))
        } catch {
            print("\(logPrefix) Could not read output dimensions: \(error)")
            return (0, 0)
        }
    }

    private func startProgressPolling(session: AVAssetExportSession) {
        DispatchQueue.main.async { [weak self] in
            self?.progressTimer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self, weak session] _ in
                guard let session = session else { return }
                let progress = session.progress
                self?.onProgress?(progress)
            }
        }
    }

    private func stopProgressPolling() {
        DispatchQueue.main.async { [weak self] in
            self?.progressTimer?.invalidate()
            self?.progressTimer = nil
        }
        // Emit final 100% progress
        onProgress?(1.0)
    }
}
