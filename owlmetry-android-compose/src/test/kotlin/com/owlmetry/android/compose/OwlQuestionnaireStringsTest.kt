package com.owlmetry.android.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pure-JVM parity tests for [OwlQuestionnaireStrings] (the Compose analog of the
 * Swift `OwlQuestionnaireStrings`). No Compose harness / Robolectric — these only
 * exercise the value type:
 *
 *  - the English defaults match the Swift catalog's `defaultValue` strings
 *    byte-for-byte (Swift ships these via `Localizable.xcstrings`; on Android
 *    they are the constructor defaults).
 *  - [OwlQuestionnaireStrings.with] overrides *only* the passed-in fields and
 *    leaves every sibling untouched — the analog of Swift's `with(...)` copy.
 *  - the DEFAULT companion equals a freshly-constructed instance, and a no-arg
 *    `with()` is a value-equal copy.
 *
 * The Android `with(...)` is a superset of Swift's (Swift omits a handful of
 * fields like `loadingTitle` / `submittingButton` / `requiredLabel` /
 * `doNotShowAgainConfirm*` / `okButton` from its builder); the extra parameters
 * are exercised here so the override surface stays regression-proof.
 */
class OwlQuestionnaireStringsTest {

    @Test
    fun `DEFAULT carries the Swift catalog defaultValue strings`() {
        val d = OwlQuestionnaireStrings.DEFAULT
        assertEquals("Quick survey", d.title)
        assertEquals("Loading…", d.loadingTitle)
        assertEquals("Submit", d.submitButton)
        assertEquals("Sending…", d.submittingButton)
        assertEquals("Not now", d.skipButton)
        assertEquals("Next", d.nextButton)
        assertEquals("Back", d.backButton)
        assertEquals("Done", d.doneButton)
        assertEquals("Cancel", d.cancelButton)
        assertEquals("Quick favor?", d.consentTitle)
        assertEquals(
            "We'd love a few minutes of your feedback to help us improve.",
            d.consentBody,
        )
        assertEquals("Sure, happy to help", d.consentAccept)
        assertEquals("Maybe later", d.consentLater)
        assertEquals("Don't ask again", d.consentNever)
        assertEquals("Don't show again", d.doNotShowAgain)
        assertEquals("Don't show questionnaires?", d.doNotShowAgainConfirmTitle)
        assertEquals(
            "We won't ask you to fill in another questionnaire.",
            d.doNotShowAgainConfirmMessage,
        )
        assertEquals("Don't show again", d.doNotShowAgainConfirmAction)
        assertEquals("Keep showing", d.doNotShowAgainCancel)
        assertEquals("Required", d.requiredLabel)
        assertEquals("Thanks!", d.successTitle)
        assertEquals("Your answers help us improve.", d.successBody)
        assertEquals("Couldn't send response", d.errorTitle)
        assertEquals("Please answer the required questions.", d.errorRequiredMissing)
        assertEquals("Something went wrong. Please try again.", d.errorGeneric)
        assertEquals("Not at all likely", d.npsLowLabel)
        assertEquals("Extremely likely", d.npsHighLabel)
        assertEquals("OK", d.okButton)
    }

    @Test
    fun `DEFAULT companion equals a freshly-constructed instance`() {
        assertEquals(OwlQuestionnaireStrings(), OwlQuestionnaireStrings.DEFAULT)
    }

    @Test
    fun `with overrides only the passed field and preserves the rest`() {
        val base = OwlQuestionnaireStrings.DEFAULT
        val overridden = base.with(consentAccept = "Let's go!")

        assertEquals("Let's go!", overridden.consentAccept)
        // Every other field is unchanged — equality holds after restoring it.
        assertEquals(base, overridden.copy(consentAccept = base.consentAccept))
        assertNotEquals(base, overridden)
    }

    @Test
    fun `with overrides multiple fields independently`() {
        val base = OwlQuestionnaireStrings.DEFAULT
        val overridden = base.with(
            submitButton = "Send it",
            errorGeneric = "Oops.",
            okButton = "Got it",
            npsHighLabel = "Totally",
        )

        assertEquals("Send it", overridden.submitButton)
        assertEquals("Oops.", overridden.errorGeneric)
        assertEquals("Got it", overridden.okButton)
        assertEquals("Totally", overridden.npsHighLabel)
        // The untouched title is still the default.
        assertEquals(base.title, overridden.title)
        // Restoring the four changed fields recovers the base.
        assertEquals(
            base,
            overridden.copy(
                submitButton = base.submitButton,
                errorGeneric = base.errorGeneric,
                okButton = base.okButton,
                npsHighLabel = base.npsHighLabel,
            ),
        )
    }

    @Test
    fun `with no arguments returns a value-equal copy`() {
        val base = OwlQuestionnaireStrings.DEFAULT
        assertEquals(base, base.with())
    }

    @Test
    fun `with chains so the last override wins per field`() {
        val result = OwlQuestionnaireStrings.DEFAULT
            .with(title = "First")
            .with(title = "Second", consentTitle = "Consent two")
        assertEquals("Second", result.title)
        assertEquals("Consent two", result.consentTitle)
    }

    @Test
    fun `with covers the Android-only builder fields beyond Swift's surface`() {
        // Swift's with(...) omits these; the Android builder accepts them.
        val overridden = OwlQuestionnaireStrings.DEFAULT.with(
            loadingTitle = "One sec…",
            submittingButton = "Working…",
            requiredLabel = "Must answer",
            doNotShowAgainConfirmTitle = "Stop surveys?",
            doNotShowAgainConfirmMessage = "No more.",
            doNotShowAgainConfirmAction = "Stop",
            doNotShowAgainCancel = "Keep going",
        )
        assertEquals("One sec…", overridden.loadingTitle)
        assertEquals("Working…", overridden.submittingButton)
        assertEquals("Must answer", overridden.requiredLabel)
        assertEquals("Stop surveys?", overridden.doNotShowAgainConfirmTitle)
        assertEquals("No more.", overridden.doNotShowAgainConfirmMessage)
        assertEquals("Stop", overridden.doNotShowAgainConfirmAction)
        assertEquals("Keep going", overridden.doNotShowAgainCancel)
    }
}
