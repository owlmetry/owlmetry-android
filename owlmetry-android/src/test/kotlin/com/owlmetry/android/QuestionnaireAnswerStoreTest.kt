package com.owlmetry.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure logic tests for [OwlQuestionnaireAnswerStore] — no Android framework, so
 * a plain JVM unit test. Mirrors the Swift `OwlQuestionnaireAnswerStore` tests.
 */
class QuestionnaireAnswerStoreTest {

    private fun schema(): OwlQuestionnaireSchema = OwlQuestionnaireSchema(
        version = 1,
        questions = listOf(
            OwlQuestionnaireQuestion.Text("t", "Text", null, required = true, placeholder = null, multiline = false),
            OwlQuestionnaireQuestion.SingleChoice(
                "s", "Single", null, required = false,
                options = listOf(OwlQuestionnaireChoiceOption("a", "A"), OwlQuestionnaireChoiceOption("b", "B")),
            ),
            OwlQuestionnaireQuestion.MultiChoice(
                "m", "Multi", null, required = false,
                options = listOf(OwlQuestionnaireChoiceOption("x", "X"), OwlQuestionnaireChoiceOption("y", "Y")),
            ),
            OwlQuestionnaireQuestion.Rating("r", "Rating", null, required = true, scale = 5),
            OwlQuestionnaireQuestion.Nps("n", "Nps", null, required = false),
        ),
    )

    @Test
    fun isAnsweredTracksEachQuestionType() {
        val s = schema()
        var store = OwlQuestionnaireAnswerStore()
        assertFalse(store.isAnswered(s.questions[0]))
        store = store.withText("t", "   ")
        // whitespace-only text counts as unanswered
        assertFalse(store.isAnswered(s.questions[0]))
        store = store.withText("t", "hi")
        assertTrue(store.isAnswered(s.questions[0]))

        store = store.withSingle("s", "a")
        assertTrue(store.isAnswered(s.questions[1]))

        store = store.togglingMulti("m", "x")
        assertTrue(store.isAnswered(s.questions[2]))
        store = store.togglingMulti("m", "x")
        assertFalse(store.isAnswered(s.questions[2]))

        store = store.withRating("r", 4)
        assertTrue(store.isAnswered(s.questions[3]))
        store = store.withNps("n", 8)
        assertTrue(store.isAnswered(s.questions[4]))
    }

    @Test
    fun hasAllRequiredGatesOnRequiredOnly() {
        val s = schema()
        var store = OwlQuestionnaireAnswerStore()
        assertFalse(store.hasAllRequired(s)) // text + rating required, both missing
        store = store.withText("t", "hi")
        assertFalse(store.hasAllRequired(s)) // rating still missing
        store = store.withRating("r", 5)
        assertTrue(store.hasAllRequired(s)) // optionals don't block
    }

    @Test
    fun firstUnansweredIndexWalksSchema() {
        val s = schema()
        var store = OwlQuestionnaireAnswerStore()
        assertEquals(0, store.firstUnansweredIndex(s))
        store = store.withText("t", "hi")
        // q[1] single is optional but unanswered → index 1
        assertEquals(1, store.firstUnansweredIndex(s))
        store = store.withSingle("s", "a").togglingMulti("m", "x").withRating("r", 3).withNps("n", 7)
        // everything answered → lands on the last index
        assertEquals(s.questions.size - 1, store.firstUnansweredIndex(s))
    }

    @Test
    fun collectedTrimsAndSortsAndDropsEmpty() {
        val s = schema()
        val store = OwlQuestionnaireAnswerStore()
            .withText("t", "  spaced  ")
            .withSingle("s", "a")
            .togglingMulti("m", "y")
            .togglingMulti("m", "x")
            .withRating("r", 4)
        // n (nps) left empty → omitted
        val collected = store.collected(s)
        assertEquals(OwlQuestionnaireAnswerValue.TextValue("spaced"), collected["t"])
        assertEquals(OwlQuestionnaireAnswerValue.ChoiceValue("a"), collected["s"])
        // multi sorted ascending
        assertEquals(OwlQuestionnaireAnswerValue.ChoicesValue(listOf("x", "y")), collected["m"])
        assertEquals(OwlQuestionnaireAnswerValue.RatingValue(4), collected["r"])
        assertFalse(collected.containsKey("n"))
    }

    @Test
    fun prefilledHydratesFromDraft() {
        val store = OwlQuestionnaireAnswerStore().prefilled(
            mapOf(
                "t" to OwlQuestionnaireAnswerValue.TextValue("draft"),
                "m" to OwlQuestionnaireAnswerValue.ChoicesValue(listOf("x", "y")),
                "r" to OwlQuestionnaireAnswerValue.RatingValue(2),
            ),
        )
        assertEquals("draft", store.text["t"])
        assertEquals(setOf("x", "y"), store.multi["m"])
        assertEquals(2, store.rating["r"])
    }
}
