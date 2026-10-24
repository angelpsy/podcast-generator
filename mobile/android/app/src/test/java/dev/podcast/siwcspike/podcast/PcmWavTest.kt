package dev.podcast.siwcspike.podcast

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class PcmWavTest {
    @Test fun producesOneValidMonoHeaderAndOrderedClippedSamples() {
        val file = Files.createTempFile("pcm", ".wav").toFile()
        try {
            PcmWav(file, 24000).use {
                it.append(floatArrayOf(0f, 1f, -1f))
                it.append(floatArrayOf(2f, -2f))
                it.finish()
            }
            val data = file.readBytes()
            val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals("RIFF", String(data, 0, 4))
            assertEquals("WAVE", String(data, 8, 4))
            assertEquals(54, data.size)
            assertEquals(46, b.getInt(4))
            assertEquals(1, b.getShort(22).toInt())
            assertEquals(24000, b.getInt(24))
            assertEquals(16, b.getShort(34).toInt())
            assertEquals(10, b.getInt(40))
            assertArrayEquals(shortArrayOf(0, 32767, -32767, 32767, -32767),
                ShortArray(5) { b.getShort(44 + it * 2) })
        } finally { file.delete() }
    }
    @Test fun emptyAndNonFiniteAudioCannotBecomeCompletedWav() {
        val file = Files.createTempFile("pcm", ".wav").toFile()
        try {
            PcmWav(file, 24000).use { wav ->
                assertThrows(IllegalArgumentException::class.java) { wav.finish() }
                assertThrows(IllegalArgumentException::class.java) { wav.append(floatArrayOf(Float.NaN)) }
            }
        } finally { file.delete() }
    }
}
