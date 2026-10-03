package dev.datell.followertracker.ui

import dev.datell.followertracker.core.Provider
import java.net.URI

enum class LoginNavigation { ALLOW, AUTHENTICATE, BLOCK }

/** In-memory diagnostic metadata. Never retains user info, paths, queries, fragments, or OAuth codes. */
data class LoginDestination(val scheme: String?, val host: String?)

fun loginDestination(url: String): LoginDestination {
    val uri = runCatching { URI(url) }.getOrNull()
    return LoginDestination(uri?.scheme?.lowercase(), uri?.host?.lowercase())
}

/** TikTok's optional native-app handoff is consumed while the signed-in website remains open. */
fun isOptionalTikTokAppLink(provider: Provider, sourceUrl: String, targetUrl: String): Boolean {
    if (provider != Provider.TIKTOK || !provider.allows(sourceUrl)) return false
    val uri = runCatching { URI(targetUrl) }.getOrNull() ?: return false
    return uri.scheme in setOf("snssdk1340", "snssdk1233", "snssdk1180") &&
        uri.host in setOf("aweme", "user") && uri.userInfo == null && uri.port == -1
}

/** Login redirects have a separate boundary from the hosts receiving collection cookies. */
fun loginNavigation(provider: Provider, url: String): LoginNavigation {
    if (provider.allows(url)) return LoginNavigation.ALLOW
    val uri = runCatching { URI(url) }.getOrNull() ?: return LoginNavigation.BLOCK
    val host = uri.host?.lowercase() ?: return LoginNavigation.BLOCK
    if (uri.scheme != "https" || uri.userInfo != null || uri.port !in listOf(-1, 443)) return LoginNavigation.BLOCK
    fun belongsTo(domain: String) = host == domain || host.endsWith(".$domain")
    if (provider == Provider.INSTAGRAM && belongsTo("facebook.com")) return LoginNavigation.ALLOW
    if (provider == Provider.X && belongsTo("twitter.com")) return LoginNavigation.ALLOW
    if (host == "accounts.google.com" || host == "appleid.apple.com") return LoginNavigation.AUTHENTICATE
    return LoginNavigation.BLOCK
}
