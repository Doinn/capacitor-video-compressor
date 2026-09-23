package com.doinn.capacitor.videocompressor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CompressedCacheCleanerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val now = 10 * CompressedCacheCleaner.MAX_AGE_MS

    private fun cacheFile(name: String, ageMs: Long): File =
        tempFolder.newFile(name).apply { setLastModified(now - ageMs) }

    @Test
    fun deletesCompressedFilesOlderThanMaxAge() {
        val stale = cacheFile("compressed_1.mp4", CompressedCacheCleaner.MAX_AGE_MS + 1)

        val deleted = CompressedCacheCleaner.purgeStale(tempFolder.root, now)

        assertEquals(1, deleted)
        assertFalse(stale.exists())
    }

    @Test
    fun keepsRecentCompressedFiles() {
        val recent = cacheFile("compressed_2.mp4", CompressedCacheCleaner.MAX_AGE_MS - 1)

        val deleted = CompressedCacheCleaner.purgeStale(tempFolder.root, now)

        assertEquals(0, deleted)
        assertTrue(recent.exists())
    }

    @Test
    fun keepsFilesExactlyAtMaxAge() {
        val boundary = cacheFile("compressed_5.mp4", CompressedCacheCleaner.MAX_AGE_MS)

        val deleted = CompressedCacheCleaner.purgeStale(tempFolder.root, now)

        assertEquals(0, deleted)
        assertTrue(boundary.exists())
    }

    @Test
    fun ignoresFilesThatAreNotCompressorOutput() {
        val otherMp4 = cacheFile("video.mp4", CompressedCacheCleaner.MAX_AGE_MS + 1)
        val otherPrefix = cacheFile("compressed_3.tmp", CompressedCacheCleaner.MAX_AGE_MS + 1)

        val deleted = CompressedCacheCleaner.purgeStale(tempFolder.root, now)

        assertEquals(0, deleted)
        assertTrue(otherMp4.exists())
        assertTrue(otherPrefix.exists())
    }

    @Test
    fun ignoresDirectories() {
        val dir = tempFolder.newFolder("compressed_4.mp4").apply {
            setLastModified(now - CompressedCacheCleaner.MAX_AGE_MS - 1)
        }

        val deleted = CompressedCacheCleaner.purgeStale(tempFolder.root, now)

        assertEquals(0, deleted)
        assertTrue(dir.exists())
    }

    @Test
    fun returnsZeroWhenDirectoryIsMissing() {
        val missing = File(tempFolder.root, "missing")

        assertEquals(0, CompressedCacheCleaner.purgeStale(missing, now))
    }
}
