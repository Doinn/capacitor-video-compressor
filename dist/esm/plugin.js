import { registerPlugin } from '@capacitor/core';
const VideoCompressor = registerPlugin('VideoCompressor', {
    web: () => import('./web').then((m) => new m.VideoCompressorWeb()),
});
export * from './definitions';
export { VideoCompressor };
//# sourceMappingURL=plugin.js.map