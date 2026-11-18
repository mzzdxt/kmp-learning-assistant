package dev.kmpjourney.pocketlab.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android

internal actual fun createPlatformHttpClient(): HttpClient = HttpClient(Android) {
    installPocketLabDefaults()
}

