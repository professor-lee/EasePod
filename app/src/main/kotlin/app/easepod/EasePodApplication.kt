package app.easepod

import android.app.Application
import android.app.Activity
import android.os.Bundle
import app.easepod.data.AndroidLibraryRepository
import app.easepod.data.AndroidSettingsRepository
import app.easepod.playback.*
import app.easepod.plugins.PluginManager
import app.easepod.plugins.ThemeManager
import app.easepod.core.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import coil.ImageLoader
import coil.ImageLoaderFactory

class EasePodApplication : Application(), ImageLoaderFactory {
    lateinit var library: AndroidLibraryRepository; private set
    lateinit var settings: AndroidSettingsRepository; private set
    lateinit var player: AndroidPlaybackController; private set
    lateinit var plugins: PluginManager; private set
    lateinit var themes: ThemeManager; private set
    private val artwork by lazy { ArtworkLoader(this) }
    override fun newImageLoader(): ImageLoader = artwork.images
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val playbackAuthorizationReady = CompletableDeferred<Unit>()
    private val startupResourcesReady = CompletableDeferred<Unit>()
    private lateinit var startupCrashGuard: StartupCrashGuard
    private var startupStartedActivities = 0
    private var startupConfigurationChanging = false
    private val startupActivityObserver = object : ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) {
            startupStartedActivities++
            startupConfigurationChanging = false
        }
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) {
            startupStartedActivities = (startupStartedActivities - 1).coerceAtLeast(0)
            startupConfigurationChanging = activity.isChangingConfigurations
            if (!startupConfigurationChanging) checkBackgroundStartupStability()
        }
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
    suspend fun awaitStartupResources() = startupResourcesReady.await()
    fun startupScreenStable() {
        startupCrashGuard.screenStable()
        unregisterActivityLifecycleCallbacks(startupActivityObserver)
    }
    private fun checkBackgroundStartupStability() = scope.launch {
        startupResourcesReady.await()
        delay(2_000)
        // A MediaSession or Worker may start the process without a screen, or the user may leave immediately.
        if (startupStartedActivities == 0 && !startupConfigurationChanging) startupScreenStable()
    }
    override fun onCreate() {
        super.onCreate()
        startupCrashGuard = StartupCrashGuard(this)
        registerActivityLifecycleCallbacks(startupActivityObserver)
        settings = AndroidSettingsRepository(this)
        library = AndroidLibraryRepository(this)
        // Music services are discovered through the native external-plugin contract.
        // The optional网易云 APK is distributed beside EasePod and can be installed independently.
        plugins = PluginManager(this)
        themes = ThemeManager(this, autoLoad = false)
        player = PlaybackRuntime.install(this, PlaybackDependencies(library, library, settings,
            object : StreamResolver {
                override suspend fun resolve(track: Track): PlayableSource {
                    val source = plugins.resolve(track, settings.persistedSettings.first().networkQuality)
                    return PlayableSource(source.uri, source.headers, source.cachePolicy != "NO_STORE",
                        if (source.cachePolicy == "OFFLINE") OfflineGrant(source.expiresAt, source.contentLength, source.contentSha256) else null,
                        allowedHosts = source.allowedHosts, expiresAtMs = source.expiresAt,
                        canSeek = source.canSeek, quality = source.quality, revision = source.revision)
                }
                override suspend fun offlinePolicy(track: Track): OfflineGrant? {
                    val source = plugins.resolve(track, settings.persistedSettings.first().networkQuality)
                    return if (source.cachePolicy == "OFFLINE") OfflineGrant(source.expiresAt, source.contentLength, source.contentSha256) else null
                }
            }, sourceAvailable = { track -> plugins.isAccountActive(track.sourceId, track.accountScope) },
            awaitSourceInitialization = { playbackAuthorizationReady.await() }))
        scope.launch {
            try {
                if (startupCrashGuard.recoveryRequired) settings.update { it.copy(safeMode = true) }
                plugins.refresh()
                var firstSettings = true
                var themesInitialized = false
                settings.persistedSettings.collect {
                    if (firstSettings && !it.safeMode &&
                        (themes.hasExternalThemeRegistry || plugins.plugins.value.any { plugin -> plugin.enabled })) {
                        startupCrashGuard.watchPluginStartup()
                    }
                    player.setSafeMode(it.safeMode)
                    if (!it.safeMode && !themesInitialized) {
                        themes.initialize()
                        themesInitialized = true
                    }
                    plugins.setSafeMode(it.safeMode)
                    plugins.awaitAccountState()
                    artwork.networkingEnabled = !it.safeMode
                    playbackAuthorizationReady.complete(Unit)
                    startupResourcesReady.complete(Unit)
                    if (firstSettings) checkBackgroundStartupStability()
                    firstSettings = false
                }
            } catch (error: Throwable) {
                playbackAuthorizationReady.completeExceptionally(error)
                startupResourcesReady.completeExceptionally(error)
                throw error
            }
        }
        scope.launch {
            var knownSources = emptySet<String>()
            var knownAccounts = emptySet<Pair<String, String>>()
            combine(plugins.plugins, plugins.activeAccountScopes) { installed, accounts ->
                val enabled = installed.filter { it.enabled }
                val sources = enabled.map { it.sourceId }.toSet()
                val accountScopes = accounts.mapNotNull { (pluginId, accountScope) ->
                    enabled.find { it.id == pluginId }?.let { it.sourceId to accountScope }
                }.toSet()
                sources to accountScopes
            }.distinctUntilChanged().collect { (sources, accounts) ->
                (knownSources - sources).forEach(player::disableSource)
                (sources - knownSources).forEach(player::enableSource)
                (knownAccounts - accounts).forEach { (source, account) -> player.disableAccount(source, account) }
                (accounts - knownAccounts).forEach { (source, account) -> player.enableAccount(source, account) }
                knownSources = sources
                knownAccounts = accounts
            }
        }
        scope.launch {
            combine(library.library, player.playback) { library, playback ->
                Triple(library.ready, library.root?.uri, playback.current?.id)
            }.distinctUntilChanged().collect { (ready, _, currentTrack) ->
                if (ready) library.releaseUnusedFolderGrants(setOfNotNull(currentTrack))
            }
        }
    }
}
