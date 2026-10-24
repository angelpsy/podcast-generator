package dev.podcast.siwcspike.podcast

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class M4aEncoder(private val context: Context) {
    private var active: Transformer? = null
    private var result: CompletableFuture<Unit>? = null

    /** Main looper only; completion validation is done by the worker. */
    fun start(wav: File, output: File, future: CompletableFuture<Unit>) {
        check(active == null)
        result = future
        try {
            val factory = DefaultEncoderFactory.Builder(context)
                .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder()
                    .setBitrate(96_000).setProfile(MediaCodecInfo.CodecProfileLevel.AACObjectLC).build())
                .build()
            val transformer = Transformer.Builder(context).setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(factory).addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        active = null; result = null; future.complete(Unit)
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        active = null; result = null; future.completeExceptionally(exportException)
                    }
                }).build()
            active = transformer
            transformer.start(EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(wav))).build(), output.path)
        } catch (e: Exception) {
            active = null; result = null; future.completeExceptionally(e)
        }
    }
    fun cancel() {
        active?.cancel()
        active = null
        result?.completeExceptionally(CancellationException())
        result = null
    }

    companion object {
        fun inspect(file: File): JSONObject {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.path)
                check(extractor.trackCount == 1) { "invalid_m4a_tracks" }
                val format = extractor.getTrackFormat(0)
                android.util.Log.i("PodcastSpike", JSONObject()
                    .put("inspectionMime", format.getString(MediaFormat.KEY_MIME))
                    .put("channels", format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    .put("rate", format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                    .put("durationUs", format.getLong(MediaFormat.KEY_DURATION))
                    .put("aacObjectType", format.getByteBuffer("csd-0")?.let { (it.get(0).toInt() and 0xff) shr 3 })
                    .toString())
                check(format.getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm") { "invalid_audio_codec" }
                val config = checkNotNull(format.getByteBuffer("csd-0")) { "missing_aac_config" }
                check((config.get(0).toInt() and 0xff) shr 3 == 2) { "not_aac_lc" }
                check(format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1) { "invalid_audio_channels" }
                val duration = format.getLong(MediaFormat.KEY_DURATION) / 1000
                check(duration > 0 && file.length() > 0) { "empty_m4a" }
                extractor.selectTrack(0)
                val firstTime = extractor.sampleTime
                val firstSize = extractor.readSampleData(java.nio.ByteBuffer.allocate(64 * 1024), 0)
                android.util.Log.i("PodcastSpike", "firstAudioSample timeUs=$firstTime bytes=$firstSize")
                // AAC priming may have a negative timestamp. Presence is checked by reading a packet.
                check(firstSize > 0) { "missing_audio_samples" }
                return JSONObject().put("codec", "AAC-LC / M4A").put("durationMs", duration)
                    .put("sampleRate", format.getInteger(MediaFormat.KEY_SAMPLE_RATE)).put("bytes", file.length())
            } finally { extractor.release() }
        }
    }
}
