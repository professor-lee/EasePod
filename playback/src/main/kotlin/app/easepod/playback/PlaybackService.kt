package app.easepod.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

@UnstableApi
object PlaybackRuntime {
    @Volatile private var instance: AndroidPlaybackController? = null
    @Synchronized fun install(context: Context, dependencies: PlaybackDependencies): AndroidPlaybackController =
        instance ?: AndroidPlaybackController(context.applicationContext, dependencies).also { instance = it }

    fun controller(): AndroidPlaybackController = checkNotNull(instance) { "Install PlaybackRuntime in Application.onCreate" }

    @Synchronized internal fun shutdown() { instance?.release(); instance = null }
}

@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val controller = PlaybackRuntime.controller()
        val builder = MediaSession.Builder(this, controller.player).setCallback(object : MediaSession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailablePlayerCommands(
                    Player.Commands.Builder().addAllCommands().remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                        .remove(Player.COMMAND_SET_MEDIA_ITEM).build(),
                ).build()
        })
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            builder.setSessionActivity(PendingIntent.getActivity(this, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        session = builder.build().also(::addSession)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!PlaybackRuntime.controller().hasPlaybackIntent) stopSelf()
    }

    override fun onDestroy() {
        session?.let { removeSession(it); it.release() }
        session = null
        PlaybackRuntime.controller().onServiceDestroyed()
        super.onDestroy()
    }
}
