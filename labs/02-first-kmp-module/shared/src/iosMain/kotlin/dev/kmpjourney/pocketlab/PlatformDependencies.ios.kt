package dev.kmpjourney.pocketlab

import platform.CoreFoundation.CFAbsoluteTimeGetCurrent
import platform.CoreFoundation.kCFAbsoluteTimeIntervalSince1970
import platform.Foundation.NSUUID

actual fun createPlatformClock(): EpochClock = object : EpochClock {
    override fun nowMilliseconds(): Long =
        (
            (CFAbsoluteTimeGetCurrent() + kCFAbsoluteTimeIntervalSince1970) *
                MILLISECONDS_PER_SECOND
        ).toLong()
}

actual fun createPlatformIdGenerator(): ExperimentIdGenerator = object : ExperimentIdGenerator {
    override fun nextId(): String = NSUUID().UUIDString()
}

private const val MILLISECONDS_PER_SECOND = 1_000

