package com.owlmetry.android.compose

/**
 * All user-facing strings rendered by [OwlFeedbackView]. The Compose analog of
 * the Swift `OwlFeedbackStrings`.
 *
 * Swift's fields are `LocalizedStringResource`s resolved against the SDK's
 * bundled `Localizable.xcstrings`. On Android the idiomatic seam is the host
 * app's own string resources, so every field here is a plain [String]: defaults
 * ship in English, and a localizing app passes `stringResource(R.string.…)`
 * values into the constructor (or via [with]) — keeping the Compose module free
 * of a resource bundle while still fully overridable.
 *
 * ```kotlin
 * // Override a single field
 * OwlFeedbackStrings.DEFAULT.with(header = "How can we help?")
 *
 * // Resolve everything against the app's catalog
 * OwlFeedbackStrings(
 *     header = stringResource(R.string.feedback_header),
 *     // …
 * )
 * ```
 */
public data class OwlFeedbackStrings(
    public val header: String = "How can we improve?",
    public val footer: String = "We read every piece of feedback.",
    public val messagePlaceholder: String = "Tell us what's on your mind…",
    public val contactSectionTitle: String = "Contact (optional)",
    public val contactSectionFooter: String = "Leave these blank and we'll still get your feedback.",
    public val namePlaceholder: String = "Your name",
    public val emailPlaceholder: String = "you@example.com",
    public val submitButton: String = "Send feedback",
    public val submittingButton: String = "Sending…",
    public val cancelButton: String = "Cancel",
    public val successTitle: String = "Thanks!",
    public val successBody: String = "Your feedback made it through.",
    public val errorTitle: String = "Couldn't send feedback",
    public val errorBlankMessage: String = "Please write a message first.",
    public val errorInvalidEmail: String = "That doesn't look like a valid email.",
    public val errorIncompleteContact: String = "Please provide both name and email or leave both empty.",
    public val errorGeneric: String = "Something went wrong. Please try again.",
    public val noContactAlertTitle: String = "No contact details",
    public val noContactAlertMessage: String =
        "Without your contact details, we won't be able to follow up on your feedback. " +
            "Are you sure you want to continue?",
    public val noContactSubmitAnyway: String = "Submit anyway",
    public val noContactAddDetails: String = "Add contact details",
    /** The confirm label on the success / error dialogs. */
    public val okButton: String = "OK",
) {
    /**
     * Return a copy with only the passed-in fields overridden — the analog of
     * Swift's `OwlFeedbackStrings.with(header:…)`. Any argument left null keeps
     * the receiver's value.
     */
    public fun with(
        header: String? = null,
        footer: String? = null,
        messagePlaceholder: String? = null,
        contactSectionTitle: String? = null,
        contactSectionFooter: String? = null,
        namePlaceholder: String? = null,
        emailPlaceholder: String? = null,
        submitButton: String? = null,
        submittingButton: String? = null,
        cancelButton: String? = null,
        successTitle: String? = null,
        successBody: String? = null,
        errorTitle: String? = null,
        errorBlankMessage: String? = null,
        errorInvalidEmail: String? = null,
        errorIncompleteContact: String? = null,
        errorGeneric: String? = null,
        noContactAlertTitle: String? = null,
        noContactAlertMessage: String? = null,
        noContactSubmitAnyway: String? = null,
        noContactAddDetails: String? = null,
        okButton: String? = null,
    ): OwlFeedbackStrings = copy(
        header = header ?: this.header,
        footer = footer ?: this.footer,
        messagePlaceholder = messagePlaceholder ?: this.messagePlaceholder,
        contactSectionTitle = contactSectionTitle ?: this.contactSectionTitle,
        contactSectionFooter = contactSectionFooter ?: this.contactSectionFooter,
        namePlaceholder = namePlaceholder ?: this.namePlaceholder,
        emailPlaceholder = emailPlaceholder ?: this.emailPlaceholder,
        submitButton = submitButton ?: this.submitButton,
        submittingButton = submittingButton ?: this.submittingButton,
        cancelButton = cancelButton ?: this.cancelButton,
        successTitle = successTitle ?: this.successTitle,
        successBody = successBody ?: this.successBody,
        errorTitle = errorTitle ?: this.errorTitle,
        errorBlankMessage = errorBlankMessage ?: this.errorBlankMessage,
        errorInvalidEmail = errorInvalidEmail ?: this.errorInvalidEmail,
        errorIncompleteContact = errorIncompleteContact ?: this.errorIncompleteContact,
        errorGeneric = errorGeneric ?: this.errorGeneric,
        noContactAlertTitle = noContactAlertTitle ?: this.noContactAlertTitle,
        noContactAlertMessage = noContactAlertMessage ?: this.noContactAlertMessage,
        noContactSubmitAnyway = noContactSubmitAnyway ?: this.noContactSubmitAnyway,
        noContactAddDetails = noContactAddDetails ?: this.noContactAddDetails,
        okButton = okButton ?: this.okButton,
    )

    public companion object {
        /** The English defaults — the analog of Swift's `OwlFeedbackStrings.default`. */
        public val DEFAULT: OwlFeedbackStrings = OwlFeedbackStrings()
    }
}
