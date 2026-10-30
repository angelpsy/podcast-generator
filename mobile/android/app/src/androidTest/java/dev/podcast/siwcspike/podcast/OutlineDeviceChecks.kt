package dev.podcast.siwcspike.podcast

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import androidx.room.Room
import dev.podcast.siwcspike.MainActivity
import dev.podcast.siwcspike.siwc.SiwcCoordinator
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Isolated storage checks; real SIWC calls only when explicitly requested by instrumentation argument. */
class OutlineDeviceChecks : Instrumentation() {
    private var live = false
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); live = arguments?.getString("live") == "true"; start() }
    private class Fixture(val db: PodcastDatabase) {
        val topics = TopicRepository(db); val repo = OutlineRepository(db); val dao = db.topics()
        val t = topics.mutate(JSONObject().put("action", "createTopic").put("title", "Звёзды").put("goal", "Понять научный метод").put("outlineDepth", 2)).getJSONObject("topic").getString("id")
        val root get() = dao.topic(t)!!.rootNodeId
        val revision get() = dao.topic(t)!!.revision
        fun command(action: String, vararg args: Pair<String, Any>): JSONObject = topics.mutate(JSONObject().put("action", action).put("topicId", t)
            .put("expectedRevision", revision).apply { args.forEach { put(it.first, it.second) } })
        fun add(title: String = "Старый", parent: String = root, kind: String = "SECTION"): String {
            val old = dao.nodes(t).map { it.id }.toSet(); command("addNode", "title" to title, "parentId" to parent, "kind" to kind)
            return dao.nodes(t).single { it.id !in old }.id
        }
        fun request(node: String = root, mode: String = "APPEND", depth: Int = 2, max: Int = 12) = JSONObject().put("topicId", t)
            .put("nodeId", node).put("expectedRevision", revision).put("mode", mode).put("depth", depth).put("maxNodes", max).put("model", "fixture")
        fun ready(node: String = root, mode: String = "APPEND", raw: String = payload()): String {
            val p = repo.create(request(node, mode)); check(repo.complete(p.id, raw, "fixture")); return p.id
        }
        fun attach(node: String, id: String) {
            if (db.podcasts().record(id) == null) {
                db.podcasts().insert(PodcastRecord(id, id, id, 1, "READY"))
                db.podcasts().asset(AudioAssetRecord(id, JSONObject().put("current", JSONObject().put("uri", "content://media/fixture/$id").put("name", "keep.m4a")).toString()))
            }
            command("attach", "nodeId" to node, "podcastId" to id)
        }
    }
    companion object {
        private fun payload(title: String = "Новый") = JSONObject().put("title", "План").put("description", "Краткий обзор")
            .put("children", JSONArray().put(JSONObject().put("kind", "QUESTION").put("title", title).put("description", "Контекст").put("children", JSONArray()))).toString()
    }
    private fun rejects(code: String, block: () -> Unit) {
        try { block(); error("Expected $code") } catch (e: TopicFailure) { check(e.code == code) { "Expected $code, got ${e.code}" } }
    }
    override fun onStart() {
        val directory = File(targetContext.cacheDir, "outline-check-${UUID.randomUUID()}").apply { mkdirs() }
        val passed = mutableListOf<String>(); val result = Bundle(); var code = Activity.RESULT_CANCELED
        fun done(name: String) { passed.add(name); sendStatus(0, Bundle().apply { putString("passed", name) }) }
        fun test(name: String, block: (Fixture) -> Unit) {
            val db = Room.databaseBuilder(targetContext, PodcastDatabase::class.java, File(directory, "$name.db").path).build()
            try { block(Fixture(db)); done(name) } finally { db.close() }
        }
        try {
            migration(directory); done("Room_3_to_4_preserves_tree_links_library_playback_and_marker")
            test("request_completion_and_preview_do_not_change_tree_or_create_audio") { f ->
                val before = f.topics.snapshot(f.t).toString(); val p = f.ready()
                check(f.repo.preview(p).getBoolean("canApply")); check(f.topics.snapshot(f.t).toString() == before)
                check(f.db.podcasts().all().isEmpty())
            }
            test("append_preserves_existing_ids_links_and_order_and_is_idempotent") { f ->
                val old = f.add(); f.attach(old, "audio"); val p = f.ready(); f.repo.apply(p, f.revision)
                val after = f.topics.snapshot(f.t).toString(); f.repo.apply(p, 0)
                check(f.topics.snapshot(f.t).toString() == after)
                check(TopicTree.children(f.dao.nodes(f.t), f.root).first().id == old && f.dao.links(f.t).single().nodeId == old)
            }
            test("replace_removes_descendant_links_only_and_preserves_target_audio") { f ->
                val old = f.add(); f.attach(f.root, "root-audio"); f.attach(old, "old-audio")
                val journals = f.db.podcasts().all().associate { it.id to f.db.podcasts().asset(it.id)!!.journal }
                val p = f.ready(mode = "REPLACE"); val preview = f.repo.preview(p)
                check(preview.getInt("removedLinks") == 1 && preview.getJSONArray("removedNodes").length() == 1)
                f.repo.apply(p, f.revision); check(f.dao.nodes(f.t).none { it.id == old })
                check(f.dao.links(f.t).single().nodeId == f.root)
                check(journals.all { f.db.podcasts().asset(it.key)!!.journal == it.value })
            }
            test("question_conversion_only_on_apply_preserves_id_and_links") { f ->
                val q = f.add(kind = "QUESTION"); f.attach(q, "audio"); val p = f.ready(q)
                check(f.dao.nodes(f.t).single { it.id == q }.kind == "QUESTION")
                check(f.repo.preview(p).getBoolean("convertQuestion")); f.repo.apply(p, f.revision)
                check(f.dao.nodes(f.t).single { it.id == q }.kind == "SECTION" && f.dao.links(f.t).single().nodeId == q)
            }
            test("stale_review_requires_explicit_ack_and_rerace_rejected") { f ->
                val p = f.ready(); val base = f.revision; f.add("Ручная правка")
                rejects("revision_conflict") { f.repo.apply(p, f.revision) }
                check(f.repo.preview(p).getBoolean("stale")); f.repo.review(p, f.revision)
                check(f.db.outlines().find(p)!!.baseRevision == base)
                f.add("Ещё правка"); rejects("revision_conflict") { f.repo.apply(p, f.revision) }
                f.repo.review(p, f.revision); f.repo.apply(p, f.revision)
                check(f.dao.nodes(f.t).size == 4)
            }
            test("invalid_output_failure_cancel_recovery_and_discard_are_nonmutating") { f ->
                val before = f.topics.snapshot(f.t).toString(); val p = f.repo.create(f.request())
                rejects("outline_invalid") { f.repo.complete(p.id, "{bad}", "fixture") }
                f.repo.fail(p.id, "invalid", false); check(f.db.outlines().find(p.id)!!.status == "FAILED")
                val cancelled = f.repo.create(f.request()); f.repo.fail(cancelled.id, "cancelled", true)
                check(!f.repo.complete(cancelled.id, payload(), "fixture"))
                val interrupted = f.repo.create(f.request()); f.repo.recover(); check(f.db.outlines().find(interrupted.id)!!.status == "INTERRUPTED")
                val ready = f.ready(); f.repo.discard(ready); rejects("proposal_not_ready") { f.repo.apply(ready, f.revision) }
                check(f.topics.snapshot(f.t).toString() == before)
            }
            test("deletion_cascades_proposal_and_late_response_cannot_recreate") { f ->
                val q = f.add(); val p = f.repo.create(f.request(q)); f.command("deleteNode", "nodeId" to q)
                check(f.db.outlines().find(p.id) == null && !f.repo.complete(p.id, payload(), "fixture"))
                check(f.dao.nodes(f.t).size == 1)
            }
            test("duplicate_append_and_maximum_target_depth_fail_without_partial_apply") { f ->
                f.add("Новый"); val p = f.ready(); val before = f.topics.snapshot(f.t).toString()
                check(!f.repo.preview(p).getBoolean("canApply")); rejects("outline_duplicate_title") { f.repo.apply(p, f.revision) }
                check(f.topics.snapshot(f.t).toString() == before)
                val a = f.add("A"); val b = f.add("B", a); val c = f.add("C", b); val d = f.add("D", c)
                rejects("outline_limits") { f.repo.create(f.request(d)) }
            }
            test("moved_target_rechecks_available_depth_before_review") { f ->
                val a = f.add("A"); val b = f.add("B"); val c = f.add("C", b)
                val nested = JSONObject(payload()).put("children", JSONArray().put(JSONObject().put("kind", "SECTION")
                    .put("title", "Раздел").put("description", "").put("children", JSONObject(payload()).getJSONArray("children")))).toString()
                val p = f.ready(a, raw = nested)
                f.command("moveNode", "nodeId" to a, "parentId" to c, "position" to 0)
                val before = f.topics.snapshot(f.t).toString()
                check(f.repo.preview(p).getString("error") == "outline_depth")
                rejects("outline_depth") { f.repo.review(p, f.revision) }
                check(f.topics.snapshot(f.t).toString() == before)
            }
            test("node_capacity_revalidated_after_request") { f ->
                val p = f.ready(); repeat(199) { f.add("N$it") }
                check(f.repo.preview(p).getString("error") == "node_limit")
                rejects("node_limit") { f.repo.review(p, f.revision) }; check(f.dao.nodes(f.t).size == 200)
            }
            persistence(directory); done("ready_proposal_survives_database_reopen")
            if (live) { liveRequests(); done("real_SIWC_science_and_technical_branch_validated_without_audio") }
            result.putString("result", "PASS ${passed.size}/${if (live) 13 else 12}"); code = Activity.RESULT_OK
        } catch (e: Throwable) {
            result.putString("result", "FAIL after ${passed.size}: ${e.javaClass.simpleName}: ${e.message}")
            result.putString("location", e.stackTrace.firstOrNull { it.className.startsWith(javaClass.name) }?.toString())
        } finally { directory.deleteRecursively() }
        result.putString("passed", passed.joinToString("\n")); finish(code, result)
    }
    private fun persistence(root: File) {
        val file = File(root, "persist.db")
        fun open() = Room.databaseBuilder(targetContext, PodcastDatabase::class.java, file.path).build()
        val first = open(); val f = Fixture(first); val p = f.ready(); first.close()
        val second = open(); try { check(second.outlines().find(p)!!.status == "READY"); check(OutlineRepository(second).preview(p).getBoolean("canApply")) } finally { second.close() }
    }
    private fun migration(root: File) {
        val file = File(root, "migration.db")
        val schema = JSONObject(context.assets.open("dev.podcast.siwcspike.podcast.PodcastDatabase/3.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        val sql = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) { val e = entities.getJSONObject(i); sql.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
                val indices = e.optJSONArray("indices") ?: JSONArray(); for (j in 0 until indices.length()) sql.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName"))) }
            val setup = schema.getJSONArray("setupQueries"); for (i in 0 until setup.length()) sql.execSQL(setup.getString(i))
            sql.execSQL("INSERT INTO podcasts VALUES ('ready','Audio','audio',1,'READY')")
            sql.execSQL("INSERT INTO podcasts VALUES ('draft','Draft','draft',2,'DRAFT')")
            sql.execSQL("INSERT INTO audio_assets VALUES ('ready','keep')")
            sql.execSQL("INSERT INTO generation_jobs VALUES ('draft','draft-metadata','/fixture')")
            sql.execSQL("INSERT INTO migration_state VALUES ('legacy-v1','complete')")
            sql.execSQL("INSERT INTO playback_state VALUES (1,'keep-playback')")
            sql.execSQL("INSERT INTO topics VALUES ('t','r','Topic','Goal',2,7,1,2)")
            sql.execSQL("INSERT INTO topic_nodes VALUES ('r','t',NULL,'ROOT','Topic','',0,0,1)")
            sql.execSQL("INSERT INTO node_podcasts VALUES ('r','ready','MAIN','ru_standard',0,1)"); sql.version = 3
        } finally { sql.close() }
        val db = Room.databaseBuilder(targetContext, PodcastDatabase::class.java, file.path).addMigrations(PodcastDatabases.MIGRATION_3_4, PodcastDatabases.MIGRATION_4_5, PodcastDatabases.MIGRATION_5_6).build()
        try { check(db.topics().topic("t")!!.revision == 7L && db.topics().links("t").size == 1)
            check(db.podcasts().all().size == 2 && db.podcasts().job("draft")!!.metadata == "draft-metadata")
            check(db.podcasts().asset("ready")!!.journal == "keep" && db.podcasts().playback() == "keep-playback" && db.podcasts().marker("legacy-v1") == "complete")
        } finally { db.close() }
    }
    private fun liveRequests() {
        startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); waitForIdleSync()
        Thread.sleep(4000) // Allow React mount and its one-time model catalog request to settle.
        val db = PodcastDatabases.get(targetContext); val f = Fixture(db)
        val before = db.podcasts().all().associate { it.id to db.podcasts().asset(it.id)?.journal }
        val siwc = SiwcCoordinator.get(targetContext)
        val deadline = System.currentTimeMillis() + 30000
        while (JSONObject(siwc.snapshot()).optBoolean("busy") && System.currentTimeMillis() < deadline) Thread.sleep(300)
        val catalog = CountDownLatch(1)
        siwc.listModels { catalog.countDown() }; check(catalog.await(30, TimeUnit.SECONDS))
        while (JSONObject(siwc.snapshot()).optBoolean("busy") && System.currentTimeMillis() < deadline) Thread.sleep(300)
        val models = JSONObject(siwc.snapshot()).getJSONArray("models"); check(models.length() > 0)
        val model = models.getJSONObject(0).getString("slug")
        Thread.sleep(1000)
        val coordinator = OutlineCoordinator.get(targetContext); coordinator.foreground(true)
        fun request(node: String) {
            val latch = CountDownLatch(1); var id = ""; var failure = ""
            coordinator.start(f.request(node).put("maxNodes", 6).put("model", model), { id = it; latch.countDown() }, { failure = it; latch.countDown() })
            var duplicate = ""
            coordinator.start(f.request(node), { error("Duplicate job accepted") }, { duplicate = it })
            check(duplicate == "outline_busy")
            check(latch.await(10, TimeUnit.SECONDS) && failure.isEmpty()) { "Start: $failure" }
            val end = System.currentTimeMillis() + 160000
            while (coordinator.busy() && System.currentTimeMillis() < end) Thread.sleep(500)
            val p = db.outlines().find(id)!!; check(p.status == "READY") { "Outline status ${p.status}: ${p.error}" }
            check(p.actualModel == model && f.repo.preview(id).getJSONArray("newNodes").length() in 1..6)
            sendStatus(0, Bundle().apply { putString("outline", "READY; nodes=${f.repo.preview(id).getJSONArray("newNodes").length()}; model=$model") })
        }
        request(f.root); check(f.dao.nodes(f.t).size == 1)
        f.command("editTopic", "title" to "Клиент-серверное взаимодействие", "goal" to "Подготовка к интервью", "outlineDepth" to 2)
        val q = f.add("Как работают HTTP и API?", kind = "QUESTION"); request(q)
        check(f.dao.nodes(f.t).size == 2 && f.dao.nodes(f.t).single { it.id == q }.kind == "QUESTION")
        check(db.podcasts().all().associate { it.id to db.podcasts().asset(it.id)?.journal } == before)
        // Keep this example and its READY proposals for normal UI preview/apply/restart checks.
    }
}
