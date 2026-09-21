package app.easepod

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupCrashPolicyTest {
    private val watching = StartupAttempt(watching = true, startedAt = 100, pid = 42)

    @Test fun twoConfirmedStartupCrashesTriggerRecovery() {
        val first = StartupCrashPolicy.consecutiveCrashes(watching.copy(crashRecorded = true), emptyList())
        val second = StartupCrashPolicy.consecutiveCrashes(watching.copy(crashRecorded = true, consecutiveCrashes = first), emptyList())
        assertEquals(1, first)
        assertEquals(StartupCrashPolicy.RECOVERY_THRESHOLD, second)
    }

    @Test fun recordedCrashAndAndroidExitOnlyCountOnce() {
        assertEquals(1, StartupCrashPolicy.consecutiveCrashes(watching.copy(crashRecorded = true),
            listOf(StartupExit(110, 42, hostProcess = true, crash = true))))
    }

    @Test fun androidConfirmsNativeCrashWithoutJavaHandler() {
        assertEquals(1, StartupCrashPolicy.consecutiveCrashes(watching,
            listOf(StartupExit(110, 42, hostProcess = true, crash = true))))
    }

    @Test fun processKillsAndMissingExitHistoryBreakTheStreak() {
        val previous = watching.copy(consecutiveCrashes = 1)
        assertEquals(0, StartupCrashPolicy.consecutiveCrashes(previous, emptyList()))
        assertEquals(0, StartupCrashPolicy.consecutiveCrashes(previous,
            listOf(StartupExit(110, 42, hostProcess = true, crash = false))))
    }

    @Test fun unrelatedProcessAndOldCrashesDoNotCount() {
        assertEquals(0, StartupCrashPolicy.consecutiveCrashes(watching, listOf(
            StartupExit(110, 43, hostProcess = true, crash = true),
            StartupExit(110, 42, hostProcess = false, crash = true),
            StartupExit(90, 42, hostProcess = true, crash = true),
        )))
    }

    @Test fun crashesAfterAStableScreenAreNotStartupCrashes() {
        assertEquals(0, StartupCrashPolicy.consecutiveCrashes(watching.copy(watching = false, consecutiveCrashes = 1),
            listOf(StartupExit(110, 42, hostProcess = true, crash = true))))
    }
}
