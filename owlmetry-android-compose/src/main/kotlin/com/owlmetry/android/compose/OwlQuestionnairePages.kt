package com.owlmetry.android.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.owlmetry.android.OwlQuestionnaireQuestion

/**
 * The per-question-type page composables for the questionnaire flow — the
 * Compose analog of the Swift questionnaire `Pages` views. Each renders a shared
 * [QuestionHeader] plus the type-specific input, and writes answers back through
 * a callback (the Compose analog of SwiftUI's `@Binding`). Internal — composed
 * by [OwlQuestionnaireFlowContainer]; not part of the public surface.
 */

/** Shared question-page header: title + optional subtitle. Mirrors Swift's `questionHeader`. */
@Composable
internal fun QuestionHeader(title: String, subtitle: String?) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.testTag(QUESTIONNAIRE_TITLE_TAG),
        )
        if (!subtitle.isNullOrEmpty()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun OwlQuestionnaireTextPage(
    question: OwlQuestionnaireQuestion.Text,
    value: String,
    onValueChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        QuestionHeader(question.title, question.subtitle)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (question.multiline) Modifier.heightIn(min = 160.dp) else Modifier)
                .testTag(QUESTIONNAIRE_TEXT_TAG),
            placeholder = { question.placeholder?.let { Text(it) } },
            singleLine = !question.multiline,
        )
        Spacer(Modifier.heightIn(min = 8.dp))
    }
}

@Composable
internal fun OwlQuestionnaireSingleChoicePage(
    question: OwlQuestionnaireQuestion.SingleChoice,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        QuestionHeader(question.title, question.subtitle)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (option in question.options) {
                ChoiceRow(
                    label = option.label,
                    isSelected = selected == option.id,
                    multi = false,
                    onClick = {
                        haptics.owlTap()
                        onSelect(option.id)
                    },
                )
            }
        }
        Spacer(Modifier.heightIn(min = 8.dp))
    }
}

@Composable
internal fun OwlQuestionnaireMultiChoicePage(
    question: OwlQuestionnaireQuestion.MultiChoice,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        QuestionHeader(question.title, question.subtitle)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (option in question.options) {
                ChoiceRow(
                    label = option.label,
                    isSelected = option.id in selected,
                    multi = true,
                    onClick = {
                        haptics.owlTap()
                        onToggle(option.id)
                    },
                )
            }
        }
        Spacer(Modifier.heightIn(min = 8.dp))
    }
}

/**
 * A single selectable option row. [multi] picks the checkbox vs radio glyph
 * (using a filled/hollow disc) — Material symbols equivalents of Swift's
 * `checkmark.circle.fill` / `checkmark.square.fill`. Selected rows tint their
 * fill + border with the theme primary, mirroring Swift's accent overlay.
 */
@Composable
private fun ChoiceRow(
    label: String,
    isSelected: Boolean,
    multi: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = shape,
        color = if (isSelected) scheme.primary.copy(alpha = 0.10f) else scheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick)
            .testTag(if (multi) QUESTIONNAIRE_MULTI_OPTION_TAG else QUESTIONNAIRE_SINGLE_OPTION_TAG)
            .semantics {
                this.selected = isSelected
                contentDescription = label
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, modifier = Modifier.weight(1f), color = scheme.onSurface)
            SelectionGlyph(isSelected = isSelected, tint = scheme.primary, idle = scheme.onSurfaceVariant)
        }
    }
}

/** Filled disc when selected, hollow ring when not. Avoids a material-icons dependency. */
@Composable
private fun SelectionGlyph(isSelected: Boolean, tint: Color, idle: Color) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(if (isSelected) tint else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        if (!isSelected) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(idle.copy(alpha = 0.18f)),
            )
        }
    }
}

@Composable
internal fun OwlQuestionnaireRatingPage(
    question: OwlQuestionnaireQuestion.Rating,
    value: Int?,
    onRate: (Int) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        QuestionHeader(question.title, question.subtitle)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(QUESTIONNAIRE_RATING_TAG),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            val scale = if (question.scale > 0) question.scale else 5
            for (star in 1..scale) {
                val filled = (value ?: 0) >= star
                StarButton(
                    filled = filled,
                    onClick = {
                        haptics.owlTap()
                        onRate(star)
                    },
                    contentDescription = star.toString(),
                )
            }
        }
    }
}

/** A tappable star — filled vs hollow rounded square stand-in. */
@Composable
private fun StarButton(filled: Boolean, onClick: () -> Unit, contentDescription: String) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (filled) "★" else "☆", // ★ / ☆
            style = MaterialTheme.typography.headlineMedium,
            color = if (filled) scheme.primary else scheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun OwlQuestionnaireNpsPage(
    question: OwlQuestionnaireQuestion.Nps,
    value: Int?,
    lowLabel: String,
    highLabel: String,
    onScore: (Int) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        QuestionHeader(question.title, question.subtitle)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(QUESTIONNAIRE_NPS_TAG),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (score in 0..10) {
                NpsChip(
                    score = score,
                    isSelected = value == score,
                    onClick = {
                        haptics.owlTap()
                        onScore(score)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(lowLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(highLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NpsChip(
    score: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(if (isSelected) scheme.primary else scheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .semantics {
                this.selected = isSelected
                contentDescription = score.toString()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            score.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = if (isSelected) scheme.onPrimary else scheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
}

// Test tags — let the Compose UI test locate inputs without depending on text.
internal const val QUESTIONNAIRE_TITLE_TAG: String = "owl_q_title"
internal const val QUESTIONNAIRE_TEXT_TAG: String = "owl_q_text"
internal const val QUESTIONNAIRE_SINGLE_OPTION_TAG: String = "owl_q_single_option"
internal const val QUESTIONNAIRE_MULTI_OPTION_TAG: String = "owl_q_multi_option"
internal const val QUESTIONNAIRE_RATING_TAG: String = "owl_q_rating"
internal const val QUESTIONNAIRE_NPS_TAG: String = "owl_q_nps"
