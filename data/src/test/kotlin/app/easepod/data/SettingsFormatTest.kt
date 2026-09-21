package app.easepod.data

import app.easepod.core.AppSettings
import app.easepod.data.proto.SettingsProto
import org.junit.Assert.*
import org.junit.Test

class SettingsFormatTest {
    @Test fun legacySettingsKeepExistingValuesAndUseStandardQuality() {
        val old = SettingsProto.newBuilder().setSchemaVersion(1).setThemeId("black")
            .setWheelSensitivity(3).setCacheLimitMb(512).setTouchGuard(true).build()
        val restored = old.toSettings()
        assertEquals("standard", restored.networkQuality)
        assertFalse(restored.fullScreen)
        assertEquals("black", restored.themeId)
        assertTrue(restored.touchGuard)
        assertEquals(3, restored.wheelSensitivity)
        assertEquals(512, restored.cacheLimitMb)
    }

    @Test fun requestedQualityRoundTripsWithoutChangingOtherPreferences() {
        val original = AppSettings(networkQuality = "lossless", touchGuard = true, wifiDownloadsOnly = false)
        assertEquals(original, SettingsProto.parseFrom(original.toProto().toByteArray()).toSettings())
    }

    @Test fun fullScreenRoundTripsWithoutChangingOtherPreferences() {
        val original = AppSettings(fullScreen = true, themeId = "black", lockScreenOverlay = true, networkQuality = "high")
        assertEquals(original, SettingsProto.parseFrom(original.toProto().toByteArray()).toSettings())
    }

    @Test fun unrecognizedStoredQualityFallsBackToStandard() {
        val stored = AppSettings().toProto().toBuilder().setNetworkQuality("unsupported").build()
        assertEquals("standard", stored.toSettings().networkQuality)
    }
}
