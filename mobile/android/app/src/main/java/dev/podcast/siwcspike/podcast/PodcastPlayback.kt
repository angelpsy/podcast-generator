package dev.podcast.siwcspike.podcast

import androidx.media3.common.Player

/** Main looper only. An error leaves the same item idle; play() alone cannot retry it. */
internal fun playPodcast(player: Player, mediaChanged: Boolean) {
    if (mediaChanged || player.playbackState == Player.STATE_IDLE) player.prepare()
    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
    player.play()
}
