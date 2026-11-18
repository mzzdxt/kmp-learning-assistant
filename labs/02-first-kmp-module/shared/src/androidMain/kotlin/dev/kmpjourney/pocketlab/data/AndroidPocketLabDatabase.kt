package dev.kmpjourney.pocketlab.data

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

fun openAndroidPocketLabDatabase(
    context: Context,
    name: String = "pocketlab.db",
): PocketLabDatabaseHandle = openPocketLabDatabase(
    AndroidSqliteDriver(
        schema = PocketLabSqlDatabase.Schema,
        context = context.applicationContext,
        name = name,
    ),
)
