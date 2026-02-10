'use strict';

var core = require('@capacitor/core');

const VideoCompressor = core.registerPlugin('VideoCompressor', {
    web: () => Promise.resolve().then(function () { return web; }).then((m) => new m.VideoCompressorWeb()),
});

class VideoCompressorWeb extends core.WebPlugin {
    async compressVideo(_options) {
        // Video compression is not available on web — return null
        // so the caller knows to upload the original file.
        return null;
    }
    async cancelCompression() {
        // No-op on web
    }
}

var web = /*#__PURE__*/Object.freeze({
    __proto__: null,
    VideoCompressorWeb: VideoCompressorWeb
});

exports.VideoCompressor = VideoCompressor;
//# sourceMappingURL=plugin.cjs.js.map
