package com.owlmetry.android.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.owlmetry.android.Owl
import com.owlmetry.android.OwlFeedbackError
import com.owlmetry.android.OwlFeedbackReceipt
import kotlinx.coroutines.launch

/**
 * Where the Submit (and Cancel) actions live. The Compose analog of Swift's
 * `OwlFeedbackActionsPlacement`.
 */
public enum class OwlFeedbackActionsPlacement {
    /**
     * Render Submit / Cancel as a top action row above the form — the Compose
     * analog of Swift's toolbar placement (which merges the actions into the
     * enclosing `NavigationStack`'s nav bar). Use this when the view sits in a
     * sheet/dialog with no app bar of its own.
     */
    TOOLBAR,

    /**
     * Render Submit / Cancel as inline buttons at the bottom of the form. Use
     * this when embedding the view inside a parent screen.
     */
    INLINE,
}

/**
 * A reusable Compose view that collects free-text feedback (plus optional name
 * and email) and submits it to Owlmetry via [Owl.sendFeedback]. The Compose
 * analog of the Swift `OwlFeedbackView`.
 *
 * The view does not own a `TopAppBar` or navigation — the host decides how to
 * present it (a `ModalBottomSheet`, a full screen, an embedded section). Default
 * actions placement is [OwlFeedbackActionsPlacement.TOOLBAR], which renders a
 * Cancel/Submit row at the top; use [OwlFeedbackActionsPlacement.INLINE] when
 * embedding with no app bar.
 *
 * After a successful submission a "Thanks!" dialog is shown; tapping its confirm
 * button invokes [onSubmitted] with the receipt. Unlike SwiftUI's
 * `@Environment(\.dismiss)`, Compose has no built-in auto-dismiss, so the host
 * is responsible for closing the surface — wire it from [onSubmitted] (and
 * [onCancel] for the cancel path).
 *
 * Validation mirrors Swift exactly:
 *  - empty/whitespace message → blank-message error.
 *  - with contact fields shown: exactly one of name/email filled → incomplete-
 *    contact error; a non-empty but malformed email → invalid-email error; both
 *    blank → a "no contact details" confirm dialog ("Submit anyway" / "Add
 *    contact details").
 *
 * @param name pre-fill for the name field.
 * @param email pre-fill for the email field.
 * @param showsContactFields whether to show (and validate) the name/email rows.
 * @param actionsPlacement where Submit/Cancel render.
 * @param strings overridable user-facing copy.
 * @param onSubmitted invoked with the receipt after the success dialog is
 *        confirmed; null disables the callback.
 * @param onCancel invoked when Cancel is tapped; null hides the Cancel action.
 */
@Composable
public fun OwlFeedbackView(
    modifier: Modifier = Modifier,
    name: String? = null,
    email: String? = null,
    showsContactFields: Boolean = true,
    actionsPlacement: OwlFeedbackActionsPlacement = OwlFeedbackActionsPlacement.TOOLBAR,
    strings: OwlFeedbackStrings = OwlFeedbackStrings.DEFAULT,
    onSubmitted: ((OwlFeedbackReceipt) -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
) {
    var message by remember { mutableStateOf("") }
    var nameField by remember { mutableStateOf(name ?: "") }
    var emailField by remember { mutableStateOf(email ?: "") }
    var isSubmitting by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf<OwlFeedbackReceipt?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showNoContactAlert by remember { mutableStateOf(false) }
    var showSuccessAlert by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    val canSubmit = message.trim().isNotEmpty()
    val locked = isSubmitting || submitted != null

    // The actual network submission. Mirrors Swift's `submit()` — toggles the
    // submitting flag, calls Owl.sendFeedback, maps OwlFeedbackError cases onto
    // the localized strings, and on success stores the receipt + shows the
    // success dialog.
    suspend fun submit() {
        val trimmedMessage = message.trim()
        val trimmedName = nameField.trim()
        val trimmedEmail = emailField.trim()

        isSubmitting = true
        try {
            val receipt = Owl.sendFeedback(
                message = trimmedMessage,
                name = trimmedName.ifEmpty { null },
                email = trimmedEmail.ifEmpty { null },
            )
            submitted = receipt
            showSuccessAlert = true
        } catch (e: OwlFeedbackError.EmptyMessage) {
            errorMessage = strings.errorBlankMessage
        } catch (e: OwlFeedbackError) {
            // ServerError / TransportFailure / NotConfigured: surface the
            // error's own message (matches Swift's localizedDescription).
            errorMessage = e.message ?: strings.errorGeneric
        } catch (e: Throwable) {
            errorMessage = strings.errorGeneric
        } finally {
            isSubmitting = false
        }
    }

    // Tap handler — runs the same client-side validation gate as Swift's
    // `onSubmitTapped` before kicking off the async submit.
    fun onSubmitTapped() {
        val trimmedMessage = message.trim()
        if (trimmedMessage.isEmpty()) {
            errorMessage = strings.errorBlankMessage
            return
        }

        val trimmedName = nameField.trim()
        val trimmedEmail = emailField.trim()

        if (showsContactFields) {
            val onlyOne = (trimmedName.isEmpty() && trimmedEmail.isNotEmpty()) ||
                (trimmedName.isNotEmpty() && trimmedEmail.isEmpty())
            if (onlyOne) {
                errorMessage = strings.errorIncompleteContact
                return
            }
            if (trimmedEmail.isNotEmpty() && !isValidEmail(trimmedEmail)) {
                errorMessage = strings.errorInvalidEmail
                return
            }
            if (trimmedName.isEmpty() && trimmedEmail.isEmpty()) {
                showNoContactAlert = true
                return
            }
        }

        scope.launch { submit() }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (actionsPlacement == OwlFeedbackActionsPlacement.TOOLBAR && submitted == null) {
            FeedbackActionsRow(
                strings = strings,
                isSubmitting = isSubmitting,
                canSubmit = canSubmit,
                onCancel = onCancel?.let {
                    {
                        haptics.owlTap()
                        it()
                    }
                },
                onSubmit = {
                    haptics.owlTap()
                    onSubmitTapped()
                },
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(strings.header, style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp)
                    .testTag(FEEDBACK_MESSAGE_TAG),
                placeholder = { Text(strings.messagePlaceholder) },
                enabled = !locked,
            )

            Text(
                strings.footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (showsContactFields) {
                Spacer(Modifier.heightIn(min = 8.dp))
                Text(strings.contactSectionTitle, style = MaterialTheme.typography.titleSmall)

                OutlinedTextField(
                    value = nameField,
                    onValueChange = { nameField = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(FEEDBACK_NAME_TAG),
                    placeholder = { Text(strings.namePlaceholder) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    enabled = !locked,
                )

                OutlinedTextField(
                    value = emailField,
                    onValueChange = { emailField = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(FEEDBACK_EMAIL_TAG),
                    placeholder = { Text(strings.emailPlaceholder) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        capitalization = KeyboardCapitalization.None,
                    ),
                    enabled = !locked,
                )

                Text(
                    strings.contactSectionFooter,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (actionsPlacement == OwlFeedbackActionsPlacement.INLINE && submitted == null) {
            InlineActionsBar(
                strings = strings,
                isSubmitting = isSubmitting,
                canSubmit = canSubmit,
                onCancel = onCancel?.let {
                    {
                        haptics.owlTap()
                        it()
                    }
                },
                onSubmit = {
                    haptics.owlTap()
                    onSubmitTapped()
                },
            )
        }
    }

    // Error dialog — single OK that clears the error. Mirrors Swift's error alert.
    val currentError = errorMessage
    if (currentError != null) {
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            modifier = Modifier.testTag(FEEDBACK_ERROR_DIALOG_TAG),
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

    // "No contact details" confirm dialog — destructive "Submit anyway" /
    // cancel "Add contact details". Mirrors Swift's no-contact alert.
    if (showNoContactAlert) {
        AlertDialog(
            onDismissRequest = { showNoContactAlert = false },
            modifier = Modifier.testTag(FEEDBACK_NO_CONTACT_DIALOG_TAG),
            confirmButton = {
                TextButton(onClick = {
                    haptics.owlTap()
                    showNoContactAlert = false
                    scope.launch { submit() }
                }) { Text(strings.noContactSubmitAnyway) }
            },
            dismissButton = {
                TextButton(onClick = {
                    haptics.owlTap()
                    showNoContactAlert = false
                }) { Text(strings.noContactAddDetails) }
            },
            title = { Text(strings.noContactAlertTitle) },
            text = { Text(strings.noContactAlertMessage) },
        )
    }

    // Success dialog — confirm invokes onSubmitted with the receipt. Mirrors
    // Swift's success alert (minus the auto-dismiss, which is the host's job).
    if (showSuccessAlert) {
        AlertDialog(
            onDismissRequest = { /* keep modal until confirmed, matching Swift */ },
            modifier = Modifier.testTag(FEEDBACK_SUCCESS_DIALOG_TAG),
            confirmButton = {
                TextButton(onClick = {
                    haptics.owlTap()
                    showSuccessAlert = false
                    submitted?.let { onSubmitted?.invoke(it) }
                }) { Text(strings.okButton) }
            },
            title = { Text(strings.successTitle) },
            text = { Text(strings.successBody) },
        )
    }

    // No-op effect keyed on `submitted` so a host observing recomposition has a
    // stable anchor; also future-proofs an auto-dismiss hook without changing
    // the call sites. Kept intentionally empty.
    LaunchedEffect(submitted) { }
}

/** Top action row (Cancel + Submit) for [OwlFeedbackActionsPlacement.TOOLBAR]. */
@Composable
private fun FeedbackActionsRow(
    strings: OwlFeedbackStrings,
    isSubmitting: Boolean,
    canSubmit: Boolean,
    onCancel: (() -> Unit)?,
    onSubmit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onCancel != null) {
            TextButton(onClick = onCancel, enabled = !isSubmitting) {
                Text(strings.cancelButton)
            }
        } else {
            Spacer(Modifier)
        }

        if (isSubmitting) {
            CircularProgressIndicator(
                modifier = Modifier
                    .heightIn(min = 24.dp)
                    .testTag(FEEDBACK_PROGRESS_TAG),
            )
        } else {
            Button(
                onClick = onSubmit,
                enabled = canSubmit,
                modifier = Modifier.testTag(FEEDBACK_SUBMIT_TAG),
            ) {
                Text(strings.submitButton)
            }
        }
    }
}

/** Bottom action bar (Submit + optional Cancel) for [OwlFeedbackActionsPlacement.INLINE]. */
@Composable
private fun InlineActionsBar(
    strings: OwlFeedbackStrings,
    isSubmitting: Boolean,
    canSubmit: Boolean,
    onCancel: (() -> Unit)?,
    onSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Button(
            onClick = onSubmit,
            enabled = !isSubmitting && canSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(FEEDBACK_SUBMIT_TAG),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.testTag(FEEDBACK_PROGRESS_TAG))
                    Text(strings.submittingButton)
                } else {
                    Text(strings.submitButton)
                }
            }
        }

        if (onCancel != null) {
            TextButton(
                onClick = onCancel,
                enabled = !isSubmitting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(strings.cancelButton)
            }
        }
    }
}

/**
 * Email validity check mirroring Swift's `^[^\s@]+@[^\s@]+\.[^\s@]+$` regex —
 * exactly one `@`, a dotted domain, no whitespace.
 */
internal fun isValidEmail(value: String): Boolean = EMAIL_REGEX.matches(value)

private val EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

// Test tags so the Compose UI test can locate fields/buttons/dialogs without
// depending on (overridable, localized) label text.
internal const val FEEDBACK_MESSAGE_TAG: String = "owl_feedback_message"
internal const val FEEDBACK_NAME_TAG: String = "owl_feedback_name"
internal const val FEEDBACK_EMAIL_TAG: String = "owl_feedback_email"
internal const val FEEDBACK_SUBMIT_TAG: String = "owl_feedback_submit"
internal const val FEEDBACK_PROGRESS_TAG: String = "owl_feedback_progress"
internal const val FEEDBACK_ERROR_DIALOG_TAG: String = "owl_feedback_error_dialog"
internal const val FEEDBACK_SUCCESS_DIALOG_TAG: String = "owl_feedback_success_dialog"
internal const val FEEDBACK_NO_CONTACT_DIALOG_TAG: String = "owl_feedback_no_contact_dialog"
