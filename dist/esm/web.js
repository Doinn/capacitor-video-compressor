import { WebPlugin } from '@capacitor/core';
export class VideoCompressorWeb extends WebPlugin {
    async compressVideo(_options) {
        // Video compression is not available on web — return null
        // so the caller knows to upload the original file.
        return null;
    }
    async cancelCompression() {
        // No-op on web
    }
}
//# sourceMappingURL=web.js.map