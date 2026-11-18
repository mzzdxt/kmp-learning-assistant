package dev.kmpjourney.pocketlab.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.inMemoryDriver

internal actual fun createTestSqlDriver(): SqlDriver =
    inMemoryDriver(PocketLabSqlDatabase.Schema)
