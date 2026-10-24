package dev.podcast.siwcspike.podcast

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToInt

/** One PCM16 mono stream; headers are finalized only after all chunks succeed. */
class PcmWav(file: File, private val sampleRate: Int) : Closeable {
    private val output = RandomAccessFile(file, "rw")
    private var frames = 0L
    init {
        require(sampleRate in 8000..192000)
        output.setLength(0)
        output.write(ByteArray(44))
    }
    fun append(samples: FloatArray) {
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, value ->
            require(value.isFinite()) { "non_finite_pcm" }
            val pcm = (value.coerceIn(-1f, 1f) * 32767).roundToInt()
            bytes[index * 2] = pcm.toByte()
            bytes[index * 2 + 1] = (pcm shr 8).toByte()
        }
        output.write(bytes)
        frames += samples.size
    }
    fun finish(): Long {
        require(frames > 0 && frames * 2 < Int.MAX_VALUE - 44)
        output.seek(0)
        fun int(value: Int) = output.writeInt(Integer.reverseBytes(value))
        fun short(value: Int) = output.writeShort(java.lang.Short.reverseBytes(value.toShort()).toInt())
        output.writeBytes("RIFF"); int((frames * 2 + 36).toInt()); output.writeBytes("WAVEfmt ")
        int(16); short(1); short(1); int(sampleRate); int(sampleRate * 2); short(2); short(16)
        output.writeBytes("data"); int((frames * 2).toInt())
        output.fd.sync()
        return frames * 1000 / sampleRate
    }
    override fun close() = output.close()
}
