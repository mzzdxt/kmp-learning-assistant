package dev.kmpjourney.pocketlab.data

import app.cash.sqldelight.driver.native.NativeSqliteDriver

fun openIosPocketLabDatabase(
    name: String = "pocketlab.db",
): PocketLabDatabaseHandle = openPocketLabDatabase(
    NativeSqliteDriver(
        schema = PocketLabSqlDatabase.Schema,
        name = name,
    ),
)
