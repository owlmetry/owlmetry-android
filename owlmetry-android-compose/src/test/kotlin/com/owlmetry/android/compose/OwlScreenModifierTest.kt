package com.owlmetry.android.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Behavior of the [owlScreen] Compose modifier, mirroring the Swift
 * `.owlScreen(_:)` view modifier's `onAppear` / `onDisappear` lifecycle:
 *  - entering the composition runs the appear effect exactly once;
 *  - leaving the composition runs the dispose (disappear) effect exactly once,
 *    after the screen was visible (so a `_duration_ms` could be measured);
 *  - re-keying on a new screen name runs dispose+appear for the swap.
 *
 * The appear/disappear side effects in production call `Owl.debug(...)`. To
 * observe the lifecycle deterministically without a configured transport, this
 * test mirrors the modifier's structure with an observable counter wrapper and
 * asserts the appear/dispose firing pattern — the same `DisposableEffect`-keyed
 * contract `owlScreen` relies on — then a smoke test confirms the real
 * `Modifier.owlScreen(...)` composes and renders its content.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class OwlScreenModifierTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `real owlScreen modifier composes and renders content`() {
        composeRule.setContent {
            Box(modifier = Modifier.owlScreen("Home")) {
                Text("home-content")
            }
        }
        composeRule.onNodeWithText("home-content").assertIsDisplayed()
    }

    @Test
    fun `real owlScreen modifier survives a full enter then exit lifecycle`() {
        // Drive the real production modifier through mount → unmount so its
        // actual onAppear (Owl.debug "sdk:screen_appeared") and onDispose
        // (Owl.debug "sdk:screen_disappeared" with _duration_ms) closures run.
        // Owl is unconfigured here — Owl.debug must no-op safely (matching the
        // SDK's "logging before configure is a no-op" contract) so the real
        // appear/disappear pair can't crash a host app's composition.
        var present by mutableStateOf(true)

        composeRule.setContent {
            if (present) {
                Box(modifier = Modifier.owlScreen("Detail")) {
                    Text("detail-content")
                }
            } else {
                Text("after-content")
            }
        }

        composeRule.onNodeWithText("detail-content").assertIsDisplayed()

        // Remove the decorated node → the real onDispose closure fires (computes
        // the _duration_ms delta off SystemClock.uptimeMillis and emits
        // sdk:screen_disappeared). Must not throw; the replacement renders.
        present = false
        composeRule.onNodeWithText("after-content").assertIsDisplayed()
    }

    @Test
    fun `appear fires on enter and dispose fires on exit`() {
        var appears = 0
        var disposes = 0
        var visible by mutableStateOf(true)

        composeRule.setContent {
            if (visible) {
                ScreenLifecycleProbe(
                    name = "Home",
                    onAppear = { appears++ },
                    onDispose = { disposes++ },
                )
            }
        }

        composeRule.runOnIdle {
            assertEquals("appear should fire exactly once on enter", 1, appears)
            assertEquals("dispose must not fire while visible", 0, disposes)
        }

        // Remove the screen from the composition → dispose fires.
        visible = false
        composeRule.runOnIdle {
            assertEquals("appear stays at one", 1, appears)
            assertEquals("dispose fires exactly once on exit", 1, disposes)
        }
    }

    @Test
    fun `re-keying on a new screen name re-runs appear and dispose`() {
        var appears = 0
        var disposes = 0
        var name by mutableStateOf("First")

        composeRule.setContent {
            ScreenLifecycleProbe(
                name = name,
                onAppear = { appears++ },
                onDispose = { disposes++ },
            )
        }

        composeRule.runOnIdle { assertEquals(1, appears) }

        name = "Second"
        composeRule.runOnIdle {
            // Keyed on the name → old effect disposes, new one appears.
            assertEquals("dispose fires for the old name", 1, disposes)
            assertEquals("appear fires for the new name", 2, appears)
        }
    }

    /**
     * Structural twin of the production [owlScreen] modifier: a `DisposableEffect`
     * keyed on [name] that fires [onAppear] on enter and [onDispose] on exit.
     * Lets the test count appear/dispose firings without a configured `Owl`
     * transport — the firing pattern is exactly what `owlScreen` relies on.
     */
    @androidx.compose.runtime.Composable
    private fun ScreenLifecycleProbe(
        name: String,
        onAppear: () -> Unit,
        onDispose: () -> Unit,
    ) {
        androidx.compose.runtime.DisposableEffect(name) {
            onAppear()
            onDispose { onDispose() }
        }
        // A trivial node so the probe occupies the tree.
        remember(name) { name }
        Box(modifier = Modifier) {}
    }
}
