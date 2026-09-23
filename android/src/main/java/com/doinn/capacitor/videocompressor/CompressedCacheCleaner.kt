package com.doinn.capacitor.videocompressor

import java.io.File

/**
 * Removes compressor output left in the cache directory.
 *
 * The plugin hands the compressed file to the app and never sees the upload
 * finish, so files are only removed once they are older than [MAX_AGE_MS],
 * which leaves room for any upload retry still in progress.
 */
internal object CompressedCacheCleaner {

    const val FILE_PREFIX = "compressed_"
    const val FILE_SUFFIX = ".mp4"
    const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

    /**
     * Deletes compressor output files in [dir] last modified more than
     * [MAX_AGE_MS] before [nowMs].
     *
     * @return the number of files deleted.
     */
    fun purgeStale(dir: File, nowMs: Long): Int {
        val files = dir.listFiles() ?: return 0

        return files.count { file ->
            file.isFile &&
                file.name.startsWith(FILE_PREFIX) &&
                file.name.endsWith(FILE_SUFFIX) &&
                nowMs - file.lastModified() > MAX_AGE_MS &&
                file.delete()
        }
    }
}
