package dev.podcast.siwcspike.podcast

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SpeechCheckpointTest {
    private fun cache(root: File, identity: String, rate: Int) = SpeechCheckpoint(root, identity, rate) { path -> object : AudioJournal {
        override fun read() = if (path.exists()) org.json.JSONObject(path.readText()) else org.json.JSONObject()
        override fun write(state: org.json.JSONObject) { path.writeText(state.toString()) }
    } }
    private fun fixture(block: (File) -> Unit) { val root = Files.createTempDirectory("speech-check").toFile(); try { block(root) } finally { root.deleteRecursively() } }
    private fun chunk(cache: SpeechCheckpoint, index: Int, frames: Int = 800) { PcmWav(cache.partial(index), 8000).use { wav -> wav.append(FloatArray(frames) { 0.1f }); wav.finish() }; cache.commit(index) }
    @Test fun completedChunksSurviveReopenAndAssembleWithoutHoldingEpisodeInMemory() = fixture { root ->
        val cache = cache(root, "ru|script", 8000); chunk(cache, 0); chunk(cache, 1, 1600)
        val restored = cache(root, "ru|script", 8000)
        assertTrue(restored.ready(0)); assertTrue(restored.ready(1))
        val out = File(root, "speech.wav"); assertEquals(300L, restored.assemble(2, out) {}); assertEquals(4844L, out.length())
    }
    @Test fun uncommittedPartialIsNeverReused() = fixture { root ->
        val cache = cache(root, "ru|script", 8000); chunk(cache, 0)
        cache.partial(1).writeBytes(ByteArray(50))
        val restored = cache(root, "ru|script", 8000); assertTrue(restored.ready(0)); assertFalse(restored.ready(1))
    }
    @Test fun changedTextLanguageRateOrVoiceIdentityCannotReuseOldChunks() = fixture { root ->
        chunk(cache(root, "ru|one", 8000), 0)
        try { cache(root, "en|one", 8000); fail() } catch (_: IllegalStateException) { }
        try { cache(root, "ru|two", 8000); fail() } catch (_: IllegalStateException) { }
        try { cache(root, "ru|one", 16000); fail() } catch (_: IllegalStateException) { }
    }
    @Test fun alteredPcmFailsHashEvenWhenHeaderIsValid() = fixture { root ->
        val cache = cache(root, "ru|script", 8000); chunk(cache, 0)
        java.io.RandomAccessFile(File(root, "chunks/0.wav"), "rw").use { it.seek(100); it.write(99) }
        try { cache.ready(0); fail() } catch (_: IllegalStateException) { }
    }
    @Test fun cancellationDuringAssemblyKeepsCommittedChunksAndNoFinalWav() = fixture { root ->
        val cache = cache(root, "ru|script", 8000); chunk(cache, 0)
        val out = File(root, "speech.wav")
        try { cache.assemble(1, out) { throw java.util.concurrent.CancellationException() }; fail() } catch (_: java.util.concurrent.CancellationException) { }
        assertFalse(out.exists()); assertTrue(cache.ready(0))
        cache.assemble(1, out) {}; assertTrue(out.exists())
    }
}
