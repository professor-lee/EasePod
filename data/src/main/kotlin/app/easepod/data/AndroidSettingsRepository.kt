package app.easepod.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import app.easepod.core.AppSettings
import app.easepod.core.SettingsRepository
import app.easepod.data.proto.SettingsProto
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first

class AndroidSettingsRepository(context: Context) : SettingsRepository {
    private val store = SettingsStorage.get(context.applicationContext)
    val persistedSettings = store.data.map { it.toSettings() }
    override val settings = persistedSettings
        .stateIn(SettingsStorage.scope, SharingStarted.Eagerly, AppSettings())

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.updateData { transform(it.toSettings()).validated().toProto() }
    }

    internal suspend fun current(): AppSettings = store.data.first().toSettings()
}

private object SettingsStorage {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var instance: DataStore<SettingsProto>? = null
    @Synchronized fun get(context: Context): DataStore<SettingsProto> = instance ?: DataStoreFactory.create(
        serializer = SettingsSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { AppSettings().toProto() },
        scope = scope,
        produceFile = { context.dataStoreFile("settings.pb") },
    ).also { instance = it }
}

internal object SettingsSerializer : Serializer<SettingsProto> {
    override val defaultValue: SettingsProto = AppSettings().toProto()
    override suspend fun readFrom(input: InputStream): SettingsProto = try {
        SettingsProto.parseFrom(input)
    } catch (error: InvalidProtocolBufferException) {
        throw CorruptionException("Invalid settings protobuf", error)
    }
    override suspend fun writeTo(t: SettingsProto, output: OutputStream) = t.writeTo(output)
}

internal fun AppSettings.validated() = copy(
    themeId = themeId.takeIf { it.matches(Regex("[a-zA-Z0-9._-]{1,120}")) } ?: "silver",
    wheelSensitivity = wheelSensitivity.coerceIn(1, 3),
    cacheLimitMb = cacheLimitMb.coerceIn(64, 8192),
    networkQuality = networkQuality.takeIf { it in setOf("standard", "high", "lossless", "hires") } ?: "standard",
)

internal fun SettingsProto.toSettings(): AppSettings {
    if (schemaVersion == 0) return AppSettings()
    return AppSettings(themeId, lockScreenOverlay, touchGuard, haptics, clickSound,
        wheelSensitivity, largeText, reducedMotion, safeMode, cacheLimitMb, wifiDownloadsOnly, networkQuality, fullScreen).validated()
}

internal fun AppSettings.toProto(): SettingsProto = SettingsProto.newBuilder()
    .setSchemaVersion(1).setThemeId(themeId).setLockScreenOverlay(lockScreenOverlay)
    .setTouchGuard(touchGuard).setHaptics(haptics).setClickSound(clickSound)
    .setWheelSensitivity(wheelSensitivity).setLargeText(largeText).setReducedMotion(reducedMotion)
    .setSafeMode(safeMode).setCacheLimitMb(cacheLimitMb).setWifiDownloadsOnly(wifiDownloadsOnly)
    .setNetworkQuality(networkQuality).setFullScreen(fullScreen).build()
