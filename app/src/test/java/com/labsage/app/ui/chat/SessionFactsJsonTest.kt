package com.labsage.app.ui.chat

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionFactsJsonTest {

    @Test
    fun parse_wrappedFacts_returnsKeyValueRows() {
        val rows = SessionFactsJson.parse(
            """{"facts":{"topic":"3DGS","gpu_count":2,"active":true}}"""
        )

        assertNotNull(rows)
        assertEquals(listOf("topic", "gpu_count", "active"), rows!!.map { it.key })
        assertEquals(listOf("3DGS", "2", "true"), rows.map { it.value })
    }

    @Test
    fun parse_nestedValue_keepsPrettyJsonUnderTopLevelKey() {
        val rows = SessionFactsJson.parse(
            """{"facts":{"gpu":{"model":"A100","memory_gb":80}}}"""
        )

        assertEquals(1, rows?.size)
        assertTrue(rows!!.single().value.contains("\"model\": \"A100\""))
    }

    @Test
    fun parse_directObject_isBackwardCompatible() {
        val rows = SessionFactsJson.parse("""{"owner":"Lab"}""")

        assertEquals(SessionFactUi("owner", "Lab"), rows?.single())
    }

    @Test
    fun remove_preservesWrapperAndOtherFacts() {
        val updated = SessionFactsJson.remove(
            """{"facts":{"topic":"3DGS","owner":"Lab"},"version":1}""",
            "topic"
        )

        val root = JsonParser.parseString(updated).asJsonObject
        assertFalse(root.getAsJsonObject("facts").has("topic"))
        assertEquals("Lab", root.getAsJsonObject("facts").get("owner").asString)
        assertEquals(1, root.get("version").asInt)
    }

    @Test
    fun malformedOrMissingFact_returnsNull() {
        assertNull(SessionFactsJson.parse("not-json"))
        assertNull(SessionFactsJson.remove("""{"facts":{}}""", "missing"))
    }
}
