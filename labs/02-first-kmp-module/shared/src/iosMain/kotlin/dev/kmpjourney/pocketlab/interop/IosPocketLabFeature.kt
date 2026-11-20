@file:OptIn(kotlin.experimental.ExperimentalObjCName::class)

package dev.kmpjourney.pocketlab.interop

import dev.kmpjourney.pocketlab.data.PocketLabDatabaseHandle
import dev.kmpjourney.pocketlab.data.SqlDelightExperimentRepository
import dev.kmpjourney.pocketlab.data.openIosPocketLabDatabase
import dev.kmpjourney.pocketlab.demo.DemoExperimentRemoteDataSource
import dev.kmpjourney.pocketlab.diagnostics.createObservedRefreshExperimentsUseCase
import dev.kmpjourney.pocketlab.network.ExperimentRemoteDataSource
import dev.kmpjourney.pocketlab.network.PocketLabNetwork
import dev.kmpjourney.pocketlab.network.createPlatformPocketLabNetwork
import kotlin.native.ObjCName

/** Apple composition root: Swift owns one feature and closes it at the same boundary. */
@ObjCName(swiftName = "PocketLabFeature")
class IosPocketLabFeature internal constructor(
    val experimentList: IosExperimentListBridge,
    private val databaseHandle: PocketLabDatabaseHandle,
    private val network: PocketLabNetwork?,
) {
    private var closed = false

    fun close() {
        if (closed) return
        closed = true
        experimentList.close()
        network?.close()
        databaseHandle.close()
    }
}

@ObjCName(swiftName = "PocketLabFeatures")
object IosPocketLabFeatures {
    fun demo(databaseName: String): IosPocketLabFeature = createFeature(
        databaseName = databaseName,
        remote = DemoExperimentRemoteDataSource(),
        network = null,
    )

    fun live(
        databaseName: String,
        baseUrl: String,
    ): IosPocketLabFeature {
        val network = createPlatformPocketLabNetwork(baseUrl)
        return createFeature(
            databaseName = databaseName,
            remote = network,
            network = network,
        )
    }

    private fun createFeature(
        databaseName: String,
        remote: ExperimentRemoteDataSource,
        network: PocketLabNetwork?,
    ): IosPocketLabFeature {
        val handle = openIosPocketLabDatabase(databaseName)
        val repository = SqlDelightExperimentRepository(handle.database)
        val refresh = createObservedRefreshExperimentsUseCase(remote, repository)
        return IosPocketLabFeature(
            experimentList = IosExperimentListBridge(repository, refresh),
            databaseHandle = handle,
            network = network,
        )
    }
}
