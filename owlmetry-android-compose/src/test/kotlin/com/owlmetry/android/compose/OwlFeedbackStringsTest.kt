package com.owlmetry.android.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM parity tests for [OwlFeedbackStrings] (the Compose analog of the
 * Swift `OwlFeedbackStrings`) and [OwlFeedbackActionsPlacement]. No Compose
 * harness / Robolectric — these only exercise the value type:
 *
 *  - the English defaults match the Swift catalog's `defaultValue` strings
 *    byte-for-byte (Swift ships these via `Localizable.xcstrings`; on Android
 *    they are the constructor defaults).
 *  - [OwlFeedbackStrings.with] overrides *only* the passed-in field and leaves
 *    every sibling untouched — the analog of Swift's `with(header:…)` copy.
 *  - a no-arg `with()` is a value-equal copy.
 *  - [OwlFeedbackActionsPlacement] enumerates exactly the Swift cases.
 */
class OwlFeedbackStringsTest {

    @Test
    fun `DEFAULT carries the Swift catalog defaultValue strings`() {
        val d = OwlFeedbackStrings.DEFAULT
        assertEquals("How can we improve?", d.header)
        assertEquals("We read every piece of feedback.", d.footer)
        assertEquals("Tell us what's on your mind…", d.messagePlaceholder)
        assertEquals("Contact (optional)", d.contactSectionTitle)
        assertEquals("Leave these blank and we'll still get your feedback.", d.contactSectionFooter)
        assertEquals("Your name", d.namePlaceholder)
        assertEquals("you@example.com", d.emailPlaceholder)
        assertEquals("Send feedback", d.submitButton)
        assertEquals("Sending…", d.submittingButton)
        assertEquals("Cancel", d.cancelButton)
        assertEquals("Thanks!", d.successTitle)
        assertEquals("Your feedback made it through.", d.successBody)
        assertEquals("Couldn't send feedback", d.errorTitle)
        assertEquals("Please write a message first.", d.errorBlankMessage)
        assertEquals("That doesn't look like a valid email.", d.errorInvalidEmail)
        assertEquals(
            "Please provide both name and email or leave both empty.",
            d.errorIncompleteContact,
        )
        assertEquals("Something went wrong. Please try again.", d.errorGeneric)
        assertEquals("No contact details", d.noContactAlertTitle)
        assertEquals(
            "Without your contact details, we won't be able to follow up on your feedback. " +
                "Are you sure you want to continue?",
            d.noContactAlertMessage,
        )
        assertEquals("Submit anyway", d.noContactSubmitAnyway)
        assertEquals("Add contact details", d.noContactAddDetails)
        assertEquals("OK", d.okButton)
    }

    @Test
    fun `DEFAULT companion equals a freshly-constructed instance`() {
        // The companion default and a default-constructed value are equal data
        // (mirrors Swift's `static let default = OwlFeedbackStrings()`).
        assertEquals(OwlFeedbackStrings(), OwlFeedbackStrings.DEFAULT)
    }

    @Test
    fun `with overrides only the passed field and preserves the rest`() {
        val base = OwlFeedbackStrings.DEFAULT
        val overridden = base.with(header = "How can we help?")

        assertEquals("How can we help?", overridden.header)
        // Every other field is unchanged — equality holds after restoring header.
        assertEquals(base, overridden.copy(header = base.header))
        // And the override is a distinct value from the receiver.
        assertNotEquals(base, overridden)
    }

    @Test
    fun `with overrides multiple fields independently`() {
        val base = OwlFeedbackStrings.DEFAULT
        val overridden = base.with(
            submitButton = "Send it",
            errorGeneric = "Oops.",
            okButton = "Got it",
        )

        assertEquals("Send it", overridden.submitButton)
        assertEquals("Oops.", overridden.errorGeneric)
        assertEquals("Got it", overridden.okButton)
        // The untouched footer is still the default.
        assertEquals(base.footer, overridden.footer)
        // Restoring the three changed fields recovers the base.
        assertEquals(
            base,
            overridden.copy(
                submitButton = base.submitButton,
                errorGeneric = base.errorGeneric,
                okButton = base.okButton,
            ),
        )
    }

    @Test
    fun `with no arguments returns a value-equal copy`() {
        val base = OwlFeedbackStrings.DEFAULT
        val copy = base.with()
        assertEquals(base, copy)
    }

    @Test
    fun `with chains so the last override wins per field`() {
        val result = OwlFeedbackStrings.DEFAULT
            .with(header = "First")
            .with(header = "Second", footer = "Footer two")
        assertEquals("Second", result.header)
        assertEquals("Footer two", result.footer)
    }

    @Test
    fun `OwlFeedbackActionsPlacement enumerates exactly the Swift cases`() {
        // Swift: case toolbar, case inline. Default in OwlFeedbackView is TOOLBAR.
        assertEquals(
            listOf(OwlFeedbackActionsPlacement.TOOLBAR, OwlFeedbackActionsPlacement.INLINE),
            OwlFeedbackActionsPlacement.entries.toList(),
        )
        assertSame(
            OwlFeedbackActionsPlacement.TOOLBAR,
            OwlFeedbackActionsPlacement.valueOf("TOOLBAR"),
        )
        assertSame(
            OwlFeedbackActionsPlacement.INLINE,
            OwlFeedbackActionsPlacement.valueOf("INLINE"),
        )
    }

    @Test
    fun `isValidEmail rejects leading and trailing dots in the domain`() {
        // Extra coverage beyond the view test: the Swift regex requires a dotted
        // domain with non-empty labels on both sides of the dot.
        assertTrue(isValidEmail("u@a.io"))
        org.junit.Assert.assertFalse(isValidEmail("u@.io"))
        org.junit.Assert.assertFalse(isValidEmail("u@a."))
        org.junit.Assert.assertFalse(isValidEmail("u@a.b c"))
    }
}
