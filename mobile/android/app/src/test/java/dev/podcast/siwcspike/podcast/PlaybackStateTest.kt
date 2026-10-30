package dev.podcast.siwcspike.podcast

import org.junit.Assert.*
import org.junit.Test

class PlaybackStateTest {
    @Test fun roundTripKeepsQueuePositionAndSettings() {
        val value = SavedPlayback(listOf("a", "b"), "b", 12345, 1.25f, 2)
        assertEquals(value, SavedPlayback.parse(value.json()))
    }
    @Test fun missingEarlierItemDoesNotShiftCurrentPodcast() {
        val saved = SavedPlayback(listOf("a", "b", "c"), "b", 15000)
        assertEquals(0, saved.resolvedIndex(listOf("b", "c")))
        assertEquals(15000, saved.resolvedPosition(listOf("b", "c")))
    }
    @Test fun missingCurrentItemStartsAvailableQueueAtZero() {
        val saved = SavedPlayback(listOf("a", "b"), "b", 15000)
        assertEquals(0, saved.resolvedIndex(listOf("a")))
        assertEquals(0, saved.resolvedPosition(listOf("a")))
    }
    @Test fun invalidSettingsAreSanitizedAndIdsDeduplicated() {
        val saved = SavedPlayback.parse("""{"ids":["a","a"],"position":-3,"speed":-1,"repeat":20}""")
        assertEquals(listOf("a"), saved.ids); assertEquals(0, saved.position)
        assertEquals(1f, saved.speed); assertEquals(0, saved.repeat)
    }
    @Test fun emptyStorageProducesNoAutoplayIntent() { assertTrue(SavedPlayback.parse(null).ids.isEmpty()) }
    @Test(expected = Exception::class) fun corruptedStorageIsNotSilentlyReset() { SavedPlayback.parse("broken") }
}
