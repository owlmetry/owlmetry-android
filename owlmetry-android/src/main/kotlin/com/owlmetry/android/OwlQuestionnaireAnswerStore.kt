package com.owlmetry.android

/**
 * Pure value collector for the questionnaire flow's per-question answers. Lives
 * in the core module (not the Compose layer) so the validation + wire-encoding
 * logic is unit-testable without a Compose runtime — the Kotlin analog of
 * Swift's `OwlQuestionnaireAnswerStore` struct, which Swift keeps separate from
 * the SwiftUI container for the same reason.
 *
 * Public so the Compose flow container (a separate module) can own one as its
 * answer state. The maps are exposed `var` so the Compose bindings can write
 * straight through; recomposition is driven by the container holding the store
 * in `mutableStateOf` and replacing it via [copyWith*] on every edit (data-class
 * copy = new identity), mirroring how the Swift `@State` store re-publishes.
 */
public data class OwlQuestionnaireAnswerStore(
    public val text: Map<String, String> = emptyMap(),
    public val single: Map<String, String> = emptyMap(),
    public val multi: Map<String, Set<String>> = emptyMap(),
    public val rating: Map<String, Int> = emptyMap(),
    public val nps: Map<String, Int> = emptyMap(),
) {
    /**
     * Return a copy with [text] for [questionId] set (or removed when null).
     * The immutable-update analog of Swift's `answers.text[id] = value`.
     */
    public fun withText(questionId: String, value: String?): OwlQuestionnaireAnswerStore =
        copy(text = text.mutate { if (value == null) remove(questionId) else put(questionId, value) })

    /** Copy with the single-choice answer for [questionId] set/removed. */
    public fun withSingle(questionId: String, value: String?): OwlQuestionnaireAnswerStore =
        copy(single = single.mutate { if (value == null) remove(questionId) else put(questionId, value) })

    /** Copy with the multi-choice answer set for [questionId] replaced. */
    public fun withMulti(questionId: String, value: Set<String>): OwlQuestionnaireAnswerStore =
        copy(multi = multi.mutate { put(questionId, value) })

    /** Copy that toggles [optionId] in the multi-choice set for [questionId]. */
    public fun togglingMulti(questionId: String, optionId: String): OwlQuestionnaireAnswerStore {
        val current = multi[questionId] ?: emptySet()
        val next = if (optionId in current) current - optionId else current + optionId
        return withMulti(questionId, next)
    }

    /** Copy with the rating answer for [questionId] set/removed. */
    public fun withRating(questionId: String, value: Int?): OwlQuestionnaireAnswerStore =
        copy(rating = rating.mutate { if (value == null) remove(questionId) else put(questionId, value) })

    /** Copy with the NPS answer for [questionId] set/removed. */
    public fun withNps(questionId: String, value: Int?): OwlQuestionnaireAnswerStore =
        copy(nps = nps.mutate { if (value == null) remove(questionId) else put(questionId, value) })

    /**
     * Hydrate a store from server-side draft state (the `in_progress` payload of
     * the eligibility envelope). Unknown question shapes are silently skipped —
     * pre-fill is best-effort and the server prunes stale keys when the user
     * completes. Mirrors Swift's `prefill(from:)`.
     */
    public fun prefilled(answers: Map<String, OwlQuestionnaireAnswerValue>): OwlQuestionnaireAnswerStore {
        val t = LinkedHashMap(text)
        val s = LinkedHashMap(single)
        val m = LinkedHashMap(multi)
        val r = LinkedHashMap(rating)
        val n = LinkedHashMap(nps)
        for ((key, value) in answers) {
            when (value) {
                is OwlQuestionnaireAnswerValue.TextValue -> t[key] = value.value
                is OwlQuestionnaireAnswerValue.ChoiceValue -> s[key] = value.value
                is OwlQuestionnaireAnswerValue.ChoicesValue -> m[key] = value.value.toSet()
                is OwlQuestionnaireAnswerValue.RatingValue -> r[key] = value.value
                is OwlQuestionnaireAnswerValue.NpsValue -> n[key] = value.value
            }
        }
        return OwlQuestionnaireAnswerStore(text = t, single = s, multi = m, rating = r, nps = n)
    }

    /**
     * Index of the first question whose id is not answered yet. Returns
     * `questions.size - 1` when every question is answered (so the flow lands on
     * the last page with Submit live). Returns 0 for an empty schema. Mirrors
     * Swift's `firstUnansweredIndex(in:)`.
     */
    public fun firstUnansweredIndex(schema: OwlQuestionnaireSchema): Int {
        val questions = schema.questions
        for ((i, q) in questions.withIndex()) {
            if (!isAnswered(q)) return i
        }
        return maxOf(0, questions.size - 1)
    }

    /** Whether [question] has a non-empty answer. Mirrors Swift's `isAnswered(_:)`. */
    public fun isAnswered(question: OwlQuestionnaireQuestion): Boolean = when (question) {
        is OwlQuestionnaireQuestion.Text -> (text[question.id] ?: "").trim().isNotEmpty()
        is OwlQuestionnaireQuestion.SingleChoice -> single[question.id] != null
        is OwlQuestionnaireQuestion.MultiChoice -> !(multi[question.id]?.isEmpty() ?: true)
        is OwlQuestionnaireQuestion.Rating -> rating[question.id] != null
        is OwlQuestionnaireQuestion.Nps -> nps[question.id] != null
    }

    /** Whether every `required` question is answered. Mirrors Swift's `hasAllRequired`. */
    public fun hasAllRequired(schema: OwlQuestionnaireSchema): Boolean =
        schema.questions.filter { it.required }.all { isAnswered(it) }

    /**
     * Collect the strongly-typed answer map to POST. Trims text, drops empty
     * answers, and sorts multi-choice ids for a stable wire shape. Mirrors
     * Swift's `collected(_:)`.
     */
    public fun collected(schema: OwlQuestionnaireSchema): Map<String, OwlQuestionnaireAnswerValue> {
        val out = LinkedHashMap<String, OwlQuestionnaireAnswerValue>()
        for (q in schema.questions) {
            when (q) {
                is OwlQuestionnaireQuestion.Text -> {
                    val raw = (text[q.id] ?: "").trim()
                    if (raw.isNotEmpty()) out[q.id] = OwlQuestionnaireAnswerValue.TextValue(raw)
                }
                is OwlQuestionnaireQuestion.SingleChoice ->
                    single[q.id]?.let { out[q.id] = OwlQuestionnaireAnswerValue.ChoiceValue(it) }
                is OwlQuestionnaireQuestion.MultiChoice -> {
                    val set = multi[q.id]
                    if (!set.isNullOrEmpty()) {
                        out[q.id] = OwlQuestionnaireAnswerValue.ChoicesValue(set.sorted())
                    }
                }
                is OwlQuestionnaireQuestion.Rating ->
                    rating[q.id]?.let { out[q.id] = OwlQuestionnaireAnswerValue.RatingValue(it) }
                is OwlQuestionnaireQuestion.Nps ->
                    nps[q.id]?.let { out[q.id] = OwlQuestionnaireAnswerValue.NpsValue(it) }
            }
        }
        return out
    }
}

/** Apply a mutation to a copy of this map, returning the new immutable map. */
private inline fun <K, V> Map<K, V>.mutate(block: MutableMap<K, V>.() -> Unit): Map<K, V> {
    val copy = LinkedHashMap(this)
    copy.block()
    return copy
}
