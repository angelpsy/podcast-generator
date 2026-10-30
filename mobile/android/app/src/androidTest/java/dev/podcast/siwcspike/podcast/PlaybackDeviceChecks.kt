package dev.podcast.siwcspike.podcast

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.*
import androidx.room.Room
import com.google.common.util.concurrent.ListenableFuture
import dev.podcast.siwcspike.MainActivity
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Uses the actual service/MediaController and existing audio. Never touches SIWC storage. */
class PlaybackDeviceChecks : Instrumentation() {
    private var observed: MediaController? = null
    private var migrationOnly = false
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); migrationOnly = arguments?.getBoolean("migrationOnly", false) == true || arguments?.getString("migrationOnly") == "true"; start() }
    private fun await(message: String, test: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ok = false; runOnMainSync { ok = test() }
            if (ok) return
            Thread.sleep(100)
        }
        var detail = ""
        runOnMainSync {
            observed?.let { c -> detail = "id=${c.currentMediaItem?.mediaId};index=${c.currentMediaItemIndex};count=${c.mediaItemCount};playing=${c.isPlaying};wantPlay=${c.playWhenReady};state=${c.playbackState};error=${c.playerError?.errorCodeName};next=${c.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)};queue=" + (0 until c.mediaItemCount).joinToString(",") { c.getMediaItemAt(it).mediaId } }
        }
        error("Timeout: $message; $detail")
    }
    override fun onStart() {
        val passed = mutableListOf<String>()
        var controller: MediaController? = null
        var fixtureId = ""
        var fixtureLibrary: PodcastLibrary? = null
        val temporary = File(targetContext.cacheDir, "playback-check-${UUID.randomUUID()}").apply { mkdirs() }
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        fun done(name: String) { passed.add(name); sendStatus(0, Bundle().apply { putString("passed", name) }) }
        try {
            migration(); done("Room_1_to_current_preserves_library_and_restores_playback")
            if (migrationOnly) {
                result.putString("result", "PASS 1/1 isolated Room migration")
                finish(Activity.RESULT_OK, result); return
            }
            startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val dao = PodcastDatabases.get(targetContext).podcasts()
            val ids = dao.all().filter { it.status == "READY" }.map { it.id }
            check(ids.size >= 2) { "Two saved episodes required" }
            lateinit var connection: ListenableFuture<MediaController>
            runOnMainSync {
                connection = MediaController.Builder(targetContext, SessionToken(targetContext,
                    ComponentName(targetContext, PlaybackService::class.java))).buildAsync()
            }
            val c = connection.get(15, TimeUnit.SECONDS); controller = c; observed = c
            // Wait for restoration before explicitly replacing the queue.
            Thread.sleep(1000)
            runOnMainSync {
                c.setMediaItems(ids.take(2).map { MediaItem.Builder().setMediaId(it)
                    .setUri("file:///deliberately-ignored-client-uri").build() })
                c.prepare(); c.play()
            }
            await("playing library IDs") { c.isPlaying && c.currentPosition > 500 && c.currentMediaItem?.mediaId == ids[0] && c.currentMediaItemIndex == 0 }
            done("library_IDs_resolve_without_trusting_client_URI")
            targetContext.startActivity(Intent().setComponent(ComponentName(context.packageName,
                FocusProbeActivity::class.java.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("transient focus loss from separate UID") {
                !c.isPlaying && c.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
            }
            await("focus returned") { c.isPlaying }
            done("transient_audio_focus_pause_and_resume_different_UID")
            runOnMainSync { c.pause(); c.seekTo(15_000); c.setPlaybackSpeed(1.25f); c.repeatMode = Player.REPEAT_MODE_ALL }
            await("seek/speed/repeat") { !c.isPlaying && c.currentPosition in 14500..16000 &&
                c.playbackParameters.speed == 1.25f && c.repeatMode == Player.REPEAT_MODE_ALL }
            val saveDeadline = SystemClock.elapsedRealtime() + 10000
            while (SystemClock.elapsedRealtime() < saveDeadline) {
                val saved = SavedPlayback.parse(dao.playback())
                if (saved.currentId == ids[0] && saved.position in 14500..16000 && saved.speed == 1.25f && saved.repeat == 2) break
                Thread.sleep(100)
            }
            val saved = SavedPlayback.parse(dao.playback())
            check(saved.currentId == ids[0] && saved.position in 14500..16000 && saved.speed == 1.25f && saved.repeat == 2)
            done("pause_seek_speed_repeat_persisted")
            runOnMainSync { c.seekToNextMediaItem(); c.play() }
            await("next") { c.currentMediaItem?.mediaId == ids[1] && c.isPlaying }
            runOnMainSync { c.seekToPreviousMediaItem() }
            await("previous") { c.currentMediaItem?.mediaId == ids[0] && c.isPlaying }
            done("previous_next")
            runOnMainSync { c.pause(); c.seekTo(c.duration - 500); c.play() }
            await("automatic transition") { c.currentMediaItem?.mediaId == ids[1] && c.isPlaying }
            done("automatic_queue_transition")
            // A missing library ID is skipped and the remaining entry still plays.
            runOnMainSync {
                c.setMediaItems(listOf(MediaItem.Builder().setMediaId("missing-${UUID.randomUUID()}").build(),
                    MediaItem.Builder().setMediaId(ids[0]).build())); c.prepare(); c.play()
            }
            await("missing item skipped") { c.mediaItemCount == 1 && c.currentMediaItem?.mediaId == ids[0] && c.isPlaying }
            done("missing_ID_skipped_without_repeat_loop")
            val media = AndroidAudioMedia(targetContext)
            fixtureId = UUID.randomUUID().toString()
            val library = PodcastLibrary(PodcastDatabases.get(targetContext),
                File(targetContext.noBackupFilesDir, "podcast"), media).also { it.initialize() }
            fixtureLibrary = library
            val metadata = JSONObject().put("schema", 2).put("id", fixtureId)
                .put("name", "playback-check-$fixtureId.m4a").put("topic", "Проверка ошибки плеера")
            val bad = File(temporary, "bad.m4a").apply { writeText("invalid audio") }
            val valid = File(temporary, "valid.m4a")
            val original = JSONObject(dao.asset(ids[0])!!.journal).getJSONObject("current").getString("uri")
            targetContext.contentResolver.openInputStream(android.net.Uri.parse(original))!!.use { input ->
                valid.outputStream().use { input.copyTo(it) }
            }
            library.begin(metadata)
            val badUri = library.save(bad, metadata) {}.getString("uri")
            runOnMainSync {
                c.setMediaItems(listOf(fixtureId, ids[0]).map { MediaItem.Builder().setMediaId(it).build() })
                c.prepare(); c.play()
            }
            await("decode failure skips to healthy track") {
                c.mediaItemCount == 1 && c.currentMediaItem?.mediaId == ids[0] && c.isPlaying
            }
            done("real_decode_error_skips_broken_item")
            runOnMainSync {
                c.setMediaItem(MediaItem.Builder().setMediaId(fixtureId).build()); c.prepare(); c.play()
            }
            await("last failed URI retained idle") { c.playerError != null && c.playbackState == Player.STATE_IDLE && !c.playWhenReady }
            media.copy(badUri, valid) {}
            runOnMainSync { playPodcast(c, false) }
            await("same URI retried after repair") { c.isPlaying && c.currentPosition > 500 }
            done("service_P03_retries_same_URI_after_repair")
            lateinit var cleanup: ListenableFuture<SessionResult>
            runOnMainSync { cleanup = c.sendCustomCommand(SessionCommand(PlaybackService.REMOVE, Bundle.EMPTY),
                Bundle().apply { putString("id", fixtureId) }) }
            check(cleanup.get(10, TimeUnit.SECONDS).resultCode == SessionResult.RESULT_SUCCESS)
            library.delete(fixtureId); fixtureId = ""
            runOnMainSync { c.setMediaItem(MediaItem.Builder().setMediaId(ids[0]).build()); c.prepare(); c.play() }
            await("healthy track before removal") { c.isPlaying && c.currentMediaItem?.mediaId == ids[0] }
            val removal = arrayOfNulls<ListenableFuture<SessionResult>>(1)
            runOnMainSync {
                removal[0] = c.sendCustomCommand(SessionCommand(PlaybackService.REMOVE, Bundle.EMPTY),
                    Bundle().apply { putString("id", ids[0]) })
            }
            check(removal[0]!!.get(10, TimeUnit.SECONDS).resultCode == SessionResult.RESULT_SUCCESS)
            await("removed from active queue") { c.mediaItemCount == 0 && !c.isPlaying }
            check(ids.all { dao.record(it) != null && dao.asset(it) != null })
            done("remove_active_item_acknowledged_without_deleting_audio")
            runOnMainSync {
                c.setMediaItems(ids.take(2).map { MediaItem.Builder().setMediaId(it).build() })
                c.prepare(); c.play(); c.setPlaybackSpeed(1f); c.repeatMode = Player.REPEAT_MODE_OFF
            }
            await("restart queue") { c.isPlaying && c.currentPosition > 500 }
            runOnMainSync { c.pause() }
            done("queue_ready_for_manual_background_test")
            result.putString("result", "PASS ${passed.size}/11; existing library/audio retained")
            code = Activity.RESULT_OK
        } catch (e: Throwable) {
            result.putString("result", "FAIL after ${passed.size}/11: ${e.javaClass.simpleName}: ${e.message}")
            result.putString("location", e.stackTrace.firstOrNull { it.className.startsWith(javaClass.name) }?.toString())
        } finally {
            if (fixtureId.isNotBlank()) runCatching {
                var cleanup: ListenableFuture<SessionResult>? = null
                runOnMainSync { cleanup = controller?.sendCustomCommand(SessionCommand(PlaybackService.REMOVE, Bundle.EMPTY),
                    Bundle().apply { putString("id", fixtureId) }) }
                cleanup?.get(10, TimeUnit.SECONDS)
                fixtureLibrary?.delete(fixtureId)
            }
            runOnMainSync { controller?.pause(); controller?.release() }
            temporary.deleteRecursively()
        }
        result.putString("passed", passed.joinToString("\n")); finish(code, result)
    }
    private fun migration() {
        val file = File(targetContext.cacheDir, "playback-migration-${UUID.randomUUID()}.db")
        val schema = JSONObject(context.assets.open("dev.podcast.siwcspike.podcast.PodcastDatabase/1.json")
            .bufferedReader().use { it.readText() }).getJSONObject("database")
        val sqlite = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) sqlite.execSQL(setup.getString(i))
            sqlite.execSQL("INSERT INTO podcasts VALUES ('legacy', 'Старый выпуск', 'старый выпуск', 1, 'READY')")
            sqlite.execSQL("INSERT INTO audio_assets VALUES ('legacy', '{}')")
            sqlite.execSQL("INSERT INTO generation_jobs VALUES ('legacy', '{}', '/fixture')")
            sqlite.execSQL("INSERT INTO migration_state VALUES ('legacy-v1', 'complete')")
            sqlite.version = 1
        } finally { sqlite.close() }
        fun open() = Room.databaseBuilder(targetContext, PodcastDatabase::class.java, file.path)
            .addMigrations(PodcastDatabases.MIGRATION_1_2, PodcastDatabases.MIGRATION_2_3, PodcastDatabases.MIGRATION_3_4, PodcastDatabases.MIGRATION_4_5, PodcastDatabases.MIGRATION_5_6).build()
        try {
            val first = open()
            try {
                val dao = first.podcasts()
                check(dao.record("legacy")!!.title == "Старый выпуск" && dao.asset("legacy") != null)
                check(dao.job("legacy") != null && dao.marker("legacy-v1") == "complete")
                check(dao.playback() == null)
                dao.playback(PlaybackRecord(snapshot = SavedPlayback(listOf("legacy"), "legacy", 30000).json()))
            } finally { first.close() }
            val second = open()
            try { check(SavedPlayback.parse(second.podcasts().playback()).position == 30000L) } finally { second.close() }
        } finally { file.delete(); File(file.path + "-wal").delete(); File(file.path + "-shm").delete() }
    }
}
