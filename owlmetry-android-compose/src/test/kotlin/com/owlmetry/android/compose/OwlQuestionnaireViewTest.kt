package com.owlmetry.android.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.owlmetry.android.OwlQuestionnaire
import com.owlmetry.android.OwlQuestionnaireChoiceOption
import com.owlmetry.android.OwlQuestionnaireQuestion
import com.owlmetry.android.OwlQuestionnaireSchema
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose UI behavior of [OwlQuestionnaireView], mirroring the navigation +
 * gating surface of the Swift `OwlQuestionnaireFlowContainer`. Renders the view
 * directly (no live `Owl.configure`), so it exercises the client-side flow that
 * runs before any network call:
 *  - the consent prompt shows when `showsConsent = true` and accepting advances.
 *  - Next is disabled until a required question is answered, then enabled.
 *  - Back appears only after advancing past the first question.
 *  - the last question shows Submit (not Next).
 *  - overridden [OwlQuestionnaireStrings] render.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class OwlQuestionnaireViewTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun spec(): OwlQuestionnaire = OwlQuestionnaire(
        id = "q1",
        slug = "demo",
        name = "Demo",
        description = "Two quick questions",
        schema = OwlQuestionnaireSchema(
            version = 1,
            questions = listOf(
                OwlQuestionnaireQuestion.SingleChoice(
                    id = "color",
                    title = "Favorite color?",
                    subtitle = null,
                    required = true,
                    options = listOf(
                        OwlQuestionnaireChoiceOption("r", "Red"),
                        OwlQuestionnaireChoiceOption("b", "Blue"),
                    ),
                ),
                OwlQuestionnaireQuestion.Text(
                    id = "why",
                    title = "Why?",
                    subtitle = null,
                    required = false,
                    placeholder = "Optional",
                    multiline = true,
                ),
            ),
        ),
    )

    @Test
    fun `consent prompt shows and accepting advances to first question`() {
        composeRule.setContent {
            OwlQuestionnaireView(questionnaire = spec(), showsConsent = true)
        }
        composeRule.onNodeWithTag(QUESTIONNAIRE_CONSENT_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(QUESTIONNAIRE_CONSENT_ACCEPT_TAG).performClick()
        // First question is now visible.
        composeRule.onNodeWithText("Favorite color?").assertIsDisplayed()
    }

    @Test
    fun `next is disabled until the required question is answered`() {
        composeRule.setContent {
            OwlQuestionnaireView(questionnaire = spec(), showsConsent = false)
        }
        composeRule.onNodeWithTag(QUESTIONNAIRE_NEXT_TAG).assertIsNotEnabled()
        // Pick an option → Next enables.
        composeRule.onAllNodesWithTag(QUESTIONNAIRE_SINGLE_OPTION_TAG).onFirst().performClick()
        composeRule.onNodeWithTag(QUESTIONNAIRE_NEXT_TAG).assertIsEnabled()
    }

    @Test
    fun `back appears after advancing and last question shows submit`() {
        composeRule.setContent {
            OwlQuestionnaireView(questionnaire = spec(), showsConsent = false)
        }
        // No Back on the first page.
        composeRule.onNodeWithTag(QUESTIONNAIRE_BACK_TAG).assertDoesNotExist()

        composeRule.onAllNodesWithTag(QUESTIONNAIRE_SINGLE_OPTION_TAG).onFirst().performClick()
        composeRule.onNodeWithTag(QUESTIONNAIRE_NEXT_TAG).performClick()

        // Second (last) question: Back present, Submit (not Next) shown.
        composeRule.onNodeWithTag(QUESTIONNAIRE_BACK_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(QUESTIONNAIRE_SUBMIT_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(QUESTIONNAIRE_NEXT_TAG).assertDoesNotExist()
    }

    @Test
    fun `optional last question allows submit without an answer`() {
        composeRule.setContent {
            OwlQuestionnaireView(questionnaire = spec(), showsConsent = false)
        }
        composeRule.onAllNodesWithTag(QUESTIONNAIRE_SINGLE_OPTION_TAG).onFirst().performClick()
        composeRule.onNodeWithTag(QUESTIONNAIRE_NEXT_TAG).performClick()
        // The text question is optional → Submit is enabled immediately.
        composeRule.onNodeWithTag(QUESTIONNAIRE_SUBMIT_TAG).assertIsEnabled()
    }

    @Test
    fun `overridden strings render`() {
        val custom = OwlQuestionnaireStrings.DEFAULT.with(consentAccept = "Let's go!")
        composeRule.setContent {
            OwlQuestionnaireView(questionnaire = spec(), showsConsent = true, strings = custom)
        }
        composeRule.onNodeWithText("Let's go!").assertIsDisplayed()
    }

    @Test
    fun `text answer can be entered on the optional page`() {
        composeRule.setContent {
            OwlQuestionnaireView(questionnaire = spec(), showsConsent = false)
        }
        composeRule.onAllNodesWithTag(QUESTIONNAIRE_SINGLE_OPTION_TAG).onFirst().performClick()
        composeRule.onNodeWithTag(QUESTIONNAIRE_NEXT_TAG).performClick()
        composeRule.onNodeWithTag(QUESTIONNAIRE_TEXT_TAG).performTextInput("because blue")
        composeRule.onNodeWithTag(QUESTIONNAIRE_TEXT_TAG).assertIsDisplayed()
    }
}
