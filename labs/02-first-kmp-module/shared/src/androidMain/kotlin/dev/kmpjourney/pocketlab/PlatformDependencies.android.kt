package dev.kmpjourney.pocketlab

import java.util.UUID

internal actual fun createPlatformClock(): EpochClock = object : EpochClock {
    override fun nowMilliseconds(): Long = System.currentTimeMillis()
}

internal actual fun createPlatformIdGenerator(): ExperimentIdGenerator = object : ExperimentIdGenerator {
    override fun nextId(): String = UUID.randomUUID().toString()
}

