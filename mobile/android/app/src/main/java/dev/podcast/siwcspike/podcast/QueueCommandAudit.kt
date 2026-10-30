package dev.podcast.siwcspike.podcast

import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

/** Bounded command journal: no titles, scripts, requests, identity or credentials. */
class QueueCommandAudit(private val file: File, private val sink: (String) -> Unit, private val limit: Long = 128 * 1024) {
    data class Command(val id: String, val action: String, val source: String, val target: String)
    fun begin(action: String, source: String, target: String = ""): Command {
        val command = Command(UUID.randomUUID().toString(), action, source, target)
        event(command, "requested"); return command
    }
    @Synchronized fun event(command: Command, result: String, affected: Int? = null, counts: Map<String, Int>? = null) {
        val actions = setOf("enqueue", "resume", "pause", "cancel", "recover", "start", "complete", "stop")
        val sources = setOf("ui", "internal", "lifecycle", "runner", "startup", "library_retry", "test")
        val results = setOf("requested", "applied", "rejected_busy", "failed", "started", "completed", "paused", "cancelled")
        val uuid = Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
        val line = JSONObject().put("time", Instant.now().toString()).put("commandId", command.id.takeIf { uuid.matches(it) } ?: "invalid")
            .put("action", command.action.takeIf { it in actions } ?: "unknown")
            .put("source", command.source.takeIf { it in sources } ?: "unknown")
            .put("scope", if (command.target.isEmpty()) "all" else "task")
            .put("targetId", command.target.takeIf { uuid.matches(it) } ?: if (command.target.isEmpty()) "" else "invalid")
            .put("result", result.takeIf { it in results } ?: "unknown")
        affected?.let { line.put("affected", it.coerceAtLeast(0)) }
        counts?.let { values -> line.put("counts", JSONObject().apply {
            for (status in listOf("QUEUED", "RUNNING", "PAUSED", "FAILED", "COMPLETED", "CANCELLED")) put(status, values[status] ?: 0)
        }) }
        val raw = line.toString()
        runCatching { sink(raw) }
        runCatching {
            file.parentFile?.mkdirs()
            if (file.length() + raw.toByteArray(Charsets.UTF_8).size + 1 > limit) {
                val old = File(file.path + ".1"); old.delete()
                if (!file.renameTo(old)) file.delete()
            }
            file.appendText(raw + "\n")
        } // Audit failure must never interrupt the queue or reveal raw exception messages.
    }
}
