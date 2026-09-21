package app.easepod

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Process

internal data class StartupAttempt(
    val watching: Boolean = false,
    val crashRecorded: Boolean = false,
    val startedAt: Long = 0,
    val pid: Int = 0,
    val consecutiveCrashes: Int = 0,
)

internal data class StartupExit(val timestamp: Long, val pid: Int, val hostProcess: Boolean, val crash: Boolean)

internal object StartupCrashPolicy {
    const val RECOVERY_THRESHOLD = 2

    fun consecutiveCrashes(previous: StartupAttempt, exits: List<StartupExit>): Int {
        if (!previous.watching) return 0
        val confirmed = previous.crashRecorded || exits.any {
            it.hostProcess && it.pid == previous.pid && it.timestamp >= previous.startedAt && it.crash
        }
        return if (confirmed) (previous.consecutiveCrashes + 1).coerceAtMost(RECOVERY_THRESHOLD) else 0
    }
}

// These small markers must reach disk before the process can crash or terminate.
@SuppressLint("ApplySharedPref")
internal class StartupCrashGuard(context: Context) {
    private val preferences = context.getSharedPreferences("startup-recovery", Context.MODE_PRIVATE)
    private var watching = false
    private var stable = false
    val recoveryRequired: Boolean

    init {
        val previous = StartupAttempt(
            preferences.getBoolean("watching", false),
            preferences.getBoolean("crashRecorded", false),
            preferences.getLong("startedAt", 0),
            preferences.getInt("pid", 0),
            preferences.getInt("consecutiveCrashes", 0),
        )
        val exits = if (previous.watching) runCatching {
            context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, previous.pid, 8).map {
                    StartupExit(it.timestamp, it.pid, it.processName == Application.getProcessName(),
                        it.reason == ApplicationExitInfo.REASON_CRASH || it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE)
                }
        }.getOrDefault(emptyList()) else emptyList()
        val consecutive = StartupCrashPolicy.consecutiveCrashes(previous, exits)
        recoveryRequired = consecutive >= StartupCrashPolicy.RECOVERY_THRESHOLD
        preferences.edit().putBoolean("watching", false).putBoolean("crashRecorded", false)
            .putInt("consecutiveCrashes", consecutive).commit()

        val platformHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            // Record a confirmed crash before Android terminates the process; a leftover marker alone is insufficient.
            runCatching { recordCrash() }
            platformHandler?.uncaughtException(thread, failure)
        }
    }

    @Synchronized fun watchPluginStartup() {
        if (stable || watching) return
        watching = true
        preferences.edit().putBoolean("watching", true).putBoolean("crashRecorded", false)
            .putLong("startedAt", System.currentTimeMillis()).putInt("pid", Process.myPid()).commit()
    }

    @Synchronized private fun recordCrash() {
        if (watching) preferences.edit().putBoolean("crashRecorded", true).commit()
    }

    @Synchronized fun screenStable() {
        if (stable) return
        stable = true
        watching = false
        preferences.edit().putBoolean("watching", false).putBoolean("crashRecorded", false)
            .putInt("consecutiveCrashes", 0).commit()
    }
}
