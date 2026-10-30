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

/** Isolated Room/checkpoint tests; explicit live argument exercises the production runner and SIWC. */
class GenerationDeviceChecks : Instrumentation() {
    private var live = false
    private var runnerChecks = false
    private var appendLive = false
    private var recoveryUi = ""
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); live = arguments?.getString("live") == "true"; runnerChecks = arguments?.getString("runner") == "true"; appendLive = arguments?.getString("appendLive") == "true"; recoveryUi = arguments?.getString("recoveryUi").orEmpty(); start() }
    private class Fixture(val db: PodcastDatabase, existingTopic: String? = null) {
        val topics = TopicRepository(db); val queue = GenerationQueue(db); val dao = db.topics()
        val topic = existingTopic ?: topics.mutate(JSONObject().put("action", "createTopic").put("title", "Свет и звёзды").put("goal", "Понять научный метод").put("outlineDepth", 2)
            .put("languagePolicy", "ru_simple")).getJSONObject("topic").getString("id")
        val root get() = dao.topic(topic)!!.rootNodeId
        val revision get() = dao.topic(topic)!!.revision
        fun command(action: String, vararg args: Pair<String, Any>): JSONObject = topics.mutate(JSONObject().put("action", action).put("topicId", topic)
            .put("expectedRevision", revision).apply { args.forEach { put(it.first, it.second) } })
        fun question(title: String, parent: String = root): String {
            val old = dao.nodes(topic).map { it.id }.toSet(); command("addNode", "title" to title, "kind" to "QUESTION", "parentId" to parent)
            return dao.nodes(topic).single { it.id !in old }.id
        }
        fun request(mode: String = "BATCH", node: String = root) = JSONObject().put("mode", mode).put("topicId", topic).put("nodeId", node)
            .put("expectedRevision", revision).put("model", "fixture").put("requestId", UUID.randomUUID().toString())
        fun enqueue(request: JSONObject): List<String> {
            request.put("previewToken", queue.preview(request).getString("token"))
            val ids = queue.enqueue(request).getJSONArray("ids"); return (0 until ids.length()).map(ids::getString)
        }
        fun publish(id: String) {
            db.podcasts().insert(PodcastRecord(id, "Тест", "тест", 1, "READY"))
            db.podcasts().asset(AudioAssetRecord(id, JSONObject().put("current", JSONObject().put("id", id).put("uri", "content://fixture/$id")).toString()))
            queue.complete(id)
        }
    }
    private fun rejects(code: String, action: () -> Unit) { try { action(); error("Expected $code") } catch (e: TopicFailure) { check(e.code == code) { "Expected $code, got ${e.code}" } } }
    override fun onStart() {
        if (recoveryUi.isNotEmpty()) {
            val result = Bundle(); var code = Activity.RESULT_CANCELED
            try { recoveryUiCheck(); result.putString("result", "PASS 1/1 recovery UI and command audit: $recoveryUi"); code = Activity.RESULT_OK }
            catch (e: Throwable) { result.putString("result", "FAIL recovery UI: ${e.javaClass.simpleName}: ${e.message}") }
            finish(code, result); return
        }
        val directory = File(targetContext.cacheDir, "generation-check-${UUID.randomUUID()}").apply { mkdirs() }
        val passed = mutableListOf<String>(); val result = Bundle(); var code = Activity.RESULT_CANCELED
        fun done(name: String) { passed.add(name); sendStatus(0, Bundle().apply { putString("passed", name) }) }
        fun test(name: String, action: (Fixture) -> Unit) {
            val db = Room.databaseBuilder(targetContext, PodcastDatabase::class.java, File(directory, "$name.db").path).build()
            try { action(Fixture(db)); done(name) } finally { db.close() }
        }
        try {
            migration(directory); done("Room_4_to_5_preserves_library_tree_proposal_playback_and_SIWC_separation")
            test("preview_is_nonmutating_and_batch_is_leaf_questions_only") { f ->
                f.question("Цвет звёзд"); f.command("addNode", "title" to "Пустой раздел", "kind" to "SECTION", "parentId" to f.root)
                val p = f.queue.preview(f.request()); check(p.getJSONArray("items").length() == 1)
                check(f.db.generationQueue().all().isEmpty() && f.db.podcasts().all().isEmpty())
            }
            test("batch_can_include_sections_and_questions_without_root_in_DFS_order") { f ->
                f.command("addNode", "title" to "Раздел", "kind" to "SECTION", "parentId" to f.root)
                val section = f.dao.nodes(f.topic).single { it.kind == "SECTION" }.id
                val q = f.question("Вопрос", section); f.question("Вопрос корня")
                val questions = f.queue.preview(f.request()).getJSONArray("items"); check(questions.length() == 2)
                val request = f.request().put("includeSections", true)
                val items = f.queue.preview(request).getJSONArray("items")
                check(items.length() == 3 && items.getJSONObject(0).getString("nodeId") == section && items.getJSONObject(1).getString("nodeId") == q)
                check(items.getJSONObject(0).getJSONObject("source").getString("scope") == "OVERVIEW")
                check(items.getJSONObject(1).getJSONObject("source").getString("scope") == "QUESTION")
                check((0 until items.length()).none { items.getJSONObject(it).getString("nodeId") == f.root })
                val ids = f.enqueue(request); check(ids.size == 3)
                check(f.queue.preview(f.request().put("includeSections", true)).getInt("skippedActive") == 3)
            }
            test("changing_section_scope_invalidates_preview_and_empty_sections_can_be_created") { f ->
                f.command("addNode", "title" to "Пустой раздел", "kind" to "SECTION", "parentId" to f.root)
                val section = f.dao.nodes(f.topic).single { it.kind == "SECTION" }.id
                val request = f.request(); request.put("previewToken", f.queue.preview(request).getString("token")).put("includeSections", true)
                rejects("revision_conflict") { f.queue.enqueue(request) }; check(f.db.generationQueue().all().isEmpty())
                val id = f.enqueue(request).single(); f.publish(id)
                check(f.dao.links(f.topic).single().nodeId == section)
                check(f.queue.preview(f.request().put("includeSections", true)).getInt("skippedReady") == 1)
            }
            test("batch_limit_20_and_explicit_next_part") { f ->
                repeat(23) { f.question("Вопрос $it") }; val first = f.request()
                check(f.queue.preview(first).getInt("remaining") == 3); check(f.enqueue(first).size == 20)
                val next = f.request(); check(f.queue.preview(next).getJSONArray("items").length() == 3 && f.queue.preview(next).getInt("skippedActive") == 20)
            }
            test("append_during_every_phase_keeps_running_task_and_FIFO_tail") { f ->
                f.question("Первый"); f.question("Второй")
                val original = f.enqueue(f.request())
                val future = System.currentTimeMillis() + 60000
                original.forEachIndexed { i, id -> f.db.generationQueue().update(f.queue.find(id)!!.copy(createdAt = future + i)) }
                check(f.queue.running(original[0]))
                val added = mutableListOf<String>()
                for (phase in listOf("PreparingVoice", "GeneratingText", "Synthesizing", "Encoding", "Saving")) {
                    f.queue.phase(original[0], phase)
                    val before = f.queue.find(original[0])!!
                    val request = JSONObject().put("mode", "STANDALONE").put("title", "Новый $phase").put("model", "fixture")
                        .put("requestId", UUID.randomUUID().toString())
                    added += f.enqueue(request).single()
                    check(f.queue.find(original[0]) == before)
                    check(f.db.generationQueue().all().count { it.status == "RUNNING" } == 1)
                    check(f.queue.next()!!.id == original[1])
                    check(added.all { f.queue.find(it)!!.status == "QUEUED" })
                }
                check(f.db.generationQueue().all().map { it.id } == original + added)
                f.publish(original[0])
                for (id in original.drop(1) + added) {
                    check(f.queue.next()!!.id == id); check(f.queue.running(id))
                    check(f.db.generationQueue().all().count { it.status == "RUNNING" } == 1)
                    f.publish(id)
                }
                check(f.queue.next() == null)
            }
            test("append_at_100_task_limit_is_atomic_with_running_and_paused_work") { f ->
                repeat(101) { f.question("Вопрос $it") }
                repeat(5) { check(f.enqueue(f.request()).size == 20) }
                val tasks = f.db.generationQueue().all(); check(tasks.size == 100)
                check(f.queue.running(tasks[0].id)); f.queue.stop(tasks[1].id, "network_error", true)
                val request = f.request(); request.put("previewToken", f.queue.preview(request).getString("token"))
                rejects("generation_queue_limit") { f.queue.enqueue(request) }
                check(f.db.generationQueue().all().size == 100 && f.queue.find(tasks[0].id)!!.status == "RUNNING")
                check(f.queue.cancel(tasks[1].id))
                val newNode = f.dao.nodes(f.topic).single { it.kind == "QUESTION" && it.id !in tasks.map { task -> task.nodeId } }.id
                check(f.enqueue(f.request("SINGLE", newNode)).size == 1)
                check(f.db.generationQueue().all().count { it.status in setOf("QUEUED", "RUNNING", "PAUSED", "FAILED") } == 100)
            }
            test("double_submit_idempotency_and_overlapping_batches_have_no_duplicates") { f ->
                f.question("Свет"); val request = f.request(); val ids = f.enqueue(request)
                val repeated = f.queue.enqueue(request)
                check(repeated.getJSONArray("ids").getString(0) == ids.single() && repeated.getInt("added") == 0)
                check(f.db.generationQueue().all().size == 1)
                check(f.queue.preview(f.request()).getInt("skippedActive") == 1)
            }
            test("stale_preview_and_structure_revision_are_rejected_atomically") { f ->
                f.question("Первый"); val request = f.request(); request.put("previewToken", f.queue.preview(request).getString("token"))
                f.question("Ручная правка"); rejects("revision_conflict") { f.queue.enqueue(request) }
                check(f.db.generationQueue().all().isEmpty())
            }
            test("settings_and_model_snapshots_survive_default_changes") { f ->
                f.question("Свет"); val id = f.enqueue(f.request()).single(); val before = f.queue.find(id)!!
                f.command("editTopic", "title" to "Свет и звёзды", "goal" to "Понять научный метод", "outlineDepth" to 2,
                    "languagePolicy" to "en_simple_ste", "contentSettings" to ContentSettings("BRIEF", "DEEP").json())
                check(f.queue.find(id) == before && JSONObject(before.settings).getString("volume") == "DETAILED")
                f.publish(id); check(f.dao.links(f.topic).single().profile == "ru_simple")
            }
            test("per_item_overrides_and_profile_specific_ready_filter") { f ->
                val a = f.question("A"); val b = f.question("B")
                val req = f.request().put("overrides", JSONObject().put(b, JSONObject().put("profile", "en_simple_ste").put("settings", ContentSettings("BRIEF", "DEEP").json())))
                val ids = f.enqueue(req); check(ids.size == 2)
                val task = ids.map { f.queue.find(it)!! }.single { it.nodeId == b }
                check(task.profile == "en_simple_ste" && JSONObject(task.settings).getString("immersion") == "DEEP")
                ids.forEach(f::publish)
                check(f.queue.preview(f.request()).getInt("skippedReady") == 1)
                check(f.queue.preview(f.request("SINGLE", a)).getJSONArray("items").length() == 0)
                check(f.queue.preview(f.request("SINGLE", a).put("additional", true)).getJSONArray("items").length() == 1)
                f.command("editTopic", "title" to "Свет и звёзды", "goal" to "Понять научный метод", "outlineDepth" to 2, "languagePolicy" to "en_simple_ste")
                check(f.topics.playlist(f.topic, f.root) == listOf(task.id))
                check(f.topics.snapshot(f.topic).getJSONArray("playlist").getJSONObject(0).getString("id") == task.id)
            }
            test("ready_publication_links_once_and_does_not_replace_existing_main") { f ->
                val q = f.question("Свет"); val id = f.enqueue(f.request()).single(); f.publish(id); val revision = f.revision
                f.queue.complete(id); check(f.dao.links(f.topic).size == 1 && f.revision == revision)
                val next = f.enqueue(f.request("SINGLE", q).put("additional", true)).single(); f.publish(next)
                check(f.dao.links(f.topic).count { it.role == "MAIN" } == 1 && f.dao.links(f.topic).count { it.role == "EXTRA" } == 1)
                f.db.podcasts().status(id, "FILE_MISSING")
                val replacement = f.enqueue(f.request("SINGLE", q)).single(); f.publish(replacement)
                check(f.dao.links(f.topic).single { it.role == "MAIN" }.podcastId == replacement)
                check(f.dao.links(f.topic).count { it.role == "EXTRA" } == 2)
                check(f.queue.preview(f.request("SINGLE", q)).getInt("skippedReady") == 1)
            }
            test("changed_deleted_or_expanded_node_keeps_result_standalone") { f ->
                val q = f.question("Свет"); val id = f.enqueue(f.request()).single()
                f.command("editNode", "nodeId" to q, "title" to "Новое название", "kind" to "QUESTION")
                f.publish(id); check(f.dao.links(f.topic).isEmpty() && f.queue.find(id)!!.error == "source_changed_detached")
                val id2 = f.enqueue(f.request()).single()
                f.command("editNode", "nodeId" to q, "title" to "Новое название", "kind" to "SECTION"); f.publish(id2)
                check(f.dao.links(f.topic).isEmpty() && f.queue.find(id2)!!.error == "source_changed_detached")
                f.question("Будет удалён"); val id3 = f.enqueue(f.request()).single(); f.command("deleteTopic"); f.publish(id3)
                check(f.db.podcasts().record(id3)!!.status == "READY" && f.queue.find(id3)!!.status == "COMPLETED")
            }
            test("queue_recovery_pauses_without_starting_requests_and_resume_is_explicit") { f ->
                f.question("A"); f.question("B"); val ids = f.enqueue(f.request()); f.queue.running(ids[0]); f.queue.phase(ids[0], "Synthesizing")
                f.queue.recover(); check(ids.all { f.queue.find(it)!!.status == "PAUSED" } && f.queue.next() == null)
                f.queue.resume(ids[0]); check(f.queue.next()!!.id == ids[0] && f.queue.find(ids[1])!!.status == "PAUSED")
                f.queue.running(ids[0]); check(f.queue.find(ids[0])!!.attempt == 2)
            }
            test("queue_cancel_prevents_batch_duplicate_but_allows_explicit_new_request") { f ->
                f.question("A"); val id = f.enqueue(f.request()).single(); f.queue.stop(id, "network_error", true)
                check(f.queue.preview(f.request()).getInt("skippedActive") == 1)
                check(f.queue.cancel(id)); check(!f.queue.running(id)); f.queue.recover(); check(f.queue.find(id)!!.status == "CANCELLED")
                check(f.queue.preview(f.request()).getJSONArray("items").length() == 1)
            }
            checkpoint(directory); done("real_AtomicFile_chunk_reopen_integrity_and_partial_rejection")
            if (live) { liveQueue(); done("real_SIWC_three_profiles_pause_resume_durable_audio_and_sequential_queue") }
            if (runnerChecks) { productionQueueControls(); done("queue_editor_and_durable_cancel_respond_during_real_TTS_without_new_Responses") }
            if (appendLive) { liveAppend(); done("append_during_real_Streaming_and_UI_TTS_then_three_serial_completions") }
            result.putString("result", "PASS ${passed.size}/${16 + (if (live) 1 else 0) + (if (runnerChecks) 1 else 0) + (if (appendLive) 1 else 0)}"); code = Activity.RESULT_OK
        } catch (e: Throwable) {
            result.putString("result", "FAIL after ${passed.size}: ${e.javaClass.simpleName}: ${e.message}")
            result.putString("location", e.stackTrace.firstOrNull { it.className.startsWith(javaClass.name) }?.toString())
        } finally { directory.deleteRecursively() }
        result.putString("passed", passed.joinToString("\n")); finish(code, result)
    }
    private fun checkpoint(root: File) {
        val dir = File(root, "speech").apply { mkdirs() }; val cache = SpeechCheckpoint(dir, "ru|Рассказ", 8000)
        PcmWav(cache.partial(0), 8000).use { it.append(FloatArray(800)); it.finish() }; cache.commit(0)
        cache.partial(1).writeText("incomplete")
        val restored = SpeechCheckpoint(dir, "ru|Рассказ", 8000); check(restored.ready(0) && !restored.ready(1))
        val wav = File(dir, "speech.wav"); check(restored.assemble(1, wav) {} == 100L && wav.length() == 1644L)
    }
    private fun migration(root: File) {
        val file = File(root, "migration.db")
        val schema = JSONObject(context.assets.open("dev.podcast.siwcspike.podcast.PodcastDatabase/4.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        val sql = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) { val e = entities.getJSONObject(i); sql.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
                val indices = e.optJSONArray("indices") ?: JSONArray(); for (j in 0 until indices.length()) sql.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName"))) }
            val setup = schema.getJSONArray("setupQueries"); for (i in 0 until setup.length()) sql.execSQL(setup.getString(i))
            sql.execSQL("INSERT INTO podcasts VALUES ('ready','Audio','audio',1,'READY')")
            sql.execSQL("INSERT INTO podcasts VALUES ('draft','Draft','draft',2,'DRAFT')")
            sql.execSQL("INSERT INTO audio_assets VALUES ('ready','keep')")
            sql.execSQL("INSERT INTO generation_jobs VALUES ('draft','keep-draft','/fixture')")
            sql.execSQL("INSERT INTO migration_state VALUES ('legacy-v1','complete')")
            sql.execSQL("INSERT INTO playback_state VALUES (1,'keep-playback')")
            sql.execSQL("INSERT INTO topics VALUES ('t','r','Topic','Goal',2,7,1,2)")
            sql.execSQL("INSERT INTO topic_nodes VALUES ('r','t',NULL,'ROOT','Topic','',0,0,1)")
            sql.execSQL("INSERT INTO node_podcasts VALUES ('r','ready','MAIN','ru_standard',0,1)")
            sql.execSQL("INSERT INTO outline_proposals VALUES ('p','t','r',7,7,'APPEND',1,5,'chosen','{}','READY','keep-proposal','','chosen',1)")
            sql.version = 4
        } finally { sql.close() }
        val db = Room.databaseBuilder(targetContext, PodcastDatabase::class.java, file.path).addMigrations(PodcastDatabases.MIGRATION_4_5, PodcastDatabases.MIGRATION_5_6).build()
        try { check(db.topics().topic("t")!!.languagePolicy == "ru_standard" && db.topics().topic("t")!!.revision == 7L)
            check(db.topics().links("t").size == 1 && db.outlines().find("p")!!.payload == "keep-proposal")
            check(db.podcasts().asset("ready")!!.journal == "keep" && db.podcasts().job("draft")!!.metadata == "keep-draft")
            check(db.podcasts().playback() == "keep-playback" && db.podcasts().marker("legacy-v1") == "complete")
        } finally { db.close() }
    }
    private fun snapshot(coordinator: PodcastCoordinator): JSONObject {
        val latch = CountDownLatch(1); var raw = ""
        coordinator.snapshot { raw = it; latch.countDown() }; check(latch.await(10, TimeUnit.SECONDS)); return JSONObject(raw)
    }
    private fun await(seconds: Int = 60, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + seconds * 1000
        while (System.currentTimeMillis() < end) { if (condition()) return; Thread.sleep(500) }; error("Condition timed out after $seconds seconds")
    }
    /** Transient snapshot-only fault; no files/database corruption and no model requests. */
    private fun recoveryUiCheck() {
        check(recoveryUi in setOf("cached", "empty"))
        startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); waitForIdleSync()
        val runner = PodcastCoordinator.get(targetContext); val db = PodcastDatabases.get(targetContext)
        await { val s = snapshot(runner); s.optBoolean("storageReady") && !s.optBoolean("busy") }
        val before = db.generationQueue().all().associate { it.id to it.status }
        check(before.values.none { it in setOf("QUEUED", "RUNNING") }) { "This check requires an already paused queue" }
        val audio = db.podcasts().all().associate { it.id to db.podcasts().asset(it.id)?.journal }
        val queue = GenerationQueue(db)
        val request = JSONObject().put("mode", "STANDALONE").put("title", "Проверка восстановления 1.11.1")
            .put("model", "fixture").put("requestId", UUID.randomUUID().toString())
        request.put("previewToken", queue.preview(request).getString("token"))
        val fixtureId = queue.enqueue(request).getJSONArray("ids").getString(0)
        queue.stop(fixtureId, "", true)
        val file = File(targetContext.noBackupFilesDir, "podcast/queue-audit.jsonl")
        val ack = File(targetContext.getExternalFilesDir(null), "ui-checks/recovery-$recoveryUi.ack")
        ack.parentFile!!.mkdirs(); ack.delete()
        val begin = java.time.Instant.now()
        fun events() = file.takeIf { it.exists() }?.readLines().orEmpty().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .filter { java.time.Instant.parse(it.getString("time")) >= begin }
        try {
            val cachedQueue = queue.json()
            runOnMainSync {
                val field = runner.javaClass.getDeclaredField("state").apply { isAccessible = true }
                val state = field.get(runner) as JSONObject
                state.put("stage", "StorageError").put("storageReady", false).put("busy", false).put("error", "storage_recovery_failed")
                if (recoveryUi == "empty") state.remove("generationQueue") else state.put("generationQueue", cachedQueue)
                runner.javaClass.getDeclaredMethod("emit").apply { isAccessible = true }.invoke(runner)
            }
            sendStatus(0, Bundle().apply { putString("recovery", "UI_READY $recoveryUi; open Queue and press retry; expected=${queue.json().length()}") })
            await(120) { val s = snapshot(runner); s.optBoolean("storageReady") && !s.optBoolean("busy") && s.optString("stage") != "StorageError" }
            await { events().any { it.optString("action") == "recover" && it.optString("source") == "ui" && it.optString("result") == "applied" } }
            val unknown = UUID.randomUUID().toString()
            runner.resumeQueue(unknown, "test")
            await { events().any { it.optString("action") == "resume" && it.optString("targetId") == unknown && it.optString("result") == "applied" && it.optInt("affected", -1) == 0 } }
            runner.cancelTask(unknown, "test"); runner.cancel("test")
            await { events().any { it.optString("action") == "cancel" && it.optString("targetId") == unknown && it.optString("result") == "applied" } &&
                events().any { it.optString("action") == "pause" && it.optString("source") == "test" && it.optString("result") == "applied" } }
            check(before.all { (id, status) -> db.generationQueue().find(id)?.status == status })
            check(audio.all { (id, meta) -> db.podcasts().asset(id)?.journal == meta })
            check(events().none { it.optString("action") == "start" || (it.optString("action") == "resume" && it.optString("scope") == "all") })
            sendStatus(0, Bundle().apply { putString("audit", "recover/ui/applied; resume/test/task/affected=0; cancel/test/task; pause/test; no starts or resume-all; original data unchanged") })
            await(60) { ack.exists() } // Host acknowledges only after the checked UI scenario succeeds.
        } finally {
            ack.delete(); ack.parentFile?.delete()
            db.openHelper.writableDatabase.execSQL("DELETE FROM generation_queue WHERE id=?", arrayOf(fixtureId))
            runner.recoverLibrary("test")
            await {
                val state = snapshot(runner)
                state.optBoolean("storageReady") && !state.optBoolean("busy") &&
                    state.optJSONArray("generationQueue")?.let { rows -> (0 until rows.length()).none { rows.getJSONObject(it).getString("id") == fixtureId } } == true
            }
        }
    }
    /** Explicit live flag: A/B use the production bridge; C is added through the normal UI. */
    private fun liveAppend() {
        startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); waitForIdleSync()
        val db = PodcastDatabases.get(targetContext); val runner = PodcastCoordinator.get(targetContext); val queue = GenerationQueue(db)
        await { !snapshot(runner).optBoolean("busy") && !JSONObject(SiwcCoordinator.get(targetContext).snapshot()).optBoolean("busy") }
        val oldQueue = db.generationQueue().all().associate { it.id to it.status }
        check(oldQueue.values.none { it in setOf("QUEUED", "RUNNING") }) { "Existing runnable queue must not be consumed by this check" }
        val oldAudio = db.podcasts().all().associate { it.id to db.podcasts().asset(it.id)?.journal }
        val siwc = SiwcCoordinator.get(targetContext); val catalog = CountDownLatch(1)
        siwc.listModels { catalog.countDown() }; check(catalog.await(30, TimeUnit.SECONDS))
        val model = JSONObject(siwc.snapshot()).getJSONArray("models").getJSONObject(0).getString("slug")
        fun enqueue(title: String): String {
            val request = JSONObject().put("mode", "STANDALONE").put("title", title).put("model", model)
                .put("profile", "ru_standard").put("settings", ContentSettings("BRIEF", "OVERVIEW").json()).put("requestId", UUID.randomUUID().toString())
            var error = ""; var preview = ""; val read = CountDownLatch(1)
            runner.previewGeneration(request.toString()) { value, failure -> preview = value.orEmpty(); error = failure.orEmpty(); read.countDown() }
            check(read.await(5, TimeUnit.SECONDS) && error.isEmpty()) { "Preview: $error" }
            request.put("previewToken", JSONObject(preview).getString("token"))
            var id = ""; val added = CountDownLatch(1)
            runner.enqueueGeneration(request.toString()) { value, failure -> error = failure.orEmpty(); if (value != null) id = JSONObject(value).getJSONArray("ids").getString(0); added.countDown() }
            check(added.await(5, TimeUnit.SECONDS) && error.isEmpty() && id.isNotBlank()) { "Enqueue: $error" }; return id
        }
        val a = enqueue("Pixel queue A: Почему небо голубое?")
        try {
            await(60) { snapshot(runner).optString("stage") == "GeneratingText" && JSONObject(siwc.snapshot()).optBoolean("busy") }
            val operation = JSONObject(siwc.snapshot()).optString("operationId")
            val b = enqueue("Pixel queue B: Почему море солёное?")
            check(queue.find(a)!!.status == "RUNNING" && queue.find(b)!!.status == "QUEUED")
            check(JSONObject(siwc.snapshot()).optString("operationId") == operation)
            sendStatus(0, Bundle().apply { putString("append", "B queued during GeneratingText; A still RUNNING; SIWC operation unchanged") })
            await(120) { snapshot(runner).optString("stage") == "Synthesizing" }
            sendStatus(0, Bundle().apply { putString("append", "UI_READY: add standalone 'Pixel queue C: Why do stars shine?' now") })
            var c = ""
            await(180) {
                c = db.generationQueue().all().firstOrNull { it.id !in oldQueue && it.title.startsWith("Pixel queue C:") }?.id.orEmpty()
                c.isNotBlank()
            }
            val phase = snapshot(runner).optString("stage")
            check(phase == "Synthesizing") { "UI append phase: $phase" }
            check(queue.find(c)!!.status == "QUEUED")
            sendStatus(0, Bundle().apply { putString("append", "C added through UI during $phase; remains QUEUED") })
            val ids = listOf(a, b, c); val runningOrder = mutableListOf(a)
            await(600) {
                val tasks = ids.map { queue.find(it)!! }
                check(tasks.none { it.status in setOf("FAILED", "PAUSED", "CANCELLED") }) { tasks.joinToString { "${it.status}:${it.error}" } }
                val running = tasks.filter { it.status == "RUNNING" }; check(running.size <= 1)
                running.firstOrNull()?.id?.let { if (it !in runningOrder) runningOrder.add(it) }
                tasks.all { it.status == "COMPLETED" }
            }
            check(runningOrder == ids && ids.all { queue.find(it)!!.attempt == 1 })
            check(oldQueue.all { (id, status) -> queue.find(id)?.status == status })
            check(oldAudio.all { (id, meta) -> db.podcasts().asset(id)?.journal == meta })
            ids.forEach { id ->
                check(db.podcasts().record(id)!!.status == "READY")
                val meta = JSONObject(db.podcasts().asset(id)!!.journal).getJSONObject("current")
                sendStatus(0, Bundle().apply { putString("episode", "READY id=$id durationMs=${meta.getLong("durationMs")} attempt=1") })
            }
        } catch (e: Throwable) { runner.cancel(); throw e }
    }
    private fun productionQueueControls() {
        startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); waitForIdleSync(); Thread.sleep(5000)
        val db = PodcastDatabases.get(targetContext); val runner = PodcastCoordinator.get(targetContext); val queue = GenerationQueue(db)
        await { !snapshot(runner).optBoolean("busy") && !JSONObject(SiwcCoordinator.get(targetContext).snapshot()).optBoolean("busy") }
        val original = db.podcasts().all().filter { it.status == "READY" }.associate { it.id to db.podcasts().asset(it.id)!!.journal }
        val source = original.values.map { JSONObject(it).getJSONObject("current") }.first { it.optString("languagePolicy") == "ru_simple" && it.has("scriptHash") }
        val model = source.getString("textModel")
        val operation = JSONObject(SiwcCoordinator.get(targetContext).snapshot()).optString("operationId")
        val request = JSONObject().put("mode", "STANDALONE").put("title", source.getString("topic")).put("model", model)
            .put("profile", "ru_simple").put("settings", source.getJSONObject("contentSettings")).put("requestId", UUID.randomUUID().toString())
        request.put("previewToken", queue.preview(request).getString("token"))
        val first = queue.enqueue(request).getJSONArray("ids").getString(0); var second = ""
        val library = PodcastLibrary(db, File(targetContext.noBackupFilesDir, "podcast"), AndroidAudioMedia(targetContext)); library.initialize()
        val task = queue.find(first)!!
        val meta = JSONObject(source.toString()).put("id", first).put("name", "podcast-control-${first.take(8)}.m4a")
            .put("generationInput", JSONObject(task.input)).put("topic", task.title).put("preferredModel", model)
        meta.remove("uri"); library.begin(meta)
        try {
            runner.resumeQueue(first)
            await(120) {
                val job = db.podcasts().job(first)
                val manifest = job?.let { File(it.directory, "chunks/manifest.json") }
                manifest?.exists() == true && JSONObject(manifest.readText()).getJSONObject("items").length() >= 1 && snapshot(runner).optString("stage") == "Synthesizing"
            }
            val another = JSONObject(request.toString()).put("requestId", UUID.randomUUID().toString())
            var preview = ""; var error = ""; val read = CountDownLatch(1)
            runner.previewGeneration(another.toString()) { value, failure -> preview = value.orEmpty(); error = failure.orEmpty(); read.countDown() }
            check(read.await(5, TimeUnit.SECONDS) && error.isEmpty()) { "Queue preview blocked during TTS: $error" }
            another.put("previewToken", JSONObject(preview).getString("token"))
            val added = CountDownLatch(1)
            runner.enqueueGeneration(another.toString()) { value, failure ->
                error = failure.orEmpty(); if (value != null) second = JSONObject(value).getJSONArray("ids").getString(0); added.countDown()
            }
            check(added.await(5, TimeUnit.SECONDS) && error.isEmpty() && second.isNotBlank()) { "Queue enqueue blocked: $error" }
            check(snapshot(runner).optBoolean("busy") && queue.find(second)!!.status == "QUEUED")
            runner.cancelTask(second); await(5) { queue.find(second)!!.status == "CANCELLED" }
            check(!queue.running(second)); runner.cancel(); await(60) { !snapshot(runner).optBoolean("busy") }
            check(JSONObject(SiwcCoordinator.get(targetContext).snapshot()).optString("operationId") == operation) { "Unexpected SIWC operation during saved-script control check" }
            check(original.all { db.podcasts().asset(it.key)!!.journal == it.value })
        } finally {
            runner.cancel(); await(60) { !snapshot(runner).optBoolean("busy") }
            library.delete(first)
            db.openHelper.writableDatabase.execSQL("DELETE FROM generation_queue WHERE id IN (?, ?)", arrayOf(first, second))
            runner.recoverLibrary(); await { snapshot(runner).optString("selectedId") != first && !snapshot(runner).optBoolean("busy") }
        }
    }
    private fun liveQueue() {
        startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); waitForIdleSync(); Thread.sleep(5000)
        val db = PodcastDatabases.get(targetContext)
        val prior = db.topics().all().firstOrNull { t -> t.title == "Свет и звёзды" && db.generationQueue().all().count { it.topicId == t.id && it.status in setOf("PAUSED", "QUEUED", "RUNNING") } == 2 }
        val f = Fixture(db, prior?.id); val runner = PodcastCoordinator.get(targetContext)
        await { !snapshot(runner).optBoolean("busy") }
        val siwc = SiwcCoordinator.get(targetContext); await { !JSONObject(siwc.snapshot()).optBoolean("busy") }
        val catalog = CountDownLatch(1); siwc.listModels { catalog.countDown() }; check(catalog.await(30, TimeUnit.SECONDS))
        val model = JSONObject(siwc.snapshot()).getJSONArray("models").getJSONObject(0).getString("slug")
        val a = f.dao.nodes(f.topic).firstOrNull { it.title == "Почему звёзды имеют разный цвет?" }?.id ?: f.question("Почему звёзды имеют разный цвет?")
        val b = f.dao.nodes(f.topic).firstOrNull { it.title == "Как по свету определить состав звезды?" }?.id ?: f.question("Как по свету определить состав звезды?")
        val original = db.podcasts().all().filter { db.generationQueue().find(it.id) == null }.associate { it.id to db.podcasts().asset(it.id)?.journal }
        val request = f.request().put("model", model).put("profile", "ru_simple").put("settings", ContentSettings("BRIEF", "DEEP").json())
            .put("overrides", JSONObject().put(b, JSONObject().put("profile", "en_simple_ste").put("settings", ContentSettings("BRIEF", "OVERVIEW").json())))
        val ids = if (prior == null) f.enqueue(request) else db.generationQueue().all().filter { it.topicId == f.topic }.map { it.id }
        check(ids.size == 2)
        val first = ids.first { f.queue.find(it)!!.nodeId == a }
        if (db.podcasts().job(first)?.let { JSONObject(it.metadata).has("script") } != true) {
        runner.resumeQueue("")
        await(300) {
            val task = f.queue.find(first)!!
            check(task.status !in setOf("FAILED", "PAUSED")) { "First task ${task.status}: ${task.error}" }
            val job = db.podcasts().job(first)
            job != null && File(job.directory, "chunks/manifest.json").exists() && JSONObject(File(job.directory, "chunks/manifest.json").readText()).getJSONObject("items").length() >= 1 && snapshot(runner).optString("stage") == "Synthesizing"
        }
        runner.cancel(); await(60) { !snapshot(runner).optBoolean("busy") && f.queue.find(first)!!.status == "PAUSED" }
        }
        val saved = JSONObject(db.podcasts().job(first)!!.metadata); val scriptHash = saved.getString("scriptHash"); val requestId = saved.optString("requestId")
        check(saved.getString("languagePolicy") == "ru_simple")
        sendStatus(0, Bundle().apply { putString("checkpoint", "PAUSED; completed chunk and script durable") })
        runner.resumeQueue("")
        await(30) { f.queue.find(first)!!.status in setOf("QUEUED", "RUNNING", "COMPLETED") }
        await(600) {
            val tasks = ids.map { f.queue.find(it)!! }; check(tasks.none { it.status in setOf("FAILED", "PAUSED") }) { tasks.joinToString { "${it.status}:${it.error}" } }
            tasks.all { it.status == "COMPLETED" }
        }
        val firstMeta = JSONObject(db.podcasts().asset(first)!!.journal).getJSONObject("current")
        check(firstMeta.getString("scriptHash") == scriptHash && firstMeta.optString("requestId") == requestId && firstMeta.getInt("reusedChunks") >= 1)
        check(f.dao.links(f.topic).size == 2 && f.dao.links(f.topic).map { it.profile }.toSet() == setOf("ru_simple", "en_simple_ste"))
        ids.forEach { id -> val meta = JSONObject(db.podcasts().asset(id)!!.journal).getJSONObject("current")
            targetContext.contentResolver.openFileDescriptor(android.net.Uri.parse(meta.getString("uri")), "r").use { check(it != null && it.statSize > 0) }
            sendStatus(0, Bundle().apply { putString("episode", "READY profile=${meta.getString("languagePolicy")} words=${meta.getInt("actualWordCount")} chunks=${meta.getInt("chunks")} reused=${meta.getInt("reusedChunks")} durationMs=${meta.getLong("durationMs")}") })
        }
        val detailed = JSONObject().put("mode", "STANDALONE").put("title", "Как браузер и сервер обмениваются данными по HTTP")
            .put("model", model).put("profile", "ru_standard").put("settings", ContentSettings("DETAILED", "OVERVIEW").json()).put("requestId", UUID.randomUUID().toString())
        val detailedId = f.enqueue(detailed).single(); runner.resumeQueue("")
        await(900) { val task = f.queue.find(detailedId)!!; check(task.status !in setOf("FAILED", "PAUSED")) { "Detailed ${task.status}: ${task.error}" }; task.status == "COMPLETED" }
        val meta = JSONObject(db.podcasts().asset(detailedId)!!.journal).getJSONObject("current")
        check(meta.getInt("actualWordCount") > 300 && meta.getJSONObject("contentSettings").getString("immersion") == "OVERVIEW")
        check(original.all { db.podcasts().asset(it.key)?.journal == it.value })
        sendStatus(0, Bundle().apply { putString("episode", "READY profile=ru_standard words=${meta.getInt("actualWordCount")} chunks=${meta.getInt("chunks")} durationMs=${meta.getLong("durationMs")}") })
    }
}
