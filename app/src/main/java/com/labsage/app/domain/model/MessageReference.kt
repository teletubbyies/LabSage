package com.labsage.app.domain.model

import com.google.gson.annotations.SerializedName
import java.util.regex.Pattern

/**
 * A chunk-level citation attached to an assistant message.
 *
 * The backend's evidence registry (SuperBizAgent internal/ai/evidence) maps the
 * `[ref]` markers the model writes inline in the answer to the retrieved chunk
 * each marker points at. The client stores the registry snapshot verbatim as
 * JSON on the message row (see MessageEntity.referencesJson) and renders it as
 * small chips under the bubble; tapping a chip opens the chunk's metadata.
 */
data class MessageReference(
    /** Marker text as it appears in the answer body, e.g. "1" for "[1]". */
    val ref: String,
    /** Stable chunk id in the knowledge base. */
    val chunkId: String,
    /** Source document (file name / path). */
    val source: String,
    /** Human-readable title of the chunk. */
    val title: String,
    /** Content fingerprint (not a publication version) — shown truncated. */
    val contentHash: String,
    /** Truncated chunk body for preview; full text stays on the server. */
    val preview: String? = null
)

/**
 * Wire/storage shape. Every field is nullable: Gson bypasses constructors, so
 * a malformed or partial item must never inject null into non-null Kotlin
 * fields — [toReference] applies the defaults instead.
 */
data class MessageReferencePayload(
    @SerializedName("ref") val ref: String? = null,
    @SerializedName("chunk_id") val chunkId: String? = null,
    @SerializedName("source") val source: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("content_hash") val contentHash: String? = null,
    @SerializedName("content") val content: String? = null
)

fun MessageReferencePayload.toReference() = MessageReference(
    ref = ref.orEmpty(),
    chunkId = chunkId.orEmpty(),
    source = source.orEmpty(),
    title = title.orEmpty(),
    contentHash = contentHash.orEmpty(),
    preview = content?.take(PREVIEW_MAX_CHARS)
)

fun MessageReference.toPayload() = MessageReferencePayload(
    ref = ref,
    chunkId = chunkId,
    source = source,
    title = title,
    contentHash = contentHash,
    content = preview
)

private const val PREVIEW_MAX_CHARS = 240

/**
 * URI scheme used to route markdown link clicks inside the answer body back to
 * the citation sheet. Must not collide with real web links.
 */
const val CITATION_URI_PREFIX = "labsage://citation/"

/**
 * Rewrites inline `[N]` citation markers into markdown links so the rendered
 * answer carries tappable inline citations:
 *
 *   模型输出 …结论[1]…  →  …结论[1](labsage://citation/1)…
 *
 * Conservative by design: only markers whose text EXACTLY matches a citation
 * ref are rewritten, markers already part of a markdown link
 * (`[1](https://…)`) are left alone, and refs containing anything outside
 * `[A-Za-z0-9_-]` are skipped (no URI-encoding pitfalls).
 */
fun inlineCitationLinks(content: String, citations: List<MessageReference>): String {
    val safeRefs = citations.asSequence()
        .map { it.ref }
        .filter { it.isNotBlank() && it.matches(Regex("[A-Za-z0-9_-]+")) }
        .distinct()
        .toList()
    if (safeRefs.isEmpty()) return content
    val alternation = safeRefs.joinToString("|") { Pattern.quote(it) }
    val marker = Regex("\\[($alternation)](?![(:])")
    return marker.replace(content) { match ->
        "[${match.groupValues[1]}]($CITATION_URI_PREFIX${match.groupValues[1]})"
    }
}

/**
 * Encodes/decodes the reference list stored in `messages.references_json`.
 *
 * Decode is deliberately forgiving: null/blank/malformed JSON yields an empty
 * list (an unreadable citation must never break rendering of the answer), and
 * items without a usable ref are dropped — the chips row is driven by the
 * registry itself, so a blank-ref item has nothing to display.
 */
object ReferencesCodec {
    private val gson = com.google.gson.Gson()

    fun encode(items: List<MessageReference>): String? =
        if (items.isEmpty()) null else gson.toJson(items.map { it.toPayload() })

    fun decode(json: String?): List<MessageReference> {
        if (json.isNullOrBlank()) return emptyList()
        val payloads = runCatching {
            gson.fromJson(json, Array<MessageReferencePayload>::class.java)
        }.getOrNull() ?: return emptyList()
        return payloads
            ?.map { it.toReference() }
            ?.filter { it.ref.isNotBlank() }
            .orEmpty()
    }
}
