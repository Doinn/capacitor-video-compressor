# VideoCompressor — Architecture & Implementation Details

Hardware-accelerated video compression plugin for Capacitor v7.
Compresses videos before upload for users on poor connections and low-end devices.

---

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [File Locations](#file-locations)
3. [Quality Presets](#quality-presets)
4. [How It Works (iOS)](#how-it-works-ios)
5. [How It Works (Android)](#how-it-works-android)
6. [Plugin Registration](#plugin-registration)
7. [Upgrading Capacitor](#upgrading-capacitor)
8. [Known Quirks & Gotchas](#known-quirks--gotchas)
9. [Error Codes](#error-codes)

---

## Architecture Overview

```
┌────────────────────────────────────────────────────────────┐
│  Consumer App (JS)                                         │
│  import { VideoCompressor } from                           │
│    '@doinn/capacitor-video-compressor'                     │
└──────────────────────┬─────────────────────────────────────┘
                       │ Capacitor Bridge (auto-registered)
        ┌──────────────┴──────────────┐
        ▼                             ▼
┌───────────────────┐   ┌─────────────────────────┐
│  iOS               │   │  Android                 │
│  VideoCompressor-  │   │  VideoCompressor-        │
│  Plugin.swift      │   │  Plugin.kt               │
│  (CAPPlugin)       │   │  (@CapacitorPlugin)      │
│       │            │   │       │                   │
│       ▼            │   │       ▼                   │
│  VideoCompressor   │   │  VideoCompressor.kt       │
│  .swift            │   │  MediaCodec + OpenGL ES   │
│  AVAssetExport-    │   │  intermediary pipeline    │
│  Session           │   │  (~30MB peak memory)      │
└───────────────────┘   └─────────────────────────┘
```

**Key principle**: The plugin bridge (Capacitor-dependent code) is thin (~30 lines per platform). The compression engines are pure platform code with no Capacitor dependency.

**Auto-registration**: As a packaged Capacitor plugin, the `capacitor` field in `package.json` enables automatic plugin discovery during `npx cap sync`. No manual `registerPlugin()` calls needed in the consumer app's `MainActivity` or `BridgeViewController`.

---

## File Locations

### TypeScript (published API)
| File | Purpose |
|------|---------|
| `src/definitions.ts` | TypeScript interfaces for all options, results, and events |
| `src/plugin.ts` | `registerPlugin('VideoCompressor')` + web fallback lazy-loading |
| `src/web.ts` | Web implementation (returns `null` — no compression on web) |
| `src/index.ts` | Re-exports |

### Native — iOS
| File | Purpose |
|------|---------|
| `ios/Sources/VideoCompressorPlugin/VideoCompressor.swift` | Compression engine (AVAssetExportSession, quality presets, progress polling) |
| `ios/Sources/VideoCompressorPlugin/VideoCompressorPlugin.swift` | Capacitor bridge (CAPPlugin subclass, calls → resolve/reject) |

### Native — Android
| File | Purpose |
|------|---------|
| `android/src/main/java/com/doinn/capacitor/videocompressor/VideoCompressor.kt` | Compression engine (MediaCodec + OpenGL ES intermediary, DeferredMuxer, sequential audio/video) |
| `android/src/main/java/com/doinn/capacitor/videocompressor/VideoCompressorPlugin.kt` | Capacitor bridge (@CapacitorPlugin, coroutine-based compression) |

---

## Quality Presets

Both iOS and Android use identical preset values:

| Preset | Resolution | Video Bitrate | Audio Bitrate | Use Case |
|--------|-----------|---------------|---------------|----------|
| `low` | 854x480 | 800 Kbps | 96 Kbps | Very slow connections, minimal quality needed |
| **`medium`** (default) | 1280x720 | 1.5 Mbps | 128 Kbps | **Standard — good balance for poor connections** |
| `high` | 1920x1080 | 3.0 Mbps | 192 Kbps | Faster connections, higher quality needed |

Custom overrides are also supported via individual parameters: `maxWidth`, `maxHeight`, `videoBitrate`, `audioBitrate`.

---

## How It Works (iOS)

**Engine**: `AVAssetExportSession`

1. Loads the video asset with precise timing
2. Selects export preset based on target resolution (`LowQuality`, `MediumQuality`, `HighestQuality`)
3. Sets `fileLengthLimit` = `duration * (videoBitrate + audioBitrate) / 8 * 1.1` — this indirectly constrains the output bitrate
4. Enables `shouldOptimizeForNetworkUse = true` (fast-start MOOV atom)
5. Polls `session.progress` every 250ms on main RunLoop, emits via `notifyListeners`
6. Output: compressed MP4 in `NSTemporaryDirectory()`

**Important**: `AVAssetExportSession` doesn't expose direct bitrate control. We use `fileLengthLimit` (max file size in bytes) to achieve approximate bitrate targets.

---

## How It Works (Android)

**Engine**: `MediaCodec` + OpenGL ES intermediary pipeline

1. **Phase 1 — Video**: Decoder outputs to `SurfaceTexture` → OpenGL ES renders each frame as a textured quad → `eglSwapBuffers` pushes to Encoder's input `Surface` (GPU-only, no YUV copy)
2. **Phase 2 — Audio**: Separate pass — either passthrough or re-encode to AAC
3. **DeferredMuxer**: Buffers `writeSampleData` calls until both video and audio tracks are added, then auto-starts `MediaMuxer` and flushes. This solves the `MediaMuxer` lifecycle constraint where `addTrack()` can only be called before `start()`.

**Why OpenGL intermediary?** Direct Surface-to-Surface (decoder Surface → encoder input Surface) doesn't work on some low-end chipsets (e.g., Samsung Galaxy A03 with MediaTek). The encoder never consumes frames from its input Surface autonomously. The OpenGL path (decoder → `SurfaceTexture` → OES texture → GLES quad → `eglSwapBuffers` → encoder) is the industry-standard workaround. The `TextureRenderer` inner class handles all EGL14/GLES20 setup.

**Why sequential?** Low-end chipsets can only run one hardware codec instance at a time. Running video + audio decoders simultaneously would fail or OOM.

**Display-space dimensions**: `calculateOutputDimensions` works in display space — input dimensions are swapped for rotation (90°/270°) before applying max width/height constraints. The `SurfaceTexture.getTransformMatrix()` already includes rotation, so no `setOrientationHint` is needed on the muxer.

**16-aligned dimensions**: `roundTo16()` rounds encoder output dimensions to multiples of 16 for hardware encoder compatibility on low-end SoCs.

**Memory**: ~30MB peak (vs ~200MB if using YUV buffer mode). Frames stay on GPU.

**Progress**: Throttled to every 500ms, emitted on main thread via `Handler(Looper.getMainLooper())`.

---

## Plugin Registration

As a packaged plugin, registration is **automatic**. When the consumer app runs `npx cap sync`, Capacitor reads the `capacitor` field in `package.json`:

```json
{
  "capacitor": {
    "ios": { "src": "ios" },
    "android": { "src": "android" }
  }
}
```

This generates the native registration code automatically. The consumer app does **not** need to call `registerPlugin()` in `MainActivity` or `BridgeViewController`.

---

## Upgrading Capacitor

### What depends on Capacitor

Only the **bridge layers** use Capacitor APIs:

#### iOS (`VideoCompressorPlugin.swift`)
```swift
import Capacitor                          // Framework import
CAPPlugin                                // Base class
CAPBridgedPlugin                         // Protocol (added in Cap 6)
CAPPluginMethod                          // Method registration
CAPPluginCall                            // .getString(), .resolve(), .reject()
notifyListeners("event", data: [...])    // Event emitter
```

#### Android (`VideoCompressorPlugin.kt`)
```kotlin
com.getcapacitor.Plugin                  // Base class
com.getcapacitor.PluginCall              // .getString(), .resolve(), .reject()
com.getcapacitor.PluginMethod            // @PluginMethod annotation
com.getcapacitor.annotation.CapacitorPlugin  // @CapacitorPlugin(name = "...")
notifyListeners("event", JSObject())     // Event emitter
```

### What does NOT depend on Capacitor

These files use only platform-native APIs and need **zero changes** during a Capacitor upgrade:

- `VideoCompressor.swift` — Pure AVFoundation
- `VideoCompressor.kt` — Pure Android (MediaCodec, MediaExtractor, MediaMuxer, EGL14, GLES20)

### Upgrade checklist

When upgrading Capacitor to a new major version:

- [ ] Check Capacitor changelog for plugin API changes
- [ ] Update `peerDependencies` in `package.json` to match new Capacitor version
- [ ] Verify `CAPPlugin` / `Plugin` base class still exists with same API
- [ ] Verify `CAPBridgedPlugin` protocol requirements (iOS)
- [ ] Verify `notifyListeners()` signature unchanged
- [ ] Build: `yarn build`
- [ ] Test on physical iOS device
- [ ] Test on physical Android device (especially low-end)
- [ ] Tag new release: `yarn release:patch`

---

## Known Quirks & Gotchas

### Path prefix (`file://`)
Native plugins return bare absolute paths (e.g., `/private/var/.../compressed.mp4`). Consumer code should add `file://` prefix when passing to Capacitor Filesystem APIs.

### Android hardware codec limits
Low-end Android devices may only support one hardware codec instance at a time. The plugin processes video and audio sequentially to avoid this.

### Galaxy A03 — Direct Surface-to-Surface broken
On Samsung Galaxy A03 (MediaTek), the encoder never consumes frames from its input Surface when using direct Surface-to-Surface. The OpenGL ES intermediary (`TextureRenderer`) is always used.

### Huawei Kirin 659 — EOS timestamp bugs
AAC encoder (`OMX.google.aac.encoder`, software fallback) emits EOS buffer with `size > 0` and `presentationTimeUs = 0`, causing MPEG4Writer out-of-order crash. Fixed with EOS guard and monotonic timestamp enforcement.

### MediaTek — Audio EOS signal retry
`encoder.dequeueInputBuffer()` can return -1 when EOS needs signaling. Must retry each loop iteration.

### DeferredMuxer lifecycle
`MediaMuxer.addTrack()` can only be called before `start()`. The `DeferredMuxer` inner class buffers data until all tracks are registered, then auto-starts.

### Codec cleanup cascading
If `decoder.stop()` throws after a codec error, subsequent cleanup calls are skipped, leaking hardware codec instances. Each `stop()`/`release()` is wrapped in `tryQuietly` independently.

### iOS `fileLengthLimit`
Advisory limit — actual output may slightly exceed it. Formula:
```
fileLengthLimit = duration * (videoBitrate + audioBitrate) / 8 * 1.1
```

### No web compression
Returns `null` on web. Videos upload uncompressed on web platform.

---

## Error Codes

Both platforms return consistent error codes:

| Code | Meaning |
|------|---------|
| `FILE_NOT_FOUND` | Input file path doesn't exist |
| `UNSUPPORTED_FORMAT` | Video format not supported by encoder |
| `COMPRESSION_FAILED` | Generic compression failure |
| `CANCELLED` | Compression was cancelled via `cancelCompression()` |
| `MISSING_PARAM` (iOS) / `INVALID_ARGUMENT` (Android) | Required `filePath` parameter missing |
