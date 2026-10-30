package dev.podcast.siwcspike.siwc

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ResponseTextTest {
    private fun event(type: String, delta: String? = null) = JSONObject().put("type", type).also {
        if (delta != null) it.put("delta", delta)
    }
    private fun read(vararg events: JSONObject, limit: Int = 3000, check: () -> Unit = {}): String =
        ResponseText.read(events.joinToString("") { "data: $it\n\n" }.reader().buffered(), limit, check)
    private fun fails(code: String, action: () -> Unit) {
        try { action(); fail("Expected $code") } catch (e: SafeFailure) { assertEquals(code, e.safeCode) }
    }

    @Test fun requestKeepsTopicSeparateAndOmitsUnsupportedFields() {
        val body = ResponseText.request("account-model", "Тема", "Инструкция")
        assertEquals(setOf("model", "store", "stream", "input", "instructions"), body.keys().asSequence().toSet())
        assertFalse(body.getBoolean("store")); assertTrue(body.getBoolean("stream"))
        assertEquals("Инструкция", body.getString("instructions"))
        assertEquals("Тема", body.getJSONArray("input").getJSONObject(0).getString("content"))
        assertEquals("user", body.getJSONArray("input").getJSONObject(0).getString("role"))
    }

    @Test fun structuredFormatIsNestedAndIndependentFromCallerMutation() {
        val schema = JSONObject().put("type", "object").put("additionalProperties", false)
        val format = JSONObject().put("type", "json_schema").put("name", "outline")
            .put("strict", true).put("schema", schema)
        val body = ResponseText.request("account-model", "Тема", "Инструкция", format)
        schema.put("type", "string")
        val sent = body.getJSONObject("text").getJSONObject("format")
        assertEquals("json_schema", sent.getString("type"))
        assertTrue(sent.getBoolean("strict"))
        assertEquals("object", sent.getJSONObject("schema").getString("type"))
        assertFalse(body.getBoolean("store")); assertTrue(body.getBoolean("stream"))
        assertFalse(body.has("response_format"))
    }

    @Test fun completionRequiredAndTerminalTextIsNotDuplicated() {
        val terminal = event("response.completed").put("response", JSONObject().put("status", "completed")
            .put("output", JSONArray().put(JSONObject().put("type", "message").put("content", JSONArray()
                .put(JSONObject().put("type", "output_text").put("text", "Привет, мир."))))))
        assertEquals("Привет, мир.", read(event("response.output_text.delta", "Привет, "), terminal))
        assertEquals("Привет, мир.", read(terminal))
        assertEquals("Привет", read(event("response.output_text.delta", "Привет"), event("response.completed")))
    }

    @Test fun eofAndIncompleteCannotReachSpeech() {
        fails("stream_interrupted") { read(event("response.output_text.delta", "Часть текста")) }
        fails("response_incomplete") { read(event("response.output_text.delta", "Часть текста"), event("response.incomplete")) }
        fails("empty_script") { read(event("response.completed")) }
        fails("response_failed") { read(event("response.failed")) }
    }

    @Test fun siwcCompletionMayOmitTextAlreadyStreamedAsDeltas() {
        val terminal = event("response.completed").put("response", JSONObject().put("status", "completed").put("output", JSONArray()))
        assertEquals("Завершённый рассказ.", read(event("response.output_text.delta", "Завершённый рассказ."), terminal))
        fails("empty_script") { read(terminal) }
    }

    @Test fun refusalIsRejectedEvenWhenSomeTextWasEmitted() {
        fails("response_refused") { read(event("response.output_text.delta", "Начало"), event("response.refusal.delta", "Нет")) }
        val refusal = event("response.completed").put("response", JSONObject().put("output", JSONArray()
            .put(JSONObject().put("type", "message").put("content", JSONArray()
                .put(JSONObject().put("type", "refusal").put("refusal", "Нет"))))))
        fails("response_refused") { read(refusal) }
    }

    @Test fun countsUnicodeCodePointsAndRejectsOverflowDuringStream() {
        assertEquals("😀😀", read(event("response.output_text.delta", "😀😀"), event("response.completed"), limit = 2))
        fails("answer_too_large") { read(event("response.output_text.delta", "😀😀😀"), limit = 2) }
    }

    @Test fun cancellationAndUsageErrorsPropagateWithoutAutomaticRetry() {
        fails("cancelled") { read(event("response.completed"), check = { throw SafeFailure("cancelled") }) }
        fails("subscription_sharing_usage_limit_exceeded") {
            read(event("error").put("code", "subscription_sharing_usage_limit_exceeded"))
        }
    }
}
