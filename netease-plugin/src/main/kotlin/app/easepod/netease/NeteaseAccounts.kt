package app.easepod.netease

import app.easepod.contract.AccountSummary
import app.easepod.contract.AuthSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.util.UUID

data class StoredNeteaseAccount(val id: String, val name: String, val cookies: List<Cookie>)

interface NeteaseAccountStore {
    fun load(): List<StoredNeteaseAccount>
    fun save(accounts: List<StoredNeteaseAccount>)
}

class NeteaseAccounts(private val api: NeteaseApi, private val store: NeteaseAccountStore,
    private val elapsed: () -> Long) {
    private class Account(val id: String, var name: String, val cookies: NeteaseCookies)
    private class Session(val id: String, val owner: String, val key: String, val expires: Long,
        val cookies: NeteaseCookies, var state: String = "WaitingScan", var lastPoll: Long = Long.MIN_VALUE) {
        val polling = Mutex()
    }
    private val guard = Any()
    private val accounts = linkedMapOf<String, Account>()
    private val sessions = mutableMapOf<String, Session>()
    private val pendingBegins = mutableMapOf<String, Any>()
    private val publicCookies = NeteaseCookies()
    private var restoreError = runCatching {
        val saved = store.load()
        require(saved.size <= MAX_ACCOUNTS && saved.map { it.id }.distinct().size == saved.size)
        saved.forEach { account ->
            require(account.id.matches(Regex("[1-9][0-9]{0,19}")) && account.name.length <= 1024)
            accounts[account.id] = Account(account.id, account.name, NeteaseCookies().apply { replace(account.cookies) })
        }
    }.exceptionOrNull()

    private fun requireStore() {
        if (restoreError != null) throw NeteaseException("AuthRequired", "网易云账号存储无法读取，请重新扫码登录")
    }
    private fun persistLocked() = store.save(accounts.values.map { StoredNeteaseAccount(it.id, it.name, it.cookies.snapshot()) })

    suspend fun <T> use(accountId: String, action: suspend (NeteaseCookies, String?) -> T): T {
        if (accountId == "public") return action(publicCookies, null)
        val account = synchronized(guard) {
            requireStore()
            accounts[accountId] ?: throw NeteaseException("AuthRequired", "请重新登录网易云音乐")
        }
        val result = action(account.cookies, account.id)
        currentCoroutineContext().ensureActive()
        synchronized(guard) {
            if (accounts[accountId] !== account) throw NeteaseException("AuthRequired", "账号已退出")
            persistLocked()
        }
        return result
    }

    suspend fun list(): List<AccountSummary> {
        val records = synchronized(guard) { requireStore(); accounts.values.toList() }
        return records.map { account ->
            val response = api.post("/api/nuser/account/get", JSONObject(), account.cookies)
            val verified = verifiedAccount(response)
            currentCoroutineContext().ensureActive()
            synchronized(guard) {
                if (accounts[account.id] !== account) throw NeteaseException("AuthRequired", "账号已退出")
                if (verified?.pluginAccountId == account.id) {
                    account.name = verified.displayName
                    persistLocked()
                    verified
                } else AccountSummary(account.id, account.name, "Expired")
            }
        }
    }

    suspend fun begin(owner: String): AuthSession {
        val generation = Any()
        synchronized(guard) {
            if (restoreError != null) { store.save(emptyList()); accounts.clear(); restoreError = null }
            cancelOwnerLocked(owner)
            pendingBegins[owner] = generation
        }
        val cookies = NeteaseCookies()
        var retained = false
        try {
        val response = api.post("/api/login/qrcode/unikey", JSONObject().put("type", 3), cookies, NeteaseMode.EAPI)
        requireSuccess(response)
        val key = response.optJSONObject("data")?.optString("unikey")?.takeIf { it.isNotBlank() }
            ?: response.optString("unikey").takeIf { it.isNotBlank() }
            ?: throw NeteaseException("InvalidResponse", "无法创建网易云二维码")
        if (key.length > 1024 || key.any { it.isISOControl() }) throw NeteaseException("InvalidResponse", "二维码数据无效")
        currentCoroutineContext().ensureActive()
        val session = Session(UUID.randomUUID().toString(), owner, key, elapsed() + 180_000L, cookies)
        synchronized(guard) {
            if (pendingBegins[owner] !== generation) return AuthSession(session.id, "Cancelled")
            cancelOwnerLocked(owner)
            sessions.values.filter { it.expires <= elapsed() }.forEach { sessions.remove(it.id); it.cookies.clear() }
            if (sessions.size >= 4) throw NeteaseException("RateLimited", "登录会话过多，请稍后重试")
            sessions[session.id] = session
            retained = true
        }
        return summary(session)
        } finally {
            synchronized(guard) { if (pendingBegins[owner] === generation) pendingBegins.remove(owner) }
            if (!retained) cookies.clear()
        }
    }

    suspend fun poll(owner: String, sessionId: String): AuthSession {
        val session = synchronized(guard) { sessions[sessionId]?.takeIf { it.owner == owner } }
            ?: return AuthSession(sessionId, "Cancelled")
        return session.polling.withLock {
            synchronized(guard) {
                if (sessions[sessionId] !== session) return@withLock AuthSession(sessionId, "Cancelled")
                if (session.expires <= elapsed()) {
                    sessions.remove(sessionId); session.cookies.clear()
                    return@withLock AuthSession(sessionId, "Expired")
                }
                if (session.lastPoll != Long.MIN_VALUE && elapsed() - session.lastPoll < 1_500L) return@withLock summary(session)
                session.lastPoll = elapsed()
            }
            val response = api.post("/api/login/qrcode/client/login", JSONObject().put("key", session.key).put("type", 3), session.cookies, NeteaseMode.EAPI)
            currentCoroutineContext().ensureActive()
            val state = when (response.optInt("code", -1)) {
                800 -> "Expired"
                801 -> "WaitingScan"
                802 -> "WaitingConfirm"
                803 -> "VerifyingAccount"
                else -> { requireSuccess(response); throw NeteaseException("InvalidResponse", "无法确认二维码状态") }
            }
            val account = if (state == "VerifyingAccount") {
                val verified = verifiedAccount(api.post("/api/nuser/account/get", JSONObject(), session.cookies))
                    ?: throw NeteaseException("AuthRequired", "扫码完成，账号尚未验证，请重试")
                if (session.cookies.snapshot().none { it.name == "MUSIC_U" && it.value.isNotBlank() })
                    throw NeteaseException("AuthRequired", "登录会话未建立，请重新扫码")
                verified
            } else null
            currentCoroutineContext().ensureActive()
            synchronized(guard) {
                if (sessions[sessionId] !== session) return@withLock AuthSession(sessionId, "Cancelled")
                if (session.expires <= elapsed()) { sessions.remove(sessionId); session.cookies.clear(); return@withLock AuthSession(sessionId, "Expired") }
                if (account != null) {
                    if (account.pluginAccountId !in accounts && accounts.size >= MAX_ACCOUNTS)
                        throw NeteaseException("RateLimited", "已达到网易云账号数量上限")
                    val previous = accounts[account.pluginAccountId]
                    accounts[account.pluginAccountId] = Account(account.pluginAccountId, account.displayName, session.cookies)
                    try { persistLocked() } catch (error: Exception) {
                        if (previous == null) accounts.remove(account.pluginAccountId) else accounts[account.pluginAccountId] = previous
                        throw error
                    }
                    sessions.remove(sessionId)
                    AuthSession(sessionId, "SignedIn", account = account)
                } else {
                    session.state = state
                    if (state == "Expired") { sessions.remove(sessionId); session.cookies.clear() }
                    summary(session)
                }
            }
        }
    }

    fun cancel(owner: String, sessionId: String): AuthSession = synchronized(guard) {
        sessions[sessionId]?.takeIf { it.owner == owner }?.let { sessions.remove(sessionId); it.cookies.clear() }
        AuthSession(sessionId, "Cancelled")
    }

    suspend fun signOut(id: String) {
        val detached = synchronized(guard) {
            requireStore()
            val account = accounts.remove(id) ?: return
            try { persistLocked() } catch (error: Exception) { accounts[id] = account; throw error }
            NeteaseCookies().apply { replace(account.cookies.snapshot()); account.cookies.clear() }
        }
        try { api.post("/api/logout", JSONObject(), detached, NeteaseMode.EAPI) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* Local credentials remain removed when the service is unavailable. */ }
        finally { detached.clear() }
    }

    fun close() = synchronized(guard) {
        pendingBegins.clear()
        sessions.values.forEach { it.cookies.clear() }
        sessions.clear()
        accounts.values.forEach { it.cookies.clear() }
        accounts.clear()
        publicCookies.clear()
    }

    fun requireActive(id: String, cookies: NeteaseCookies) = synchronized(guard) {
        if (accounts[id]?.cookies !== cookies) throw NeteaseException("AuthRequired", "账号已退出")
    }

    private fun cancelOwnerLocked(owner: String) {
        sessions.values.filter { it.owner == owner }.forEach { sessions.remove(it.id); it.cookies.clear() }
    }
    private fun summary(session: Session): AuthSession {
        val qr = "https://music.163.com/login".toHttpUrl().newBuilder().addQueryParameter("codekey", session.key).build().toString()
        return AuthSession(session.id, session.state, if (session.state == "Expired") null else qr, session.expires)
    }

    companion object {
        private const val MAX_ACCOUNTS = 4
        internal fun verifiedAccount(response: JSONObject): AccountSummary? {
            if (response.optInt("code") in setOf(301, 302)) return null
            requireSuccess(response)
            val body = response.optJSONObject("data") ?: response
            val profile = body.optJSONObject("profile") ?: return null
            val userId = profile.optLong("userId", -1).takeIf { it > 0 }?.toString() ?: return null
            val accountId = body.optJSONObject("account")?.optLong("id", -1)
            if (accountId != null && accountId > 0 && accountId.toString() != userId) return null
            return AccountSummary(userId, profile.optString("nickname").ifBlank { "网易云用户" }.take(1024), "SignedIn")
        }
        internal fun requireSuccess(response: JSONObject) {
            when (response.optInt("code", -1)) {
                200 -> Unit
                301, 302 -> throw NeteaseException("AuthRequired", "请重新登录网易云音乐")
                401, 403 -> throw NeteaseException("AccessDenied", "网易云暂不允许此请求")
                404 -> throw NeteaseException("NotFound", "内容不存在或已下架")
                429, 509 -> throw NeteaseException("RateLimited", "请求过于频繁，请稍后重试")
                else -> throw NeteaseException("ServiceUnavailable", "网易云暂时无法完成请求")
            }
        }
    }
}
