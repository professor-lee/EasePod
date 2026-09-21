package app.easepod.contract

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

abstract class MusicPluginService : Service() {
    abstract val pluginId: String
    abstract val capabilities: Set<String>
    protected open val bundledHost: Boolean = false
    protected abstract suspend fun handle(request: RequestEnvelope): ResultEnvelope
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connections = ConcurrentHashMap<String, Int>()
    private val requests = ConcurrentHashMap<Pair<String, String>, Job>()
    private val terminals = ConcurrentHashMap.newKeySet<Pair<String, String>>()
    private val lock = Any()

    private fun isTrustedHost(uid: Int, hostPackage: String? = null): Boolean =
        if (bundledHost) uid == applicationInfo.uid && (hostPackage == null || hostPackage == packageName)
        else if (hostPackage == null) TrustedHosts(this).isTrusted(uid)
        else TrustedHosts(this).isTrusted(uid, hostPackage)

    private val binder = object : IMusicPlugin.Stub() {
        override fun handshake(hello: HostHello, callback: IHandshakeCallback) {
            val uid = Binder.getCallingUid()
            val approved = isTrustedHost(uid, hello.hostPackage)
            val error = when {
                !approved -> "HostNotApproved"
                runCatching { Protocol.checkSize(hello) }.isFailure -> "PayloadTooLarge"
                hello.major != Protocol.MAJOR || hello.minMinor > 0 || hello.maxMinor < 0 -> "IncompatibleProtocol"
                hello.connectionId.isBlank() || hello.connectionId.length > 4096 -> "InvalidRequest"
                else -> null
            }
            if (error == null) {
                // One current generation per host UID, including after host process restart.
                connections.filterValues { it == uid }.keys.forEach(::closeConnection)
                connections[hello.connectionId] = uid
                runCatching { callback.asBinder().linkToDeath({ closeConnection(hello.connectionId) }, 0) }
            }
            runCatching { callback.onResult(PluginHello(hello.connectionId, pluginId,
                capabilities = capabilities.toList(), error = error)) }
        }

        override fun execute(request: RequestEnvelope, callback: IResultCallback) {
            val uid = Binder.getCallingUid()
            if (!isTrustedHost(uid) || connections[request.connectionId] != uid) return
            val key = request.connectionId to request.requestId
            fun reject(code: String) { runCatching { callback.onResult(ResultEnvelope(request.connectionId, request.requestId, request.operation, errorCode = code)) } }
            val problem = validateRequest(request)
            if (problem != null) { reject(problem); return }
            synchronized(lock) {
                if (requests.containsKey(key) || key in terminals) { reject("DuplicateRequest"); return }
                if (terminals.count { it.first == request.connectionId } >= 4096) { reject("ReconnectRequired"); return }
                if (requests.keys.count { it.first == request.connectionId } >= 2) { reject("RateLimited"); return }
                val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                    try {
                        val timeout = (request.deadlineElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(1)
                        val response = withTimeout(timeout) { handle(request) }
                        require(response.connectionId == request.connectionId && response.requestId == request.requestId && response.operation == request.operation)
                        Protocol.checkSize(response)
                        if (connections[request.connectionId] == uid &&
                            isTrustedHost(uid) && terminals.add(key)) callback.onResult(response)
                    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                        if (terminals.add(key)) reject(if (request.operation in Protocol.writeOperations) "MutationUnknown" else "Timeout")
                    } catch (_: CancellationException) {
                        // Cancelled generations deliberately have no late completion.
                    } catch (error: Exception) {
                        if (terminals.add(key)) reject(if (error.message == "PayloadTooLarge") "PayloadTooLarge" else "PluginFault")
                    } finally { requests.remove(key) }
                }
                requests[key] = job
                job.start()
            }
        }

        override fun cancel(connectionId: String, requestId: String) {
            val uid = Binder.getCallingUid()
            if (!isTrustedHost(uid) || connections[connectionId] != uid) return
            val key = connectionId to requestId
            if (requests.containsKey(key)) terminals.add(key)
            requests.remove(key)?.cancel()
        }
    }

    private fun validateRequest(r: RequestEnvelope): String? {
        if (runCatching { Protocol.checkSize(r) }.isFailure) return "PayloadTooLarge"
        if (r.operation !in Protocol.operations || Protocol.operations[r.operation] !in capabilities) return "Unsupported"
        if (r.operation == "Browse" && HomeCatalog.isHome(r.remoteId)) {
            if (HomeCatalog.CAPABILITY !in capabilities) return "Unsupported"
            if (r.accountContext == "public") return "AuthRequired"
        }
        if (r.requestId.isBlank() || r.traceId.isBlank() || r.pageSize !in 1..50 || r.remoteIds.size > 50 ||
            r.accountContext.isBlank() || r.query.orEmpty().length > 1024) return "InvalidRequest"
        if (r.deadlineElapsedMs <= SystemClock.elapsedRealtime()) return "Timeout"
        if (r.deadlineElapsedMs - SystemClock.elapsedRealtime() > 60_000) return "InvalidRequest"
        if (r.operation in setOf("ResolvePlayback", "GetLyrics") && r.remoteId.isNullOrBlank()) return "InvalidRequest"
        if (r.operation in setOf("PollAuth", "CancelAuth") && r.sessionId.isNullOrBlank()) return "InvalidRequest"
        if (r.operation in setOf("GetFavorite", "GetPlaylistInfo") && r.remoteId.isNullOrBlank()) return "InvalidRequest"
        if (r.operation in setOf("SetFavorite", "EditPlaylist", "GetMutation") && r.mutation?.validFor(r.operation, r.remoteId) != true) return "InvalidRequest"
        if (r.operation == "EditPlaylist" && r.mutation?.action == "CREATE" && "library.playlist.create" !in capabilities) return "Unsupported"
        if (r.operation in setOf("GetFavorite", "SetFavorite", "ListEditablePlaylists", "GetPlaylistInfo", "EditPlaylist", "GetMutation") && r.accountContext == "public") return "AuthRequired"
        return null
    }

    private fun closeConnection(connectionId: String) {
        connections.remove(connectionId)
        requests.keys.filter { it.first == connectionId }.forEach { requests.remove(it)?.cancel() }
        terminals.removeAll { it.first == connectionId }
    }
    override fun onBind(intent: Intent?): IBinder? = if (intent?.action == Protocol.ACTION) binder else null
    override fun onUnbind(intent: Intent?): Boolean { connections.keys.toList().forEach(::closeConnection); return false }
    override fun onDestroy() { scope.cancel(); connections.clear(); requests.clear(); terminals.clear(); super.onDestroy() }
}
