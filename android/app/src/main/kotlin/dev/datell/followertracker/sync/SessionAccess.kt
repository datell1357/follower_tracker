package dev.datell.followertracker.sync

import dev.datell.followertracker.core.Provider

interface SessionAccess {
    fun header(provider: Provider, url: String): String
    fun hasAuthentication(provider: Provider): Boolean
    fun acceptResponseCookies(provider: Provider, url: String, values: List<String>, expectedHeader: String)
}

/** Compare the saved session token at commit time, after any concurrent logout or reconnect. */
internal fun sessionWriteGuard(saved: String?, current: () -> String?): () -> Boolean =
    { current() == saved }
