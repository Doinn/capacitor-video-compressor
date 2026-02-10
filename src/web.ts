import { WebPlugin } from '@capacitor/core';

import type {
  CompressVideoOptions,
  CompressVideoResult,
  VideoCompressorPlugin,
} from './definitions';

export class VideoCompressorWeb
  extends WebPlugin
  implements VideoCompressorPlugin
{
  async compressVideo(
    _options: CompressVideoOptions,
  ): Promise<CompressVideoResult | null> {
    // Video compression is not available on web — return null
    // so the caller knows to upload the original file.
    return null;
  }

  async cancelCompression(): Promise<void> {
    // No-op on web
  }
}
