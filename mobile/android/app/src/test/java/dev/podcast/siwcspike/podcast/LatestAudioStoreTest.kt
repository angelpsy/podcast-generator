package dev.podcast.siwcspike.podcast

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

class LatestAudioStoreTest {
    private class Journal : AudioJournal {
        var raw = "{}"
        var failCommit = false
        override fun read() = JSONObject(raw)
        override fun write(state: JSONObject) {
            if (failCommit && state.optJSONObject("current")?.optString("id") == "new") {
                failCommit = false
                throw IOException("crash before journal commit")
            }
            raw = state.toString()
        }
    }
    private class Media : AudioMedia {
        val rows = linkedMapOf<String, Boolean>()
        val deleted = mutableListOf<String>()
        var failCopy = false
        var failDelete = false
        var nullFindQuery = false
        var nullPublicationQuery = false
        var nullOwnershipQuery = false
        var afterPublish: () -> Unit = {}
        override fun find(name: String): String? {
            val uri = "content://media/$name"
            val cursor = if (nullFindQuery) null else TestMediaCursor(if (rows.containsKey(uri)) 1L else null).cursor
            return AudioMediaRows.find(cursor) { uri }
        }
        override fun create(name: String): String = "content://media/$name".also { rows[it] = false }
        override fun copy(uri: String, source: File, checkCancelled: () -> Unit) {
            checkCancelled()
            if (failCopy) throw IOException("disk full")
        }
        override fun isPublished(uri: String): Boolean {
            val value = rows[uri]?.let { if (it) 0 else 1 }
            return AudioMediaRows.isPublished(if (nullPublicationQuery) null else TestMediaCursor(value).cursor)
        }
        override fun publish(uri: String) { rows[uri] = true; afterPublish() }
        override fun delete(uri: String) {
            if (failDelete) throw IOException("delete failed")
            val owner = if (rows.containsKey(uri)) "our.app" else null
            AudioMediaRows.deleteOwned(if (nullOwnershipQuery) null else TestMediaCursor(owner).cursor, "our.app") {
                rows.remove(uri); deleted.add(uri); 1
            }
        }
    }
    private class Fixture {
        val journal = Journal()
        val media = Media()
        val store = LatestAudioStore(journal, media)
        fun save(id: String, check: () -> Unit = {}) = store.save(File("fixture.m4a"),
            JSONObject().put("id", id).put("name", "$id.m4a"), check)
    }
    @Test fun replacementDeletesOnlyPreviousOwnedReferenceAfterPublication() {
        val f = Fixture()
        val old = f.save("old").getString("uri")
        f.media.rows["content://media/user-copy"] = true
        f.media.afterPublish = { assertTrue(f.media.rows.containsKey(old)) }
        val new = f.save("new").getString("uri")
        assertEquals(listOf(old), f.media.deleted)
        assertEquals(setOf(new, "content://media/user-copy"), f.media.rows.keys)
        assertEquals(new, f.store.current()!!.getString("uri"))
    }
    @Test fun failedWritePreservesOldAndRecoveryRemovesOnlyPendingAudio() {
        val f = Fixture()
        val old = f.save("old").getString("uri")
        f.media.failCopy = true
        assertThrows(IOException::class.java) { f.save("new") }
        assertEquals(old, f.store.current()!!.getString("uri"))
        f.store.recover()
        assertEquals(setOf(old), f.media.rows.keys)
    }
    @Test fun crashAfterPublishRecoversNewFileWithoutPublishingDuplicate() {
        val f = Fixture()
        f.save("old")
        f.journal.failCommit = true
        assertThrows(IOException::class.java) { f.save("new") }
        assertTrue(f.media.rows["content://media/new.m4a"] == true)
        f.store.recover()
        assertEquals(setOf("content://media/new.m4a"), f.media.rows.keys)
        assertEquals("new", f.store.current()!!.getString("id"))
        f.save("new")
        assertEquals(1, f.media.rows.size)
    }
    @Test fun cancellationBeforePublishKeepsPreviousResult() {
        val f = Fixture()
        val old = f.save("old").getString("uri")
        var checks = 0
        assertThrows(CancellationException::class.java) {
            f.save("new") { if (++checks == 3) throw CancellationException() }
        }
        f.store.recover()
        assertEquals(setOf(old), f.media.rows.keys)
    }
    @Test fun lateCancellationDoesNotDeletePublishedResult() {
        val f = Fixture()
        f.save("old")
        var cancelled = false
        f.media.afterPublish = { cancelled = true }
        f.save("new") { if (cancelled) throw CancellationException() }
        assertEquals("new", f.store.current()!!.getString("id"))
        assertEquals(setOf("content://media/new.m4a"), f.media.rows.keys)
    }
    @Test fun failedCleanupIsRetriedAndPreventsAccumulation() {
        val f = Fixture()
        f.save("old")
        f.media.failDelete = true
        f.save("new")
        assertTrue(f.store.cleanupPending())
        assertEquals("new", f.store.current()!!.getString("id"))
        assertThrows(IOException::class.java) { f.save("third") }
        assertEquals(2, f.media.rows.size)
        f.media.failDelete = false
        f.store.recover()
        assertEquals(setOf("content://media/new.m4a"), f.media.rows.keys)
        assertFalse(f.store.cleanupPending())
    }
    @Test fun insertCrashWindowIsRecoveredUsingDurableUniqueName() {
        val f = Fixture()
        val old = f.save("old").getString("uri")
        f.journal.write(f.journal.read().put("pending", JSONObject().put("id", "new").put("name", "new.m4a")))
        f.media.create("new.m4a") // Process dies before persisting returned URI.
        f.store.recover()
        assertEquals(setOf(old), f.media.rows.keys)
    }

    @Test fun unavailablePublicationQueryPreservesPublishedFileUntilRecoveryCanConfirmIt() {
        val f = Fixture()
        f.save("old")
        f.journal.failCommit = true
        assertThrows(IOException::class.java) { f.save("new") }
        val before = f.journal.raw
        f.media.nullPublicationQuery = true
        assertThrows(IOException::class.java) { f.store.recover() }
        assertEquals(before, f.journal.raw)
        assertEquals(setOf("content://media/old.m4a", "content://media/new.m4a"), f.media.rows.keys)
        assertTrue(f.media.deleted.isEmpty())
        f.media.nullPublicationQuery = false
        f.store.recover()
        assertEquals("new", f.store.current()!!.getString("id"))
        f.save("new") // Retry cannot create a duplicate of the recovered publication.
        assertEquals(setOf("content://media/new.m4a"), f.media.rows.keys)
    }

    @Test fun unavailableOwnershipQueryRetainsCleanupAndBlocksAnotherPublication() {
        val f = Fixture()
        f.save("old")
        f.media.nullOwnershipQuery = true
        f.save("new")
        assertTrue(f.store.cleanupPending())
        assertEquals("new", f.store.current()!!.getString("id"))
        assertThrows(IOException::class.java) { f.save("third") }
        assertEquals(2, f.media.rows.size)
        f.media.nullOwnershipQuery = false
        f.store.recover()
        assertFalse(f.store.cleanupPending())
        assertEquals(setOf("content://media/new.m4a"), f.media.rows.keys)
        f.save("third")
        assertEquals(setOf("content://media/third.m4a"), f.media.rows.keys)
    }

    @Test fun unavailableFindQueryDoesNotForgetAnInsertBeforeUriWasSaved() {
        val f = Fixture()
        val old = f.save("old").getString("uri")
        f.journal.write(f.journal.read().put("pending", JSONObject().put("id", "new").put("name", "new.m4a")))
        f.media.create("new.m4a")
        val before = f.journal.raw
        f.media.nullFindQuery = true
        assertThrows(IOException::class.java) { f.store.recover() }
        assertEquals(before, f.journal.raw)
        assertTrue(f.media.rows.containsKey("content://media/new.m4a"))
        f.media.nullFindQuery = false
        f.store.recover()
        assertEquals(setOf(old), f.media.rows.keys)
        assertFalse(f.journal.read().has("pending"))
    }
}
