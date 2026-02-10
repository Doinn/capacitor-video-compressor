# @doinn/capacitor-video-compressor

Native video compression plugin for Capacitor v7. Compresses videos before upload using hardware acceleration on both iOS and Android.

- **iOS**: AVAssetExportSession with `fileLengthLimit` bitrate control
- **Android**: MediaCodec Surface-to-Surface pipeline with OpenGL ES intermediary (~30MB peak memory)
- **Web**: Returns `null` — no compression available

## Install

```bash
# From a git repo (private):
yarn add @doinn/capacitor-video-compressor@git+ssh://git@github.com:doinn/capacitor-video-compressor.git

# Then sync native projects:
npx cap sync
```

## Usage

```typescript
import { VideoCompressor } from '@doinn/capacitor-video-compressor';

// Compress a video
const result = await VideoCompressor.compressVideo({
  filePath: '/path/to/video.mp4',
  quality: 'medium',
});

if (result) {
  console.log(`Compressed: ${result.originalSize} → ${result.compressedSize} bytes`);
  console.log(`Output: ${result.compressedPath}`);
}

// Listen for progress
const listener = await VideoCompressor.addListener(
  'compressionProgress',
  (event) => {
    console.log(`Progress: ${(event.progress * 100).toFixed(0)}%`);
  }
);

// Cancel compression
await VideoCompressor.cancelCompression();

// Clean up listener
await listener.remove();
```

## API

### `compressVideo(options)`

| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `filePath` | `string` | *required* | Path to source video (`file://`, `content://`, or absolute path) |
| `quality` | `'low' \| 'medium' \| 'high'` | `'medium'` | Quality preset |
| `maxWidth` | `number` | from preset | Override max output width |
| `maxHeight` | `number` | from preset | Override max output height |
| `videoBitrate` | `number` | from preset | Override video bitrate (bps) |
| `audioBitrate` | `number` | from preset | Override audio bitrate (bps) |
| `deleteOriginal` | `boolean` | `false` | Delete source file after compression |
| `maxDuration` | `number` | `undefined` | Trim video to N seconds (iOS only) |

**Returns** `CompressVideoResult | null` — `null` on web platform.

### `cancelCompression()`

Cancels an in-progress compression.

### Events

| Event | Data | Description |
|-------|------|-------------|
| `compressionProgress` | `{ progress: number }` | Progress from 0.0 to 1.0 |

## Quality Presets

| Preset | Resolution | Video Bitrate | Audio Bitrate |
|--------|-----------|---------------|---------------|
| `low` | 854x480 | 800 Kbps | 96 Kbps |
| **`medium`** | 1280x720 | 1.5 Mbps | 128 Kbps |
| `high` | 1920x1080 | 3.0 Mbps | 192 Kbps |

## Error Codes

| Code | Meaning |
|------|---------|
| `FILE_NOT_FOUND` | Input file path doesn't exist |
| `UNSUPPORTED_FORMAT` | Video format not supported by encoder |
| `COMPRESSION_FAILED` | Generic compression failure |
| `CANCELLED` | Cancelled via `cancelCompression()` |
| `MISSING_PARAM` / `INVALID_ARGUMENT` | Required `filePath` missing |

## Development

```bash
# Install dependencies
yarn install

# Build TypeScript
yarn build

# Tag a release
yarn release:patch   # 1.0.0 → 1.0.1
yarn release:minor   # 1.0.0 → 1.1.0
yarn release:major   # 1.0.0 → 2.0.0
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for detailed platform implementation notes.

## Platform Notes

### iOS
- Uses `AVAssetExportSession` — Apple handles codec selection internally
- `fileLengthLimit` controls output size (advisory, may be slightly exceeded)
- Minimum iOS 14.0

### Android
- MediaCodec Surface-to-Surface pipeline with OpenGL intermediary
- Hardened for: Huawei Kirin (EOS timestamp bugs), Samsung Galaxy A03 (direct Surface broken), MediaTek (EOS signal retry)
- 16-aligned output dimensions for hardware encoder compatibility
- Sequential video→audio processing to respect hardware codec slot limits
- Minimum API 23

## License

MIT
