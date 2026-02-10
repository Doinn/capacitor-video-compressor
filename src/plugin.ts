import { registerPlugin } from '@capacitor/core';

import type { VideoCompressorPlugin } from './definitions';

const VideoCompressor = registerPlugin<VideoCompressorPlugin>(
  'VideoCompressor',
  {
    web: () => import('./web').then((m) => new m.VideoCompressorWeb()),
  },
);

export * from './definitions';
export { VideoCompressor };
