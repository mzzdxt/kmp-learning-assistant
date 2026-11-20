package dev.kmpjourney.pocketlab.android

import android.app.Application
import dev.kmpjourney.pocketlab.data.SqlDelightExperimentRepository
import dev.kmpjourney.pocketlab.data.openAndroidPocketLabDatabase
import dev.kmpjourney.pocketlab.demo.DemoExperimentRemoteDataSource
import dev.kmpjourney.pocketlab.diagnostics.createObservedRefreshExperimentsUseCase

class PocketLabApplication : Application() {
    val graph: AndroidPocketLabGraph by lazy {
        AndroidPocketLabGraph(this)
    }
}

/** Process-scoped resources. Android terminates the process instead of calling a reliable close hook. */
class AndroidPocketLabGraph(application: Application) {
    private val databaseHandle = openAndroidPocketLabDatabase(
        context = application,
        name = "pocketlab-demo.db",
    )

    private val repository = SqlDelightExperimentRepository(databaseHandle.database)
    private val refreshExperiments = createObservedRefreshExperimentsUseCase(
        remote = DemoExperimentRemoteDataSource(),
        local = repository,
    )

    internal val experimentListGateway: ExperimentListGateway = KmpExperimentListGateway(
        localStore = repository,
        refreshExperiments = refreshExperiments,
    )
}
