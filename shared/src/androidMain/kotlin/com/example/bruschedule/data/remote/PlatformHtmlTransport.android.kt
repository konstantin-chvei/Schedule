package com.example.bruschedule.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies

actual fun createPlatformHtmlTransport(): HtmlTransport = KtorHtmlTransport(HttpClient(CIO) {
    install(HttpCookies) {
        storage = AcceptAllCookiesStorage()
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 15_000
        connectTimeoutMillis = 8_000
        socketTimeoutMillis = 15_000
    }
})
