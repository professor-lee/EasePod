package app.easepod.netease

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies that the optional plugin is discoverable by EasePod without adding a launcher icon. */
@RunWith(AndroidJUnit4::class)
class NeteaseManifestDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun pluginHasNoLauncherEntryButKeepsHostApprovalAndMusicService() {
        val pm = context.packageManager
        assertEquals(null, pm.getLaunchIntentForPackage(context.packageName))

        val approval = pm.queryIntentActivities(
            Intent("app.easepod.action.APPROVE_HOST").setPackage(context.packageName),
            PackageManager.ResolveInfoFlags.of(0),
        )
        assertTrue("host approval action must remain available", approval.any {
            it.activityInfo.name == HostApprovalActivity::class.java.name
        })

        val service = pm.getServiceInfo(
            ComponentName(context, NeteaseMusicService::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )
        assertTrue(service.exported)
        assertTrue(service.enabled)
        assertNotNull(pm.queryIntentServices(
            Intent("app.easepod.action.MUSIC_PLUGIN").setPackage(context.packageName),
            PackageManager.ResolveInfoFlags.of(0),
        ).singleOrNull { it.serviceInfo.name == NeteaseMusicService::class.java.name })
    }
}
