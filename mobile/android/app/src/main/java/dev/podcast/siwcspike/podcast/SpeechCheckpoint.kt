package dev.podcast.siwcspike.podcast

import java.io.File
import java.io.RandomAccessFile
import org.json.JSONObject

/** One verified PCM16 WAV per finished fragment; only the current fragment is held in memory. */
class SpeechCheckpoint(private val directory: File, identity: String, private val rate: Int, journalFactory: (File) -> AudioJournal = ::AtomicAudioJournal) {
    private val folder = File(directory, "chunks").apply { check(mkdirs() || isDirectory) }
    private val journal = journalFactory(File(folder, "manifest.json"))
    private val key = ContentHash.of("speech-v1|$rate|$identity|sid0|speed1|steps8|supertonic3-int8")
    private val manifest = journal.read().let { old ->
        if (old.length() == 0) JSONObject().put("identity", key).put("items", JSONObject()).also(journal::write)
        else old.also { check(it.getString("identity") == key) { "checkpoint_mismatch" } }
    }
    fun partial(index: Int) = File(folder, "$index.part")
    private fun file(index: Int) = File(folder, "$index.wav")
    private fun digest(file: File): String {
        val hash = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val bytes = ByteArray(65536); while (true) { val n = input.read(bytes); if (n < 0) break; hash.update(bytes, 0, n) } }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    private fun validate(file: File): Long = RandomAccessFile(file, "r").use { input ->
        check(input.length() > 44 && (input.length() - 44) % 2 == 0L) { "checkpoint_mismatch" }
        fun ascii(n: Int) = ByteArray(n).also(input::readFully).toString(Charsets.US_ASCII)
        fun int() = Integer.reverseBytes(input.readInt())
        fun short() = java.lang.Short.reverseBytes(input.readShort()).toInt()
        check(ascii(4) == "RIFF" && int().toLong() == input.length() - 8 && ascii(8) == "WAVEfmt " && int() == 16 && short() == 1 && short() == 1 && int() == rate && int() == rate * 2 && short() == 2 && short() == 16 && ascii(4) == "data" && int().toLong() == input.length() - 44) { "checkpoint_mismatch" }
        (input.length() - 44) / 2
    }
    fun ready(index: Int): Boolean {
        val info = manifest.getJSONObject("items").optJSONObject(index.toString()) ?: return false
        val wav = file(index)
        check(validate(wav) == info.getLong("frames") && digest(wav) == info.getString("hash")) { "checkpoint_mismatch" }
        return true
    }
    fun commit(index: Int) {
        val partial = partial(index); val frames = validate(partial)
        check(partial.renameTo(file(index))) { "chunk_rename_failed" }
        manifest.getJSONObject("items").put(index.toString(), JSONObject().put("frames", frames).put("hash", digest(file(index))))
        journal.write(manifest)
    }
    fun assemble(count: Int, destination: File, checkCancelled: () -> Unit): Long {
        val partial = File(destination.parentFile, "speech.part")
        val duration = PcmWav(partial, rate).use { output ->
            repeat(count) { index -> checkCancelled(); check(ready(index)); output.appendPcm(file(index), checkCancelled) }
            output.finish()
        }
        check(partial.renameTo(destination)) { "wav_rename_failed" }; return duration
    }
}
