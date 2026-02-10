import type { PluginListenerHandle } from '@capacitor/core';

export interface CompressVideoOptions {
  /**
   * Path to the source video file.
   * Accepts file:// URIs, content:// URIs (Android), or absolute file paths.
   */
  filePath: string;

  /**
   * Quality preset: 'low' (480p), 'medium' (720p), 'high' (1080p).
   * @default 'medium'
   */
  quality?: 'low' | 'medium' | 'high';

  /**
   * Override maximum output width (pixels).
   * If not specified, uses the quality preset's default.
   */
  maxWidth?: number;

  /**
   * Override maximum output height (pixels).
   * If not specified, uses the quality preset's default.
   */
  maxHeight?: number;

  /**
   * Override video bitrate (bits per second).
   * If not specified, uses the quality preset's default.
   */
  videoBitrate?: number;

  /**
   * Override audio bitrate (bits per second).
   * If not specified, uses the quality preset's default.
   */
  audioBitrate?: number;

  /**
   * Whether to delete the original file after compression.
   * @default false
   */
  deleteOriginal?: boolean;

  /**
   * Maximum duration in seconds. Video will be trimmed if longer.
   * Only supported on iOS. Reserved for future use on Android.
   */
  maxDuration?: number;
}

export interface CompressVideoResult {
  /** Absolute path to the compressed video file */
  compressedPath: string;
  /** Original file size in bytes */
  originalSize: number;
  /** Compressed file size in bytes */
  compressedSize: number;
  /** Video duration in seconds */
  duration: number;
  /** Output video width in pixels */
  width: number;
  /** Output video height in pixels */
  height: number;
}

export interface CompressionProgressEvent {
  /** Progress value from 0.0 to 1.0 */
  progress: number;
}

export interface VideoCompressorPlugin {
  /**
   * Compress a video file using native hardware acceleration.
   *
   * On iOS, uses AVAssetExportSession with fileLengthLimit for bitrate control.
   * On Android, uses a MediaCodec Surface-to-Surface pipeline with OpenGL intermediary.
   * On web, returns null (no compression available).
   */
  compressVideo(options: CompressVideoOptions): Promise<CompressVideoResult | null>;

  /**
   * Cancel an in-progress compression.
   */
  cancelCompression(): Promise<void>;

  /**
   * Listen for compression progress updates.
   */
  addListener(
    eventName: 'compressionProgress',
    listenerFunc: (event: CompressionProgressEvent) => void,
  ): Promise<PluginListenerHandle>;

  /**
   * Remove all listeners for the given event.
   */
  removeAllListeners(): Promise<void>;
}
