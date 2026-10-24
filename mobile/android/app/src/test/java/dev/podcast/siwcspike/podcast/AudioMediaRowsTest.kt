package dev.podcast.siwcspike.podcast

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AudioMediaRowsTest {
    @Test fun nullQueriesFailWithoutMakingDestructiveDecisions() {
        assertThrows(IOException::class.java) { AudioMediaRows.find(null) { error("No known ID") } }
        assertThrows(IOException::class.java) { AudioMediaRows.isPublished(null) }
        assertThrows(IOException::class.java) { AudioMediaRows.deleteOwned(null, "our.app") { error("Must not delete") } }
    }

    @Test fun emptyCursorsConfirmAbsenceAndAreClosed() {
        val find = TestMediaCursor(null)
        val publication = TestMediaCursor(null)
        val deletion = TestMediaCursor(null)
        assertNull(AudioMediaRows.find(find.cursor) { error("No known ID") })
        assertFalse(AudioMediaRows.isPublished(publication.cursor))
        AudioMediaRows.deleteOwned(deletion.cursor, "our.app") { error("Already absent") }
        assertTrue(find.closed && publication.closed && deletion.closed)
    }

    @Test fun publicationAndOwnershipAreReadBeforeDeleting() {
        assertEquals("content://media/7", AudioMediaRows.find(TestMediaCursor(7L).cursor) { "content://media/$it" })
        assertTrue(AudioMediaRows.isPublished(TestMediaCursor(0).cursor))
        assertFalse(AudioMediaRows.isPublished(TestMediaCursor(1).cursor))
        val owner = TestMediaCursor("our.app")
        var deletions = 0
        AudioMediaRows.deleteOwned(owner.cursor, "our.app") { assertTrue(owner.closed); deletions++; 1 }
        assertEquals(1, deletions)
    }

    @Test fun foreignOwnerAndFailedDeleteRemainErrors() {
        val foreign = TestMediaCursor("another.app")
        assertThrows(IllegalStateException::class.java) {
            AudioMediaRows.deleteOwned(foreign.cursor, "our.app") { error("Foreign audio must stay") }
        }
        assertTrue(foreign.closed)
        assertThrows(IllegalStateException::class.java) {
            AudioMediaRows.deleteOwned(TestMediaCursor("our.app").cursor, "our.app") { 0 }
        }
    }
}
