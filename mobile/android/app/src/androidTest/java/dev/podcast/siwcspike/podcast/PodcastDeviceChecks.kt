package dev.podcast.siwcspike.podcast

import android.app.Activity
import android.app.Instrumentation
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Explicitly launched, dependency-free device checks. Never reads SIWC storage or calls OpenAI. */
class PodcastDeviceChecks : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val result = Bundle()
        val root = File(targetContext.cacheDir, "podcast-device-check-${UUID.randomUUID()}").apply { mkdirs() }
        val passed = mutableListOf<String>()
        var resultCode = Activity.RESULT_CANCELED
        try {
            val currentFile = File(targetContext.noBackupFilesDir, "podcast/current.json")
            val original = currentFile.readBytes()
            val current = JSONObject(original.toString(Charsets.UTF_8)).getJSONObject("current")
            val source = File(root, "source.m4a")
            targetContext.contentResolver.openInputStream(Uri.parse(current.getString("uri")))!!.use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            fun test(name: String, block: () -> Unit) {
                block()
                passed.add(name)
                sendStatus(0, Bundle().apply { putString("passed", name) })
            }
            test("P02_null_publication_preserves_published_file") { storage(root, source, "publication") }
            test("P02_null_owner_retries_cleanup_and_blocks_next_save") { storage(root, source, "owner") }
            test("P02_null_find_preserves_pending_until_recovery") { storage(root, source, "find") }
            test("P03_real_player_retries_same_uri_after_error") { playback(root) }
            test("P04_failed_first_atomic_write_has_no_retry") { initialDraftFailure(root) }
            test("P04_saved_draft_survives_later_write_failure") { savedDraftFailure(root) }
            check(currentFile.readBytes().contentEquals(original)) { "Application journal changed during isolated checks" }
            targetContext.contentResolver.openInputStream(Uri.parse(current.getString("uri")))!!.use {
                check(it.readBytes().contentEquals(source.readBytes())) { "Application episode changed" }
            }
            result.putString("result", "PASS ${passed.size}/6; application journal and episode unchanged")
            result.putString("passed", passed.joinToString("\n"))
            resultCode = Activity.RESULT_OK
        } catch (e: Throwable) {
            result.putString("result", "FAIL after ${passed.size}/6: ${e.javaClass.simpleName}: ${e.message}")
            result.putString("location", e.stackTrace.firstOrNull { it.className.startsWith(javaClass.name) }?.toString())
        } finally {
            root.deleteRecursively()
        }
        finish(resultCode, result)
    }

    private class Journal(file: File) : AudioJournal {
        val real = AtomicAudioJournal(file)
        var failCommitId = ""
        override fun read() = real.read()
        override fun write(state: JSONObject) {
            if (failCommitId.isNotEmpty() && state.optJSONObject("current")?.optString("id") == failCommitId) {
                throw IOException("injected_commit_failure")
            }
            real.write(state)
        }
    }

    private inner class Media : AudioMedia {
        val real = AndroidAudioMedia(targetContext)
        val created = mutableListOf<String>()
        var nullQuery = ""
        var failAfterInsert = false
        override fun find(name: String): String? = if (nullQuery == "find")
            AudioMediaRows.find(null) { error("unexpected_cursor") } else real.find(name)
        override fun isPublished(uri: String): Boolean = if (nullQuery == "publication")
            AudioMediaRows.isPublished(null) else real.isPublished(uri)
        override fun delete(uri: String) {
            if (nullQuery == "owner") AudioMediaRows.deleteOwned(null, targetContext.packageName) {
                error("delete_must_not_run")
            } else real.delete(uri)
        }
        override fun create(name: String): String {
            val uri = real.create(name).also { created.add(it) }
            if (failAfterInsert) throw IOException("injected_crash_after_insert")
            return uri
        }
        override fun copy(uri: String, source: File, checkCancelled: () -> Unit) = real.copy(uri, source, checkCancelled)
        override fun publish(uri: String) = real.publish(uri)
        fun cleanupFixtures() = created.forEach { real.delete(it) }
    }

    private fun episode() = UUID.randomUUID().toString().let {
        JSONObject().put("id", it).put("name", "device-check-$it.m4a")
    }

    private fun fails(block: () -> Unit) {
        var failed = false
        try { block() } catch (_: IOException) { failed = true }
        check(failed) { "Expected controlled IO failure" }
    }

    private fun storage(root: File, source: File, scenario: String) {
        val journal = Journal(File(root, "$scenario.json"))
        val media = Media()
        val store = LatestAudioStore(journal, media)
        try {
            val old = store.save(source, episode()) {}
            val next = episode()
            when (scenario) {
                "publication" -> {
                    journal.failCommitId = next.getString("id")
                    fails { store.save(source, next) {} }
                    journal.failCommitId = ""
                    val pendingUri = journal.read().getJSONObject("pending").getString("uri")
                    check(media.real.isPublished(pendingUri))
                    media.nullQuery = "publication"
                    fails { store.recover() }
                    check(store.current()!!.getString("id") == old.getString("id"))
                    check(journal.read().has("pending") && media.real.isPublished(pendingUri))
                    media.nullQuery = ""
                    store.recover()
                    check(store.current()!!.getString("id") == next.getString("id"))
                    check(!media.real.isPublished(old.getString("uri")))
                    check(!journal.read().has("pending") && !store.cleanupPending())
                    store.recover()
                    check(media.created.size == 2)
                }
                "owner" -> {
                    media.nullQuery = "owner"
                    val published = store.save(source, next) {}
                    check(store.cleanupPending() && media.real.isPublished(old.getString("uri")))
                    fails { store.save(source, episode()) {} }
                    check(media.created.size == 2 && store.cleanupPending())
                    media.nullQuery = ""
                    store.recover()
                    check(!store.cleanupPending() && !media.real.isPublished(old.getString("uri")))
                    check(media.real.isPublished(published.getString("uri")))
                }
                "find" -> {
                    media.failAfterInsert = true
                    fails { store.save(source, next) {} }
                    val pending = journal.read().getJSONObject("pending")
                    check(!pending.has("uri"))
                    check(media.real.find(next.getString("name")) != null)
                    media.nullQuery = "find"
                    fails { store.recover() }
                    check(journal.read().has("pending"))
                    check(media.real.find(next.getString("name")) != null)
                    media.nullQuery = ""
                    store.recover()
                    check(!journal.read().has("pending"))
                    check(media.real.find(next.getString("name")) == null)
                    check(media.real.isPublished(old.getString("uri")))
                }
            }
        } finally { media.cleanupFixtures() }
    }

    private fun metadata() = JSONObject().put("schema", 2).put("id", "test-draft")
        .put("name", "test-draft.m4a").put("topic", "Проверка сохранения")

    private fun initialDraftFailure(root: File) {
        val parent = File(root, "blocked-parent").apply { writeText("not a directory") }
        val journal = AtomicAudioJournal(File(parent, "meta.json"))
        val inMemory = metadata()
        fails { journal.write(inMemory) }
        check(PodcastDraft.resumable(journal, null) == null)
        check(parent.delete() && parent.mkdir())
        check(PodcastDraft.resumable(journal, null) == null)
        journal.write(inMemory)
        check(PodcastDraft.resumable(journal, null)?.getString("id") == "test-draft")
        check(PodcastDraft.resumable(journal, "test-draft") == null)
    }

    private fun savedDraftFailure(root: File) {
        val file = File(root, "saved-meta.json")
        val journal = AtomicAudioJournal(file)
        val saved = metadata().put("script", "Сохранённый русский текст.")
        journal.write(saved)
        val blocked = File(file.path + ".new").apply { mkdir() }
        fails { journal.write(metadata().put("script", "Не сохранённый текст.")) }
        check(PodcastDraft.resumable(journal, null)?.getString("script") == saved.getString("script"))
        // AtomicFile.openRead may already have removed the abandoned .new entry.
        check(!blocked.exists() || blocked.delete())
        journal.write(saved)
        check(PodcastDraft.read(journal)?.getString("script") == saved.getString("script"))
    }

    private fun eventually(block: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ok = false
            runOnMainSync { ok = block() }
            if (ok) return
            Thread.sleep(50)
        }
        error("Player condition timed out")
    }

    private fun playback(root: File) {
        val file = File(root, "recover.wav").apply { writeText("invalid audio for controlled player failure") }
        val error = AtomicBoolean(false)
        lateinit var player: ExoPlayer
        runOnMainSync {
            player = ExoPlayer.Builder(targetContext).build()
            player.volume = 0f
            player.addListener(object : Player.Listener {
                override fun onPlayerError(e: PlaybackException) { error.set(true) }
            })
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            playPodcast(player, true)
        }
        try {
            eventually { error.get() && player.playbackState == Player.STATE_IDLE }
            // Restore bytes at the SAME URI. Exercise the production retry command and real ExoPlayer.
            val samples = 44_100 * 2
            val bytes = samples * 2
            val wav = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN)
            wav.put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVEfmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(44_100).putInt(88_200).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(bytes)
            file.writeBytes(wav.array())
            runOnMainSync { playPodcast(player, false) }
            eventually { player.isPlaying && player.currentPosition >= 250 }
            var pausedAt = 0L
            runOnMainSync { player.pause(); pausedAt = player.currentPosition }
            runOnMainSync {
                playPodcast(player, false)
                check(player.currentPosition >= pausedAt) { "Pause retry reset position" }
            }
            eventually { player.playbackState == Player.STATE_ENDED }
            runOnMainSync { playPodcast(player, false) }
            eventually { player.isPlaying && player.currentPosition in 100..1000 }
        } finally { runOnMainSync { player.release() } }
    }
}
