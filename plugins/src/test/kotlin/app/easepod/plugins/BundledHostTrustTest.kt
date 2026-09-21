package app.easepod.plugins

import android.content.Intent
import app.easepod.contract.HostHello
import app.easepod.contract.IHandshakeCallback
import app.easepod.contract.IMusicPlugin
import app.easepod.contract.MusicPluginService
import app.easepod.contract.PluginHello
import app.easepod.contract.Protocol
import app.easepod.contract.RequestEnvelope
import app.easepod.contract.ResultEnvelope
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BundledHostTrustTest {
    @Test fun bundledServiceAcceptsOnlyItsOwnPackageAndUid() {
        val controller = Robolectric.buildService(BundledFixture::class.java).create()
        try {
            val service = controller.get()
            ShadowBinder.setCallingUid(service.applicationInfo.uid)
            assertNull(handshake(service, service.packageName).error)
            assertEquals("HostNotApproved", handshake(service, "other.package").error)
            ShadowBinder.setCallingUid(service.applicationInfo.uid + 1)
            assertEquals("HostNotApproved", handshake(service, service.packageName).error)
        } finally { controller.destroy(); ShadowBinder.reset() }
    }

    @Test fun externalServiceStillRequiresExplicitHostApprovalForTheSameUid() {
        val controller = Robolectric.buildService(ExternalFixture::class.java).create()
        try {
            val service = controller.get()
            ShadowBinder.setCallingUid(service.applicationInfo.uid)
            assertEquals("HostNotApproved", handshake(service, service.packageName).error)
        } finally { controller.destroy(); ShadowBinder.reset() }
    }

    private fun handshake(service: MusicPluginService, host: String): PluginHello {
        val binder = IMusicPlugin.Stub.asInterface(service.onBind(Intent(Protocol.ACTION)))
        var result: PluginHello? = null
        binder.handshake(HostHello(java.util.UUID.randomUUID().toString(), host), object : IHandshakeCallback.Stub() {
            override fun onResult(hello: PluginHello) { result = hello }
        })
        return requireNotNull(result)
    }

    class BundledFixture : MusicPluginService() {
        override val pluginId = "bundled.fixture"
        override val capabilities = emptySet<String>()
        override val bundledHost = true
        override suspend fun handle(request: RequestEnvelope) = ResultEnvelope(request.connectionId, request.requestId, request.operation)
    }

    class ExternalFixture : MusicPluginService() {
        override val pluginId = "external.fixture"
        override val capabilities = emptySet<String>()
        override suspend fun handle(request: RequestEnvelope) = ResultEnvelope(request.connectionId, request.requestId, request.operation)
    }
}
