package app.easepod.sampleplugin

import app.easepod.contract.CatalogItem
import app.easepod.contract.MusicPluginService
import app.easepod.contract.RequestEnvelope
import app.easepod.contract.ResultEnvelope
import kotlinx.coroutines.delay

/** Instrumentation fixture, omitted from release and absent from plugin discovery. */
class ContractFixtureService : MusicPluginService() {
    override val pluginId = "app.easepod.sampleplugin.fixture"
    override val capabilities = setOf("catalog.browse", "catalog.search")
    override suspend fun handle(request: RequestEnvelope): ResultEnvelope {
        if (request.query == "die") android.os.Process.killProcess(android.os.Process.myPid())
        if (request.query == "slow") delay(800)
        return ResultEnvelope(request.connectionId, request.requestId, request.operation,
            items = listOf(CatalogItem(remoteId = "fixture", title = "Fixture", availability = "AVAILABLE")))
    }
}
