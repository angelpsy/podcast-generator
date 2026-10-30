package dev.podcast.siwcspike.podcast

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** All calls on the coordinator's serial worker. Never opens or changes SIWC storage. */
class PodcastLibrary(private val db: PodcastDatabase, private val root: File, private val media: AudioMedia) {
    private val dao = db.podcasts()
    var selectedId: String = ""
        private set
    private var initialized = false
    val draft: File get() = dao.job(selectedId)?.let { File(it.directory) } ?: File(root, "jobs/$selectedId")
    val draftJournal: AudioJournal get() {
        val id = selectedId
        return object : AudioJournal {
            override fun read() = dao.job(id)?.let { JSONObject(it.metadata) } ?: JSONObject()
            override fun write(state: JSONObject) {
                check(state.getString("id") == id)
                val old = requireNotNull(dao.job(id))
                dao.job(old.copy(metadata = state.toString()))
            }
        }
    }
    private fun journal(id: String) = object : AudioJournal {
        override fun read() = dao.asset(id)?.let { JSONObject(it.journal) } ?: JSONObject()
        override fun write(state: JSONObject) {
            // Ready state and the publication URI commit together; temporary files are cleaned later.
            db.runInTransaction {
                dao.asset(AudioAssetRecord(id, state.toString()))
                if (state.has("current")) { dao.status(id, "READY"); GenerationQueue(db).complete(id)
                    db.generationQueue().find(id)?.error?.takeIf { it.isNotEmpty() }?.let { notice ->
                        state.getJSONObject("current").put("attachmentWarning", notice)
                        dao.asset(AudioAssetRecord(id, state.toString()))
                    } }
            }
        }
    }
    private fun store(id: String = selectedId) = LatestAudioStore(journal(id), media)

    fun initialize() {
        initialized = false
        if (dao.marker("legacy-v1") == null) migrate()
        initialized = true
        try { recover() } catch (e: Exception) { initialized = false; throw e }
        if (dao.record(selectedId) == null) selectedId = dao.all().firstOrNull()?.id.orEmpty()
    }

    private fun migrate() {
        val legacyJournal = AtomicAudioJournal(File(root, "current.json"))
        val raw = legacyJournal.read()
        require(raw.length() == 0 || raw.has("current") || raw.has("pending") || raw.has("cleanup")) {
            "invalid_legacy_journal"
        }
        for (key in listOf("current", "pending")) if (raw.has(key)) {
            val meta = raw.getJSONObject(key)
            require(meta.getString("id").isNotBlank() && meta.getString("name").isNotBlank())
        }
        val legacy = LatestAudioStore(legacyJournal, media)
        legacy.recover() // A null provider cursor is an error, never an empty installation.
        val current = legacy.current()
        val rawDraft = AtomicAudioJournal(File(root, "draft/meta.json")).read()
        val pending = if (rawDraft.length() == 0) null else {
            requireNotNull(PodcastDraft.read(object : AudioJournal {
                override fun read() = rawDraft
                override fun write(state: JSONObject) = error("read_only")
            })) { "invalid_legacy_draft" }
        }
        current?.let { require(it.getString("id").isNotBlank() && it.getString("uri").isNotBlank()) }
        for (meta in listOfNotNull(current, pending)) {
            meta.put("languagePolicy", "ru_standard").put("contentSettingsOrigin", "LEGACY")
                .put("targetWordsMin", 120).put("targetWordsMax", 180)
        }
        db.runInTransaction {
            current?.let { importRecord(it, "READY", 0)
                dao.asset(AudioAssetRecord(it.getString("id"), JSONObject().put("current", it).toString())) }
            if (pending != null && pending.getString("id") != current?.getString("id")) {
                importRecord(pending, "DRAFT", 1)
                dao.job(GenerationJobRecord(pending.getString("id"), pending.toString(), File(root, "draft").absolutePath))
            }
            dao.marker(LibraryMigration("legacy-v1", "complete"))
        }
        // Keep legacy metadata as a backup. After the marker no production code writes it.
    }

    private fun importRecord(meta: JSONObject, status: String, offset: Long = 0) {
        val title = meta.optString("topic").ifBlank { meta.getString("name") }
        dao.insert(PodcastRecord(meta.getString("id"), title, LibrarySearch.normalize(title),
            System.currentTimeMillis() + offset, status))
    }
    fun hasDraft(id: String) = dao.job(id) != null
    fun hasRecord(id: String) = dao.record(id) != null
    fun begin(meta: JSONObject) {
        check(initialized)
        val id = meta.getString("id")
        require(id.matches(Regex("[A-Za-z0-9-]+")))
        db.runInTransaction {
            importRecord(meta, "DRAFT")
            dao.job(GenerationJobRecord(id, meta.toString(), File(root, "jobs/$id").absolutePath))
        }
        selectedId = id
    }
    fun select(id: String) { requireNotNull(dao.record(id)); selectedId = id }
    fun current(): JSONObject? = if (selectedId.isEmpty()) null else store().current()
    fun cleanupPending() = selectedId.isNotEmpty() && store().cleanupPending()
    fun save(source: File, episode: JSONObject, check: () -> Unit): JSONObject {
        check(initialized && episode.getString("id") == selectedId)
        return store().save(source, episode, check)
    }
    fun completeDraft(id: String = selectedId) {
        dao.job(id)?.let { job ->
            if (File(job.directory).deleteRecursively() || !File(job.directory).exists()) dao.deleteJob(id)
        }
    }
    fun recover() {
        check(initialized)
        dao.all().forEach { row ->
            if (row.status == "DELETING") delete(row.id) else {
                store(row.id).recover()
                val current = store(row.id).current()
                if (current != null) {
                    dao.status(row.id, if (media.isPublished(current.getString("uri"))) "READY" else "FILE_MISSING")
                    completeDraft(row.id)
                }
            }
        }
    }
    fun delete(id: String) {
        check(initialized)
        if (dao.record(id) == null) return
        dao.status(id, "DELETING") // Tombstone persists across provider/disk failures.
        val state = journal(id).read()
        val uris = linkedSetOf<String>()
        for (key in listOf("current", "pending")) state.optJSONObject(key)?.let { meta ->
            val uri = meta.optString("uri").ifBlank { media.find(meta.getString("name")).orEmpty() }
            if (uri.isNotBlank()) uris.add(uri)
        }
        state.optString("cleanup").takeIf { it.isNotBlank() }?.let { uris.add(it) }
        uris.forEach(media::delete)
        dao.job(id)?.let { check(File(it.directory).deleteRecursively() || !File(it.directory).exists()) }
        db.runInTransaction {
            db.topics().invalidatePodcast(id, System.currentTimeMillis())
            db.playlists().invalidatePodcast(id, System.currentTimeMillis())
            dao.delete(id)
        }
        if (selectedId == id) selectedId = dao.all().firstOrNull()?.id.orEmpty()
    }
    fun list(query: String = ""): JSONArray = JSONArray().apply {
        val memberships = db.topics().podcastMemberships().groupBy { it.podcastId }
        dao.search(LibrarySearch.pattern(query)).forEach { row ->
            val meta = store(row.id).current()
            put(JSONObject().put("id", row.id).put("title", row.title).put("status", row.status)
                .put("topics", JSONArray(memberships[row.id].orEmpty().map { JSONObject().put("id", it.topicId).put("title", it.title) }))
                .put("durationMs", meta?.optLong("durationMs") ?: 0).put("profile", meta?.optString("languagePolicy").orEmpty().ifBlank { "ru_standard" }).put("selected", row.id == selectedId))
        }
    }
}
