package com.owlmetry.android.compose

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.owlmetry.android.Owl
import com.owlmetry.android.OwlQuestionnaire
import com.owlmetry.android.OwlQuestionnaireAnswerStore
import com.owlmetry.android.OwlQuestionnaireDraft
import com.owlmetry.android.OwlQuestionnaireError
import com.owlmetry.android.OwlQuestionnaireQuestion
import com.owlmetry.android.OwlQuestionnaireReceipt
import kotlinx.coroutines.launch

/**
 * A Compose view that renders an [OwlQuestionnaire] as a step-through flow (one
 * question per page, with a progress bar) and submits via
 * [Owl.saveQuestionnaireResponse]. The Compose analog of the Swift
 * `OwlQuestionnaireView`.
 *
 * The view does not own a top app bar or navigation — the host decides how to
 * present it (a `ModalBottomSheet`, a full screen, an embedded section). Pass
 * [showsConsent] = true to add the "Quick favor?" consent prompt up front.
 *
 * Mirrors the Swift flow's phase machine (consent → running → success),
 * button-driven navigation (no swipe-past-a-required-question), background draft
 * saves on each Next, and a required-questions gate before final submit.
 *
 * Unlike SwiftUI's `@Environment(\.dismiss)`, Compose has no built-in
 * auto-dismiss, so the host closes the surface — wire it from [onSubmitted] /
 * [onCancel] / [onDismissed].
 *
 * @param questionnaire the spec to render.
 * @param inProgress an existing draft to resume (pre-fills + lands on the first
 *        unanswered question, skipping consent).
 * @param showsConsent whether to show the consent prompt before the questions.
 * @param strings overridable user-facing copy.
 * @param onSubmitted invoked with the receipt after Done on the success page.
 * @param onCancel invoked when the user cancels mid-flow / declines "later".
 * @param onDismissed invoked after a global "don't show again" dismissal.
 */
@Composable
public fun OwlQuestionnaireView(
    questionnaire: OwlQuestionnaire,
    modifier: Modifier = Modifier,
    inProgress: OwlQuestionnaireDraft? = null,
    showsConsent: Boolean = false,
    strings: OwlQuestionnaireStrings = OwlQuestionnaireStrings.DEFAULT,
    onSubmitted: ((OwlQuestionnaireReceipt) -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    onDismissed: (() -> Unit)? = null,
) {
    OwlQuestionnaireFlowContainer(
        questionnaire = questionnaire,
        modifier = modifier,
        inProgress = inProgress,
        showsConsent = showsConsent,
        strings = strings,
        onSubmitted = onSubmitted,
        onCancel = onCancel,
        onDismissed = onDismissed,
    )
}

/** Phase of the questionnaire flow. Mirrors Swift's `OwlQuestionnairePhase`. */
private sealed interface QuestionnairePhase {
    data object Consent : QuestionnairePhase
    data class Running(val index: Int) : QuestionnairePhase
    data class Success(val receipt: OwlQuestionnaireReceipt) : QuestionnairePhase
}

/**
 * Internal host that owns the questionnaire flow: phase machine, answer state,
 * submit/dismiss. The Compose analog of Swift's `OwlQuestionnaireFlowContainer`
 * (minus the iOS sheet-detent machinery — on Android the host owns the
 * container's presentation, so detents aren't the SDK's concern).
 */
@Composable
internal fun OwlQuestionnaireFlowContainer(
    questionnaire: OwlQuestionnaire,
    modifier: Modifier = Modifier,
    inProgress: OwlQuestionnaireDraft? = null,
    showsConsent: Boolean = false,
    strings: OwlQuestionnaireStrings = OwlQuestionnaireStrings.DEFAULT,
    onSubmitted: ((OwlQuestionnaireReceipt) -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    onDismissed: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    // Hydrate the answer store from any server-side draft before first render,
    // so the saved values are visible immediately. Mirrors Swift's container
    // init that prefills before SwiftUI creates the page bindings.
    var answers by remember(questionnaire.id, inProgress) {
        mutableStateOf(
            if (inProgress != null) {
                OwlQuestionnaireAnswerStore().prefilled(inProgress.answers)
            } else {
                OwlQuestionnaireAnswerStore()
            },
        )
    }

    // Resuming a draft skips consent (the user already opted in) and lands on
    // the first unanswered question. Mirrors Swift's resume init logic.
    val resuming = inProgress != null
    val startIndex = if (resuming) answers.firstUnansweredIndex(questionnaire.schema) else 0
    val showConsentNow = showsConsent && !resuming

    var phase by remember(questionnaire.id) {
        mutableStateOf<QuestionnairePhase>(
            if (showConsentNow) QuestionnairePhase.Consent else QuestionnairePhase.Running(startIndex),
        )
    }

    var isSubmitting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showDismissConfirm by remember { mutableStateOf(false) }

    val questions = questionnaire.schema.questions
    val total = questions.size

    // --- Phase transitions (mirror Swift's container methods) ---

    fun emitConsentShown() {
        Owl.info("sdk:questionnaire_consent_shown", attributes = mapOf("_slug" to questionnaire.slug))
    }

    fun acceptConsent() {
        Owl.info("sdk:questionnaire_started", attributes = mapOf("_slug" to questionnaire.slug))
        phase = QuestionnairePhase.Running(0)
    }

    fun declineLater() {
        Owl.debug(
            "sdk:questionnaire_consent_dismissed",
            attributes = mapOf("_slug" to questionnaire.slug, "_reason" to "later"),
        )
        onCancel?.invoke()
    }

    fun cancelMidFlow() {
        onCancel?.invoke()
    }

    fun finishSuccess(receipt: OwlQuestionnaireReceipt) {
        onSubmitted?.invoke(receipt)
    }

    suspend fun saveDraftInBackground() {
        val payload = answers.collected(questionnaire.schema)
        // Skip empty saves — advancing past an unanswered optional question.
        if (payload.isEmpty()) return
        runCatching {
            Owl.saveQuestionnaireResponse(slug = questionnaire.slug, answers = payload, isComplete = false)
        }
        // Soft-fail: the final submit resends the accumulated answers.
    }

    suspend fun submit() {
        if (!answers.hasAllRequired(questionnaire.schema)) {
            errorMessage = strings.errorRequiredMissing
            return
        }
        isSubmitting = true
        try {
            val receipt = Owl.saveQuestionnaireResponse(
                slug = questionnaire.slug,
                answers = answers.collected(questionnaire.schema),
                isComplete = true,
            )
            Owl.info("sdk:questionnaire_submitted", attributes = mapOf("_slug" to questionnaire.slug))
            phase = QuestionnairePhase.Success(receipt)
        } catch (e: OwlQuestionnaireError) {
            errorMessage = e.message ?: strings.errorGeneric
        } catch (e: Throwable) {
            errorMessage = strings.errorGeneric
        } finally {
            isSubmitting = false
        }
    }

    suspend fun dismissGlobally() {
        isSubmitting = true
        try {
            Owl.dismissQuestionnaires()
            Owl.debug(
                "sdk:questionnaire_consent_dismissed",
                attributes = mapOf("_slug" to questionnaire.slug, "_reason" to "never"),
            )
            onDismissed?.invoke()
        } catch (e: OwlQuestionnaireError) {
            errorMessage = e.message ?: strings.errorGeneric
        } catch (e: Throwable) {
            errorMessage = strings.errorGeneric
        } finally {
            isSubmitting = false
        }
    }

    Box(modifier = modifier.fillMaxWidth().testTag(QUESTIONNAIRE_ROOT_TAG)) {
        AnimatedContent(
            targetState = phase,
            transitionSpec = {
                (fadeIn(tween(200)) togetherWith fadeOut(tween(200)))
            },
            label = "owl_questionnaire_phase",
        ) { current ->
            when (current) {
                is QuestionnairePhase.Consent -> {
                    // Fire the consent-shown event once on first composition of
                    // this phase. `remember` keyed on the phase object guards it.
                    remember(current) { emitConsentShown(); true }
                    OwlQuestionnaireConsentView(
                        title = strings.consentTitle,
                        message = questionnaire.description?.takeIf { it.isNotEmpty() } ?: strings.consentBody,
                        acceptLabel = strings.consentAccept,
                        laterLabel = strings.consentLater,
                        neverLabel = strings.consentNever,
                        onAccept = { acceptConsent() },
                        onLater = { declineLater() },
                        onNever = { showDismissConfirm = true },
                    )
                }

                is QuestionnairePhase.Running -> {
                    val clamped = current.index.coerceIn(0, maxOf(0, total - 1))
                    val question = questions.getOrNull(clamped)
                    if (question == null) {
                        // Empty schema — nothing to show; treat as cancel.
                        TextButton(onClick = { cancelMidFlow() }) { Text(strings.cancelButton) }
                    } else {
                        RunningPage(
                            index = clamped,
                            total = total,
                            question = question,
                            answers = answers,
                            strings = strings,
                            isSubmitting = isSubmitting,
                            onAnswersChange = { answers = it },
                            onCancel = { cancelMidFlow() },
                            onBack = { phase = QuestionnairePhase.Running((clamped - 1).coerceAtLeast(0)) },
                            onNext = {
                                scope.launch { saveDraftInBackground() }
                                phase = QuestionnairePhase.Running((clamped + 1).coerceAtMost(total - 1))
                            },
                            onSubmit = { scope.launch { submit() } },
                        )
                    }
                }

                is QuestionnairePhase.Success -> {
                    OwlQuestionnaireSuccessView(
                        title = strings.successTitle,
                        message = strings.successBody,
                        doneLabel = strings.doneButton,
                        onDone = { finishSuccess(current.receipt) },
                    )
                }
            }
        }
    }

    // Error dialog — single OK that clears the error. Mirrors Swift's error alert.
    val currentError = errorMessage
    if (currentError != null) {
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            modifier = Modifier.testTag(QUESTIONNAIRE_ERROR_DIALOG_TAG),
            confirmButton = {
                TextButton(onClick = {
                    haptics.owlTap()
                    errorMessage = null
                }) { Text(strings.okButton) }
            },
            title = { Text(strings.errorTitle) },
            text = { Text(currentError) },
        )
    }

    // "Don't show again" confirm — destructive action dismisses globally.
    // Mirrors Swift's confirmationDialog on the consent "never" path.
    if (showDismissConfirm) {
        AlertDialog(
            onDismissRequest = { showDismissConfirm = false },
            modifier = Modifier.testTag(QUESTIONNAIRE_DISMISS_DIALOG_TAG),
            confirmButton = {
                TextButton(onClick = {
                    haptics.owlTap()
                    showDismissConfirm = false
                    scope.launch { dismissGlobally() }
                }) { Text(strings.doNotShowAgainConfirmAction) }
            },
            dismissButton = {
                TextButton(onClick = {
                    haptics.owlTap()
                    showDismissConfirm = false
                }) { Text(strings.doNotShowAgainCancel) }
            },
            title = { Text(strings.doNotShowAgainConfirmTitle) },
            text = { Text(strings.doNotShowAgainConfirmMessage) },
        )
    }
}

/**
 * One running page: progress bar + cancel header + the type-specific question
 * page + the Back/Next-or-Submit button bar. The Next button is disabled when a
 * required question is unanswered, mirroring Swift's `canAdvance` gate.
 */
@Composable
private fun RunningPage(
    index: Int,
    total: Int,
    question: OwlQuestionnaireQuestion,
    answers: OwlQuestionnaireAnswerStore,
    strings: OwlQuestionnaireStrings,
    isSubmitting: Boolean,
    onAnswersChange: (OwlQuestionnaireAnswerStore) -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSubmit: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val isLast = index == total - 1
    val canAdvance = !question.required || answers.isAnswered(question)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = {
                    haptics.owlTap()
                    onCancel()
                },
                enabled = !isSubmitting,
                modifier = Modifier.testTag(QUESTIONNAIRE_CANCEL_TAG),
            ) {
                Text(strings.cancelButton)
            }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            Text(
                "${index + 1} / $total",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OwlQuestionnaireProgressBar(current = index, total = total)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            QuestionPage(
                question = question,
                answers = answers,
                npsLowLabel = strings.npsLowLabel,
                npsHighLabel = strings.npsHighLabel,
                onAnswersChange = onAnswersChange,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (index > 0) {
                OutlinedButton(
                    onClick = {
                        haptics.owlTap()
                        onBack()
                    },
                    enabled = !isSubmitting,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 50.dp)
                        .testTag(QUESTIONNAIRE_BACK_TAG),
                ) {
                    Text(strings.backButton)
                }
            }

            Button(
                onClick = {
                    haptics.owlTap()
                    if (isLast) onSubmit() else onNext()
                },
                enabled = canAdvance && !isSubmitting,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 50.dp)
                    .testTag(if (isLast) QUESTIONNAIRE_SUBMIT_TAG else QUESTIONNAIRE_NEXT_TAG),
            ) {
                if (isLast && isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.testTag(QUESTIONNAIRE_PROGRESS_TAG),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(if (isLast) strings.submitButton else strings.nextButton)
                }
            }
        }
    }
}

/** Dispatch to the type-specific page, wiring its answer write back into the store. */
@Composable
private fun QuestionPage(
    question: OwlQuestionnaireQuestion,
    answers: OwlQuestionnaireAnswerStore,
    npsLowLabel: String,
    npsHighLabel: String,
    onAnswersChange: (OwlQuestionnaireAnswerStore) -> Unit,
) {
    when (question) {
        is OwlQuestionnaireQuestion.Text -> OwlQuestionnaireTextPage(
            question = question,
            value = answers.text[question.id] ?: "",
            onValueChange = { onAnswersChange(answers.withText(question.id, it)) },
        )
        is OwlQuestionnaireQuestion.SingleChoice -> OwlQuestionnaireSingleChoicePage(
            question = question,
            selected = answers.single[question.id],
            onSelect = { onAnswersChange(answers.withSingle(question.id, it)) },
        )
        is OwlQuestionnaireQuestion.MultiChoice -> OwlQuestionnaireMultiChoicePage(
            question = question,
            selected = answers.multi[question.id] ?: emptySet(),
            onToggle = { onAnswersChange(answers.togglingMulti(question.id, it)) },
        )
        is OwlQuestionnaireQuestion.Rating -> OwlQuestionnaireRatingPage(
            question = question,
            value = answers.rating[question.id],
            onRate = { onAnswersChange(answers.withRating(question.id, it)) },
        )
        is OwlQuestionnaireQuestion.Nps -> OwlQuestionnaireNpsPage(
            question = question,
            value = answers.nps[question.id],
            lowLabel = npsLowLabel,
            highLabel = npsHighLabel,
            onScore = { onAnswersChange(answers.withNps(question.id, it)) },
        )
    }
}

internal const val QUESTIONNAIRE_ROOT_TAG: String = "owl_q_root"
internal const val QUESTIONNAIRE_CANCEL_TAG: String = "owl_q_cancel"
internal const val QUESTIONNAIRE_BACK_TAG: String = "owl_q_back"
internal const val QUESTIONNAIRE_NEXT_TAG: String = "owl_q_next"
internal const val QUESTIONNAIRE_SUBMIT_TAG: String = "owl_q_submit"
internal const val QUESTIONNAIRE_PROGRESS_TAG: String = "owl_q_progress"
internal const val QUESTIONNAIRE_ERROR_DIALOG_TAG: String = "owl_q_error_dialog"
internal const val QUESTIONNAIRE_DISMISS_DIALOG_TAG: String = "owl_q_dismiss_dialog"
