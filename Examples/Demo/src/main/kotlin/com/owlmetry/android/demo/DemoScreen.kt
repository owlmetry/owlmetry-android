package com.owlmetry.android.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.owlmetry.android.Owl
import com.owlmetry.android.OwlAttachment
import com.owlmetry.android.OwlQuestionnaire
import com.owlmetry.android.OwlQuestionnaireDraft
import com.owlmetry.android.compose.OwlFeedbackActionsPlacement
import com.owlmetry.android.compose.OwlFeedbackView
import com.owlmetry.android.compose.OwlQuestionnaireGate
import com.owlmetry.android.compose.OwlQuestionnaireView
import com.owlmetry.android.compose.owlScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Accent colors mirroring the SwiftUI demo's per-button `.tint(...)` choices so
// the Android demo reads section-for-section like its iOS sibling.
private val Indigo = Color(0xFF5C6BC0)
private val Green = Color(0xFF2E7D32)
private val Red = Color(0xFFC62828)
private val Orange = Color(0xFFEF6C00)
private val Purple = Color(0xFF7B1FA2)
private val Cyan = Color(0xFF00838F)
private val BlueTint = Color(0xFF1565C0)
private val GrayTint = Color(0xFF616161)

/**
 * The root demo screen — the Android analog of the Swift demo's `ContentView`,
 * mirroring its sections one-for-one:
 *   Run Full Demo · Logging · Metrics · Funnel Demo · Identity · User Properties
 *   · Attribution (N/A on Android) · Feedback · Questionnaires · Backend Demo
 *   · Event Log.
 *
 * The whole screen is wrapped in [OwlQuestionnaireGate] (the auto-trigger, the
 * analog of SwiftUI's `.owlQuestionnaire(...)` modifier), and the root column
 * carries [Modifier.owlScreen] = "Home" for automatic screen tracking.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemoScreen() {
    val scope = rememberCoroutineScope()

    // --- Shared state (mirrors ContentView's @State) ---
    val eventLog = remember { mutableStateListOf<String>() }
    fun appendLog(message: String) {
        eventLog.add(message)
    }

    var userId by rememberSaveable { mutableStateOf("") }
    var customKey by rememberSaveable { mutableStateOf("") }
    var customValue by rememberSaveable { mutableStateOf("") }
    var logMessage by rememberSaveable { mutableStateOf("Hello from the demo app") }
    var greetName by rememberSaveable { mutableStateOf("World") }
    var isRunningDemo by remember { mutableStateOf(false) }
    var lastFeedbackId by remember { mutableStateOf<String?>(null) }

    // Feedback presentation
    var showFeedbackSheet by remember { mutableStateOf(false) }
    var showFeedbackEmbedded by rememberSaveable { mutableStateOf(false) }

    // Questionnaire controls (mirror ContentView's questionnaire @State)
    var questionnaireSlug by rememberSaveable { mutableStateOf("dev-demo-survey") }
    var questionnaireEligibleToggle by rememberSaveable { mutableStateOf(true) }
    var questionnaireShowsConsent by rememberSaveable { mutableStateOf(true) }
    var questionnaireForceShow by rememberSaveable { mutableStateOf(false) }
    var manualPresentationUsesConsent by remember { mutableStateOf(true) }
    var manualQuestionnaire by remember { mutableStateOf<OwlQuestionnaire?>(null) }
    var manualQuestionnaireInProgress by remember { mutableStateOf<OwlQuestionnaireDraft?>(null) }
    var lastQuestionnaireId by remember { mutableStateOf<String?>(null) }
    var lastDismissDate by remember { mutableStateOf<Date?>(null) }
    // Re-read launch/foreground counters on each questionnaire action so the
    // status block reflects the latest values.
    var counterTick by remember { mutableStateOf(0) }

    // Record the "screen opened" metric once, like Swift's `.onAppear`.
    LaunchedEffect(Unit) {
        Owl.recordMetric("demo_app_opened")
        appendLog("App opened — recorded demo_app_opened")
    }

    suspend fun loadAndPresentQuestionnaire() {
        try {
            val result = Owl.fetchQuestionnaire(slug = questionnaireSlug)
            val q = result.questionnaire
            if (q != null) {
                manualQuestionnaire = q
                manualQuestionnaireInProgress = result.inProgress
                val draft = result.inProgress
                if (draft != null) {
                    appendLog(
                        "[QUESTIONNAIRE] resumed slug=$questionnaireSlug " +
                            "responseId=${draft.responseId} answered=${draft.answers.size}",
                    )
                } else {
                    appendLog("[QUESTIONNAIRE] fetched slug=$questionnaireSlug")
                }
            } else {
                val reason = result.ineligibleReason?.wire ?: "unknown"
                appendLog("[QUESTIONNAIRE] not eligible — reason=$reason")
            }
        } catch (e: Throwable) {
            appendLog("[QUESTIONNAIRE] fetch failed: ${e.message}")
        }
    }

    suspend fun runFullDemo() {
        appendLog("— Full Demo Started —")

        // 1. info event
        Owl.info("Demo started", screenName = "DemoScreen")
        appendLog("[INFO] Demo started")

        // 2. record a metric
        Owl.recordMetric("demo_full_test")
        appendLog("[METRIC] demo_full_test")

        // 2b. lifecycle metric
        val op = Owl.startOperation("demo-operation")
        appendLog("[METRIC] demo-operation:start")
        delay(500)
        op.complete(attributes = mapOf("result" to "success"))
        appendLog("[METRIC] demo-operation:complete")

        // 3. backend greet → 2 info events server-side
        val greetResult = callBackend(path = "/api/greet", body = mapOf("name" to "OwlBot"))
        appendLog("[BACKEND] greet: $greetResult")

        // 4. pause between backend calls
        delay(1_000)

        // 5. backend checkout → info + warn + error server-side
        val checkoutResult = callBackend(path = "/api/checkout", body = mapOf("item" to "Premium Plan"))
        appendLog("[BACKEND] checkout: $checkoutResult")

        // 6. funnel demo: onboarding flow
        Owl.step("welcome-screen"); appendLog("[STEP] welcome-screen")
        delay(300)
        Owl.step("create-account"); appendLog("[STEP] create-account")
        delay(300)
        Owl.step("complete-profile"); appendLog("[STEP] complete-profile")

        // 7. user properties
        Owl.setUserProperties(mapOf("plan" to "premium", "rc_subscriber" to "true"))
        appendLog("[PROPS] plan=premium, rc_subscriber=true")
        delay(500)

        // 8. error event for investigation
        Owl.error("Simulated client crash", screenName = "DemoScreen")
        appendLog("[ERROR] Simulated client crash")

        appendLog("— Full Demo Complete —")
    }

    // The auto-trigger gate wraps the whole screen — the analog of SwiftUI's
    // `.owlQuestionnaire(slug:trigger:...)` modifier on the NavigationStack.
    OwlQuestionnaireGate(
        slug = questionnaireSlug,
        trigger = com.owlmetry.android.OwlQuestionnaireTrigger.afterLaunch,
        showsConsent = questionnaireShowsConsent,
        isEligible = { questionnaireEligibleToggle },
        forceShow = questionnaireForceShow,
        onSubmitted = { receipt ->
            lastQuestionnaireId = receipt.id
            appendLog("[QUESTIONNAIRE] auto-gate submitted id=${receipt.id}")
        },
        onCancel = { appendLog("[QUESTIONNAIRE] auto-gate cancelled / declined later") },
        onDismissed = { appendLog("[QUESTIONNAIRE] auto-gate dismissed globally") },
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .owlScreen("Home")
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
        ) {
            item {
                Text(
                    "Owlmetry Demo",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            // ---------------------- Run Full Demo ----------------------
            item {
                SectionCard("Run Full Demo") {
                    TintedButton(
                        text = "Run Full Demo",
                        tint = Indigo,
                        enabled = !isRunningDemo,
                        trailingSpinner = isRunningDemo,
                    ) {
                        if (isRunningDemo) return@TintedButton
                        isRunningDemo = true
                        scope.launch {
                            runFullDemo()
                            isRunningDemo = false
                        }
                    }
                }
            }

            // ---------------------- Logging ----------------------
            item {
                SectionCard("Logging") {
                    OutlinedTextField(
                        value = logMessage,
                        onValueChange = { logMessage = it },
                        label = { Text("Message") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    TintedButton("Info", BlueTint) {
                        Owl.info(logMessage, screenName = "DemoScreen")
                        appendLog("[INFO] $logMessage")
                    }
                    TintedButton("Debug", GrayTint) {
                        Owl.debug(logMessage, screenName = "DemoScreen")
                        appendLog("[DEBUG] $logMessage")
                    }
                    TintedButton("Warn", Orange) {
                        Owl.warn(logMessage, screenName = "DemoScreen")
                        appendLog("[WARN] $logMessage")
                    }
                    TintedButton("Error", Red) {
                        Owl.error(logMessage, screenName = "DemoScreen")
                        appendLog("[ERROR] $logMessage")
                    }
                }
            }

            // ---------------------- Metrics ----------------------
            item {
                SectionCard("Metrics") {
                    OutlinedTextField(
                        value = customKey,
                        onValueChange = { customKey = it },
                        label = { Text("Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = customValue,
                        onValueChange = { customValue = it },
                        label = { Text("Value") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    TintedButton("Record Metric", Indigo) {
                        val attrs: Map<String, String?> =
                            if (customKey.isEmpty()) emptyMap() else mapOf(customKey to customValue)
                        Owl.recordMetric("demo_custom_event", attributes = attrs)
                        appendLog("[METRIC] demo_custom_event $attrs")
                    }
                    TintedButton("Simulate Conversion", Green) {
                        val op = Owl.startOperation("photo-conversion", attributes = mapOf("input_format" to "heic"))
                        appendLog("[METRIC] photo-conversion:start")
                        scope.launch {
                            delay(1_000)
                            op.complete(attributes = mapOf("output_format" to "jpeg", "output_size" to "524288"))
                            appendLog("[METRIC] photo-conversion:complete")
                        }
                    }
                    TintedButton("Simulate Failed Operation", Red) {
                        val op = Owl.startOperation("photo-conversion", attributes = mapOf("input_format" to "raw"))
                        appendLog("[METRIC] photo-conversion:start")
                        op.fail(error = "unsupported_format")
                        appendLog("[METRIC] photo-conversion:fail")
                    }
                    TintedButton("Simulate Failure with Attachment", Red) {
                        // Synthesised bytes so the demo needs no real file — in
                        // real code you'd pass the actual file that failed.
                        val fakeInput = "fake broken image bytes — demo only".toByteArray()
                        Owl.error(
                            "photo conversion failed",
                            screenName = "DemoScreen",
                            attributes = mapOf("input_format" to "heic", "stage" to "decode"),
                            attachments = listOf(
                                OwlAttachment.bytes(
                                    bytes = fakeInput,
                                    name = "broken-input.heic",
                                    contentType = "image/heic",
                                ),
                            ),
                        )
                        appendLog("[ERROR+ATTACHMENT] photo conversion failed")
                    }
                }
            }

            // ---------------------- Funnel Demo ----------------------
            item {
                SectionCard("Funnel Demo") {
                    TintedButton("1. Welcome Screen", Purple) {
                        Owl.step("welcome-screen"); appendLog("[STEP] welcome-screen")
                    }
                    TintedButton("2. Create Account", Purple) {
                        Owl.step("create-account"); appendLog("[STEP] create-account")
                    }
                    TintedButton("3. Complete Profile", Purple) {
                        Owl.step("complete-profile"); appendLog("[STEP] complete-profile")
                    }
                    TintedButton("4. First Post", Purple) {
                        Owl.step("first-post"); appendLog("[STEP] first-post")
                    }
                }
            }

            // ---------------------- Identity ----------------------
            item {
                SectionCard("Identity") {
                    OutlinedTextField(
                        value = userId,
                        onValueChange = { userId = it },
                        label = { Text("User ID") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    )
                    TintedButton("Set User", Indigo, enabled = userId.isNotEmpty()) {
                        if (userId.isEmpty()) return@TintedButton
                        Owl.setUser(userId)
                        appendLog("Set user: $userId")
                    }
                    TintedButton("Clear User", GrayTint) {
                        Owl.clearUser()
                        appendLog("Cleared user (kept anon ID)")
                    }
                    TintedButton("Clear + New Anonymous ID", Red) {
                        Owl.clearUser(newAnonymousId = true)
                        appendLog("Cleared user + new anonymous ID")
                    }
                }
            }

            // ---------------------- User Properties ----------------------
            item {
                SectionCard("User Properties") {
                    OutlinedTextField(
                        value = customKey,
                        onValueChange = { customKey = it },
                        label = { Text("Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    )
                    OutlinedTextField(
                        value = customValue,
                        onValueChange = { customValue = it },
                        label = { Text("Value") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    )
                    TintedButton("Set Property", Indigo, enabled = customKey.isNotEmpty()) {
                        if (customKey.isEmpty()) return@TintedButton
                        Owl.setUserProperties(mapOf(customKey to customValue))
                        appendLog("[PROPS] $customKey = ${if (customValue.isEmpty()) "(deleted)" else customValue}")
                        customKey = ""
                        customValue = ""
                    }
                    TintedButton("Set Demo Properties", Purple) {
                        Owl.setUserProperties(
                            mapOf(
                                "plan" to "premium",
                                "rc_subscriber" to "true",
                                "rc_product" to "monthly_pro",
                            ),
                        )
                        appendLog("[PROPS] plan=premium, rc_subscriber=true, rc_product=monthly_pro")
                    }
                }
            }

            // ---------------------- Attribution ----------------------
            item {
                SectionCard("Attribution") {
                    Text(
                        "Apple Search Ads attribution is auto-captured by the Swift SDK on " +
                            "iOS. It relies on Apple's AdServices framework, which has no Android " +
                            "equivalent — so this section is not applicable on Android.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Android install attribution (Play Install Referrer, etc.) is a future, " +
                            "platform-specific addition and is not wired into this demo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---------------------- Feedback ----------------------
            item {
                SectionCard("Feedback") {
                    TintedButton("Send Feedback (Sheet)", Cyan) {
                        showFeedbackSheet = true
                    }
                    OutlinedButton(
                        onClick = { showFeedbackEmbedded = !showFeedbackEmbedded },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (showFeedbackEmbedded) "Hide Embedded Feedback" else "Send Feedback (Embedded)")
                    }
                    if (showFeedbackEmbedded) {
                        // Embedded usage: inline actions, no contact fields —
                        // mirrors the iOS embedded NavigationLink variant.
                        OwlFeedbackView(
                            showsContactFields = false,
                            actionsPlacement = OwlFeedbackActionsPlacement.INLINE,
                            onSubmitted = { receipt ->
                                lastFeedbackId = receipt.id
                                appendLog("[FEEDBACK] sent id=${receipt.id}")
                                showFeedbackEmbedded = false
                            },
                            onCancel = { showFeedbackEmbedded = false },
                        )
                    }
                    lastFeedbackId?.let {
                        Text(
                            "Last feedback id: $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ---------------------- Questionnaires ----------------------
            item {
                SectionCard("Questionnaires") {
                    OutlinedTextField(
                        value = questionnaireSlug,
                        onValueChange = { questionnaireSlug = it },
                        label = { Text("Slug") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    )
                    ToggleRow("Eligible (gates auto gate)", questionnaireEligibleToggle) {
                        questionnaireEligibleToggle = it
                    }
                    ToggleRow("Auto gate shows consent prompt", questionnaireShowsConsent) {
                        questionnaireShowsConsent = it
                    }
                    ToggleRow("Force show (bypass triggers / dismissed)", questionnaireForceShow) {
                        questionnaireForceShow = it
                    }
                    TintedButton("Show now (with consent)", Orange) {
                        manualPresentationUsesConsent = true
                        scope.launch {
                            loadAndPresentQuestionnaire()
                            counterTick++
                        }
                    }
                    TintedButton("Show now (questions only)", Orange) {
                        manualPresentationUsesConsent = false
                        scope.launch {
                            loadAndPresentQuestionnaire()
                            counterTick++
                        }
                    }
                    TintedButton("Dismiss globally", Red) {
                        scope.launch {
                            try {
                                val date = Owl.dismissQuestionnaires()
                                lastDismissDate = date
                                appendLog("[QUESTIONNAIRE] dismissed globally at $date")
                            } catch (e: Throwable) {
                                appendLog("[QUESTIONNAIRE] dismiss failed: ${e.message}")
                            }
                        }
                    }
                    TintedButton("Reset everything for fresh test", Red) {
                        Owl.debugClearShownQuestionnaires()
                        Owl.clearUser(newAnonymousId = true)
                        lastQuestionnaireId = null
                        lastDismissDate = null
                        counterTick++
                        appendLog("[QUESTIONNAIRE] full reset — fresh anon ID, in-process cache cleared")
                        appendLog("→ tap a \"Show now\" button, or background + foreground to re-fire the auto-gate")
                    }

                    // Status block — re-read counters whenever counterTick changes.
                    @Suppress("UNUSED_EXPRESSION") counterTick
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "Launch count: ${Owl.launchCount}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Foreground count: ${Owl.foregroundCount}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Owl.firstLaunchAt?.let { millis ->
                            Text(
                                "First launch: ${formatTimestamp(millis)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        lastQuestionnaireId?.let {
                            Text(
                                "Last response: $it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        lastDismissDate?.let {
                            Text(
                                "Last dismiss: $it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // ---------------------- Backend Demo ----------------------
            item {
                SectionCard("Backend Demo") {
                    OutlinedTextField(
                        value = greetName,
                        onValueChange = { greetName = it },
                        label = { Text("Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    TintedButton("Greet", Green) {
                        Owl.recordMetric("backend_greet_tapped", attributes = mapOf("name" to greetName))
                        appendLog("[METRIC] backend_greet_tapped")
                        scope.launch {
                            val result = callBackend(
                                path = "/api/greet",
                                body = mapOf("name" to greetName, "userId" to userId.ifEmpty { null }),
                            )
                            appendLog("[BACKEND] $result")
                        }
                    }
                    TintedButton("Checkout (simulated failure)", Orange) {
                        Owl.recordMetric("backend_checkout_tapped", attributes = mapOf("item" to "Widget"))
                        appendLog("[METRIC] backend_checkout_tapped")
                        scope.launch {
                            val result = callBackend(
                                path = "/api/checkout",
                                body = mapOf("item" to "Widget", "userId" to userId.ifEmpty { null }),
                            )
                            appendLog("[BACKEND] $result")
                        }
                    }
                }
            }

            // ---------------------- Event Log ----------------------
            item {
                SectionCard("Event Log") {
                    if (eventLog.isEmpty()) {
                        Text(
                            "No events sent yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        // Newest first, like the Swift demo's reversed log.
                        for (entry in eventLog.reversed()) {
                            Text(
                                entry,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                        }
                    }
                }
            }
        }
    }

    // Feedback bottom sheet — the analog of the iOS `.sheet` presenting
    // OwlFeedbackView with default (toolbar) actions + contact fields.
    if (showFeedbackSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showFeedbackSheet = false },
            sheetState = sheetState,
        ) {
            OwlFeedbackView(
                name = userId.ifEmpty { null },
                onSubmitted = { receipt ->
                    lastFeedbackId = receipt.id
                    appendLog("[FEEDBACK] sent id=${receipt.id}")
                    showFeedbackSheet = false
                },
                onCancel = { showFeedbackSheet = false },
            )
        }
    }

    // Manual questionnaire presentation — the analog of the iOS `.sheet`
    // presenting OwlQuestionnaireView from a fetched spec.
    val manualQ = manualQuestionnaire
    if (manualQ != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = {
                appendLog("[QUESTIONNAIRE] manual cancelled / declined later")
                manualQuestionnaire = null
                manualQuestionnaireInProgress = null
            },
            sheetState = sheetState,
        ) {
            OwlQuestionnaireView(
                questionnaire = manualQ,
                inProgress = manualQuestionnaireInProgress,
                // Skip consent when resuming a draft — the user opted in earlier.
                showsConsent = manualPresentationUsesConsent && manualQuestionnaireInProgress == null,
                onSubmitted = { receipt ->
                    lastQuestionnaireId = receipt.id
                    appendLog("[QUESTIONNAIRE] manual submitted id=${receipt.id}")
                    manualQuestionnaire = null
                    manualQuestionnaireInProgress = null
                },
                onCancel = {
                    appendLog("[QUESTIONNAIRE] manual cancelled / declined later")
                    manualQuestionnaire = null
                    manualQuestionnaireInProgress = null
                },
                onDismissed = {
                    appendLog("[QUESTIONNAIRE] manual dismissed globally")
                    manualQuestionnaire = null
                    manualQuestionnaireInProgress = null
                },
            )
        }
    }
}

/** A titled Material3 card grouping one section's controls — the analog of a SwiftUI `Section`. */
@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** A full-width tinted button mirroring SwiftUI's `Button { }.tint(color)`, with an optional trailing spinner. */
@Composable
private fun TintedButton(
    text: String,
    tint: Color,
    enabled: Boolean = true,
    trailingSpinner: Boolean = false,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = tint),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text)
            if (trailingSpinner) {
                CircularProgressIndicator(
                    modifier = Modifier.height(18.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

/** A label + Material3 Switch row — the analog of SwiftUI's `Toggle`. */
@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private val timestampFormat = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault())

/** Format an epoch-millis instant for the questionnaire status block. */
private fun formatTimestamp(millis: Long): String = timestampFormat.format(Date(millis))
