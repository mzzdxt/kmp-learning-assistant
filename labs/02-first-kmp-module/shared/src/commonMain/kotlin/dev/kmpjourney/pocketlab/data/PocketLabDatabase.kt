package dev.kmpjourney.pocketlab.data

import app.cash.sqldelight.db.SqlDriver

class PocketLabDatabaseHandle internal constructor(
    val database: PocketLabSqlDatabase,
    private val driver: SqlDriver,
) {
    fun close() {
        driver.close()
    }
}

fun openPocketLabDatabase(driver: SqlDriver): PocketLabDatabaseHandle =
    PocketLabDatabaseHandle(
        database = PocketLabSqlDatabase(driver),
        driver = driver,
    )
