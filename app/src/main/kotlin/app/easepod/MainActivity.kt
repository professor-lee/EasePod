package app.easepod

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.easepod.core.WheelKey
import app.easepod.ui.AppModel
import app.easepod.ui.DeviceShell
import app.easepod.ui.SystemEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()
    private val pendingExport: PendingExport by viewModels()
    private val keyguard get() = getSystemService(KeyguardManager::class.java)
    private var exportBytes: ByteArray?
        get() = pendingExport.bytes
        set(value) { pendingExport.bytes = value }
    private var externalWindow = false
    private var unlockWindow = false
    private var pendingInstall = false
    private var fileGeneration: Long? = null
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val locked = keyguard.isKeyguardLocked
            model.refreshLock(locked)
            if (locked) pendingExport.clear()
        }
    }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        externalWindow = false
        val token = fileGeneration; fileGeneration = null
        if (uri == null || !model.acceptsFileResult(token)) model.fileResultCancelled() else {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); model.folderChosen(uri) }
            catch (e: SecurityException) { model.toast("目录授权失败，请重新选择") }
        }
    }
    private val pluginPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        externalWindow = false
        val token = fileGeneration; fileGeneration = null
        if (uri != null && model.acceptsFileResult(token)) model.pluginFileChosen(uri) else model.fileResultCancelled()
    }
    private val backupPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        externalWindow = false
        val token = fileGeneration; fileGeneration = null
        if (uri == null || !model.acceptsFileResult(token)) model.fileResultCancelled()
        else model.work {
            val bytes = readLimited(uri, 16 * 1024 * 1024)
            if (model.acceptsFileResult(token)) model.backupImported(bytes) else { bytes.fill(0); model.fileResultCancelled() }
        }
    }
    private val backupWriter = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        finishExport(uri, "备份已导出")
    }
    private val diagnosticsWriter = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        finishExport(uri, "诊断已导出")
    }
    private fun finishExport(uri: Uri?, success: String) {
        externalWindow = false
        val bytes = exportBytes; exportBytes = null
        val token = fileGeneration; fileGeneration = null
        if (uri == null || bytes == null || !model.acceptsFileResult(token)) { bytes?.fill(0); model.fileResultCancelled() }
        else {
            val owner = model
            val resolver = applicationContext.contentResolver
            owner.work {
                suspend fun checkSession() = withContext(Dispatchers.Main.immediate) {
                    if (!owner.acceptsFileResult(token)) throw CancellationException("Export session ended")
                }
                try {
                    withContext(Dispatchers.IO) {
                        checkSession()
                        resolver.openOutputStream(uri, "wt")?.use { output ->
                            var offset = 0
                            while (offset < bytes.size) {
                                checkSession()
                                val count = minOf(64 * 1024, bytes.size - offset)
                                output.write(bytes, offset, count)
                                offset += count
                            }
                            checkSession()
                        } ?: error("无法写入文件")
                    }
                    checkSession()
                    owner.toast(success)
                } finally { bytes.fill(0) }
            }
        }
    }
    private val installer = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        externalWindow = false
        if (pendingInstall) { pendingInstall = false; model.installationReturned() } else model.resumed()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        externalWindow = savedInstanceState?.getBoolean("externalWindow") ?: false
        pendingInstall = savedInstanceState?.getBoolean("pendingInstall") ?: false
        fileGeneration = savedInstanceState?.takeIf { it.containsKey("fileGeneration") }?.getLong("fileGeneration")
        enableEdgeToEdge()
        val owner = model
        owner.installationWindowActive(pendingInstall)
        val retained = pendingExport
        val systemKeyguard = applicationContext.getSystemService(KeyguardManager::class.java)
        // The model can finish encryption between the old Activity's destruction and its replacement.
        owner.lockQuery = { systemKeyguard.isKeyguardLocked }
        owner.effectHandler = { effect -> retained.dispatch(effect, owner.sessionGeneration) }
        registerReceiver(lockReceiver, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_SCREEN_ON) }, RECEIVER_NOT_EXPORTED)
        setContent {
            MaterialTheme {
                LaunchedEffect(Unit) {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        (application as EasePodApplication).awaitStartupResources()
                        withFrameNanos { }
                        delay(2_000)
                        (application as EasePodApplication).startupScreenStable()
                    }
                }
                LaunchedEffect(model.settings.lockScreenOverlay) {
                    setShowWhenLocked(model.settings.lockScreenOverlay)
                    if (!model.settings.lockScreenOverlay && keyguard.isKeyguardLocked) moveTaskToBack(true)
                }
                LaunchedEffect(model.settings.fullScreen) { applySystemBars() }
                DeviceShell(model)
            }
        }
    }
    private fun handleEffect(effect: SystemEffect) {
        if (pendingInstall && (effect is SystemEffect.Install || effect is SystemEffect.Uninstall || effect is SystemEffect.ApproveHost || effect is SystemEffect.OpenExternal)) {
            model.toast("请先完成系统安装流程"); return
        }
        if (effect != SystemEffect.Unlock && keyguard.isKeyguardLocked) {
            when (effect) {
                is SystemEffect.ExportBackup -> effect.bytes.fill(0)
                is SystemEffect.ExportDiagnostics -> effect.bytes.fill(0)
                else -> Unit
            }
            model.toast("请先解锁设备"); return
        }
        try {
            when (effect) {
                SystemEffect.PickFolder -> { fileGeneration = model.sessionGeneration; externalWindow = true; folderPicker.launch(null) }
                SystemEffect.PickPlugin -> { fileGeneration = model.sessionGeneration; externalWindow = true; pluginPicker.launch(arrayOf("*/*")) }
                is SystemEffect.ExportBackup -> { fileGeneration = model.sessionGeneration; exportBytes = effect.bytes; externalWindow = true; backupWriter.launch("easepod-backup.epb") }
                is SystemEffect.ExportDiagnostics -> { fileGeneration = model.sessionGeneration; exportBytes = effect.bytes; externalWindow = true; diagnosticsWriter.launch("easepod-diagnostics.txt") }
                is SystemEffect.OpenExternal -> {
                    val uri = Uri.parse(effect.url)
                    require(uri.scheme == "https" && uri.host != null && uri.userInfo == null)
                    externalWindow = true
                    installer.launch(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
                }
                SystemEffect.ImportBackup -> { fileGeneration = model.sessionGeneration; externalWindow = true; backupPicker.launch(arrayOf("*/*")) }
                SystemEffect.Unlock -> { if (unlockWindow) return; unlockWindow = true; keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() { unlockWindow = false; model.unlockResult(true) }
                    override fun onDismissCancelled() { unlockWindow = false; model.unlockResult(false) }
                    override fun onDismissError() { unlockWindow = false; model.unlockResult(false) }
                }) }
                is SystemEffect.Install -> { pendingInstall = true; model.installationWindowActive(true); externalWindow = true; val plugins = (application as EasePodApplication).plugins; installer.launch(if (plugins.canInstallPackages()) plugins.installIntent(effect.candidate) else plugins.unknownSourcesIntent()) }
                is SystemEffect.Uninstall -> { externalWindow = true; installer.launch((application as EasePodApplication).plugins.uninstallIntent(effect.pluginId)) }
                is SystemEffect.ApproveHost -> {
                    val intent = (application as EasePodApplication).plugins.hostApprovalIntent(effect.pluginId)
                    if (intent == null) model.toast("插件未提供宿主授权入口") else { externalWindow = true; installer.launch(intent) }
                }
            }
        } catch (e: Exception) {
            externalWindow = false; unlockWindow = false; pendingInstall = false; fileGeneration = null
            model.installationWindowActive(false)
            exportBytes?.fill(0); exportBytes = null
            if (effect == SystemEffect.Unlock) model.unlockResult(false) else model.fileResultCancelled()
            if (effect is SystemEffect.OpenExternal) model.message("无法打开链接", effect.url) else model.toast("无法打开系统窗口，请重试")
        }
    }
    private suspend fun readLimited(uri: Uri, maximum: Int): ByteArray = withContext(Dispatchers.IO) {
        contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val size = input.read(buffer); if (size < 0) break; require(output.size() + size <= maximum) { "备份文件过大" }; output.write(buffer, 0, size) }
            output.toByteArray()
        } ?: error("无法读取备份")
    }
    override fun onResume() {
        super.onResume()
        model.resumed()
        pendingExport.attach(model::acceptsFileResult, ::handleEffect)
    }
    override fun onPause() { pendingExport.detach(); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("externalWindow", externalWindow); outState.putBoolean("pendingInstall", pendingInstall); fileGeneration?.let { outState.putLong("fileGeneration", it) }; super.onSaveInstanceState(outState) }
    private fun applySystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = if (model.settings.fullScreen) WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE else WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            if (model.settings.fullScreen) hide(WindowInsetsCompat.Type.statusBars()) else show(WindowInsetsCompat.Type.statusBars())
        }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) { model.refreshLock(keyguard.isKeyguardLocked); applySystemBars() } else model.windowLostFocus()
    }
    override fun onStop() {
        model.suspended(preserveFileSession = externalWindow || isChangingConfigurations, preserveUnlock = unlockWindow)
        if (!isChangingConfigurations && !externalWindow) pendingExport.clear()
        super.onStop()
    }
    override fun onDestroy() {
        unregisterReceiver(lockReceiver)
        if (!isChangingConfigurations) { model.effectHandler = null; model.lockQuery = null; pendingExport.clear() }
        super.onDestroy()
    }
    // Activity's public window callback is marked restricted by the AndroidX bridge it inherits.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (model.editing) return super.dispatchKeyEvent(event)
        val key = when (event.keyCode) { KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> WheelKey.CENTER; KeyEvent.KEYCODE_ESCAPE -> WheelKey.MENU; KeyEvent.KEYCODE_DPAD_LEFT -> WheelKey.PREVIOUS; KeyEvent.KEYCODE_DPAD_RIGHT -> WheelKey.NEXT; KeyEvent.KEYCODE_SPACE -> WheelKey.PLAY; else -> null }
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) { model.rotate(if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1); return true }
            if (key != null) { if (event.repeatCount == 0) model.key(key); return true }
        }
        return if (key != null) true else super.dispatchKeyEvent(event)
    }
}

class PendingExport : ViewModel() {
    var bytes: ByteArray? = null
    private var handler: ((SystemEffect) -> Unit)? = null
    private var queued: Pair<Long, SystemEffect>? = null

    fun dispatch(effect: SystemEffect, generation: Long) {
        handler?.let { it(effect); return }
        if (effect is SystemEffect.ExportBackup || effect is SystemEffect.ExportDiagnostics || effect is SystemEffect.Install) {
            clearQueued()
            queued = generation to effect
        }
    }

    fun attach(accepts: (Long?) -> Boolean, handler: (SystemEffect) -> Unit) {
        this.handler = handler
        val pending = queued ?: return
        queued = null
        if (accepts(pending.first)) handler(pending.second) else erase(pending.second)
    }

    fun detach() { handler = null }
    fun clear() { bytes?.fill(0); bytes = null; clearQueued() }
    private fun clearQueued() { queued?.second?.let(::erase); queued = null }
    private fun erase(effect: SystemEffect) {
        when (effect) {
            is SystemEffect.ExportBackup -> effect.bytes.fill(0)
            is SystemEffect.ExportDiagnostics -> effect.bytes.fill(0)
            else -> Unit
        }
    }
    override fun onCleared() { detach(); clear() }
}
