package app.easepod.netease

import app.easepod.contract.MutationResult
import app.easepod.contract.RequestEnvelope
import app.easepod.contract.ResultEnvelope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class NeteaseFavorites(private val api: NeteaseApi) {
    private val writes = Mutex()

    suspend fun handle(request: RequestEnvelope, cookies: NeteaseCookies, userId: String?,
        requireActive: () -> Unit): ResultEnvelope {
        fun result() = ResultEnvelope(request.connectionId, request.requestId, request.operation)
        if (request.operation !in setOf("GetFavorite", "SetFavorite")) return result().copy(errorCode = "Unsupported")
        if (userId == null) return result().copy(errorCode = "AuthRequired")
        val track = request.remoteId?.takeIf { it.toLongOrNull()?.let { id -> id > 0 && id.toString() == it } == true }
            ?: return result().copy(errorCode = "InvalidRequest")
        if (request.operation == "SetFavorite" && request.mutation?.validFor("SetFavorite", track) != true) {
            return result().copy(errorCode = "InvalidRequest")
        }

        suspend fun read(): Boolean {
            currentCoroutineContext().ensureActive()
            requireActive()
            val response = api.post("/api/song/like/get", JSONObject().put("uid", userId), cookies, NeteaseMode.EAPI)
            NeteaseAccounts.requireSuccess(response)
            val ids = response.optJSONArray("ids") ?: throw NeteaseException("InvalidResponse", "无法读取喜欢状态")
            currentCoroutineContext().ensureActive()
            requireActive()
            return (0 until ids.length()).any { ids.optString(it) == track }
        }

        if (request.operation == "GetFavorite") return result().copy(favoriteState = read())
        return writes.withLock {
            val mutation = requireNotNull(request.mutation)
            val desired = mutation.desiredFavorite
            fun receipt(status: String) = result().copy(mutation = MutationResult(mutation.mutationId, status,
                remoteId = track, message = if (status == "UNKNOWN") "喜欢状态待确认，请刷新" else null))
            if (read() == desired) return@withLock receipt("APPLIED")
            currentCoroutineContext().ensureActive()
            requireActive()
            // Once the write starts, failure cannot prove that the server did not apply it.
            try {
                val response = api.post("/api/radio/like", JSONObject().put("alg", "itembased")
                    .put("trackId", track).put("like", desired).put("time", "3"), cookies)
                NeteaseAccounts.requireSuccess(response)
                receipt(if (read() == desired) "APPLIED" else "UNKNOWN")
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                receipt("UNKNOWN")
            }
        }
    }
}
