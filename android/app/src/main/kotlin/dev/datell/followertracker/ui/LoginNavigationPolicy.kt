package dev.datell.followertracker.ui

import dev.datell.followertracker.core.Provider
import java.net.URI

enum class LoginNavigation { ALLOW, EXTERNAL_SIGN_IN, BLOCK }

/** Login redirects have a separate boundary from the hosts receiving collection cookies. */
fun loginNavigation(provider: Provider, url: String): LoginNavigation {
    if (provider.allows(url)) return LoginNavigation.ALLOW
    val uri = runCatching { URI(url) }.getOrNull() ?: return LoginNavigation.BLOCK
    val host = uri.host?.lowercase() ?: return LoginNavigation.BLOCK
    if (uri.scheme != "https" || uri.userInfo != null || uri.port !in listOf(-1, 443)) return LoginNavigation.BLOCK
    fun belongsTo(domain: String) = host == domain || host.endsWith(".$domain")
    if (provider == Provider.INSTAGRAM && belongsTo("facebook.com")) return LoginNavigation.ALLOW
    if (provider == Provider.X && belongsTo("twitter.com")) return LoginNavigation.ALLOW
    if (host == "accounts.google.com" || host == "appleid.apple.com") return LoginNavigation.EXTERNAL_SIGN_IN
    return LoginNavigation.BLOCK
}
