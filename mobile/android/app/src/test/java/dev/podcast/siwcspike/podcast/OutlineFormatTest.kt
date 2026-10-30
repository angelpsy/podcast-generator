package dev.podcast.siwcspike.podcast

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OutlineFormatTest {
    private fun node(title: String = "Вопрос", children: JSONArray = JSONArray(), kind: String = "QUESTION") = JSONObject()
        .put("kind", kind).put("title", title).put("description", "Описание").put("children", children)
    private fun plan(children: JSONArray = JSONArray().put(node())) = JSONObject().put("title", "План").put("description", "Обзор").put("children", children)
    private fun reject(raw: String, depth: Int = 4, max: Int = 50) {
        try { OutlineFormat.parse(raw, depth, max); fail("Invalid model output accepted") } catch (_: TopicFailure) { }
    }
    @Test fun russianTreeRoundTripsAndFlattens() {
        val raw = plan(JSONArray().put(node("Раздел", JSONArray().put(node()), "SECTION"))).toString()
        val tree = OutlineFormat.parse(raw, 2, 2)
        assertEquals(tree, OutlineFormat.parse(tree.json().toString(), 2, 2))
        assertEquals(2, OutlineFormat.flatten(tree).getJSONObject(1).getInt("depth"))
    }
    @Test fun rejectsLenientJsonDuplicateKeysAndTrailingData() {
        val raw = plan().toString()
        listOf(raw + " garbage", raw.replace("\"title\":\"План\"", "\"title\":\"План\",\"title\":\"Other\""),
            raw.replace("\"title\"", "'title'"), raw.replace("\"title\"", "title"), raw.dropLast(1) + ",}", "```json\n$raw\n```").forEach(::reject)
    }
    @Test fun rejectsMissingExtraAndWrongTypedFields() {
        reject(plan().put("id", "model-generated-id").toString())
        reject(plan().apply { remove("description") }.toString())
        reject(plan().put("children", "not-array").toString())
        reject(plan(JSONArray().put(node().put("kind", "ROOT"))).toString())
        reject(plan(JSONArray().put(node().put("title", 12))).toString())
    }
    @Test fun rejectsEmptyTreeAndQuestionWithChildren() {
        reject(plan(JSONArray()).toString())
        reject(plan(JSONArray().put(node(children = JSONArray().put(node())))).toString())
    }
    @Test fun enforcesRequestedDepthAndTotalNodeCount() {
        val raw = plan(JSONArray().put(node(children = JSONArray().put(node()), kind = "SECTION"))).toString()
        reject(raw, 1); reject(raw, 2, 1)
        assertEquals(2, OutlineFormat.flatten(OutlineFormat.parse(raw, 2, 2)).length())
    }
    @Test fun countsUnicodeCodepointsAndRejectsControls() {
        OutlineFormat.parse(plan(JSONArray().put(node("😀".repeat(160)))).toString(), 1, 1)
        reject(plan(JSONArray().put(node("😀".repeat(161)))).toString())
        reject(plan(JSONArray().put(node(" "))).toString())
        reject(plan(JSONArray().put(node("a\u0000b"))).toString())
    }
    @Test fun siblingDuplicatesNormalizeCaseYoAndWhitespace() {
        reject(plan(JSONArray().put(node("Ёлка  зимой")).put(node("елка зимой"))).toString())
    }
    @Test fun boundedBeforeRecursiveParsing() {
        reject("[".repeat(10000) + "]".repeat(10000))
        reject(" ".repeat(OutlineFormat.MAX_CHARS + 1))
        reject(plan().toString(), 5); reject(plan().toString(), 1, 51)
    }
    @Test fun acceptsFourLevelsAndFiftyNodesAtLimits() {
        var branch = node()
        repeat(3) { branch = node("Раздел$it", JSONArray().put(branch), "SECTION") }
        assertEquals(4, OutlineFormat.flatten(OutlineFormat.parse(plan(JSONArray().put(branch)).toString(), 4, 4)).length())
        val children = JSONArray(); repeat(50) { children.put(node("Вопрос $it")) }
        assertEquals(50, OutlineFormat.flatten(OutlineFormat.parse(plan(children).toString(), 1, 50)).length())
        children.put(node("51")); reject(plan(children).toString())
    }
    @Test fun strictRecursiveSchemaHasRequiredFieldsAndNoIds() {
        val format = OutlineFormat.format(); assertTrue(format.getBoolean("strict"))
        val schema = format.getJSONObject("schema")
        assertFalse(schema.getBoolean("additionalProperties"))
        assertEquals(4, schema.getJSONObject("\$defs").getJSONObject("node").getJSONArray("required").length())
    }
}
