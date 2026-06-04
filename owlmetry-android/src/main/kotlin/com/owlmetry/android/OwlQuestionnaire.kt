package com.owlmetry.android

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The questionnaire model graph — the Android analog of the Swift SDK's
 * `OwlQuestionnaire.swift`. Public so consumers can render a spec manually via
 * the Compose `OwlQuestionnaireView`.
 *
 * Swift backs the wire format with `Codable`; under the SDK's framework-only
 * dependency rule (no kotlinx-serialization / Moshi) the analog is hand-written
 * [JSONObject] decode/encode helpers — same shape, same snake_case keys, nulls
 * omitted. The decode path mirrors Swift's polymorphic `OwlQuestionnaireQuestion`
 * decoder that dispatches on the `type` discriminator.
 */
public data class OwlQuestionnaire(
    public val id: String,
    public val slug: String,
    public val name: String,
    public val description: String?,
    public val schema: OwlQuestionnaireSchema,
) {
    internal companion object {
        /**
         * Decode a questionnaire spec from the server's JSON. Mirrors Swift's
         * `OwlQuestionnaire: Codable`. Throws [OwlQuestionnaireParseException] on
         * a structurally invalid payload (missing required keys / unknown
         * question type) so the transport can surface a `transportFailure`.
         */
        fun fromJson(json: JSONObject): OwlQuestionnaire {
            val id = json.optString("id")
            val slug = json.optString("slug")
            val name = json.optString("name")
            val description = json.optStringOrNull("description")
            val schemaJson = json.optJSONObject("schema")
                ?: throw OwlQuestionnaireParseException("questionnaire missing schema")
            return OwlQuestionnaire(
                id = id,
                slug = slug,
                name = name,
                description = description,
                schema = OwlQuestionnaireSchema.fromJson(schemaJson),
            )
        }
    }
}

public data class OwlQuestionnaireSchema(
    public val version: Int,
    public val questions: List<OwlQuestionnaireQuestion>,
) {
    internal companion object {
        fun fromJson(json: JSONObject): OwlQuestionnaireSchema {
            val version = json.optInt("version", 1)
            val arr = json.optJSONArray("questions") ?: JSONArray()
            val questions = ArrayList<OwlQuestionnaireQuestion>(arr.length())
            for (i in 0 until arr.length()) {
                val q = arr.optJSONObject(i) ?: continue
                questions.add(OwlQuestionnaireQuestion.fromJson(q))
            }
            return OwlQuestionnaireSchema(version = version, questions = questions)
        }
    }
}

public data class OwlQuestionnaireChoiceOption(
    public val id: String,
    public val label: String,
) {
    internal companion object {
        fun fromJson(json: JSONObject): OwlQuestionnaireChoiceOption =
            OwlQuestionnaireChoiceOption(
                id = json.optString("id"),
                label = json.optString("label"),
            )
    }
}

/**
 * A single question in a questionnaire schema. The Kotlin analog of Swift's
 * polymorphic `OwlQuestionnaireQuestion` enum — a sealed class so the `when`
 * over its subtypes is exhaustive at the use sites (the page renderers, the
 * answer store, the draft hydrator). [id], [title], [subtitle], and [required]
 * are exposed on the base type so callers can read them without unwrapping,
 * matching the Swift enum's computed properties.
 */
public sealed class OwlQuestionnaireQuestion {
    public abstract val id: String
    public abstract val title: String
    public abstract val subtitle: String?
    public abstract val required: Boolean

    public data class Text(
        public override val id: String,
        public override val title: String,
        public override val subtitle: String?,
        public override val required: Boolean,
        public val placeholder: String?,
        public val multiline: Boolean,
    ) : OwlQuestionnaireQuestion()

    public data class SingleChoice(
        public override val id: String,
        public override val title: String,
        public override val subtitle: String?,
        public override val required: Boolean,
        public val options: List<OwlQuestionnaireChoiceOption>,
    ) : OwlQuestionnaireQuestion()

    public data class MultiChoice(
        public override val id: String,
        public override val title: String,
        public override val subtitle: String?,
        public override val required: Boolean,
        public val options: List<OwlQuestionnaireChoiceOption>,
    ) : OwlQuestionnaireQuestion()

    public data class Rating(
        public override val id: String,
        public override val title: String,
        public override val subtitle: String?,
        public override val required: Boolean,
        /** V1: always 5. */
        public val scale: Int,
    ) : OwlQuestionnaireQuestion()

    public data class Nps(
        public override val id: String,
        public override val title: String,
        public override val subtitle: String?,
        public override val required: Boolean,
    ) : OwlQuestionnaireQuestion()

    internal companion object {
        /**
         * Decode a question by dispatching on the `type` discriminator —
         * mirrors Swift's polymorphic `OwlQuestionnaireQuestion.init(from:)`.
         * The wire types are snake_case (`single_choice`, `multi_choice`).
         */
        fun fromJson(json: JSONObject): OwlQuestionnaireQuestion {
            val type = json.optString("type")
            val id = json.optString("id")
            val title = json.optString("title")
            val subtitle = json.optStringOrNull("subtitle")
            val required = json.optBoolean("required", false)
            return when (type) {
                "text" -> Text(
                    id = id,
                    title = title,
                    subtitle = subtitle,
                    required = required,
                    placeholder = json.optStringOrNull("placeholder"),
                    multiline = json.optBoolean("multiline", false),
                )
                "single_choice" -> SingleChoice(
                    id = id,
                    title = title,
                    subtitle = subtitle,
                    required = required,
                    options = decodeOptions(json),
                )
                "multi_choice" -> MultiChoice(
                    id = id,
                    title = title,
                    subtitle = subtitle,
                    required = required,
                    options = decodeOptions(json),
                )
                "rating" -> Rating(
                    id = id,
                    title = title,
                    subtitle = subtitle,
                    required = required,
                    // Swift requires `scale`; default to the V1 constant rather
                    // than throwing so a server that ever omits it still renders.
                    scale = json.optInt("scale", 5),
                )
                "nps" -> Nps(
                    id = id,
                    title = title,
                    subtitle = subtitle,
                    required = required,
                )
                else -> throw OwlQuestionnaireParseException("unknown question type: $type")
            }
        }

        private fun decodeOptions(json: JSONObject): List<OwlQuestionnaireChoiceOption> {
            val arr = json.optJSONArray("options") ?: JSONArray()
            val out = ArrayList<OwlQuestionnaireChoiceOption>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(OwlQuestionnaireChoiceOption.fromJson(o))
            }
            return out
        }
    }
}

/**
 * Heterogeneous answer value. The wire encodes as the underlying type directly
 * (string / array-of-string / int), not as a tagged union — the server
 * validates against the schema. The Kotlin analog of Swift's
 * `OwlQuestionnaireAnswerValue` enum.
 */
public sealed class OwlQuestionnaireAnswerValue {
    public data class TextValue(public val value: String) : OwlQuestionnaireAnswerValue()
    /** A single option id. */
    public data class ChoiceValue(public val value: String) : OwlQuestionnaireAnswerValue()
    /** A set of option ids. */
    public data class ChoicesValue(public val value: List<String>) : OwlQuestionnaireAnswerValue()
    /** 1..scale. */
    public data class RatingValue(public val value: Int) : OwlQuestionnaireAnswerValue()
    /** 0..10. */
    public data class NpsValue(public val value: Int) : OwlQuestionnaireAnswerValue()
}

/**
 * Receipt returned by `POST /v1/questionnaires/:slug/responses`. [wasSubmitted]
 * is `true` only on the call that flipped the server-side `submitted_at` from
 * null to non-null — used by the flow container to know whether to transition
 * to the success phase. Subsequent draft-saves return `wasSubmitted = false`.
 * The public analog of Swift's `OwlQuestionnaireReceipt`.
 */
public data class OwlQuestionnaireReceipt(
    public val id: String,
    public val createdAt: Date,
    public val wasSubmitted: Boolean,
)

/**
 * An in-progress draft surfaced by `GET /v1/questionnaires/:slug` when the
 * caller already has an unsubmitted response. The flow container reads this to
 * pre-fill its answer store and skip to the first unanswered question. The
 * public analog of Swift's `OwlQuestionnaireDraft`.
 */
public data class OwlQuestionnaireDraft(
    public val responseId: String,
    public val answers: Map<String, OwlQuestionnaireAnswerValue>,
)

/**
 * Result of [Owl.fetchQuestionnaire]. When [questionnaire] is non-null the
 * questionnaire exists and is eligible to present; [inProgress] carries the
 * existing draft (if any) so the flow container can resume. When [questionnaire]
 * is null, the questionnaire isn't eligible — typically `alreadyResponded`,
 * `globallyDismissed`, or `inactive` ([ineligibleReason]). The public analog of
 * Swift's `OwlQuestionnaireFetchResult`.
 */
public data class OwlQuestionnaireFetchResult(
    public val questionnaire: OwlQuestionnaire?,
    public val inProgress: OwlQuestionnaireDraft? = null,
    public val ineligibleReason: OwlQuestionnaireIneligibleReason? = null,
)

/**
 * Reason a questionnaire is not eligible to present right now. Mirrors the
 * server's eligibility envelope (and Swift's `OwlQuestionnaireIneligibleReason`).
 * [wire] is the raw server reason string.
 */
public enum class OwlQuestionnaireIneligibleReason(public val wire: String) {
    ALREADY_RESPONDED("already_responded"),
    GLOBALLY_DISMISSED("globally_dismissed"),
    INACTIVE("inactive"),
    ;

    internal companion object {
        /** Map a server reason string onto a case, or null if unrecognized. */
        fun fromWire(value: String?): OwlQuestionnaireIneligibleReason? {
            if (value == null) return null
            return entries.firstOrNull { it.wire == value }
        }
    }
}

/**
 * Errors surfaced by [Owl.fetchQuestionnaire] / [Owl.saveQuestionnaireResponse] /
 * [Owl.dismissQuestionnaires]. Non-eligible fetches return a result with a null
 * questionnaire instead of throwing. The public analog of Swift's
 * `OwlQuestionnaireError` enum; the [message] mirrors Swift's `errorDescription`
 * so a thrown error reads identically.
 */
public sealed class OwlQuestionnaireError(message: String) : Exception(message) {
    /** [Owl.configure] has not been called yet. */
    public object NotConfigured : OwlQuestionnaireError(
        "Owlmetry is not configured. Call Owl.configure(...) first.",
    )

    /** The slug doesn't exist on the server (404). */
    public object SlugNotFound : OwlQuestionnaireError("Questionnaire slug not found.")

    /** The server rejected the answers (400). [detail] is the body verbatim. */
    public data class InvalidAnswers(public val detail: String) :
        OwlQuestionnaireError("Invalid answers: $detail")

    /** The server responded with a non-2xx status. [body] is returned verbatim. */
    public data class ServerError(
        public val statusCode: Int,
        public val body: String?,
    ) : OwlQuestionnaireError(
        if (!body.isNullOrEmpty()) "Server returned $statusCode: $body" else "Server returned $statusCode",
    )

    /** A transport-level failure (network unreachable, invalid response, decode error). */
    public data class TransportFailure(public val detail: String) :
        OwlQuestionnaireError(detail)
}

/** Thrown internally when a questionnaire/schema/question JSON payload is malformed. */
internal class OwlQuestionnaireParseException(message: String) : Exception(message)

// MARK: - Wire encoding helpers (internal)

/**
 * Encode a `Map<String, OwlQuestionnaireAnswerValue>` into the wire shape
 * (`{ questionId: string | string[] | int }`). Mirrors Swift's
 * `OwlQuestionnaireAnswersWire` encoder.
 */
internal fun encodeAnswers(answers: Map<String, OwlQuestionnaireAnswerValue>): JSONObject {
    val obj = JSONObject()
    for ((key, value) in answers) {
        when (value) {
            is OwlQuestionnaireAnswerValue.TextValue -> obj.put(key, value.value)
            is OwlQuestionnaireAnswerValue.ChoiceValue -> obj.put(key, value.value)
            is OwlQuestionnaireAnswerValue.ChoicesValue -> obj.put(key, JSONArray(value.value))
            is OwlQuestionnaireAnswerValue.RatingValue -> obj.put(key, value.value)
            is OwlQuestionnaireAnswerValue.NpsValue -> obj.put(key, value.value)
        }
    }
    return obj
}

/**
 * Project the server's draft `answers` JSON (`{ questionId: string | string[] |
 * int }`) onto the strongly-typed [OwlQuestionnaireAnswerValue] map by
 * dispatching on each question's type. Answers whose question id is no longer in
 * the schema (mid-draft schema edits) or whose shape doesn't match the question
 * type are skipped — pre-fill is best-effort, and the server prunes unknown keys
 * on final submit. Mirrors Swift's `hydrateDraftAnswers` + `AnyAnswerJSON`.
 */
internal fun hydrateDraftAnswers(
    raw: JSONObject,
    schema: OwlQuestionnaireSchema,
): Map<String, OwlQuestionnaireAnswerValue> {
    val out = LinkedHashMap<String, OwlQuestionnaireAnswerValue>()
    for (question in schema.questions) {
        if (!raw.has(question.id) || raw.isNull(question.id)) continue
        val value = raw.opt(question.id)
        when (question) {
            is OwlQuestionnaireQuestion.Text ->
                (value as? String)?.let { out[question.id] = OwlQuestionnaireAnswerValue.TextValue(it) }
            is OwlQuestionnaireQuestion.SingleChoice ->
                (value as? String)?.let { out[question.id] = OwlQuestionnaireAnswerValue.ChoiceValue(it) }
            is OwlQuestionnaireQuestion.MultiChoice ->
                (value as? JSONArray)?.let { arr ->
                    val list = ArrayList<String>(arr.length())
                    for (i in 0 until arr.length()) {
                        (arr.opt(i) as? String)?.let(list::add)
                    }
                    out[question.id] = OwlQuestionnaireAnswerValue.ChoicesValue(list)
                }
            is OwlQuestionnaireQuestion.Rating ->
                intFromJson(value)?.let { out[question.id] = OwlQuestionnaireAnswerValue.RatingValue(it) }
            is OwlQuestionnaireQuestion.Nps ->
                intFromJson(value)?.let { out[question.id] = OwlQuestionnaireAnswerValue.NpsValue(it) }
        }
    }
    return out
}

/** Coerce a JSON value to Int, accepting Integer / Long but not String. Best-effort. */
private fun intFromJson(value: Any?): Int? = when (value) {
    is Int -> value
    is Long -> value.toInt()
    is Number -> value.toInt()
    else -> null
}

/** `optString` that returns null (not "") for a missing or JSON-null key. */
internal fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val s = optString(key, "")
    return s.ifEmpty { null }
}

/**
 * Shared ISO-8601 parser for questionnaire timestamps (`created_at`,
 * `dismissed_at`) — same shape as [OwlFeedbackReceipt]'s. Tolerates both
 * fractional-second and whole-second forms; degrades to "now" on an unparseable
 * value, matching Swift's `?? Date()`. Confined to a [ThreadLocal] because
 * `SimpleDateFormat` is not thread-safe.
 */
internal object QuestionnaireDates {
    private val isoParser: ThreadLocal<SimpleDateFormat> =
        object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat =
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
        }

    private val isoParserNoMillis: ThreadLocal<SimpleDateFormat> =
        object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat =
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
        }

    fun parseOrNow(value: String?): Date {
        if (value.isNullOrEmpty()) return Date()
        return runCatching { isoParser.get()!!.parse(value) }.getOrNull()
            ?: runCatching { isoParserNoMillis.get()!!.parse(value) }.getOrNull()
            ?: Date()
    }
}
