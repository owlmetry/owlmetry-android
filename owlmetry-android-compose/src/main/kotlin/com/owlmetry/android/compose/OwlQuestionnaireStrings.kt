package com.owlmetry.android.compose

/**
 * User-facing strings rendered by the questionnaire flow. The Compose analog of
 * the Swift `OwlQuestionnaireStrings`.
 *
 * Same convention as [OwlFeedbackStrings]: every field is a plain [String] so
 * the Compose module stays free of a resource bundle while remaining fully
 * overridable — defaults ship in English, and a localizing app passes
 * `stringResource(R.string.…)` values into the constructor (or via [with]).
 */
public data class OwlQuestionnaireStrings(
    // Phase: running flow (questions + nav)
    public val title: String = "Quick survey",
    public val loadingTitle: String = "Loading…",
    public val submitButton: String = "Submit",
    public val submittingButton: String = "Sending…",
    public val skipButton: String = "Not now",
    public val nextButton: String = "Next",
    public val backButton: String = "Back",
    public val doneButton: String = "Done",
    public val cancelButton: String = "Cancel",
    // Phase: consent (small detent)
    public val consentTitle: String = "Quick favor?",
    public val consentBody: String = "We'd love a few minutes of your feedback to help us improve.",
    public val consentAccept: String = "Sure, happy to help",
    public val consentLater: String = "Maybe later",
    public val consentNever: String = "Don't ask again",
    // Global dismiss confirmation (reached from consent "never" path)
    public val doNotShowAgain: String = "Don't show again",
    public val doNotShowAgainConfirmTitle: String = "Don't show questionnaires?",
    public val doNotShowAgainConfirmMessage: String =
        "We won't ask you to fill in another questionnaire.",
    public val doNotShowAgainConfirmAction: String = "Don't show again",
    public val doNotShowAgainCancel: String = "Keep showing",
    // Misc
    public val requiredLabel: String = "Required",
    public val successTitle: String = "Thanks!",
    public val successBody: String = "Your answers help us improve.",
    public val errorTitle: String = "Couldn't send response",
    public val errorRequiredMissing: String = "Please answer the required questions.",
    public val errorGeneric: String = "Something went wrong. Please try again.",
    public val npsLowLabel: String = "Not at all likely",
    public val npsHighLabel: String = "Extremely likely",
    /** The confirm label on the error dialog. */
    public val okButton: String = "OK",
) {
    /**
     * Return a copy with only the passed-in fields overridden — the analog of
     * Swift's `OwlQuestionnaireStrings.with(...)`. Any argument left null keeps
     * the receiver's value.
     */
    @Suppress("LongParameterList", "CyclomaticComplexMethod")
    public fun with(
        title: String? = null,
        loadingTitle: String? = null,
        submitButton: String? = null,
        submittingButton: String? = null,
        skipButton: String? = null,
        nextButton: String? = null,
        backButton: String? = null,
        doneButton: String? = null,
        cancelButton: String? = null,
        consentTitle: String? = null,
        consentBody: String? = null,
        consentAccept: String? = null,
        consentLater: String? = null,
        consentNever: String? = null,
        doNotShowAgain: String? = null,
        doNotShowAgainConfirmTitle: String? = null,
        doNotShowAgainConfirmMessage: String? = null,
        doNotShowAgainConfirmAction: String? = null,
        doNotShowAgainCancel: String? = null,
        requiredLabel: String? = null,
        successTitle: String? = null,
        successBody: String? = null,
        errorTitle: String? = null,
        errorRequiredMissing: String? = null,
        errorGeneric: String? = null,
        npsLowLabel: String? = null,
        npsHighLabel: String? = null,
        okButton: String? = null,
    ): OwlQuestionnaireStrings = copy(
        title = title ?: this.title,
        loadingTitle = loadingTitle ?: this.loadingTitle,
        submitButton = submitButton ?: this.submitButton,
        submittingButton = submittingButton ?: this.submittingButton,
        skipButton = skipButton ?: this.skipButton,
        nextButton = nextButton ?: this.nextButton,
        backButton = backButton ?: this.backButton,
        doneButton = doneButton ?: this.doneButton,
        cancelButton = cancelButton ?: this.cancelButton,
        consentTitle = consentTitle ?: this.consentTitle,
        consentBody = consentBody ?: this.consentBody,
        consentAccept = consentAccept ?: this.consentAccept,
        consentLater = consentLater ?: this.consentLater,
        consentNever = consentNever ?: this.consentNever,
        doNotShowAgain = doNotShowAgain ?: this.doNotShowAgain,
        doNotShowAgainConfirmTitle = doNotShowAgainConfirmTitle ?: this.doNotShowAgainConfirmTitle,
        doNotShowAgainConfirmMessage = doNotShowAgainConfirmMessage ?: this.doNotShowAgainConfirmMessage,
        doNotShowAgainConfirmAction = doNotShowAgainConfirmAction ?: this.doNotShowAgainConfirmAction,
        doNotShowAgainCancel = doNotShowAgainCancel ?: this.doNotShowAgainCancel,
        requiredLabel = requiredLabel ?: this.requiredLabel,
        successTitle = successTitle ?: this.successTitle,
        successBody = successBody ?: this.successBody,
        errorTitle = errorTitle ?: this.errorTitle,
        errorRequiredMissing = errorRequiredMissing ?: this.errorRequiredMissing,
        errorGeneric = errorGeneric ?: this.errorGeneric,
        npsLowLabel = npsLowLabel ?: this.npsLowLabel,
        npsHighLabel = npsHighLabel ?: this.npsHighLabel,
        okButton = okButton ?: this.okButton,
    )

    public companion object {
        /** The English defaults — the analog of Swift's `OwlQuestionnaireStrings.default`. */
        public val DEFAULT: OwlQuestionnaireStrings = OwlQuestionnaireStrings()
    }
}
