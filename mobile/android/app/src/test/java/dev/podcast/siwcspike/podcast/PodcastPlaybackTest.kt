package dev.podcast.siwcspike.podcast

import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class PodcastPlaybackTest {
    private class Playback(var state: Int, var position: Long = 12_000) {
        val commands = mutableListOf<String>()
        var playWhenReady = false
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getPlaybackState" -> state
                "prepare" -> { commands.add("prepare"); state = Player.STATE_BUFFERING; null }
                "seekTo" -> { commands.add("seek"); position = args!![0] as Long; null }
                "play" -> { commands.add("play"); playWhenReady = true; null }
                else -> error("Unexpected Player call: ${method.name}")
            }
        } as Player
    }

    @Test fun retryAfterPlaybackErrorPreparesTheSameUriWithoutResettingPosition() {
        val failed = Playback(Player.STATE_IDLE) // Media3 state after onPlayerError.
        playPodcast(failed.player, mediaChanged = false)
        assertEquals(listOf("prepare", "play"), failed.commands)
        assertEquals(Player.STATE_BUFFERING, failed.state)
        assertTrue(failed.playWhenReady)
        assertEquals(12_000L, failed.position)
    }

    @Test fun ordinaryResumeDoesNotPrepareOrRewind() {
        val paused = Playback(Player.STATE_READY)
        playPodcast(paused.player, mediaChanged = false)
        assertEquals(listOf("play"), paused.commands)
        assertEquals(12_000L, paused.position)
    }

    @Test fun completedEpisodeRestartsFromBeginning() {
        val ended = Playback(Player.STATE_ENDED, position = 75_000)
        playPodcast(ended.player, mediaChanged = false)
        assertEquals(listOf("seek", "play"), ended.commands)
        assertEquals(0L, ended.position)
    }

    @Test fun aNewMediaItemIsPreparedBeforePlayback() {
        val replacement = Playback(Player.STATE_READY)
        playPodcast(replacement.player, mediaChanged = true)
        assertEquals(listOf("prepare", "play"), replacement.commands)
    }
}
