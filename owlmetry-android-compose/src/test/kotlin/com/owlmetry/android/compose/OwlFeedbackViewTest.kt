package com.owlmetry.android.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose UI behavior of [OwlFeedbackView], mirroring the validation surface of
 * the Swift `OwlFeedbackView`. Driven by the Compose test harness under
 * Robolectric (no live `Owl.configure`), so it exercises the client-side
 * validation gate that runs *before* any `Owl.sendFeedback` network call:
 *  - the Submit button is disabled until a non-blank message is typed.
 *  - exactly one of name/email filled → the incomplete-contact error dialog.
 *  - a malformed email → the invalid-email error dialog.
 *  - both contact fields blank → the "no contact details" confirm dialog.
 *  - [OwlFeedbackStrings] overrides render (custom header text).
 *
 * Also asserts [isValidEmail] matches the Swift regex semantics.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class OwlFeedbackViewTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `submit is disabled until a non-blank message is typed`() {
        composeRule.setContent {
            OwlFeedbackView(showsContactFields = false)
        }

        composeRule.onNodeWithTag(FEEDBACK_SUBMIT_TAG).assertIsNotEnabled()

        composeRule.onNodeWithTag(FEEDBACK_MESSAGE_TAG).performTextInput("This app rocks")
        composeRule.onNodeWithTag(FEEDBACK_SUBMIT_TAG).assertIsEnabled()
    }

    @Test
    fun `whitespace-only message keeps submit disabled`() {
        composeRule.setContent {
            OwlFeedbackView(showsContactFields = false)
        }
        composeRule.onNodeWithTag(FEEDBACK_MESSAGE_TAG).performTextInput("    ")
        composeRule.onNodeWithTag(FEEDBACK_SUBMIT_TAG).assertIsNotEnabled()
    }

    @Test
    fun `overridden strings render`() {
        val custom = OwlFeedbackStrings.DEFAULT.with(header = "How can we help you today?")
        composeRule.setContent {
            OwlFeedbackView(showsContactFields = false, strings = custom)
        }
        composeRule.onNodeWithText("How can we help you today?").assertIsDisplayed()
    }

    @Test
    fun `only-name contact shows the incomplete-contact error`() {
        val strings = OwlFeedbackStrings.DEFAULT
        composeRule.setContent {
            OwlFeedbackView(showsContactFields = true, strings = strings)
        }
        composeRule.onNodeWithTag(FEEDBACK_MESSAGE_TAG).performTextInput("Hi")
        composeRule.onNodeWithTag(FEEDBACK_NAME_TAG).performTextInput("Ada")
        composeRule.onNodeWithTag(FEEDBACK_SUBMIT_TAG).performClick()

        composeRule.onNodeWithTag(FEEDBACK_ERROR_DIALOG_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(strings.errorIncompleteContact).assertIsDisplayed()
    }

    @Test
    fun `malformed email shows the invalid-email error`() {
        val strings = OwlFeedbackStrings.DEFAULT
        composeRule.setContent {
            OwlFeedbackView(showsContactFields = true, strings = strings)
        }
        composeRule.onNodeWithTag(FEEDBACK_MESSAGE_TAG).performTextInput("Hi")
        composeRule.onNodeWithTag(FEEDBACK_NAME_TAG).performTextInput("Ada")
        composeRule.onNodeWithTag(FEEDBACK_EMAIL_TAG).performTextInput("not-an-email")
        composeRule.onNodeWithTag(FEEDBACK_SUBMIT_TAG).performClick()

        composeRule.onNodeWithTag(FEEDBACK_ERROR_DIALOG_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(strings.errorInvalidEmail).assertIsDisplayed()
    }

    @Test
    fun `both contact fields blank shows the no-contact confirm dialog`() {
        val strings = OwlFeedbackStrings.DEFAULT
        composeRule.setContent {
            OwlFeedbackView(showsContactFields = true, strings = strings)
        }
        composeRule.onNodeWithTag(FEEDBACK_MESSAGE_TAG).performTextInput("Anonymous note")
        composeRule.onNodeWithTag(FEEDBACK_SUBMIT_TAG).performClick()

        composeRule.onNodeWithTag(FEEDBACK_NO_CONTACT_DIALOG_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(strings.noContactSubmitAnyway).assertIsDisplayed()
        composeRule.onNodeWithText(strings.noContactAddDetails).assertIsDisplayed()
    }

    @Test
    fun `cancel action is hidden when no onCancel is provided`() {
        val strings = OwlFeedbackStrings.DEFAULT
        composeRule.setContent {
            OwlFeedbackView(showsContactFields = false, strings = strings, onCancel = null)
        }
        composeRule.onNodeWithText(strings.cancelButton).assertDoesNotExist()
    }

    @Test
    fun `isValidEmail matches the Swift regex semantics`() {
        assertTrue(isValidEmail("ada@example.com"))
        assertTrue(isValidEmail("a.b+c@sub.domain.io"))
        assertFalse(isValidEmail("no-at-sign"))
        assertFalse(isValidEmail("two@@example.com"))
        assertFalse(isValidEmail("space in@example.com"))
        assertFalse(isValidEmail("missing@domain"))
        assertFalse(isValidEmail("@example.com"))
        assertFalse(isValidEmail(""))
    }
}
