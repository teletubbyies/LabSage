package com.labsage.app.ui.chat

import androidx.compose.runtime.Immutable
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** A single top-level entry from the backend's `{ "facts": { ... } }` payload. */
@Immutable
data class SessionFactUi(
    val key: String,
    val value: String
)

/**
 * Converts the backend's string-encoded facts JSON into UI rows and back again.
 *
 * The current backend wraps facts as `{ "facts": { ... } }`. Direct JSON
 * objects are also accepted for backward compatibility. Nested values remain
 * grouped under their top-level key and are rendered as pretty JSON.
 */
object SessionFactsJson {
    private val prettyGson = GsonBuilder().setPrettyPrinting().create()

    /** Returns null when [raw] is not a supported JSON object. */
    fun parse(raw: String): List<SessionFactUi>? {
        val container = parseDocument(raw)?.facts ?: return null
        return container.entrySet().map { (key, value) ->
            SessionFactUi(key = key, value = value.toDisplayValue())
        }
    }

    /**
     * Removes one top-level fact while preserving the backend wrapper and any
     * unrelated metadata. Returns null for malformed JSON or a missing key.
     */
    fun remove(raw: String, key: String): String? {
        val document = parseDocument(raw) ?: return null
        if (!document.facts.has(key)) return null
        document.facts.remove(key)
        return prettyGson.toJson(document.root)
    }

    private fun parseDocument(raw: String): FactsDocument? {
        if (raw.isBlank()) {
            val root = JsonObject()
            val facts = JsonObject()
            root.add("facts", facts)
            return FactsDocument(root, facts)
        }

        return runCatching {
            val root = JsonParser.parseString(raw).takeIf { it.isJsonObject }?.asJsonObject
                ?: return null
            val wrappedFacts = root.get("facts")
            val facts = when {
                wrappedFacts == null -> root
                wrappedFacts.isJsonObject -> wrappedFacts.asJsonObject
                else -> return null
            }
            FactsDocument(root, facts)
        }.getOrNull()
    }

    private fun JsonElement.toDisplayValue(): String = when {
        isJsonNull -> "null"
        isJsonPrimitive && asJsonPrimitive.isString -> asString
        isJsonPrimitive -> toString()
        else -> prettyGson.toJson(this)
    }

    private data class FactsDocument(
        val root: JsonObject,
        val facts: JsonObject
    )
}
