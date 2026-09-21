package app.easepod.netease

import android.os.SystemClock
import app.easepod.contract.MusicPluginService
import app.easepod.contract.RequestEnvelope
import app.easepod.contract.ResultEnvelope
import kotlinx.coroutines.CancellationException
import java.util.UUID

class NeteaseMusicService : MusicPluginService() {
    override val pluginId = NeteasePlugin.id
    override val capabilities = NeteasePlugin.capabilities
    private val api: NeteaseApi by lazy {
        val settings = getSharedPreferences("netease_device", MODE_PRIVATE)
        val deviceId = settings.getString("device_id", null) ?: UUID.randomUUID().toString().replace("-", "").also {
            check(settings.edit().putString("device_id", it).commit())
        }
        NeteaseHttpClient(deviceId)
    }
    private val accountState = lazy { NeteaseAccounts(api, EncryptedNeteaseAccountStore(this), SystemClock::elapsedRealtime) }
    private val accounts by accountState
    private val catalog by lazy { NeteaseCatalog(api) }
    private val favorites by lazy { NeteaseFavorites(api) }

    override fun onDestroy() {
        super.onDestroy()
        if (accountState.isInitialized()) accounts.close()
    }

    override suspend fun handle(request: RequestEnvelope): ResultEnvelope {
        fun result() = ResultEnvelope(request.connectionId, request.requestId, request.operation)
        return try {
            when (request.operation) {
                "BeginAuth" -> if (request.authMethod != "qr") result().copy(errorCode = "Unsupported")
                    else result().copy(auth = accounts.begin(request.connectionId))
                "PollAuth" -> result().copy(auth = accounts.poll(request.connectionId, requireNotNull(request.sessionId)))
                "CancelAuth" -> result().copy(auth = accounts.cancel(request.connectionId, requireNotNull(request.sessionId)))
                "ListAccounts" -> result().copy(accounts = accounts.list())
                "SignOut" -> { accounts.signOut(request.accountContext); result() }
                "GetFavorite", "SetFavorite" -> accounts.use(request.accountContext) { cookies, userId ->
                    favorites.handle(request, cookies, userId) {
                        if (userId != null) accounts.requireActive(userId, cookies)
                    }
                }
                else -> accounts.use(request.accountContext) { cookies, userId -> catalog.handle(request, cookies, userId) }
            }
        } catch (error: CancellationException) { throw error }
        catch (error: NeteaseException) { result().copy(errorCode = error.code, errorMessage = error.message?.take(512)) }
    }
}
