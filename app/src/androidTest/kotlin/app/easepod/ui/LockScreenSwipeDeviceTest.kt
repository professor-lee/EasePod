package app.easepod.ui

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LockScreenSwipeDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var enabled by mutableStateOf(true)
    private var guarded by mutableStateOf(false)
    private var epoch by mutableIntStateOf(0)
    private var unlocks = 0
    private var taps = 0
    private var navigationBottom = 0

    @Before fun showLockScreen() {
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent {
            val inset = WindowInsets.navigationBars.getBottom(LocalDensity.current)
            SideEffect { navigationBottom = inset }
            Box(Modifier.fillMaxSize().testTag("lock-screen").lockScreenSwipeToUnlock(enabled, epoch) { unlocks++ }) {
                Box(Modifier.fillMaxSize().pointerInput(guarded) {
                    if (guarded) awaitPointerEventScope {
                        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }.clickable { taps++ })
            }
        }
    }

    @Test fun bottomSwipeUnlocksOnceOnRelease() {
        compose.onNodeWithTag("lock-screen").performTouchInput {
            val start = Offset(centerX, bottom - navigationBottom - 16.dp.toPx())
            down(start)
            moveTo(start - Offset(0f, 90.dp.toPx()))
            moveTo(start - Offset(0f, 140.dp.toPx()))
        }
        compose.runOnIdle { assertEquals(0, unlocks) }
        compose.onNodeWithTag("lock-screen").performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, unlocks); assertEquals(0, taps) }
        swipeUp()
        compose.runOnIdle { assertEquals(2, unlocks) }
    }

    @Test fun touchGuardDoesNotBlockUnlock() {
        compose.runOnIdle { guarded = true }
        swipeUp()
        compose.runOnIdle { assertEquals(1, unlocks); assertEquals(0, taps) }
    }

    @Test fun bottomTapStillReachesChildControls() {
        compose.onNodeWithTag("lock-screen").performTouchInput {
            click(Offset(centerX, bottom - navigationBottom - 16.dp.toPx()))
        }
        compose.runOnIdle { assertEquals(0, unlocks); assertEquals(1, taps) }
    }

    @Test fun shortSwipeAndStartOutsideBottomEdgeDoNotUnlock() {
        swipeUp(distanceDp = 48)
        swipeUp(startInsetDp = 100)
        compose.runOnIdle { assertEquals(0, unlocks) }
    }

    @Test fun horizontalDownwardAndReversedDragsDoNotUnlock() {
        compose.onNodeWithTag("lock-screen").performTouchInput {
            val start = Offset(centerX, bottom - navigationBottom - 48.dp.toPx())
            down(start)
            moveTo(start + Offset(80.dp.toPx(), 0f))
            moveTo(start + Offset(80.dp.toPx(), -120.dp.toPx()))
            up()
            down(start)
            moveTo(start + Offset(0f, 24.dp.toPx()))
            moveTo(start - Offset(0f, 120.dp.toPx()))
            up()
            down(start)
            moveTo(start - Offset(0f, 140.dp.toPx()))
            moveTo(start - Offset(0f, 90.dp.toPx()))
            up()
        }
        compose.runOnIdle { assertEquals(0, unlocks) }
    }

    @Test fun secondPointerCancelsEvenAfterCrossingUnlockThreshold() {
        compose.onNodeWithTag("lock-screen").performTouchInput {
            val start = Offset(centerX, bottom - navigationBottom - 16.dp.toPx())
            down(0, start)
            moveTo(0, start - Offset(0f, 100.dp.toPx()))
            down(1, start + Offset(32.dp.toPx(), 0f))
            up(1)
            moveTo(0, start - Offset(0f, 140.dp.toPx()))
            up(0)
        }
        compose.runOnIdle { assertEquals(0, unlocks) }
    }

    @Test fun cancelledSwipeDoesNotUnlockAndNextGestureStillWorks() {
        compose.onNodeWithTag("lock-screen").performTouchInput {
            val start = Offset(centerX, bottom - navigationBottom - 16.dp.toPx())
            down(start)
            moveTo(start - Offset(0f, 120.dp.toPx()))
            cancel()
        }
        compose.runOnIdle { assertEquals(0, unlocks) }
        swipeUp()
        compose.runOnIdle { assertEquals(1, unlocks) }
    }

    @Test fun disabledOverlayDoesNotUnlock() {
        compose.runOnIdle { enabled = false }
        swipeUp()
        compose.runOnIdle { assertEquals(0, unlocks) }
    }

    @Test fun epochChangeCancelsGestureInProgress() {
        compose.onNodeWithTag("lock-screen").performTouchInput {
            val start = Offset(centerX, bottom - navigationBottom - 16.dp.toPx())
            down(start)
            moveTo(start - Offset(0f, 120.dp.toPx()))
        }
        compose.runOnIdle { epoch++ }
        compose.onNodeWithTag("lock-screen").performTouchInput { up() }
        compose.runOnIdle { assertEquals(0, unlocks) }
        swipeUp()
        compose.runOnIdle { assertEquals(1, unlocks) }
    }

    private fun swipeUp(startInsetDp: Int = 16, distanceDp: Int = 120) {
        compose.onNodeWithTag("lock-screen").performTouchInput {
            val start = Offset(centerX, bottom - navigationBottom - startInsetDp.dp.toPx())
            swipe(start, start - Offset(0f, distanceDp.dp.toPx()), durationMillis = 250)
        }
    }
}
