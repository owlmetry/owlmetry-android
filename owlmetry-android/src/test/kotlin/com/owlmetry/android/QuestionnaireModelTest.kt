package com.owlmetry.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Core questionnaire model + wire-format tests. Runs under Robolectric because
 * the decode/encode paths exercise real `org.json` (plain JVM unit tests stub it
 * to no-ops). Mirrors the Swift SDK's questionnaire model/codable coverage.
 */
@RunWith(RobolectricTestRunner::class)
class QuestionnaireModelTest {

    private fun fullSchemaJson(): String = """
        {
          "id": "q_1",
          "slug": "post-onboarding",
          "name": "Post Onboarding",
          "description": "Tell us how it went",
          "schema": {
            "version": 1,
            "questions": [
              { "type": "text", "id": "q_text", "title": "Thoughts?", "required": true, "placeholder": "Type here", "multiline": true },
              { "type": "single_choice", "id": "q_single", "title": "Pick one", "required": false,
                "options": [ { "id": "a", "label": "Apple" }, { "id": "b", "label": "Banana" } ] },
              { "type": "multi_choice", "id": "q_multi", "title": "Pick some", "required": false,
                "options": [ { "id": "x", "label": "X" }, { "id": "y", "label": "Y" } ] },
              { "type": "rating", "id": "q_rating", "title": "Rate", "required": true, "scale": 5 },
              { "type": "nps", "id": "q_nps", "title": "Recommend?", "required": false }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun decodesFullQuestionnaire() {
        val q = OwlQuestionnaire.fromJson(JSONObject(fullSchemaJson()))
        assertEquals("q_1", q.id)
        assertEquals("post-onboarding", q.slug)
        assertEquals("Post Onboarding", q.name)
        assertEquals("Tell us how it went", q.description)
        assertEquals(1, q.schema.version)
        assertEquals(5, q.schema.questions.size)

        val text = q.schema.questions[0] as OwlQuestionnaireQuestion.Text
        assertEquals("q_text", text.id)
        assertTrue(text.required)
        assertTrue(text.multiline)
        assertEquals("Type here", text.placeholder)

        val single = q.schema.questions[1] as OwlQuestionnaireQuestion.SingleChoice
        assertEquals(2, single.options.size)
        assertEquals("Apple", single.options[0].label)

        val rating = q.schema.questions[3] as OwlQuestionnaireQuestion.Rating
        assertEquals(5, rating.scale)

        assertTrue(q.schema.questions[4] is OwlQuestionnaireQuestion.Nps)
    }

    @Test
    fun nullDescriptionDecodesAsNull() {
        val json = JSONObject(
            """{ "id": "i", "slug": "s", "name": "n", "schema": { "version": 1, "questions": [] } }""",
        )
        val q = OwlQuestionnaire.fromJson(json)
        assertNull(q.description)
    }

    @Test(expected = OwlQuestionnaireParseException::class)
    fun unknownQuestionTypeThrows() {
        val json = JSONObject(
            """{ "id": "i", "slug": "s", "name": "n",
                  "schema": { "version": 1, "questions": [ { "type": "slider", "id": "x", "title": "t", "required": false } ] } }""",
        )
        OwlQuestionnaire.fromJson(json)
    }

    @Test
    fun encodesAnswersToWireShape() {
        val answers = linkedMapOf<String, OwlQuestionnaireAnswerValue>(
            "q_text" to OwlQuestionnaireAnswerValue.TextValue("hello"),
            "q_single" to OwlQuestionnaireAnswerValue.ChoiceValue("a"),
            "q_multi" to OwlQuestionnaireAnswerValue.ChoicesValue(listOf("x", "y")),
            "q_rating" to OwlQuestionnaireAnswerValue.RatingValue(4),
            "q_nps" to OwlQuestionnaireAnswerValue.NpsValue(9),
        )
        val obj = encodeAnswers(answers)
        assertEquals("hello", obj.getString("q_text"))
        assertEquals("a", obj.getString("q_single"))
        assertEquals(2, obj.getJSONArray("q_multi").length())
        assertEquals("x", obj.getJSONArray("q_multi").getString(0))
        assertEquals(4, obj.getInt("q_rating"))
        assertEquals(9, obj.getInt("q_nps"))
    }

    @Test
    fun hydrateDraftAnswersProjectsByQuestionType() {
        val q = OwlQuestionnaire.fromJson(JSONObject(fullSchemaJson()))
        val raw = JSONObject(
            """{ "q_text": "draft text", "q_single": "b",
                  "q_multi": ["x"], "q_rating": 3, "q_nps": 7,
                  "q_unknown": "ignored" }""",
        )
        val hydrated = hydrateDraftAnswers(raw, q.schema)
        assertEquals(OwlQuestionnaireAnswerValue.TextValue("draft text"), hydrated["q_text"])
        assertEquals(OwlQuestionnaireAnswerValue.ChoiceValue("b"), hydrated["q_single"])
        assertEquals(OwlQuestionnaireAnswerValue.ChoicesValue(listOf("x")), hydrated["q_multi"])
        assertEquals(OwlQuestionnaireAnswerValue.RatingValue(3), hydrated["q_rating"])
        assertEquals(OwlQuestionnaireAnswerValue.NpsValue(7), hydrated["q_nps"])
        // Unknown keys (not in schema) are dropped.
        assertFalse(hydrated.containsKey("q_unknown"))
    }

    @Test
    fun hydrateDraftAnswersSkipsShapeMismatches() {
        val q = OwlQuestionnaire.fromJson(JSONObject(fullSchemaJson()))
        // q_rating expects an int but the draft has a string; drop it.
        val raw = JSONObject("""{ "q_rating": "not-an-int", "q_text": "ok" }""")
        val hydrated = hydrateDraftAnswers(raw, q.schema)
        assertFalse(hydrated.containsKey("q_rating"))
        assertEquals(OwlQuestionnaireAnswerValue.TextValue("ok"), hydrated["q_text"])
    }
}
