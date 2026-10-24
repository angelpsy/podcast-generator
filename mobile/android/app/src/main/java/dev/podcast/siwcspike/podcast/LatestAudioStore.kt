package dev.podcast.siwcspike.podcast

import org.json.JSONObject
import java.io.File

/** Only this interface touches MediaStore. Tests inject publication/crash failures. */
interface AudioMedia {
    fun find(name: String): String?
    fun create(name: String): String
    fun copy(uri: String, source: File, checkCancelled: () -> Unit)
    fun isPublished(uri: String): Boolean
    fun publish(uri: String)
    fun delete(uri: String)
}

interface AudioJournal {
    fun read(): JSONObject
    fun write(state: JSONObject)
}

/** Serial worker only. Old audio is deleted strictly AFTER durable publication of new audio. */
class LatestAudioStore(private val journal: AudioJournal, private val media: AudioMedia) {
    fun current(): JSONObject? = journal.read().optJSONObject("current")
    fun cleanupPending(): Boolean = journal.read().optString("cleanup").isNotEmpty()

    fun recover() {
        var state = journal.read()
        state.optJSONObject("pending")?.let { pending ->
            val uri = pending.optString("uri").ifEmpty { media.find(pending.getString("name")) ?: "" }
            if (uri.isNotEmpty() && media.isPublished(uri)) {
                pending.put("uri", uri)
                commit(state, pending)
            } else {
                if (uri.isNotEmpty()) media.delete(uri)
                state.remove("pending")
                journal.write(state)
            }
        }
        cleanup()
    }

    private fun commit(state: JSONObject, episode: JSONObject) {
        val old = state.optJSONObject("current")?.optString("uri").orEmpty()
        state.put("current", episode).remove("pending")
        if (old.isNotEmpty() && old != episode.getString("uri")) state.put("cleanup", old)
        journal.write(state)
    }

    fun cleanup() {
        val state = journal.read()
        val uri = state.optString("cleanup")
        if (uri.isEmpty()) return
        check(uri != state.optJSONObject("current")?.optString("uri")) { "invalid_cleanup" }
        media.delete(uri)
        state.remove("cleanup")
        journal.write(state)
    }

    fun save(source: File, episode: JSONObject, checkCancelled: () -> Unit): JSONObject {
        recover() // Prevent a new job from accumulating files after failed cleanup.
        current()?.let { if (it.optString("id") == episode.getString("id")) return it }
        checkCancelled()
        val state = journal.read()
        val pending = JSONObject(episode.toString())
        state.put("pending", pending)
        journal.write(state) // Name is durable BEFORE insert; recover can find the crash-window row.
        val uri = media.create(pending.getString("name"))
        pending.put("uri", uri)
        journal.write(state)
        media.copy(uri, source, checkCancelled)
        checkCancelled()
        media.publish(uri)
        // Publication is the commit point. Late cancellation must not remove a complete result.
        commit(state, pending)
        try { cleanup() } catch (_: Exception) { /* Persisted cleanup is retried before next run. */ }
        return pending
    }
}
