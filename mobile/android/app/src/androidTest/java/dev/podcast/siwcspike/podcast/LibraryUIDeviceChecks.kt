package dev.podcast.siwcspike.podcast

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.room.Room
import dev.podcast.siwcspike.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class LibraryUIDeviceChecks : Instrumentation() {
    private var storageOnly = false
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); storageOnly = arguments?.getString("storageOnly") == "true"; start() }
    private class Fixture(val db: PodcastDatabase) {
        val repo = PlaylistRepository(db)
        fun create(title: String = "Мой список") = repo.mutate(JSONObject().put("action", "create").put("title", title)).getString("id")
        fun command(id: String, action: String, vararg values: Pair<String, Any>): JSONObject = repo.mutate(JSONObject().put("id", id).put("action", action)
            .put("expectedRevision", db.playlists().find(id)!!.revision).apply { values.forEach { put(it.first, it.second) } })
        fun episode(id: String, profile: String = "ru_standard", status: String = "READY") {
            db.podcasts().insert(PodcastRecord(id, "Запись $id", "запись $id", 1, status))
            db.podcasts().asset(AudioAssetRecord(id, JSONObject().put("current", JSONObject().put("id", id).put("uri", "content://fixture/$id")
                .put("name", "$id.m4a").put("languagePolicy", profile).put("durationMs", 1000)).toString()))
        }
        fun add(list: String, id: String) = command(list, "add", "podcastId" to id)
    }
    private fun rejects(code: String, block: () -> Unit) { try { block(); error("Expected $code") } catch (e: TopicFailure) { check(e.code == code) { "Expected $code, got ${e.code}" } } }
    override fun onStart() {
        val root = File(targetContext.cacheDir, "library-ui-${UUID.randomUUID()}").apply { mkdirs() }; val passed = mutableListOf<String>(); val result = Bundle(); var code = Activity.RESULT_CANCELED
        fun done(name: String) { passed.add(name); sendStatus(0, Bundle().apply { putString("passed", name) }) }
        fun test(name: String, block: (Fixture) -> Unit) { val db = Room.databaseBuilder(targetContext, PodcastDatabase::class.java, File(root, "$name.db").path).build(); try { block(Fixture(db)); done(name) } finally { db.close() } }
        try {
            migration(root); done("Room_5_to_6_preserves_existing_tables_and_checkpoint_metadata")
            test("manual_CRUD_does_not_delete_episode_or_audio") { f ->
                f.episode("a"); val original = f.db.podcasts().asset("a")!!; val p = f.create(); f.add(p, "a")
                f.command(p, "rename", "title" to "Переименован"); check(f.repo.detail(p).getString("title") == "Переименован")
                f.command(p, "delete"); check(f.db.podcasts().asset("a") == original)
            }
            test("duplicate_add_is_idempotent_and_one_audio_can_be_in_two_lists") { f ->
                f.episode("a"); val p = f.create(); val q = f.create(); f.add(p, "a"); val revision = f.db.playlists().find(p)!!.revision
                f.add(p, "a"); f.add(q, "a"); check(f.db.playlists().entries(p).size == 1 && f.db.playlists().entries(q).size == 1 && f.db.playlists().find(p)!!.revision == revision)
            }
            test("reorder_remove_and_readd_preserve_order_and_snapshot") { f ->
                val p = f.create(); listOf("a", "b", "c").forEach { f.episode(it); f.add(p, it) }
                val original = f.repo.playIds(p)
                f.command(p, "move", "podcastId" to "c", "position" to 0); check(f.repo.playIds(p) == listOf("c", "a", "b"))
                f.command(p, "remove", "podcastId" to "a"); f.add(p, "a"); check(f.repo.playIds(p) == listOf("c", "b", "a") && original == listOf("a", "b", "c"))
            }
            test("stale_and_invalid_mutations_are_atomic") { f ->
                val p = f.create(); f.episode("a"); f.add(p, "a"); val before = f.repo.detail(p).toString()
                rejects("revision_conflict") { f.repo.mutate(JSONObject().put("action", "rename").put("id", p).put("expectedRevision", 0).put("title", "Wrong")) }
                rejects("invalid_position") { f.command(p, "move", "podcastId" to "a", "position" to -1) }
                rejects("text_length") { f.command(p, "rename", "title" to " ") }
                check(f.repo.detail(p).toString() == before)
            }
            test("missing_files_are_visible_but_skipped_and_drafts_rejected") { f ->
                val p = f.create(); f.episode("missing", status = "FILE_MISSING"); f.episode("draft", status = "DRAFT"); f.add(p, "missing")
                rejects("empty_playlist") { f.repo.playIds(p) }; rejects("podcast_not_ready") { f.add(p, "draft") }
                check(f.repo.detail(p).getJSONArray("items").getJSONObject(0).getString("status") == "FILE_MISSING")
            }
            test("podcast_delete_cascades_entries_invalidates_revision_and_deletes_only_own_audio") { f ->
                f.episode("a"); f.episode("b"); val p = f.create(); f.add(p, "a"); f.add(p, "b"); val revision = f.db.playlists().find(p)!!.revision
                val deleted = mutableListOf<String>(); val media = object : AudioMedia {
                    override fun find(name: String): String? = null
                    override fun create(name: String): String = error("unused")
                    override fun copy(uri: String, source: File, checkCancelled: () -> Unit) = error("unused")
                    override fun isPublished(uri: String) = true
                    override fun publish(uri: String) = error("unused")
                    override fun delete(uri: String) { deleted.add(uri) }
                }
                f.db.podcasts().marker(LibraryMigration("legacy-v1", "complete"))
                val library = PodcastLibrary(f.db, File(root, "delete-fixture"), media); library.initialize(); library.delete("a")
                check(f.db.playlists().find(p)!!.revision > revision && f.repo.playIds(p) == listOf("b"))
                check(deleted == listOf("content://fixture/a") && f.db.podcasts().record("b") != null)
            }
            test("topic_profile_filter_copy_dedupe_and_independence") { f ->
                f.episode("r"); f.episode("e", "en_simple_ste")
                val topic = TopicRepository(f.db); val t = topic.mutate(JSONObject().put("action", "createTopic").put("title", "Тема").put("goal", "Цель").put("outlineDepth", 2)).getJSONObject("topic")
                val id = t.getString("id"); val node = t.getString("rootNodeId")
                fun attach(podcast: String) { topic.mutate(JSONObject().put("action", "attach").put("topicId", id).put("nodeId", node).put("expectedRevision", f.db.topics().topic(id)!!.revision).put("podcastId", podcast)) }
                attach("r"); attach("e"); check(f.repo.topicIds(id, node, false) == listOf("r")); check(f.repo.topicIds(id, node, true).toSet() == setOf("r", "e"))
                val copy = f.repo.mutate(JSONObject().put("action", "copyTopic").put("title", "Копия").put("topicId", id).put("nodeId", node)
                    .put("allProfiles", true).put("expectedRevision", f.db.topics().topic(id)!!.revision)).getString("id")
                topic.mutate(JSONObject().put("action", "deleteTopic").put("topicId", id).put("expectedRevision", f.db.topics().topic(id)!!.revision))
                check(f.repo.playIds(copy).toSet() == setOf("r", "e") && f.db.podcasts().all().size == 2)
            }
            test("stale_topic_copy_is_rejected_without_creating_list") { f ->
                val topic = TopicRepository(f.db); val t = topic.mutate(JSONObject().put("action", "createTopic").put("title", "Тема").put("outlineDepth", 2)).getJSONObject("topic")
                rejects("revision_conflict") { f.repo.mutate(JSONObject().put("action", "copyTopic").put("title", "Wrong").put("topicId", t.getString("id")).put("expectedRevision", -1)) }
                check(f.db.playlists().all().isEmpty())
            }
            preferences(root); done("preferences_validation_persistence_and_legacy_model_import")
            reopen(root); done("manual_list_order_survives_database_reopen")
            if (!storageOnly) { playback(); done("manual_queue_survives_reorder_delete_activity_recreate_and_Home") }
            result.putString("result", "PASS ${passed.size}/${if (storageOnly) 11 else 12}"); code = Activity.RESULT_OK
        } catch (e: Throwable) { result.putString("result", "FAIL after ${passed.size}: ${e.javaClass.simpleName}: ${e.message}"); result.putString("location", e.stackTrace.firstOrNull { it.className.startsWith(javaClass.name) }?.toString()) }
        finally { root.deleteRecursively() }
        result.putString("passed", passed.joinToString("\n")); finish(code, result)
    }
    private fun reopen(root: File) {
        val file = File(root, "reopen.db"); fun open() = Room.databaseBuilder(targetContext, PodcastDatabase::class.java, file.path).build()
        val db = open(); val f = Fixture(db); f.episode("a"); f.episode("b"); val id = f.create(); f.add(id, "b"); f.add(id, "a"); db.close()
        val next = open(); try { check(PlaylistRepository(next).playIds(id) == listOf("b", "a")) } finally { next.close() }
    }
    private fun preferences(root: File) {
        val suffix = UUID.randomUUID().toString(); val context = object : ContextWrapper(targetContext) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$name-$suffix", mode)
            override fun getNoBackupFilesDir(): File = File(root, "prefs").apply { mkdirs() }
        }
        val input = AtomicAudioJournal(File(context.noBackupFilesDir, "podcast/input.json")); input.write(JSONObject().put("model", "legacy-model"))
        check(AppPreferences.read(context).getString("model") == "legacy-model")
        val saved = AppPreferences.save(context, JSONObject().put("theme", "dark").put("profile", "en_simple_ste").put("settings", ContentSettings("BRIEF", "DEEP").json()))
        input.write(JSONObject().put("model", "other")); check(AppPreferences.read(context).toString() == saved.toString())
        rejects("invalid_theme") { AppPreferences.save(context, JSONObject().put("theme", "bad")) }
        check(AppPreferences.read(context).toString() == saved.toString()); context.deleteSharedPreferences("app-preferences-$suffix")
    }
    private fun migration(root: File) {
        val file = File(root, "migration.db"); val schema = JSONObject(context.assets.open("dev.podcast.siwcspike.podcast.PodcastDatabase/5.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        val sql = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) { val e = entities.getJSONObject(i); sql.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
                val indices = e.optJSONArray("indices") ?: JSONArray(); for (j in 0 until indices.length()) sql.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName"))) }
            val setup = schema.getJSONArray("setupQueries"); for (i in 0 until setup.length()) sql.execSQL(setup.getString(i))
            sql.execSQL("INSERT INTO podcasts VALUES ('p','Podcast','podcast',1,'READY')")
            sql.execSQL("INSERT INTO audio_assets VALUES ('p','keep-audio')")
            sql.execSQL("INSERT INTO generation_jobs VALUES ('p','keep-checkpoint','/fixture')")
            sql.execSQL("INSERT INTO migration_state VALUES ('legacy-v1','complete')")
            sql.execSQL("INSERT INTO playback_state VALUES (1,'keep-playback')")
            sql.execSQL("INSERT INTO topics VALUES ('t','r','Topic','Goal',2,3,1,2,'ru_simple','')")
            sql.execSQL("INSERT INTO topic_nodes VALUES ('r','t',NULL,'ROOT','Topic','',0,0,1)")
            sql.execSQL("INSERT INTO outline_proposals VALUES ('o','t','r',3,3,'APPEND',1,5,'model','{}','READY','keep-proposal','','model',1)")
            sql.execSQL("INSERT INTO generation_queue VALUES ('j','batch','Title','t','r','ru_simple','{}','{}','hash','model','PAUSED','Synthesizing','',1,2)"); sql.version = 5
        } finally { sql.close() }
        val db = Room.databaseBuilder(targetContext, PodcastDatabase::class.java, file.path).addMigrations(PodcastDatabases.MIGRATION_5_6).build()
        try { check(db.podcasts().asset("p")!!.journal == "keep-audio" && db.podcasts().job("p")!!.metadata == "keep-checkpoint")
            check(db.podcasts().playback() == "keep-playback" && db.outlines().find("o")!!.payload == "keep-proposal")
            check(db.generationQueue().find("j")!!.attempt == 2 && db.topics().topic("t")!!.languagePolicy == "ru_simple")
            check(db.playlists().all().isEmpty())
        } finally { db.close() }
    }
    private fun await(condition: () -> Boolean) { val end = System.currentTimeMillis() + 15000; while (System.currentTimeMillis() < end) { var ok = false; runOnMainSync { ok = condition() }; if (ok) return; Thread.sleep(100) }; error("Playback timeout") }
    private fun playback() {
        val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val db = PodcastDatabases.get(targetContext); val ids = db.podcasts().all().filter { it.status == "READY" }.take(2).map { it.id }; check(ids.size == 2)
        val originals = ids.associateWith { db.podcasts().asset(it)!!.journal }; val f = Fixture(db); val p = f.create("Временный плейлист проверки")
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
        runOnMainSync { future = MediaController.Builder(targetContext, SessionToken(targetContext, ComponentName(targetContext, PlaybackService::class.java))).buildAsync() }
        val controller = future.get(15, TimeUnit.SECONDS)
        try {
            ids.forEach { f.add(p, it) }; val started = java.util.concurrent.CountDownLatch(1); var accepted = false
            PlaybackClient.get(targetContext).playQueue(f.repo.playIds(p)) { accepted = it; started.countDown() }; check(started.await(10, TimeUnit.SECONDS) && accepted)
            await { controller.isPlaying && controller.currentPosition > 500 }
            f.command(p, "move", "podcastId" to ids[1], "position" to 0)
            await { (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId } == ids }
            val updated = java.util.concurrent.CountDownLatch(1); accepted = false
            PlaybackClient.get(targetContext).updateQueue(f.repo.playIds(p)) { accepted = it; updated.countDown() }
            check(updated.await(10, TimeUnit.SECONDS) && accepted)
            val expected = ids.reversed()
            await { (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId } == expected && controller.currentMediaItem?.mediaId == ids[0] && controller.currentPosition >= 500 && controller.isPlaying }
            f.command(p, "remove", "podcastId" to ids[0]); f.command(p, "delete")
            runOnMainSync { activity.recreate() }; waitForIdleSync()
            await { controller.isPlaying && (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId } == expected }
            uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close(); Thread.sleep(5000)
            await { controller.isPlaying && controller.currentPosition > 5000 }
            check(ids.all { db.podcasts().asset(it)!!.journal == originals.getValue(it) })
        } finally { if (db.playlists().find(p) != null) f.command(p, "delete"); runOnMainSync { controller.pause(); controller.release() } }
    }
}
