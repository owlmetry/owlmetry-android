package com.owlmetry.android.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.owlmetry.android.Owl
import com.owlmetry.android.OwlQuestionnaire
import com.owlmetry.android.OwlQuestionnaireDraft
import com.owlmetry.android.OwlQuestionnaireReceipt
import com.owlmetry.android.OwlQuestionnaireState
import com.owlmetry.android.OwlQuestionnaireTrigger
import kotlinx.coroutines.launch

/**
 * Auto-present an Owlmetry questionnaire when [trigger]'s conditions hold and
 * the user is eligible per server-side state. The Compose analog of Swift's
 * `.owlQuestionnaire(slug:trigger:...)` view modifier.
 *
 * SwiftUI implements this as a `ViewModifier` that attaches a hidden sheet to a
 * background subview. Compose has no modifier-driven presentation, so this is a
 * wrapper composable instead: render your screen as [content], and the gate
 * overlays a [ModalBottomSheet] hosting [OwlQuestionnaireView] once the trigger
 * fires and the server returns an eligible spec.
 *
 * The trigger evaluates once per composition entry and again on each
 * foreground transition (via a [LifecycleEventObserver] on `ON_RESUME`) — the
 * Android analog of Swift re-evaluating on
 * `UIApplication.willEnterForegroundNotification`. Per-process dedup
 * ([Owl.questionnaireWasShownThisProcess]) plus the in-memory [presented] flag
 * prevent re-presenting the same slug within a launch; cross-launch dedup is the
 * server's job.
 *
 * Set [forceShow] = true to bypass every local gate (trigger conditions,
 * [isEligible], per-process dedup) and ask the server to also ignore
 * `alreadyResponded` / `globallyDismissed` — intended for previewing the UI in
 * debug builds. Gate it yourself behind a debug flag so production users never
 * trip it. Mirrors Swift's `forceShow`.
 *
 * @param content the host screen to render beneath the gate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun OwlQuestionnaireGate(
    slug: String,
    modifier: Modifier = Modifier,
    trigger: OwlQuestionnaireTrigger = OwlQuestionnaireTrigger.afterLaunch,
    showsConsent: Boolean = true,
    isEligible: (() -> Boolean)? = null,
    forceShow: Boolean = false,
    strings: OwlQuestionnaireStrings = OwlQuestionnaireStrings.DEFAULT,
    onSubmitted: ((OwlQuestionnaireReceipt) -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    onDismissed: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var payload by remember(slug) { mutableStateOf<Presentation?>(null) }
    // In-process flag so a foreground re-eval can't re-present while the sheet is
    // already up (or has been shown once this composition, under forceShow).
    var presented by remember(slug) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Evaluation closure shared by the first-composition pass and the
    // foreground observer. Mirrors Swift's `evaluate(force:)`.
    suspend fun evaluate() {
        if (!forceShow) {
            if (presented) return
            if (Owl.questionnaireWasShownThisProcess(slug)) return
            if (trigger.isManual) return
            val snapshot = OwlQuestionnaireState.shared?.snapshot() ?: return
            if (!trigger.isSatisfied(snapshot)) return
            if (isEligible != null && !isEligible()) return
        }
        val result = runCatching { Owl.fetchQuestionnaire(slug = slug, force = forceShow) }.getOrNull() ?: return
        val questionnaire = result.questionnaire ?: return
        if (!forceShow) {
            Owl.markQuestionnaireShown(slug)
        }
        presented = true
        payload = Presentation(questionnaire, result.inProgress)
    }

    // First-composition evaluation, re-fired when slug or forceShow changes —
    // mirrors Swift's `.task(id: "\(slug)|\(forceShow)")`.
    LaunchedEffect(slug, forceShow) { evaluate() }

    // Foreground re-evaluation. ON_RESUME is the Android analog of iOS's
    // willEnterForeground. Under forceShow we still allow re-presentation.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, slug, forceShow) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { evaluate() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = modifier) {
        content()

        val current = payload
        if (current != null) {
            ModalBottomSheet(
                onDismissRequest = {
                    // Swipe-to-dismiss / scrim tap is treated as a mid-flow cancel.
                    onCancel?.invoke()
                    payload = null
                },
                sheetState = sheetState,
            ) {
                OwlQuestionnaireView(
                    questionnaire = current.questionnaire,
                    inProgress = current.inProgress,
                    // Resume on top of an existing draft means the user already
                    // opted in earlier — skip consent. Mirrors Swift's gate.
                    showsConsent = showsConsent && current.inProgress == null,
                    strings = strings,
                    onSubmitted = {
                        onSubmitted?.invoke(it)
                        payload = null
                    },
                    onCancel = {
                        onCancel?.invoke()
                        payload = null
                    },
                    onDismissed = {
                        onDismissed?.invoke()
                        payload = null
                    },
                )
            }
        }
    }
}

/** The spec + optional resume draft carried into the presented sheet. */
private data class Presentation(
    val questionnaire: OwlQuestionnaire,
    val inProgress: OwlQuestionnaireDraft?,
)
