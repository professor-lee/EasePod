package app.easepod.sampleplugin

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

/** The sample follows the same explicit host approval flow as every external music plugin. */
@RunWith(AndroidJUnit4::class)
class SampleManifestDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun pluginHasNoLauncherEntryButKeepsHostApprovalAndMusicService() {
        val pm = context.packageManager
        assertEquals(null, pm.getLaunchIntentForPackage(context.packageName))
        assertTrue(pm.queryIntentActivities(
            Intent("app.easepod.action.APPROVE_HOST").setPackage(context.packageName),
            PackageManager.ResolveInfoFlags.of(0),
        ).any { it.activityInfo.name == HostApprovalActivity::class.java.name })
        val service = pm.getServiceInfo(
            ComponentName(context, SampleMusicService::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )
        assertTrue(service.exported)
        assertNotNull(pm.queryIntentServices(
            Intent("app.easepod.action.MUSIC_PLUGIN").setPackage(context.packageName),
            PackageManager.ResolveInfoFlags.of(0),
        ).singleOrNull { it.serviceInfo.name == SampleMusicService::class.java.name })
    }
}
