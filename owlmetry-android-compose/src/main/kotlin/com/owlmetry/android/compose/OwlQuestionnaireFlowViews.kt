package com.owlmetry.android.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Consent / success / progress sub-views for the questionnaire flow — the
 * Compose analog of Swift's `OwlQuestionnaireConsentView`,
 * `OwlQuestionnaireSuccessView`, and `OwlQuestionnaireProgressBar`. Internal —
 * composed by [OwlQuestionnaireFlowContainer].
 */

/**
 * The consent prompt shown before the question flow when `showsConsent` is set.
 * The primary CTA is the only filled button; "Maybe later" and "Don't ask
 * again" sit below as low-emphasis text buttons, mirroring the Swift consent
 * view's visual weighting.
 */
@Composable
internal fun OwlQuestionnaireConsentView(
    title: String,
    message: String,
    acceptLabel: String,
    laterLabel: String,
    neverLabel: String,
    onAccept: () -> Unit,
    onLater: () -> Unit,
    onNever: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .testTag(QUESTIONNAIRE_CONSENT_TAG),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
            onClick = {
                haptics.owlTap()
                onAccept()
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 50.dp)
                .padding(top = 8.dp)
                .testTag(QUESTIONNAIRE_CONSENT_ACCEPT_TAG),
        ) {
            Text(acceptLabel)
        }

        TextButton(
            onClick = {
                haptics.owlTap()
                onLater()
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(QUESTIONNAIRE_CONSENT_LATER_TAG),
        ) {
            Text(laterLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        TextButton(
            onClick = {
                haptics.owlTap()
                onNever()
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(QUESTIONNAIRE_CONSENT_NEVER_TAG),
        ) {
            Text(neverLabel, color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * The in-flow success page, replacing a system dialog so dismissal is a single
 * tap through Done — matches the step-flow's non-swipe-dismissible contract.
 */
@Composable
internal fun OwlQuestionnaireSuccessView(
    title: String,
    message: String,
    doneLabel: String,
    onDone: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
            .testTag(QUESTIONNAIRE_SUCCESS_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text("✓", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = {
                haptics.owlTap()
                onDone()
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 50.dp)
                .testTag(QUESTIONNAIRE_DONE_TAG),
        ) {
            Text(doneLabel)
        }
    }
}

/**
 * Segmented horizontal progress bar — one segment per question, filled through
 * and including the current index. Mirrors Swift's `OwlQuestionnaireProgressBar`.
 */
@Composable
internal fun OwlQuestionnaireProgressBar(current: Int, total: Int) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val count = maxOf(total, 1)
        for (i in 0 until count) {
            Surface(
                color = if (i <= current) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.25f),
                shape = RoundedCornerShape(2.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp),
            ) {}
        }
    }
}

internal const val QUESTIONNAIRE_CONSENT_TAG: String = "owl_q_consent"
internal const val QUESTIONNAIRE_CONSENT_ACCEPT_TAG: String = "owl_q_consent_accept"
internal const val QUESTIONNAIRE_CONSENT_LATER_TAG: String = "owl_q_consent_later"
internal const val QUESTIONNAIRE_CONSENT_NEVER_TAG: String = "owl_q_consent_never"
internal const val QUESTIONNAIRE_SUCCESS_TAG: String = "owl_q_success"
internal const val QUESTIONNAIRE_DONE_TAG: String = "owl_q_done"
