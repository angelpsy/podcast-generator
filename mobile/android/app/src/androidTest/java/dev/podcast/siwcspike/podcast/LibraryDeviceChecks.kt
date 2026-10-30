package dev.podcast.siwcspike.podcast

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import androidx.room.Room
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/** Real Room/SQLite, isolated legacy fixtures and fault-injected MediaStore. No SIWC access. */
class LibraryDeviceChecks : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private class Media : AudioMedia {
        val files = linkedMapOf<String, Boolean>()
        var unavailable = false
        var failInsert = false
        var failPublish = false
        var failDelete = false
        private fun query() { if (unavailable) throw IOException("media_query_failed") }
        override fun find(name: String): String? { query(); return name.takeIf(files::containsKey) }
        override fun create(name: String): String {
            check(!files.containsKey(name)); files[name] = false
            if (failInsert) throw IOException("after_insert")
            return name
        }
        override fun copy(uri: String, source: File, checkCancelled: () -> Unit) { checkCancelled(); check(source.exists()) }
        override fun isPublished(uri: String): Boolean { query(); return files[uri] == true }
        override fun publish(uri: String) { files[uri] = true; if (failPublish) throw IOException("after_publish") }
        override fun delete(uri: String) { query(); if (failDelete) throw IOException("delete_failed"); files.remove(uri) }
    }
    private fun meta(id: String, title: String = id) = JSONObject().put("schema", 2)
        .put("id", id).put("name", "$id.m4a").put("topic", title)
    override fun onStart() {
        val root = File(targetContext.cacheDir, "library-check-${UUID.randomUUID()}").apply { mkdirs() }
        val passed = mutableListOf<String>()
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        fun test(name: String, block: (File, PodcastDatabase, Media) -> Unit) {
            val folder = File(root, name).apply { mkdirs() }
            val db = Room.databaseBuilder(targetContext, PodcastDatabase::class.java, File(folder, "library.db").path).build()
            try { block(folder, db, Media()); passed.add(name)
                sendStatus(0, Bundle().apply { putString("passed", name) })
            } finally { db.close() }
        }
        try {
            test("empty_and_idempotent") { folder, db, media ->
                val library = PodcastLibrary(db, folder, media)
                library.initialize(); library.initialize(); check(library.list().length() == 0)
                check(db.podcasts().marker("legacy-v1") == "complete")
            }
            test("legacy_ready_and_script_wav_import") { folder, db, media ->
                val current = meta("old").put("uri", "old.m4a").put("script", "Сохранённый рассказ.")
                media.files["old.m4a"] = true
                val journal = AtomicAudioJournal(File(folder, "current.json"))
                journal.write(JSONObject().put("current", current))
                val draft = File(folder, "draft").apply { mkdirs() }
                val pending = meta("draft").put("script", "Сохранённый русский текст.")
                AtomicAudioJournal(File(draft, "meta.json")).write(pending)
                File(draft, "speech.wav").writeText("wav-fixture")
                File(draft, "audio.m4a").writeText("encoded-fixture")
                val before = File(folder, "current.json").readBytes()
                val library = PodcastLibrary(db, folder, media)
                library.initialize(); check(library.list().length() == 2 && library.selectedId == "draft")
                val restored = PodcastScript.completedDraft(library.draftJournal) { error("must_not_request_text") }
                check(restored.getString("script") == pending.getString("script"))
                check(File(library.draft, "speech.wav").readText() == "wav-fixture")
                check(File(library.draft, "audio.m4a").readText() == "encoded-fixture")
                library.initialize(); check(library.list().length() == 2)
                check(File(folder, "current.json").readBytes().contentEquals(before))
                check(library.list("old").getJSONObject(0).getString("profile") == "ru_standard")
                library.select("old"); check(library.current()!!.getString("script") == current.getString("script"))
                File(folder, "current.json").writeText("broken backup ignored after marker")
                library.initialize(); check(library.list().length() == 2)
            }
            test("legacy_draft_already_published") { folder, db, media ->
                val old = meta("old").put("uri", "old.m4a")
                media.files["old.m4a"] = true
                AtomicAudioJournal(File(folder, "current.json")).write(JSONObject().put("current", old))
                AtomicAudioJournal(File(folder, "draft/meta.json")).write(old)
                val library = PodcastLibrary(db, folder, media); library.initialize()
                check(library.list().length() == 1 && db.podcasts().job("old") == null)
            }
            test("invalid_legacy_blocks_transaction") { folder, db, media ->
                File(folder, "draft").mkdirs(); File(folder, "draft/meta.json").writeText("{broken")
                val library = PodcastLibrary(db, folder, media)
                fails { library.initialize() }; check(db.podcasts().all().isEmpty())
                check(db.podcasts().marker("legacy-v1") == null)
            }
            test("invalid_json_shape_blocks_import") { folder, db, media ->
                AtomicAudioJournal(File(folder, "current.json")).write(JSONObject().put("unexpected", true))
                fails { PodcastLibrary(db, folder, media).initialize() }
                check(db.podcasts().marker("legacy-v1") == null)
            }
            test("migration_transaction_rollback_and_retry") { folder, db, media ->
                val current = meta("ready").put("uri", "ready.m4a")
                media.files["ready.m4a"] = true
                AtomicAudioJournal(File(folder, "current.json")).write(JSONObject().put("current", current))
                AtomicAudioJournal(File(folder, "draft/meta.json")).write(meta("blocked"))
                db.podcasts().insert(PodcastRecord("blocked", "fixture", "fixture", 1))
                val library = PodcastLibrary(db, folder, media)
                fails { library.initialize() }
                check(db.podcasts().record("ready") == null && db.podcasts().marker("legacy-v1") == null)
                db.podcasts().delete("blocked"); library.initialize()
                check(library.list().length() == 2 && media.files["ready.m4a"] == true)
            }
            test("legacy_null_query_preserves_pending") { folder, db, media ->
                val pending = meta("pending")
                val journal = AtomicAudioJournal(File(folder, "current.json"))
                journal.write(JSONObject().put("pending", pending)); media.files["pending.m4a"] = true
                media.unavailable = true
                val library = PodcastLibrary(db, folder, media)
                fails { library.initialize() }; check(journal.read().has("pending"))
                check(db.podcasts().marker("legacy-v1") == null && db.podcasts().all().isEmpty())
                media.unavailable = false; library.initialize()
                check(library.current()!!.getString("id") == "pending")
            }
            test("three_publications_retained_and_reopened") { folder, db, media ->
                val library = PodcastLibrary(db, folder, media); library.initialize()
                val source = File(folder, "audio").apply { writeText("audio") }
                repeat(3) { n -> val m = meta("item-$n"); library.begin(m); library.save(source, m) {} }
                check(media.files.size == 3 && library.list().length() == 3)
                db.close()
                val reopenedDb = Room.databaseBuilder(targetContext, PodcastDatabase::class.java,
                    File(folder, "library.db").path).build()
                try {
                    val reopened = PodcastLibrary(reopenedDb, folder, media); reopened.initialize()
                    check(reopened.list().length() == 3 && reopenedDb.podcasts().all().all { it.status == "READY" })
                } finally { reopenedDb.close() }
            }
            test("independent_drafts_and_script_reuse") { folder, db, media ->
                val library = PodcastLibrary(db, folder, media); library.initialize()
                library.begin(meta("one").put("script", "Первый сохранённый рассказ."))
                library.begin(meta("two").put("script", "Второй сохранённый рассказ."))
                library.select("one"); check(library.draftJournal.read().getString("script").startsWith("Первый"))
                library.select("two"); check(library.draftJournal.read().getString("script").startsWith("Второй"))
            }
            test("insert_crash_cleanup_then_retry") { folder, db, media ->
                val library = PodcastLibrary(db, folder, media); library.initialize()
                val m = meta("insert"); library.begin(m)
                val source = File(folder, "audio").apply { writeText("audio") }
                media.failInsert = true; fails { library.save(source, m) {} }
                media.unavailable = true; fails { library.recover() }; check(media.files.size == 1)
                media.unavailable = false; media.failInsert = false; library.recover()
                check(media.files.isEmpty() && db.podcasts().job("insert") != null)
                library.save(source, m) {}; check(media.files.size == 1)
            }
            test("publication_crash_commits_once") { folder, db, media ->
                val library = PodcastLibrary(db, folder, media); library.initialize()
                val m = meta("publish"); library.begin(m)
                val source = File(folder, "audio").apply { writeText("audio") }
                media.failPublish = true; fails { library.save(source, m) {} }
                media.failPublish = false; library.recover(); library.recover()
                check(library.current()!!.getString("id") == "publish" && media.files.size == 1)
                check(db.podcasts().record("publish")!!.status == "READY")
                check(library.save(source, m) {}.getString("id") == "publish" && media.files.size == 1)
            }
            test("missing_audio_and_failed_delete_tombstone") { folder, db, media ->
                val library = PodcastLibrary(db, folder, media); library.initialize()
                val source = File(folder, "audio").apply { writeText("audio") }
                for (id in listOf("one", "two")) { val m = meta(id); library.begin(m); library.save(source, m) {} }
                media.files.remove("one.m4a"); library.recover()
                check(db.podcasts().record("one")!!.status == "FILE_MISSING")
                media.failDelete = true; fails { library.delete("two") }
                check(db.podcasts().record("two")!!.status == "DELETING" && media.files.size == 1)
                media.failDelete = false; library.recover()
                check(db.podcasts().record("two") == null && db.podcasts().record("one") != null)
                library.delete("one"); check(library.list().length() == 0)
            }
            test("literal_unicode_search") { folder, db, media ->
                val library = PodcastLibrary(db, folder, media); library.initialize()
                library.begin(meta("one", "Ёлка и Замыкания 100%_")); library.begin(meta("two", "Другой рассказ"))
                check(library.list("елка").length() == 1 && library.list("замыкания").length() == 1)
                check(library.list("%_").length() == 1 && library.list("\\").length() == 0)
            }
            test("foreign_key_transaction_rollback") { _, db, _ ->
                fails { db.runInTransaction {
                    db.podcasts().insert(PodcastRecord("rollback", "title", "title", 1))
                    db.podcasts().job(GenerationJobRecord("missing-parent", "{}", "/unused"))
                } }
                check(db.podcasts().record("rollback") == null)
            }
            test("library_shows_current_topic_memberships_dedupes_nodes_and_tracks_removal") { folder, db, media ->
                val library = PodcastLibrary(db, folder, media); library.initialize()
                db.podcasts().insert(PodcastRecord("shared", "Рассказ", "рассказ", 1, "READY"))
                val dao = db.topics()
                for ((id, title) in listOf("a" to "Астрономия", "b" to "Научпоп")) {
                    dao.insert(TopicRecord(id, "$id-root", title, "", createdAt = 1, updatedAt = 1))
                    dao.insert(TopicNodeRecord("$id-root", id, null, "ROOT", title, rootSlot = 1))
                    dao.insert(TopicNodeRecord("$id-q", id, "$id-root", "QUESTION", "Вопрос"))
                    dao.link(NodePodcastRecord("$id-root", "shared", "MAIN", "ru_standard", 0, 1))
                    dao.link(NodePodcastRecord("$id-q", "shared", "MAIN", "ru_standard", 0, 1))
                }
                val memberships = library.list().getJSONObject(0).getJSONArray("topics")
                check(memberships.length() == 2 && memberships.getJSONObject(0).getString("title") == "Астрономия")
                dao.update(dao.topic("a")!!.copy(title = "Звёзды"))
                check(library.list("Рассказ").getJSONObject(0).getJSONArray("topics").getJSONObject(0).getString("title") == "Звёзды")
                dao.deleteTopic("a"); check(library.list().getJSONObject(0).getJSONArray("topics").length() == 1)
                dao.unlink("b-root", "shared"); dao.unlink("b-q", "shared")
                check(library.list().getJSONObject(0).getJSONArray("topics").length() == 0)
                check(db.podcasts().record("shared") != null)
            }
            result.putString("result", "PASS ${passed.size}/15 isolated Room checks; no SIWC access")
            code = Activity.RESULT_OK
        } catch (e: Throwable) {
            result.putString("result", "FAIL after ${passed.size}/15: ${e.javaClass.simpleName}: ${e.message}")
            result.putString("location", e.stackTrace.firstOrNull { it.className.startsWith(javaClass.name) }?.toString())
        } finally { root.deleteRecursively() }
        result.putString("passed", passed.joinToString("\n"))
        finish(code, result)
    }
    private fun fails(block: () -> Unit) {
        var failed = false
        try { block() } catch (_: Exception) { failed = true }
        check(failed) { "Expected controlled failure" }
    }
}
