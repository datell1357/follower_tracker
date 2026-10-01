package dev.datell.followertracker.sync

import dev.datell.followertracker.core.Provider

interface SessionAccess {
    fun header(provider: Provider, url: String): String
    fun hasAuthentication(provider: Provider): Boolean
    fun acceptResponseCookies(provider: Provider, url: String, values: List<String>, expectedHeader: String)
}
