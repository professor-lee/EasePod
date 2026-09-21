package app.easepod.plugins

import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import androidx.core.content.FileProvider
import app.easepod.contract.*
import app.easepod.core.Track
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class PluginManager(context: Context, bundledPlugins: List<BundledMusicPlugin> = emptyList()) {
    private val context = context.applicationContext
    private val bundledPlugins = bundledPlugins.toList().also { descriptors ->
        require(descriptors.map { it.id }.distinct().size == descriptors.size)
        require(descriptors.all { it.id.matches(Regex("[a-zA-Z][a-zA-Z0-9_.-]{2,127}")) &&
            it.name.length in 1..128 && it.capabilities.all(Protocol.capabilities::contains) })
    }
    private val pm = context.packageManager
    private val dao = PluginDatabase.create(this.context).dao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutablePlugins = MutableStateFlow<List<PluginInfo>>(emptyList())
    val plugins = mutablePlugins.asStateFlow()
    val cloudLibrary: CloudLibraryAccess = CloudLibrary.create(this.context, object : CloudLibraryTransport {
        override fun supports(pluginId: String, capability: String): Boolean =
            plugins.value.any { it.id == pluginId && it.enabled && !safeMode && capability in it.capabilities }
        override suspend fun request(pluginId: String, accountScope: String, operation: String,
            remoteId: String?, cursor: String?, mutation: LibraryMutation?, authorized: (() -> Boolean)?) =
            execute(pluginId, operation, accountScope, remoteId, cursor = cursor, mutation = mutation, writeAuthorized = authorized)
    })
    private val connections = ConcurrentHashMap<String, Connection>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val permits = ConcurrentHashMap<String, Semaphore>()
    private val allCalls = GLOBAL_CALLS
    private val refreshLock = Mutex()
    private val failures = ConcurrentHashMap<String, MutableList<Long>>()
    private val blockedUntil = ConcurrentHashMap<String, Long>()
    private val deaths = ConcurrentHashMap<String, MutableList<Long>>()
    private val generations = ConcurrentHashMap<String, AtomicLong>()
    private val probes = ConcurrentHashMap.newKeySet<String>()
    private val quarantined = ConcurrentHashMap.newKeySet<String>()
    private val disabledSources = ConcurrentHashMap.newKeySet<String>()
    private val accountLocks = ConcurrentHashMap<String, Mutex>()
    private val connectionGuard = Any()
    private val requestGuard = Any()
    private val closedAccountScopes = ConcurrentHashMap.newKeySet<Pair<String, String>>()
    private val activeAccounts = MutableStateFlow<Set<Pair<String, String>>>(emptySet())
    // Plugin IDs paired with opaque host account scopes; never remote account identifiers.
    val activeAccountScopes = activeAccounts.asStateFlow()
    private val accountStateReady = CompletableDeferred<Unit>()
    @Volatile private var safeMode = true
    private data class Connection(val token: String, val service: IMusicPlugin, val binding: ServiceConnection,
        val uid: Int, val capabilities: Set<String>, val death: IBinder.DeathRecipient,
        val pending: ConcurrentHashMap<String, CompletableDeferred<ResultEnvelope>> = ConcurrentHashMap(),
        val requestScopes: ConcurrentHashMap<String, String> = ConcurrentHashMap())

    init {
        scope.launch {
            try { dao.observeAccounts().collect { records ->
                activeAccounts.value = records.filter { it.active }.map { it.pluginId to it.scope }.toSet()
                accountStateReady.complete(Unit)
            } } catch (error: Throwable) { accountStateReady.completeExceptionally(error); throw error }
        }
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val packageName = intent?.data?.schemeSpecificPart ?: return
                val affected = plugins.value.filter { it.packageName == packageName }
                affected.forEach { disconnect(it.id) }
                if (intent.action == Intent.ACTION_PACKAGE_REMOVED && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
                scope.launch {
                    if (intent.action == Intent.ACTION_PACKAGE_REMOVED) affected.forEach { dao.removeAccounts(it.id); dao.remove(it.id) }
                    refresh()
                }
            }
        },
            IntentFilter().apply { addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_REPLACED); addDataScheme("package") }, Context.RECEIVER_NOT_EXPORTED)
        scope.launch { refresh() }
    }
    suspend fun refresh() = refreshLock.withLock {
        val records = dao.all().associateBy { it.id }
        for (record in records.values) {
            if (runCatching { pm.getPackageInfo(record.packageName, PackageManager.PackageInfoFlags.of(0)) }.isFailure) {
                dao.removeAccounts(record.id); dao.remove(record.id)
            }
        }
        quarantined.addAll(records.values.filter { it.quarantined }.map { it.id })
        val found = withContext(Dispatchers.IO) {
            pm.queryIntentServices(Intent(Protocol.ACTION), PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong())).mapNotNull { resolved ->
                runCatching {
                    val service = resolved.serviceInfo
                    require(service.exported && service.enabled && service.packageName != context.packageName)
                    val pack = pm.getPackageInfo(service.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
                    val resourceId = service.metaData?.getInt(Protocol.MANIFEST_META) ?: 0
                    require(resourceId != 0)
                    val json = pm.getResourcesForApplication(service.applicationInfo).openRawResource(resourceId).use { stream ->
                        val bytes = stream.readNBytes(16 * 1024 + 1); require(bytes.size <= 16 * 1024); JSONObject(bytes.toString(Charsets.UTF_8))
                    }
                    val id = json.getString("pluginId"); require(id.matches(Regex("[a-zA-Z][a-zA-Z0-9_.-]{2,127}")))
                    require(bundledPlugins.none { it.id == id })
                    require(json.getInt("manifestVersion") == 1 && json.getString("kind") == "music-service")
                    require(json.getString("packageName") == service.packageName && json.getString("serviceClass") == service.name)
                    require(json.getLong("versionCode") == pack.longVersionCode)
                    val protocol = json.getJSONObject("protocol")
                    val compatible = protocol.getInt("major") == Protocol.MAJOR && protocol.getInt("minMinor") <= Protocol.MINOR && protocol.getInt("maxMinor") >= Protocol.MINOR
                    val declared = json.getJSONArray("capabilities").let { a -> require(a.length() <= 50); (0 until a.length()).map { a.getString(it) }.toSet() }
                    require(declared.all { it.length <= 64 })
                    val signature = PackageIdentity.fingerprints(context, service.packageName).sorted().joinToString(",")
                    val record = records[id]
                    val identityChanged = record != null && (record.packageName != service.packageName || record.serviceName != service.name)
                    // A previous bundled registration may use the host package. Migrate it to
                    // the external APK identity and require a fresh explicit signature approval.
                    if (identityChanged) {
                        dao.removeAccounts(id)
                        quarantined.remove(id)
                    }
                    val approved = record != null && !identityChanged && approvedIdentity(record, signature)
                    val registered = if (record == null || identityChanged) {
                        PluginRecord(id, service.packageName, service.name, sourceId = record?.sourceId?.takeIf { it.isNotBlank() } ?: "plugin.${UUID.randomUUID()}").also { dao.put(it) }
                    } else record
                    val domains = json.getJSONArray("networkDomains").let { a -> require(a.length() <= 50); (0 until a.length()).map { a.getString(it).lowercase() }.toSet() }
                    require(domains.all { it.length <= 253 && it.matches(Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+")) })
                    PluginInfo(id, json.getString("displayName").also { require(it.length in 1..128) }, service.packageName, service.name, signature, pack.versionName ?: "", approved && compatible && registered.enabled,
                        when { !compatible -> "协议不兼容"; !approved -> "待信任"; id in quarantined -> "已隔离"; safeMode && registered.enabled -> "安全模式"; registered.enabled -> "连接中"; else -> "已禁用" },
                        declared.intersect(Protocol.capabilities), registered.sourceId, approved,
                        networkDomains = domains, versionCode = pack.longVersionCode, protocolCompatible = compatible)
                }.getOrElse { error -> if (error is CancellationException) throw error else null }
            }.groupBy { it.id }.filterValues { it.size == 1 }.values.map { it.single() } + bundledPlugins.map { descriptor ->
                val service = pm.getServiceInfo(ComponentName(context.packageName, descriptor.serviceClass), PackageManager.ComponentInfoFlags.of(0))
                require(!service.exported && service.enabled && service.applicationInfo.uid == context.applicationInfo.uid)
                val pack = pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
                val signature = PackageIdentity.fingerprints(context, context.packageName).sorted().joinToString(",")
                val previous = records[descriptor.id]
                val registered = descriptor.registration(context.packageName, signature, previous)
                if (previous != null && (previous.packageName != registered.packageName || previous.serviceName != registered.serviceName)) {
                    dao.removeAccounts(descriptor.id)
                    quarantined.remove(descriptor.id)
                }
                if (registered != previous) dao.put(registered)
                PluginInfo(descriptor.id, descriptor.name, context.packageName, descriptor.serviceClass, signature,
                    pack.versionName.orEmpty(), enabled = registered.enabled,
                    status = when { descriptor.id in quarantined -> "已隔离"; safeMode && registered.enabled -> "安全模式"; registered.enabled -> "已启用"; else -> "已禁用" },
                    capabilities = descriptor.capabilities, sourceId = registered.sourceId, approved = true,
                    networkDomains = descriptor.networkDomains, versionCode = pack.longVersionCode, builtin = true)
            }
        }
        for (old in mutablePlugins.value) {
            val new = found.find { it.id == old.id }
            if (new == null || !new.enabled || new.signature != old.signature || new.versionCode != old.versionCode) disconnect(old.id)
        }
        mutablePlugins.value = found.map { p -> connections[p.id]?.let {
            p.copy(capabilities = it.capabilities, status = if (blockedUntil.containsKey(p.id)) "暂时熔断" else "已启用")
        } ?: p }
        if (!safeMode) for (plugin in found.filter { it.enabled && !it.builtin && it.id !in quarantined }) runCatching { connect(plugin.id) }
    }
    private fun approvedIdentity(record: PluginRecord, current: String): Boolean {
        if (record.approvedSignature.isBlank()) return false
        if (current == record.approvedSignature) return true
        if (',' in current || ',' in record.approvedSignature) return false
        return record.approvedSignature in PackageIdentity.fingerprints(context, record.packageName, history = true)
    }
    suspend fun approve(pluginId: String, fingerprint: String) {
        val p = info(pluginId); require(p.signature == fingerprint) { "插件签名已变化，请重新核对" }
        require(!p.builtin) { "内置音乐服务无需信任授权" }
        val current = PackageIdentity.fingerprints(context, p.packageName).sorted().joinToString(",")
        require(current == fingerprint) { "插件签名已变化，请刷新后核对" }
        val old = dao.get(p.id)
        val changedIdentity = old != null && (old.packageName != p.packageName || old.serviceName != p.serviceName ||
            (old.approvedSignature.isNotBlank() && !approvedIdentity(old, current)))
        if (changedIdentity) dao.removeAccounts(p.id)
        quarantined.remove(pluginId); blockedUntil.remove(pluginId); failures.remove(pluginId); deaths.remove(pluginId)
        dao.put(PluginRecord(p.id, p.packageName, p.serviceName, fingerprint, false,
            if (changedIdentity || p.sourceId.isBlank()) "plugin.${UUID.randomUUID()}" else p.sourceId)); refresh()
    }
    suspend fun setEnabled(pluginId: String, enabled: Boolean) {
        val p = info(pluginId); require(!enabled || p.approved) { "请先确认插件签名" }
        if (!enabled) { synchronized(requestGuard) { disabledSources.add(pluginId); disconnect(pluginId) } }
        require(!enabled || p.protocolCompatible) { "插件协议不兼容" }
        val record = dao.get(pluginId) ?: error("插件未注册")
        dao.put(record.copy(enabled = enabled))
        if (enabled) disabledSources.remove(pluginId)
        refresh()
    }
    suspend fun retry(pluginId: String) {
        blockedUntil.remove(pluginId); failures.remove(pluginId); deaths.remove(pluginId); probes.remove(pluginId); quarantined.remove(pluginId)
        dao.get(pluginId)?.let { dao.put(it.copy(quarantined = false)) }
        disconnect(pluginId); connect(pluginId)
    }
    suspend fun setSafeMode(enabled: Boolean) {
        synchronized(requestGuard) {
            safeMode = enabled
            if (enabled) plugins.value.forEach { disconnect(it.id) }
        }
        refresh()
    }
    private fun info(id: String): PluginInfo = plugins.value.find { it.id == id } ?: throw PluginException("Unavailable", "插件不可用")
    suspend fun awaitAccountState() = accountStateReady.await()
    fun isAccountActive(sourceId: String, accountScope: String): Boolean {
        val plugin = plugins.value.find { it.sourceId == sourceId } ?: return false
        if (!plugin.enabled || !plugin.approved || safeMode || plugin.id in disabledSources) return false
        return accountScope == "public" || ((plugin.id to accountScope) in activeAccounts.value && (plugin.id to accountScope) !in closedAccountScopes)
    }
    private fun update(id: String, transform: (PluginInfo) -> PluginInfo) { synchronized(mutablePlugins) { mutablePlugins.value = mutablePlugins.value.map { if (it.id == id) transform(it) else it } } }
    private fun disconnect(id: String) {
        val removed = synchronized(requestGuard) {
            synchronized(connectionGuard) {
                generations.computeIfAbsent(id) { AtomicLong() }.incrementAndGet()
                connections.remove(id)
            }
        }
        removed?.let { connection ->
            connection.pending.keys.forEach { runCatching { connection.service.cancel(connection.token, it) } }
            connection.pending.values.forEach { it.completeExceptionally(PluginException("PluginDisconnected", "插件已断开")) }
            connection.pending.clear()
            runCatching { connection.service.asBinder().unlinkToDeath(connection.death, 0) }
            runCatching { context.unbindService(connection.binding) }
        }
    }
    private suspend fun connect(id: String): Connection = locks.computeIfAbsent(id) { Mutex() }.withLock {
        if (safeMode || id in disabledSources) throw PluginException("SourceDisabled", "音乐服务已暂停")
        if (id in quarantined) throw PluginException("Quarantined", "插件已隔离，请手动重试")
        connections[id]?.let { return@withLock it }
        val p = info(id); require(p.enabled && p.approved) { "音乐服务尚未启用" }
        require((blockedUntil[id] ?: 0) <= SystemClock.elapsedRealtime()) { "插件暂时隔离，请稍后重试" }
        val token = UUID.randomUUID().toString()
        val generation = generations.computeIfAbsent(id) { AtomicLong() }.get()
        val binderReady = CompletableDeferred<IBinder>()
        val died = java.util.concurrent.atomic.AtomicBoolean()
        fun disconnected() {
            binderReady.completeExceptionally(PluginException("PluginDisconnected"))
            if (generations[id]?.get() != generation || !died.compareAndSet(false, true)) return
            disconnect(id); recordDeath(id)
        }
        val binding = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (name != ComponentName(p.packageName, p.serviceName) || generations[id]?.get() != generation) binderReady.completeExceptionally(PluginException("PluginDisconnected"))
                else binderReady.complete(binder)
            }
            override fun onServiceDisconnected(name: ComponentName) { disconnected() }
            override fun onBindingDied(name: ComponentName) { disconnected() }
            override fun onNullBinding(name: ComponentName) { binderReady.completeExceptionally(PluginException("InvalidResponse")) }
        }
        var deathLink: Pair<IBinder, IBinder.DeathRecipient>? = null
        try {
            require(context.bindService(Intent(Protocol.ACTION).setComponent(ComponentName(p.packageName, p.serviceName)), binding, Context.BIND_AUTO_CREATE)) { "无法连接插件" }
            val binder = withTimeout(10_000) { binderReady.await() }
            val service = IMusicPlugin.Stub.asInterface(binder)
            val uid = pm.getApplicationInfo(p.packageName, PackageManager.ApplicationInfoFlags.of(0)).uid
            require(pm.getPackagesForUid(uid)?.toList() == listOf(p.packageName)) { "共享 UID 插件不受支持" }
            val currentSignature = PackageIdentity.fingerprints(context, p.packageName).sorted().joinToString(",")
            require(currentSignature == p.signature) { "插件身份已变化" }
            val handshake = CompletableDeferred<PluginHello>()
            val death = IBinder.DeathRecipient { handshake.completeExceptionally(PluginException("PluginDisconnected")); disconnected() }
            binder.linkToDeath(death, 0)
            deathLink = binder to death
            service.handshake(HostHello(token, context.packageName), object : IHandshakeCallback.Stub() {
                override fun onResult(hello: PluginHello) { if (Binder.getCallingUid() == uid && generations[id]?.get() == generation) handshake.complete(hello) }
            })
            val hello = withTimeout(10_000) { handshake.await() }
            Protocol.checkSize(hello)
            hello.error?.let { error -> throw PluginException(error, if (error == "HostNotApproved") "请在插件中批准 EasePod 的签名" else "插件握手失败") }
            require(hello.connectionId == token && hello.pluginId == id && hello.major == Protocol.MAJOR && hello.minor == Protocol.MINOR) { "插件协议不匹配" }
            require(hello.capabilities.all { it in p.capabilities }) { "插件声明了未经注册的能力" }
            val connection = Connection(token, service, binding, uid, hello.capabilities.toSet(), death)
            synchronized(connectionGuard) {
                if (generations[id]?.get() != generation || safeMode || id in disabledSources || !info(id).enabled) throw PluginException("PluginDisconnected")
                connections[id] = connection
            }
            update(id) { it.copy(status = "已启用", capabilities = connection.capabilities, failure = null) }
            connection
        } catch (e: Exception) {
            deathLink?.let { (binder, death) -> runCatching { binder.unlinkToDeath(death, 0) } }
            runCatching { context.unbindService(binding) }
            if (e !is CancellationException && !died.get()) {
                val code = (e as? PluginException)?.code ?: "PluginFault"
                if (code == "HostNotApproved") update(id) { it.copy(status = "待宿主授权", failure = code) } else fault(id, code)
            }
            throw e
        }
    }
    private fun recordDeath(id: String) {
        val now = SystemClock.elapsedRealtime()
        val list = deaths.computeIfAbsent(id) { mutableListOf() }
        synchronized(list) {
            list.removeAll { now - it > 300_000 }; list.add(now)
            if (list.size >= 3) {
                quarantined.add(id)
                scope.launch { dao.get(id)?.let { dao.put(it.copy(quarantined = true)) } }
            }
        }
        fault(id, "PluginDisconnected")
    }
    private fun fault(id: String, code: String) {
        val now = SystemClock.elapsedRealtime()
        val list = failures.computeIfAbsent(id) { mutableListOf() }
        synchronized(list) { list.removeAll { now - it > 60_000 }; list.add(now); if (list.size >= 3) blockedUntil[id] = now + 30_000 }
        update(id) { it.copy(status = when { id in quarantined -> "已隔离"; blockedUntil.containsKey(id) -> "暂时熔断"; else -> "连接故障" }, failure = code) }
    }
    private suspend fun execute(id: String, operation: String, accountScope: String = "public", remoteId: String? = null,
        query: String? = null, cursor: String? = null, pageSize: Int = 30, quality: String = "standard", sessionId: String? = null,
        method: String = "qr", remoteIds: List<String> = emptyList(), mutation: LibraryMutation? = null,
        writeAuthorized: (() -> Boolean)? = null): ResultEnvelope =
        permits.computeIfAbsent(id) { Semaphore(2) }.withPermit { allCalls.withPermit {
            val blocked = blockedUntil[id]
            if (blocked != null && (SystemClock.elapsedRealtime() < blocked || !probes.add(id))) throw PluginException("CircuitOpen", "插件暂时熔断", 30_000)
            try {
            val connection = connect(id)
            if (Protocol.operations[operation] !in connection.capabilities) throw PluginException("Unsupported", "服务不支持此功能")
            val account = if (accountScope == "public") "public" else dao.accounts(id)
                .find { it.scope == accountScope && (it.active || operation == "SignOut") }?.remoteId
                ?: throw PluginException("AuthRequired", "账号需要重新登录")
            val requestId = UUID.randomUUID().toString()
            val timeout = if (operation == "ResolvePlayback") 8000L else 10000L
            val request = RequestEnvelope(connection.token, requestId, UUID.randomUUID().toString(), SystemClock.elapsedRealtime() + timeout,
                account, operation, remoteId, query, cursor, pageSize.coerceIn(1, 50),
                quality = Protocol.playbackQuality(quality, connection.capabilities), sessionId = sessionId, authMethod = method, remoteIds = remoteIds, mutation = mutation)
            Protocol.checkSize(request)
            val result = CompletableDeferred<ResultEnvelope>()
            var dispatched = false
            var upstreamRejected = false
            try {
                val callback = object : IResultCallback.Stub() {
                    override fun onResult(response: ResultEnvelope) {
                        if (Binder.getCallingUid() != connection.uid || connections[id] !== connection || response.connectionId != connection.token || response.requestId != requestId) return
                        result.complete(response)
                    }
                }
                synchronized(requestGuard) {
                    if (operation in Protocol.writeOperations && writeAuthorized?.invoke() != true) throw PluginException("OperationCancelled", "请解锁后重新确认云端操作")
                    if ((id to accountScope) in closedAccountScopes && operation != "SignOut") throw PluginException("AuthRequired")
                    if (connections[id] !== connection || safeMode || id in disabledSources) throw PluginException("SourceDisabled")
                    connection.pending[requestId] = result
                    connection.requestScopes[requestId] = accountScope
                    dispatched = true
                    connection.service.execute(request, callback)
                }
                val response = withTimeout(timeout) { result.await() }
                Protocol.checkSize(response)
                require(response.operation == operation && response.items.size <= 50 && response.accounts.size <= 50 && response.lyrics.size <= 50 && response.playlists.size <= 50) { "插件响应无效" }
                response.errorCode?.let { code -> upstreamRejected = true; throw PluginException(code, response.errorMessage?.take(512) ?: "服务请求失败", response.retryAfterMs.takeIf { it >= 0 }?.coerceIn(1_000, 300_000)) }
                if (blocked != null) { blockedUntil.remove(id); failures.remove(id); update(id) { it.copy(status = "已启用", failure = null) } }
                response
            } catch (e: Exception) {
                runCatching { connection.service.cancel(connection.token, requestId) }
                val converted = when (e) {
                    is TimeoutCancellationException -> PluginException("Timeout", "服务请求超时")
                    is TransactionTooLargeException -> PluginException("PayloadTooLarge", "插件响应超过大小限制")
                    is DeadObjectException -> PluginException("PluginDisconnected", "插件已断开")
                    is PluginException, is CancellationException -> e
                    else -> PluginException(if (e.message == "PayloadTooLarge") "PayloadTooLarge" else "InvalidResponse", "插件响应无效")
                }
                if (converted is PluginException && converted.code in setOf("Timeout", "PayloadTooLarge", "InvalidResponse", "PluginFault")) fault(id, converted.code)
                if (operation in Protocol.writeOperations && dispatched && !upstreamRejected && converted !is CancellationException)
                    throw PluginException("MutationUnknown", "云端操作结果待确认")
                throw converted
            } finally { connection.pending.remove(requestId); connection.requestScopes.remove(requestId) }
            } finally { if (blocked != null) probes.remove(id) }
        } }
    suspend fun browse(pluginId: String, parentId: String? = null, cursor: String? = null, pageSize: Int = 30, accountScope: String = "public") = catalog(pluginId, accountScope, execute(pluginId, "Browse", accountScope, parentId, cursor = cursor, pageSize = pageSize))
    suspend fun search(pluginId: String, query: String, cursor: String? = null, pageSize: Int = 30, accountScope: String = "public"): CatalogPage {
        require(query.length in 1..1024); return catalog(pluginId, accountScope, execute(pluginId, "Search", accountScope, query = query, cursor = cursor, pageSize = pageSize))
    }
    suspend fun details(pluginId: String, remoteId: String, cursor: String? = null, pageSize: Int = 30, accountScope: String = "public"): CatalogPage =
        catalog(pluginId, accountScope, execute(pluginId, "GetDetails", accountScope, cursor = cursor, pageSize = pageSize, remoteIds = listOf(remoteId)))
    private suspend fun catalog(id: String, scope: String, result: ResultEnvelope): CatalogPage {
        val p = info(id)
        val items = result.items.map { item ->
            require(item.kind in setOf("TRACK", "ALBUM", "ARTIST", "PLAYLIST") && item.remoteId.isNotBlank() && item.remoteId.length <= 4096 && item.title.length <= 1024 && item.artists.all { it.length <= 1024 })
            val artwork = item.artworkUrl?.let { value ->
                runCatching {
                    require(java.net.URI(value).rawQuery == null) { "CredentialedArtworkUnsupported" }
                    validateUrl(value, p.networkDomains); value
                }.getOrElse { error -> if (error is CancellationException) throw error else null }
            }
            item.copy(artworkUrl = artwork)
        }
        return CatalogMapper.map(p.sourceId, scope, result.copy(items = items))
    }
    private fun pluginFor(track: Track) = plugins.value.find { it.sourceId == track.sourceId } ?: throw PluginException("Unavailable", "曲目来源不可用")
    suspend fun resolve(track: Track, quality: String = "standard"): PluginPlaybackSource {
        val p = pluginFor(track)
        val source = execute(p.id, "ResolvePlayback", track.accountScope, track.remoteId, quality = quality).playback ?: throw PluginException("InvalidResponse")
        if (source.remoteId != track.remoteId || source.cachePolicy !in setOf("NO_STORE", "STREAM", "OFFLINE") ||
            source.length < -1 || source.length == 0L || source.contentSha256?.matches(Regex("[a-fA-F0-9]{64}")) == false) {
            fault(p.id, "InvalidResponse"); throw PluginException("InvalidResponse", "播放源无效")
        }
        try { validateUrl(source.url, p.networkDomains) }
        catch (error: CancellationException) { throw error }
        catch (_: java.net.UnknownHostException) { throw PluginException("NetworkUnavailable", "媒体域名无法解析") }
        catch (_: Exception) { fault(p.id, "InvalidResponse"); throw PluginException("InvalidResponse", "媒体地址未通过安全校验") }
        // No general authorization field until the protocol can prove a resource-scoped grant.
        if (source.headers.size > 8 || source.headers.any { it.name.lowercase() !in setOf("user-agent", "accept") || it.value.length > 4096 || it.value.any { c -> c.code !in 32..126 } }) throw PluginException("UnsupportedTransport", "媒体需要当前协议不支持的授权")
        if (source.expiresAtEpochMs >= 0 && source.expiresAtEpochMs <= System.currentTimeMillis()) throw PluginException("ExpiredSource", "播放源已过期")
        return PluginPlaybackSource(source.url, source.headers.associate { it.name to it.value }, source.cachePolicy, source.expiresAtEpochMs.takeIf { it >= 0 }, source.mime,
            source.resolverRevision, source.actualQuality, source.canSeek, source.isPreview, source.length.takeIf { it >= 0 }, source.bitrate.takeIf { it >= 0 }, p.networkDomains, source.contentSha256)
    }
    suspend fun lyrics(track: Track, cursor: String? = null): List<LyricLine> = execute(pluginFor(track).id, "GetLyrics", track.accountScope, track.remoteId, cursor = cursor).lyrics.map { LyricLine(it.timeMs.takeIf { value -> value >= 0 }, it.text, it.translation) }
    suspend fun lyricsPage(track: Track, cursor: String? = null): LyricsPage {
        val response = execute(pluginFor(track).id, "GetLyrics", track.accountScope, track.remoteId, cursor = cursor)
        return LyricsPage(response.lyrics.map { LyricLine(it.timeMs.takeIf { time -> time >= 0 }, it.text, it.translation) }, response.nextCursor)
    }
    suspend fun accounts(pluginId: String): List<PluginAccount> = execute(pluginId, "ListAccounts").accounts.map { mapAccount(pluginId, it) }
    private suspend fun mapAccount(plugin: String, account: AccountSummary, allowReactivate: Boolean = false): PluginAccount = accountLocks.computeIfAbsent(plugin) { Mutex() }.withLock {
        require(account.pluginAccountId.isNotBlank() && account.pluginAccountId !in setOf("public", "local") && account.pluginAccountId.length <= 4096 && account.displayName.length <= 1024)
        val existing = dao.accounts(plugin).find { it.remoteId == account.pluginAccountId }
        val record = when {
            existing == null -> AccountRecord(plugin, account.pluginAccountId, UUID.randomUUID().toString(), account.authState == "SignedIn").also { dao.account(it) }
            allowReactivate && account.authState == "SignedIn" -> existing.copy(active = true).also {
                dao.account(it); closedAccountScopes.remove(plugin to it.scope)
            }
            account.authState != "SignedIn" -> existing.copy(active = false).also { dao.account(it) }
            else -> existing
        }
        activeAccounts.update { if (record.active) it + (plugin to record.scope) else it - (plugin to record.scope) }
        PluginAccount(account.pluginAccountId, account.displayName, record.scope, if (record.active) account.authState else "SignedOut")
    }
    suspend fun beginAuth(pluginId: String, method: String = "qr") = auth(pluginId, execute(pluginId, "BeginAuth", method = method))
    suspend fun pollAuth(pluginId: String, sessionId: String) = auth(pluginId, execute(pluginId, "PollAuth", sessionId = sessionId))
    suspend fun cancelAuth(pluginId: String, sessionId: String) { execute(pluginId, "CancelAuth", sessionId = sessionId) }
    private suspend fun auth(id: String, result: ResultEnvelope): PluginAuthSession {
        val auth = result.auth ?: throw PluginException("InvalidResponse")
        require(auth.sessionId.isNotBlank() && auth.state in setOf("Idle", "Creating", "WaitingScan", "WaitingConfirm", "VerifyingAccount", "SignedIn", "Expired", "Cancelled", "Failed")) { "InvalidResponse" }
        val expires = auth.expiresAtElapsedMs.takeIf { it >= 0 }
        auth.qrContent?.let { qr -> require(qr.length <= 4096 && qr.none { it.isISOControl() }) { "InvalidResponse" } }
        val state = if (expires != null && expires <= SystemClock.elapsedRealtime() && auth.state !in setOf("SignedIn", "Cancelled", "Failed")) "Expired" else auth.state
        val account = if (state == "SignedIn") {
            val announced = auth.account ?: throw PluginException("InvalidResponse")
            // A login result is confirmed by a fresh bounded account read.
            val verified = execute(id, "ListAccounts").accounts.find { it.pluginAccountId == announced.pluginAccountId && it.authState == "SignedIn" }
                ?: throw PluginException("AuthRequired", "登录状态尚未验证")
            mapAccount(id, verified, allowReactivate = true)
        } else null
        return PluginAuthSession(auth.sessionId, state, auth.qrContent, expires, account)
    }
    suspend fun signOut(pluginId: String, accountScope: String) {
        require(accountScope !in setOf("public", "local"))
        synchronized(requestGuard) {
            closedAccountScopes.add(pluginId to accountScope)
            connections[pluginId]?.let { connection ->
                connection.requestScopes.filterValues { it == accountScope }.keys.forEach { requestId ->
                    connection.pending.remove(requestId)?.completeExceptionally(PluginException("AuthRequired"))
                    runCatching { connection.service.cancel(connection.token, requestId) }
                }
            }
        }
        accountLocks.computeIfAbsent(pluginId) { Mutex() }.withLock { dao.deactivateAccount(pluginId, accountScope) }
        try { execute(pluginId, "SignOut", accountScope) }
        finally { disconnect(pluginId) }
    }
    fun hostApprovalIntent(pluginId: String): Intent? {
        val plugin = info(pluginId)
        if (plugin.builtin) return null
        val intent = Intent(Protocol.APPROVE_HOST_ACTION).setPackage(plugin.packageName)
        val activity = pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))?.activityInfo ?: return null
        if (!activity.exported || activity.packageName != plugin.packageName) return null
        return intent.setComponent(ComponentName(activity.packageName, activity.name)).putExtra("hostPackage", context.packageName)
    }
    suspend fun inspectApk(uri: Uri): InstallCandidate = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "plugin-apks").apply { mkdirs() }
        val target = File(directory, "${UUID.randomUUID()}.apk")
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { output ->
                val bytes = ByteArray(8192); var total = 0L
                while (true) { val count = input.read(bytes); if (count < 0) break; total += count; require(total <= 100 * 1024 * 1024) { "APK 超过 100 MB" }; output.write(bytes, 0, count) }
            } } ?: error("无法读取 APK")
            val pack = pm.getPackageArchiveInfo(target.path, PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SERVICES or PackageManager.GET_META_DATA) ?: error("无效的 APK")
            val service = pack.services?.filter { it.exported && it.metaData?.containsKey(Protocol.MANIFEST_META) == true }?.singleOrNull()
                ?: error("APK 必须声明一个音乐插件服务")
            val application = requireNotNull(pack.applicationInfo).apply { sourceDir = target.path; publicSourceDir = target.path }
            val manifest = pm.getResourcesForApplication(application).openRawResource(service.metaData.getInt(Protocol.MANIFEST_META)).use { input ->
                val bytes = input.readNBytes(16 * 1024 + 1); require(bytes.size <= 16 * 1024); JSONObject(bytes.toString(Charsets.UTF_8))
            }
            require(manifest.getInt("manifestVersion") == 1 && manifest.getString("kind") == "music-service" &&
                manifest.getString("packageName") == pack.packageName && manifest.getString("serviceClass") == service.name &&
                manifest.getLong("versionCode") == pack.longVersionCode) { "APK 插件清单与包身份不一致" }
            val pluginId = manifest.getString("pluginId").also { require(it.matches(Regex("[a-zA-Z][a-zA-Z0-9_.-]{2,127}"))) }
            val displayName = manifest.getString("displayName").also { require(it.length in 1..128) }
            val signature = pack.signingInfo!!.apkContentsSigners.map { digest(it.toByteArray()) }.sorted().joinToString(",")
            val hash = target.inputStream().use { input -> val md = MessageDigest.getInstance("SHA-256"); val b = ByteArray(8192); while (true) { val n = input.read(b); if (n < 0) break; md.update(b, 0, n) }; md.digest().joinToString("") { "%02x".format(it) } }
            InstallCandidate("apk", displayName, pack.versionName.orEmpty(), target.path, pack.packageName, signature, hash, pack.longVersionCode, pluginId = pluginId)
        } catch (e: Exception) { target.delete(); throw e }
    }
    /**
     * Inspect an APK shipped in the host application's assets.
     *
     * Market entries are deliberately fed through the same archive inspection path as
     * user selected files.  This keeps the package identity, manifest and signature
     * checks identical for bundled and external sources while leaving the APK in the
     * normal pending-install staging directory.
     */
    suspend fun inspectBundledApk(assetPath: String): InstallCandidate = withContext(Dispatchers.IO) {
        require(assetPath.isNotBlank() && !assetPath.startsWith('/') && ".." !in assetPath.split('/')) {
            "插件市场资源路径无效"
        }
        val staged = File.createTempFile("plugin-market-", ".apk", context.cacheDir)
        try {
            context.assets.open(assetPath).use { input ->
                staged.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            inspectApk(Uri.fromFile(staged))
        } finally {
            staged.delete()
        }
    }
    fun canInstallPackages() = pm.canRequestPackageInstalls()
    fun unknownSourcesIntent() = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
    fun installIntent(candidate: InstallCandidate): Intent {
        val file = File(candidate.filePath)
        require(file.canonicalFile.parentFile == File(context.cacheDir, "plugin-apks").canonicalFile && file.isFile)
        require(candidate.sha256 == file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256"); val bytes = ByteArray(8192)
            while (true) { val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }) { "安装文件已变化" }
        return Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(FileProvider.getUriForFile(context, "${context.packageName}.plugin.files", file), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT, true)
    }
    fun uninstallIntent(pluginId: String): Intent {
        val plugin = info(pluginId)
        require(!plugin.builtin) { "内置音乐服务不能单独卸载" }
        return Intent(Intent.ACTION_DELETE, Uri.parse("package:${plugin.packageName}"))
    }
    companion object {
        private val GLOBAL_CALLS = Semaphore(4)
        fun mediaId(sourceId: String, accountScope: String, kind: String, remoteId: String): String =
            digest(org.json.JSONArray(listOf(sourceId, accountScope, kind, remoteId)).toString().toByteArray(Charsets.UTF_8))
        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        suspend fun validateUrl(value: String, hosts: Set<String>) { MediaUriPolicy.validate(value, hosts) }
    }
}
