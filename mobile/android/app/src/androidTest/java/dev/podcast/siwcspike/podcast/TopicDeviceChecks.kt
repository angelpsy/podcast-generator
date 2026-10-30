package dev.podcast.siwcspike.podcast

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.room.Room
import dev.podcast.siwcspike.MainActivity
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.google.common.util.concurrent.ListenableFuture

/** Isolated Room fixtures plus an explicitly selected production playback check. No SIWC access. */
class TopicDeviceChecks : Instrumentation() {
    private var storageOnly = false
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); storageOnly = arguments?.getString("storageOnly") == "true"; start() }
    private class Fixture(val db: PodcastDatabase) {
        val repo = TopicRepository(db)
        val dao = db.topics()
        fun create(title: String = "Техника"): String = repo.mutate(JSONObject().put("action", "createTopic")
            .put("title", title).put("goal", "Понять тему").put("outlineDepth", 2)).getJSONObject("topic").getString("id")
        fun root(topic: String) = dao.topic(topic)!!.rootNodeId
        fun command(topic: String, action: String, vararg args: Pair<String, Any>): JSONObject {
            val request = JSONObject().put("action", action).put("topicId", topic)
                .put("expectedRevision", dao.topic(topic)!!.revision)
            args.forEach { request.put(it.first, it.second) }
            return repo.mutate(request)
        }
        fun add(topic: String, parent: String, title: String, kind: String = "SECTION"): String {
            val before = dao.nodes(topic).map { it.id }.toSet()
            command(topic, "addNode", "parentId" to parent, "title" to title, "kind" to kind)
            return dao.nodes(topic).single { it.id !in before }.id
        }
        fun podcast(id: String, title: String = id, status: String = "READY") {
            db.podcasts().insert(PodcastRecord(id, title, LibrarySearch.normalize(title), System.currentTimeMillis(), status))
            db.podcasts().asset(AudioAssetRecord(id, JSONObject().put("current", JSONObject().put("id", id)
                .put("uri", "content://media/fixture/$id").put("name", "$id.m4a")).toString()))
        }
        fun attach(topic: String, node: String, id: String) = command(topic, "attach", "nodeId" to node, "podcastId" to id)
    }
    private fun rejects(code: String, block: () -> Unit) {
        try { block(); error("Expected $code") } catch (e: TopicFailure) { check(e.code == code) { "Expected $code, got ${e.code}" } }
    }
    private fun fails(block: () -> Unit) { var failed = false; try { block() } catch (_: Exception) { failed = true }; check(failed) }
    override fun onStart() {
        val root = File(targetContext.cacheDir, "topic-check-${UUID.randomUUID()}").apply { mkdirs() }
        val passed = mutableListOf<String>()
        val result = Bundle(); var code = Activity.RESULT_CANCELED
        fun done(name: String) { passed.add(name); sendStatus(0, Bundle().apply { putString("passed", name) }) }
        fun test(name: String, block: (Fixture) -> Unit) {
            val db = Room.databaseBuilder(targetContext, PodcastDatabase::class.java, File(root, "$name.db").path).build()
            try { block(Fixture(db)); done(name) } finally { db.close() }
        }
        try {
            migration(root); done("Room_2_to_3_preserves_library_draft_playback_and_marker")
            test("topic_root_CRUD_and_depth_setting_does_not_prune") { f ->
                val t = f.create(); val r = f.root(t); val a = f.add(t, r, "Раздел"); f.add(t, a, "Вопрос", "QUESTION")
                f.command(t, "editTopic", "title" to "Новое название", "goal" to "Новая цель", "outlineDepth" to 1)
                check(f.dao.nodes(t).size == 3 && f.dao.topic(t)!!.outlineDepth == 1)
                check(f.dao.nodes(t).single { it.id == r }.title == "Новое название")
                check(f.repo.list().getJSONArray("topics").length() == 1)
                f.command(t, "deleteTopic"); check(f.dao.topic(t) == null && f.dao.nodes(t).isEmpty())
            }
            test("node_edit_rejects_question_with_children_and_root_delete") { f ->
                val t = f.create(); val r = f.root(t); val a = f.add(t, r, "A"); val q = f.add(t, a, "Q", "QUESTION")
                f.command(t, "editNode", "nodeId" to q, "title" to "Вопрос после правки", "description" to "Контекст", "kind" to "QUESTION")
                check(f.dao.nodes(t).single { it.id == q }.description == "Контекст")
                val rev = f.dao.topic(t)!!.revision
                rejects("question_has_no_children") { f.command(t, "editNode", "nodeId" to a, "title" to "A", "kind" to "QUESTION") }
                rejects("root_locked") { f.command(t, "deleteNode", "nodeId" to r) }
                check(f.dao.topic(t)!!.revision == rev && f.dao.nodes(t).size == 3)
            }
            test("sibling_reorder_cross_parent_move_and_compaction") { f ->
                val t = f.create(); val r = f.root(t); val a = f.add(t, r, "A"); val b = f.add(t, r, "B"); val c = f.add(t, r, "C")
                f.command(t, "moveNode", "nodeId" to a, "parentId" to r, "position" to 2)
                check(TopicTree.children(f.dao.nodes(t), r).map { it.id } == listOf(b, c, a))
                val q = f.add(t, a, "Q", "QUESTION"); f.podcast("audio"); f.attach(t, q, "audio")
                f.command(t, "moveNode", "nodeId" to q, "parentId" to b, "position" to 0)
                check(f.dao.links(t).single().nodeId == q && TopicTree.children(f.dao.nodes(t), a).isEmpty())
                f.command(t, "deleteNode", "nodeId" to c)
                check(TopicTree.children(f.dao.nodes(t), r).map { it.position } == listOf(0, 1))
            }
            test("cycle_depth_and_invalid_position_are_atomic") { f ->
                val t = f.create(); val r = f.root(t); val a = f.add(t, r, "A"); val b = f.add(t, a, "B")
                val c = f.add(t, b, "C"); val d = f.add(t, c, "D"); val extra = f.add(t, r, "Extra"); f.add(t, extra, "Child")
                val before = f.repo.snapshot(t).toString()
                rejects("cycle") { f.command(t, "moveNode", "nodeId" to a, "parentId" to c, "position" to 0) }
                rejects("max_depth") { f.command(t, "addNode", "parentId" to d, "title" to "Too deep", "kind" to "SECTION") }
                rejects("max_depth") { f.command(t, "moveNode", "nodeId" to extra, "parentId" to c, "position" to 0) }
                rejects("invalid_position") { f.command(t, "moveNode", "nodeId" to extra, "parentId" to r, "position" to 199) }
                check(f.repo.snapshot(t).toString() == before)
            }
            test("stale_revision_does_not_overwrite_edits") { f ->
                val t = f.create(); val old = f.dao.topic(t)!!.revision
                f.add(t, f.root(t), "Saved")
                rejects("revision_conflict") { f.repo.mutate(JSONObject().put("action", "editTopic").put("topicId", t)
                    .put("expectedRevision", old).put("title", "Stale").put("outlineDepth", 2)) }
                check(f.dao.topic(t)!!.title == "Техника" && f.dao.nodes(t).size == 2)
            }
            test("database_guards_root_and_cross_topic_parent") { f ->
                val t = f.create(); val other = f.create("Other")
                fails { f.dao.insert(TopicNodeRecord("another-root", t, null, "ROOT", "root", rootSlot = 1)) }
                fails { f.dao.insert(TopicNodeRecord("cross-parent", t, f.root(other), "SECTION", "bad")) }
                check(f.dao.nodes(t).size == 1 && f.dao.nodes(other).size == 1)
                fails { f.command(t, "moveNode", "nodeId" to f.add(t, f.root(t), "A"), "parentId" to f.root(other), "position" to 0) }
            }
            test("node_limit_200_including_root") { f ->
                val t = f.create(); val r = f.root(t)
                f.db.runInTransaction { repeat(199) { i -> f.dao.insert(TopicNodeRecord("n$i", t, r, "SECTION", "node", position = i)) } }
                rejects("node_limit") { f.add(t, r, "Overflow") }
                check(f.dao.nodes(t).size == 200)
            }
            test("links_deduplicate_MAIN_switch_and_extra_order") { f ->
                val t = f.create(); val r = f.root(t)
                for (id in listOf("one", "two", "three")) { f.podcast(id); f.attach(t, r, id) }
                val before = f.dao.topic(t)!!.revision; f.attach(t, r, "one")
                check(f.dao.links(t).size == 3 && f.dao.topic(t)!!.revision == before)
                f.command(t, "moveLink", "nodeId" to r, "podcastId" to "three", "position" to 0)
                check(f.repo.playlist(t, r) == listOf("one", "three", "two"))
                f.command(t, "makeMain", "nodeId" to r, "podcastId" to "two")
                check(f.dao.links(t).count { it.role == "MAIN" } == 1 && f.dao.links(t).single { it.role == "MAIN" }.podcastId == "two")
                fails { f.dao.link(NodePodcastRecord(r, "three", "MAIN", "ru_standard", 0, 1)) }
            }
            test("derived_branch_playlist_DFS_and_missing_file_metadata") { f ->
                val t = f.create(); val r = f.root(t); val a = f.add(t, r, "A"); val q = f.add(t, a, "Q", "QUESTION"); val b = f.add(t, r, "B")
                for (id in listOf("overview", "shared", "deep", "last")) f.podcast(id)
                f.attach(t, r, "overview"); f.attach(t, r, "shared"); f.attach(t, a, "shared"); f.attach(t, q, "deep"); f.attach(t, b, "last")
                check(f.repo.playlist(t, r) == listOf("overview", "shared", "deep", "last"))
                check(f.repo.playlist(t, a) == listOf("shared", "deep"))
                f.db.podcasts().status("deep", "FILE_MISSING")
                check(f.repo.playlist(t, a) == listOf("shared"))
                check(f.repo.snapshot(t, a).getJSONArray("playlist").length() == 2)
            }
            test("detach_branch_and_topic_delete_preserve_library") { f ->
                val t = f.create(); val other = f.create("Other"); val a = f.add(t, f.root(t), "A"); val q = f.add(t, a, "Q", "QUESTION")
                f.podcast("shared"); f.attach(t, a, "shared"); f.attach(t, q, "shared"); f.attach(other, f.root(other), "shared")
                val before = f.db.podcasts().asset("shared")!!.journal
                f.command(t, "detach", "nodeId" to q, "podcastId" to "shared")
                check(f.db.podcasts().asset("shared")!!.journal == before)
                f.command(t, "deleteNode", "nodeId" to a)
                check(f.dao.nodes(t).size == 1 && f.dao.links(t).isEmpty())
                f.command(t, "deleteTopic")
                check(f.dao.links(other).size == 1 && f.db.podcasts().asset("shared")!!.journal == before)
            }
            test("explicit_library_delete_cascades_links_and_invalidates_topics") { f ->
                val one = f.create(); val two = f.create("Other"); f.podcast("audio")
                f.attach(one, f.root(one), "audio"); f.attach(two, f.root(two), "audio")
                val before = listOf(one, two).associateWith { f.dao.topic(it)!!.revision }
                val deleted = mutableListOf<String>()
                val media = object : AudioMedia {
                    override fun find(name: String): String? = null
                    override fun create(name: String): String = error("unexpected_create")
                    override fun copy(uri: String, source: File, checkCancelled: () -> Unit) = error("unexpected_copy")
                    override fun publish(uri: String) = error("unexpected_publish")
                    override fun isPublished(uri: String) = true
                    override fun delete(uri: String) { deleted.add(uri) }
                }
                val library = PodcastLibrary(f.db, File(root, "delete-library").apply { mkdirs() }, media)
                library.initialize(); library.delete("audio")
                check(deleted == listOf("content://media/fixture/audio"))
                for (t in listOf(one, two)) { check(f.dao.links(t).isEmpty() && f.dao.topic(t)!!.revision == before.getValue(t) + 1) }
                check(f.db.podcasts().asset("audio") == null)
            }
            test("search_literal_unicode_paging_and_reject_draft") { f ->
                val t = f.create(); val r = f.root(t)
                f.podcast("needle", "Ёлка ЗАМЫКАНИЯ 100%_"); f.podcast("draft", status = "DRAFT")
                repeat(55) { f.podcast("extra-$it", "Other $it") }
                check(f.repo.search("елка", 0).getJSONArray("items").length() == 1)
                check(f.repo.search("%_", 0).getJSONArray("items").length() == 1)
                check(f.repo.search("Other", 0).getJSONArray("items").length() == 50 && f.repo.search("Other", 0).getBoolean("hasMore"))
                check(f.repo.search("Other", 50).getJSONArray("items").length() == 5)
                rejects("podcast_not_ready") { f.attach(t, r, "draft") }
                rejects("empty_playlist") { f.repo.playlist(t, r) }
            }
            if (!storageOnly) { productionPlayback(); done("production_queue_survives_tree_delete_and_activity_recreate") }
            result.putString("result", "PASS ${passed.size}/${if (storageOnly) 13 else 14}; SIWC and existing audio untouched")
            code = Activity.RESULT_OK
        } catch (e: Throwable) {
            result.putString("result", "FAIL after ${passed.size}: ${e.javaClass.simpleName}: ${e.message}")
            result.putString("location", e.stackTrace.firstOrNull { it.className.startsWith(javaClass.name) }?.toString())
        } finally { root.deleteRecursively() }
        result.putString("passed", passed.joinToString("\n")); finish(code, result)
    }
    private fun migration(root: File) {
        val file = File(root, "migration.db")
        val schema = JSONObject(context.assets.open("dev.podcast.siwcspike.podcast.PodcastDatabase/2.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        val sqlite = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                sqlite.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries"); for (i in 0 until setup.length()) sqlite.execSQL(setup.getString(i))
            sqlite.execSQL("INSERT INTO podcasts VALUES ('ready', 'Старый выпуск', 'старый выпуск', 1, 'READY')")
            sqlite.execSQL("INSERT INTO podcasts VALUES ('draft', 'Черновик', 'черновик', 2, 'DRAFT')")
            sqlite.execSQL("INSERT INTO audio_assets VALUES ('ready', '{\"current\":{\"script\":\"Сценарий\"}}')")
            sqlite.execSQL("INSERT INTO generation_jobs VALUES ('draft', '{\"script\":\"Черновик\"}', '/fixture')")
            sqlite.execSQL("INSERT INTO migration_state VALUES ('legacy-v1', 'complete')")
            sqlite.execSQL("INSERT INTO playback_state VALUES (1, ?)", arrayOf(SavedPlayback(listOf("ready"), "ready", 65432, 1.25f, 2).json()))
            sqlite.version = 2
        } finally { sqlite.close() }
        fun open() = Room.databaseBuilder(targetContext, PodcastDatabase::class.java, file.path).addMigrations(PodcastDatabases.MIGRATION_2_3, PodcastDatabases.MIGRATION_3_4, PodcastDatabases.MIGRATION_4_5, PodcastDatabases.MIGRATION_5_6).build()
        var created = ""
        val db = open()
        try {
            check(db.podcasts().all().size == 2 && db.podcasts().marker("legacy-v1") == "complete")
            check(JSONObject(db.podcasts().job("draft")!!.metadata).getString("script") == "Черновик")
            check(JSONObject(db.podcasts().asset("ready")!!.journal).getJSONObject("current").getString("script") == "Сценарий")
            check(SavedPlayback.parse(db.podcasts().playback()) == SavedPlayback(listOf("ready"), "ready", 65432, 1.25f, 2))
            created = Fixture(db).create("Сохранённый топик")
        } finally { db.close() }
        val reopened = open()
        try { check(reopened.topics().topic(created)!!.title == "Сохранённый топик" && reopened.topics().nodes(created).size == 1) }
        finally { reopened.close() }
    }
    private fun await(block: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < deadline) { var ok = false; runOnMainSync { ok = block() }; if (ok) return; Thread.sleep(100) }
        error("Playback condition timed out")
    }
    private fun productionPlayback() {
        val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val db = PodcastDatabases.get(targetContext); val f = Fixture(db)
        val ids = db.podcasts().all().filter { it.status == "READY" }.filter { row ->
            db.podcasts().asset(row.id)?.let { JSONObject(it.journal).optJSONObject("current")?.optString("languagePolicy", "ru_standard") } == "ru_standard"
        }.take(2).map { it.id }
        check(ids.size == 2) { "Two ready library episodes required" }
        val originals = ids.associateWith { db.podcasts().asset(it)!!.journal }
        lateinit var connection: ListenableFuture<MediaController>
        runOnMainSync { connection = MediaController.Builder(targetContext, SessionToken(targetContext, ComponentName(targetContext, PlaybackService::class.java))).buildAsync() }
        val c = connection.get(15, TimeUnit.SECONDS)
        val t = f.create("Проверка очереди топика")
        try {
            val r = f.root(t); val a = f.add(t, r, "Раздел"); val q = f.add(t, a, "Вопрос", "QUESTION")
            f.attach(t, r, ids[0]); f.attach(t, q, ids[1]); f.attach(t, q, ids[0])
            val expected = f.repo.playlist(t, r); check(expected == ids)
            val started = CountDownLatch(1); var accepted = false
            PlaybackClient.get(targetContext).playQueue(expected) { accepted = it; started.countDown() }
            check(started.await(10, TimeUnit.SECONDS) && accepted)
            await { c.mediaItemCount == 2 && c.currentMediaItem?.mediaId == ids[0] && c.isPlaying && c.currentPosition > 500 }
            f.command(t, "moveNode", "nodeId" to q, "parentId" to r, "position" to 1)
            f.command(t, "detach", "nodeId" to r, "podcastId" to ids[0])
            f.command(t, "deleteNode", "nodeId" to a)
            f.command(t, "deleteTopic")
            runOnMainSync { activity.recreate() }
            waitForIdleSync()
            await { activity.isDestroyed && c.isPlaying && (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId } == expected }
            check(ids.all { db.podcasts().asset(it)!!.journal == originals.getValue(it) })
        } finally {
            if (f.dao.topic(t) != null) f.command(t, "deleteTopic")
            runOnMainSync { c.pause(); c.release() }
        }
    }
}
