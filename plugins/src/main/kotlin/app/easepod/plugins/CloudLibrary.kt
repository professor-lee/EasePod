package app.easepod.plugins

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.easepod.contract.CloudPlaylist
import app.easepod.contract.LibraryMutation
import app.easepod.contract.MutationResult
import app.easepod.contract.ResultEnvelope
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

data class CloudMutationReceipt(val id: String, val pluginId: String, val accountScope: String,
    val operation: String, val remoteId: String?, val action: String, val title: String,
    val desiredFavorite: Boolean = false)
data class CloudPlaylistPage(val items: List<CloudPlaylist>, val nextCursor: String?)

interface CloudLibraryAccess {
    val pending: StateFlow<List<CloudMutationReceipt>>
    suspend fun favorite(pluginId: String, accountScope: String, trackId: String): Boolean
    suspend fun playlists(pluginId: String, accountScope: String, cursor: String? = null): CloudPlaylistPage
    suspend fun playlist(pluginId: String, accountScope: String, playlistId: String): CloudPlaylist
    suspend fun submit(pluginId: String, accountScope: String, remoteId: String?, title: String, mutation: LibraryMutation,
        authorized: () -> Boolean): MutationResult
    suspend fun refresh(receipt: CloudMutationReceipt): MutationResult
}

internal interface CloudLibraryTransport {
    fun supports(pluginId: String, capability: String): Boolean
    suspend fun request(pluginId: String, accountScope: String, operation: String,
        remoteId: String? = null, cursor: String? = null, mutation: LibraryMutation? = null,
        authorized: (() -> Boolean)? = null): ResultEnvelope
}

internal interface CloudReceiptStore {
    fun read(): List<CloudMutationReceipt>
    fun write(receipts: List<CloudMutationReceipt>)
}

/** A receipt journal, never an outgoing queue. Reopening the app never replays writes. */
internal class CloudLibrary(private val transport: CloudLibraryTransport, private val store: CloudReceiptStore,
    private val online: () -> Boolean) : CloudLibraryAccess {
    private val restored = runCatching(store::read)
    private val state = MutableStateFlow(restored.getOrDefault(emptyList()))
    override val pending: StateFlow<List<CloudMutationReceipt>> = state
    private val guard = Any()
    private val inFlight = mutableSetOf<String>()
    private fun requireOnline(account: String) {
        if (account in setOf("", "public", "local")) throw PluginException("AuthRequired", "请先登录音乐服务")
        if (!online()) throw PluginException("NetworkUnavailable", "当前离线，无法连接云端")
    }
    private suspend fun update(reservedId: String? = null, transform: (List<CloudMutationReceipt>) -> List<CloudMutationReceipt>) = withContext(Dispatchers.IO) {
        synchronized(guard) {
            val next = transform(state.value); store.write(next)
            if (reservedId != null) inFlight.add(reservedId)
            state.value = next
        }
    }
    override suspend fun favorite(pluginId: String, accountScope: String, trackId: String): Boolean {
        requireOnline(accountScope)
        return transport.request(pluginId, accountScope, "GetFavorite", trackId).favoriteState
            ?: throw PluginException("InvalidResponse", "无法确认收藏状态")
    }
    override suspend fun playlists(pluginId: String, accountScope: String, cursor: String?): CloudPlaylistPage {
        requireOnline(accountScope)
        val response = transport.request(pluginId, accountScope, "ListEditablePlaylists", cursor = cursor)
        response.playlists.forEach(::validatePlaylist)
        return CloudPlaylistPage(response.playlists, response.nextCursor?.takeUnless { it == cursor })
    }
    override suspend fun playlist(pluginId: String, accountScope: String, playlistId: String): CloudPlaylist {
        requireOnline(accountScope)
        val item = transport.request(pluginId, accountScope, "GetPlaylistInfo", playlistId).playlists.singleOrNull()
            ?: throw PluginException("InvalidResponse", "无法确认歌单权限")
        validatePlaylist(item)
        if (item.remoteId != playlistId) throw PluginException("InvalidResponse", "歌单身份不匹配")
        return item
    }
    private fun validatePlaylist(item: CloudPlaylist) {
        if (item.remoteId.isBlank() || item.revision.isBlank() || item.title.length > 1024 ||
            item.allowedActions.any { it !in LibraryMutation.playlistActions || it == "CREATE" })
            throw PluginException("InvalidResponse", "歌单信息无效")
    }
    override suspend fun submit(pluginId: String, accountScope: String, remoteId: String?, title: String, mutation: LibraryMutation,
        authorized: () -> Boolean): MutationResult {
        requireOnline(accountScope)
        check(restored.isSuccess) { "云端操作记录无法读取，请检查应用存储后重试" }
        val operation = if (mutation.action == "FAVORITE") "SetFavorite" else "EditPlaylist"
        require(mutation.validFor(operation, remoteId)) { "云端操作无效" }
        val capability = if (operation == "SetFavorite") "library.favorite" else "library.playlist.write"
        if (!transport.supports(pluginId, capability)) throw PluginException("Unsupported", "服务不支持此功能")
        if (mutation.action == "CREATE" && !transport.supports(pluginId, "library.playlist.create")) throw PluginException("Unsupported", "服务不支持创建歌单")
        if (operation == "EditPlaylist" && mutation.action != "CREATE") {
            val current = playlist(pluginId, accountScope, requireNotNull(remoteId))
            if (mutation.action !in current.allowedActions) throw PluginException("Rejected", "当前账号没有此歌单的修改权限")
            if (mutation.expectedRevision != current.revision) return MutationResult(mutation.mutationId, "CONFLICT", message = "歌单已变化，请刷新后重新操作")
        }
        if (!authorized()) throw PluginException("OperationCancelled", "操作已失效，请解锁后重新确认")
        requireOnline(accountScope)
        val receipt = CloudMutationReceipt(mutation.mutationId, pluginId, accountScope, operation,
            remoteId, mutation.action, title.take(1024), mutation.desiredFavorite)
        val result = try {
            update(receipt.id) { receipts ->
                if (receipts.any { it.id == receipt.id || it.pluginId == pluginId && it.accountScope == accountScope && it.remoteId == remoteId && it.operation == operation })
                    throw PluginException("MutationPending", "此项目的上次操作仍待确认，请先刷新结果")
                if (receipts.size >= 256) throw PluginException("MutationPending", "待确认操作已达上限，请先核对结果")
                receipts + receipt
            }
            try {
                val response = transport.request(pluginId, accountScope, operation, remoteId, mutation = mutation, authorized = authorized)
                validated(receipt, response.mutation)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error is PluginException && error.code in setOf("AuthRequired", "AuthExpired", "Unsupported", "InvalidRequest", "EntitlementRequired", "RegionRestricted", "Conflict", "Rejected", "OperationCancelled"))
                    MutationResult(receipt.id, if (error.code == "Conflict") "CONFLICT" else "REJECTED", message = error.message)
                else unknown(receipt)
            }
        } finally { synchronized(guard) { inFlight.remove(receipt.id) } }
        if (result.status == "UNKNOWN") return try { refresh(receipt) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { unknown(receipt) }
        return finish(receipt, result)
    }
    override suspend fun refresh(receipt: CloudMutationReceipt): MutationResult {
        if (receipt !in state.value) throw PluginException("NotFound", "操作结果已处理")
        requireOnline(receipt.accountScope)
        synchronized(guard) { if (receipt.id in inFlight) return unknown(receipt) }
        val result = try {
            when {
                transport.supports(receipt.pluginId, "library.mutation.read") -> validated(receipt,
                    transport.request(receipt.pluginId, receipt.accountScope, "GetMutation", receipt.remoteId,
                        mutation = LibraryMutation(receipt.id, "READ")).mutation)
                receipt.action == "FAVORITE" && favorite(receipt.pluginId, receipt.accountScope, receipt.remoteId!!) == receipt.desiredFavorite ->
                    MutationResult(receipt.id, "APPLIED", remoteId = receipt.remoteId)
                else -> unknown(receipt)
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { unknown(receipt) }
        return finish(receipt, result)
    }
    private fun validated(receipt: CloudMutationReceipt, result: MutationResult?): MutationResult {
        if (result == null || result.mutationId != receipt.id || result.status !in setOf("APPLIED", "REJECTED", "CONFLICT", "UNKNOWN")) return unknown(receipt)
        if (result.status == "APPLIED" && receipt.operation == "EditPlaylist" &&
            (result.revision.isNullOrBlank() || result.remoteId.isNullOrBlank() || receipt.remoteId?.let { it != result.remoteId } == true)) return unknown(receipt)
        return result
    }
    private fun unknown(receipt: CloudMutationReceipt) = MutationResult(receipt.id, "UNKNOWN", message = "结果待确认，未重复发送")
    private suspend fun finish(receipt: CloudMutationReceipt, result: MutationResult): MutationResult {
        if (result.status != "UNKNOWN") update { it.filterNot { saved -> saved.id == receipt.id } }
        return result
    }
    companion object {
        fun create(context: Context, transport: CloudLibraryTransport) = CloudLibrary(transport, PreferencesCloudReceiptStore(context)) {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        }
        fun mutationId(): String = UUID.randomUUID().toString()
    }
}

internal class PreferencesCloudReceiptStore(context: Context) : CloudReceiptStore {
    private val preferences = context.getSharedPreferences("cloud-mutation-receipts", Context.MODE_PRIVATE)
    override fun read(): List<CloudMutationReceipt> {
        val encoded = preferences.getString("receipts", "[]") ?: "[]"
        val array = JSONArray(encoded)
        require(array.length() <= 256)
        return List(array.length()) { index -> array.getJSONObject(index).let { item ->
            CloudMutationReceipt(item.getString("id"), item.getString("plugin"), item.getString("account"),
                item.getString("operation"), item.optString("remote").takeIf(String::isNotBlank),
                item.getString("action"), item.getString("title"), item.optBoolean("desired"))
        } }
    }
    override fun write(receipts: List<CloudMutationReceipt>) {
        val array = JSONArray()
        receipts.forEach { receipt -> array.put(JSONObject().put("id", receipt.id).put("plugin", receipt.pluginId)
            .put("account", receipt.accountScope).put("operation", receipt.operation).put("remote", receipt.remoteId)
            .put("action", receipt.action).put("title", receipt.title).put("desired", receipt.desiredFavorite)) }
        check(preferences.edit().putString("receipts", array.toString()).commit()) { "无法保存云操作收据，请核对远端状态" }
    }
}
