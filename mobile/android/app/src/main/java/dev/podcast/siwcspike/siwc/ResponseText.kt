package dev.podcast.siwcspike.siwc

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader

/** Shared Responses contract. Partial text is never a successful result. */
internal object ResponseText {
    fun request(model: String, input: String, instructions: String? = null, responseFormat: JSONObject? = null): JSONObject =
        JSONObject().put("model", model).put("store", false).put("stream", true)
            .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", input)))
            .also {
                if (instructions != null) it.put("instructions", instructions)
                if (responseFormat != null) it.put("text", JSONObject().put("format", JSONObject(responseFormat.toString())))
            }

    fun read(reader: BufferedReader, maxCodePoints: Int, checkCancelled: () -> Unit,
             onDelta: (String) -> Unit = {}, onTerminal: (Int, Int) -> Unit = { _, _ -> }): String {
        val text = StringBuilder()
        var completed = false
        fun bounded(value: String) {
            if (value.codePointCount(0, value.length) > maxCodePoints) throw SafeFailure("answer_too_large")
        }
        Protocol.readSse(reader) { event ->
            checkCancelled()
            when (event.optString("type")) {
                "response.output_text.delta" -> {
                    text.append(event.getString("delta"))
                    bounded(text.toString())
                    onDelta(text.toString())
                }
                "response.refusal.delta", "response.refusal.done" -> throw SafeFailure("response_refused")
                "response.incomplete" -> throw SafeFailure("response_incomplete")
                "response.failed", "error" -> {
                    val error = event.optJSONObject("response")?.optJSONObject("error") ?: event.optJSONObject("error")
                    val code = error?.optString("code") ?: event.optString("code")
                    throw SafeFailure(if (code.matches(Regex("subscription_sharing_[a-z_]{1,80}"))) code else "response_failed")
                }
                "response.completed" -> {
                    val response = event.optJSONObject("response")
                    if (response != null && response.optString("status", "completed") != "completed") {
                        throw SafeFailure("response_incomplete")
                    }
                    // The terminal output is authoritative when present (also covers a stream without deltas).
                    response?.optJSONArray("output")?.let { output ->
                        val parts = mutableListOf<String>()
                        for (i in 0 until output.length()) {
                            val item = output.getJSONObject(i)
                            if (item.optString("type") != "message") continue
                            val content = item.optJSONArray("content") ?: continue
                            for (j in 0 until content.length()) {
                                val part = content.getJSONObject(j)
                                if (part.optString("type") == "refusal") throw SafeFailure("response_refused")
                                if (part.optString("type") == "output_text") parts.add(part.getString("text"))
                            }
                        }
                        onTerminal(text.codePointCount(0, text.length), parts.size)
                        // SIWC can confirm completion with output:[] after streaming all text as deltas.
                        if (parts.isNotEmpty()) {
                            text.setLength(0)
                            text.append(parts.joinToString("\n\n"))
                        }
                        bounded(text.toString())
                    }
                    completed = true
                }
            }
            !completed
        }
        checkCancelled()
        if (!completed) throw SafeFailure("stream_interrupted")
        if (text.isBlank()) throw SafeFailure("empty_script")
        return text.toString()
    }
}
