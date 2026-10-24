package dev.podcast.siwcspike.podcast

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PodcastDraftTest {
    private class Journal : AudioJournal {
        var raw = "{}"
        var failWrite = false
        var failRead = false
        override fun read(): JSONObject {
            if (failRead) throw IOException("unavailable storage")
            return JSONObject(raw)
        }
        override fun write(state: JSONObject) {
            if (failWrite) throw IOException("disk full")
            raw = state.toString()
        }
    }
    private fun meta() = JSONObject().put("schema", 2).put("id", "draft").put("name", "podcast-draft.m4a")
        .put("topic", "Почему море солёное?")

    @Test fun failedInitialMetadataWriteDoesNotOfferANonexistentDraft() {
        val journal = Journal()
        val inMemory = meta()
        journal.failWrite = true
        assertThrows(IOException::class.java) { journal.write(inMemory) }
        assertNull(PodcastDraft.resumable(journal, "last-saved"))
        journal.failWrite = false // Freeing space does not magically persist the failed write.
        assertNull(PodcastDraft.read(journal))
        assertNull(PodcastDraft.resumable(journal, "last-saved"))
        journal.write(meta()) // An explicit new creation can now succeed.
        assertEquals("draft", PodcastDraft.resumable(journal, "last-saved")!!.getString("id"))
    }

    @Test fun failureBeforeDraftWriteAlsoLeavesContinuationUnavailable() {
        val inputJournal = Journal().apply { failWrite = true }
        val draftJournal = Journal()
        assertThrows(IOException::class.java) { inputJournal.write(JSONObject().put("topic", "Тема")) }
        assertNull(PodcastDraft.resumable(draftJournal, null))
    }

    @Test fun savedScriptIsAvailableEvenWhenTheNextAttemptFailsBeforeLoadingItsMetadata() {
        val journal = Journal()
        journal.write(meta().put("script", "Сохранённый русский текст."))
        val recovered = PodcastDraft.resumable(journal, "previous")!!
        assertEquals("Сохранённый русский текст.", recovered.getString("script"))
    }

    @Test fun missingInvalidAndAlreadyPublishedDraftsAreNotResumable() {
        val journal = Journal()
        assertNull(PodcastDraft.resumable(journal, null))
        for (invalid in listOf(meta().put("schema", 1), meta().put("id", ""), meta().put("topic", " "),
            meta().put("name", ""), meta().put("topic", "я".repeat(501)))) {
            journal.write(invalid)
            assertNull(PodcastDraft.resumable(journal, "previous"))
        }
        journal.write(meta())
        assertNull(PodcastDraft.resumable(journal, "draft"))
    }

    @Test fun aReadFailurePreservesTheDurableDraftForALaterRetry() {
        val journal = Journal()
        journal.write(meta())
        val before = journal.raw
        journal.failRead = true
        assertThrows(IOException::class.java) { PodcastDraft.resumable(journal, null) }
        assertEquals(before, journal.raw)
        journal.failRead = false
        assertEquals("draft", PodcastDraft.resumable(journal, null)!!.getString("id"))
    }
}
