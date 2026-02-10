import { WebPlugin } from '@capacitor/core';
import type { CompressVideoOptions, CompressVideoResult, VideoCompressorPlugin } from './definitions';
export declare class VideoCompressorWeb extends WebPlugin implements VideoCompressorPlugin {
    compressVideo(_options: CompressVideoOptions): Promise<CompressVideoResult | null>;
    cancelCompression(): Promise<void>;
}
