package dev.kmpjourney.pocketlab.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Properties

fun openJvmPocketLabDatabase(path: String): PocketLabDatabaseHandle = openPocketLabDatabase(
    JdbcSqliteDriver(
        url = "jdbc:sqlite:$path",
        properties = Properties(),
        schema = PocketLabSqlDatabase.Schema,
    ),
)
