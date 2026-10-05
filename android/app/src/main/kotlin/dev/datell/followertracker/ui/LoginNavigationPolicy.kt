package dev.datell.followertracker.ui

import dev.datell.followertracker.core.Provider
import java.net.URI
import java.net.URLDecoder

enum class LoginNavigation { ALLOW, AUTHENTICATE, BLOCK }

fun facebookPageUrl(input: String): String? {
    val text = input.trim()
    val url = when {
        text.startsWith("http://", true) -> "https://" + text.substring(7)
        text.startsWith("https://", true) -> "https://" + text.substring(8)
        text.startsWith("facebook.com/", true) || text.startsWith("www.facebook.com/", true) || text.startsWith("m.facebook.com/", true) -> "https://$text"
        else -> return null
    }
    return url.takeIf(Provider.FACEBOOK::allows)
}

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

/** Facebook supplies an HTTPS profile or named Page fallback for a native-app handoff. */
fun facebookBrowserFallback(provider: Provider, sourceUrl: String, targetUrl: String): String? {
    if (provider != Provider.FACEBOOK || !provider.allows(sourceUrl)) return null
    val intent = runCatching { URI(targetUrl) }.getOrNull() ?: return null
    if (intent.scheme != "intent" || intent.host != "profile" || intent.userInfo != null || intent.port != -1) return null
    val parts = intent.rawFragment?.split(';') ?: return null
    if (parts.firstOrNull() != "Intent" || parts.lastOrNull() != "end") return null
    fun singleValue(key: String) = parts.filter { it.startsWith("$key=") }.singleOrNull()?.substringAfter('=')
    if (singleValue("scheme") != "fb" || singleValue("package") !in setOf("com.facebook.katana", "com.facebook.lite")) return null
    val encoded = singleValue("S.browser_fallback_url") ?: return null
    // Intent extras use URI decoding, where a literal plus is not a space.
    val fallback = runCatching { URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return null
    if (!provider.allows(fallback)) return null
    val page = runCatching { URI(fallback) }.getOrNull() ?: return null
    if (page.path == "/profile.php") return fallback
    return fallback.takeIf { facebookNamedProfilePath(page.path) }
}

private fun facebookNamedProfilePath(path: String): Boolean {
    val name = path.trim('/').lowercase()
    val reserved = setOf("login", "checkpoint", "challenge", "two_factor", "two-factor", "twofactor", "reel", "reels", "watch",
        "groups", "events", "marketplace", "search", "pages", "settings", "notifications", "friends", "bookmarks", "gaming",
        "help", "privacy", "policies", "photos", "stories")
    return path.matches(Regex("/[A-Za-z0-9.]{1,255}/?")) && !name.endsWith(".php") && name !in reserved
}

private fun facebookProfileID(uri: URI): String? = runCatching {
    uri.rawQuery.orEmpty().split('&').map { part ->
        URLDecoder.decode(part.substringBefore('='), "UTF-8") to URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
    }.filter { it.first == "id" }.singleOrNull()?.second?.takeIf { it.matches(Regex("[0-9]+")) }
}.getOrNull()

/** Used only after a Page confirmation; login retains its normal mobile user agent. */
fun facebookPageDesktopUrl(source: String): String? {
    if (!Provider.FACEBOOK.allows(source)) return null
    val uri = runCatching { URI(source) }.getOrNull() ?: return null
    val path = uri.path.trimEnd('/')
    val target = if (path == "/profile.php") facebookProfileID(uri) != null else facebookNamedProfilePath(uri.path) ||
        path.matches(Regex("/(pages|people)/[^/]+/[0-9]+")) || path.matches(Regex("/p/[^/]+-[0-9]+"))
    if (!target) return null
    return "https://www.facebook.com${uri.rawPath}" + (uri.rawQuery?.let { "?$it" } ?: "")
}

fun sameFacebookPageTarget(requested: String, current: String): Boolean {
    if (facebookPageDesktopUrl(requested) == null || facebookPageDesktopUrl(current) == null) return false
    val a = URI(requested); val b = URI(current)
    if (!a.path.trimEnd('/').equals(b.path.trimEnd('/'), true)) return false
    return a.path.trimEnd('/') != "/profile.php" || facebookProfileID(a) == facebookProfileID(b)
}

/** Collection must stay on the authenticated person's profile, including native-app fallbacks. */
fun facebookCollectionFallback(provider: Provider, sourceUrl: String, targetUrl: String, expectedId: String): String? {
    if (!expectedId.matches(Regex("[0-9]+"))) return null
    val fallback = facebookBrowserFallback(provider, sourceUrl, targetUrl) ?: return null
    val uri = runCatching { java.net.URI(fallback) }.getOrNull() ?: return null
    if (uri.path != "/profile.php") return null
    val query = runCatching { uri.rawQuery.orEmpty().split('&').map { part ->
        val key = URLDecoder.decode(part.substringBefore('='), "UTF-8")
        val value = URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
        key to value
    } }.getOrNull() ?: return null
    return fallback.takeIf { query.filter { it.first == "id" }.map { it.second } == listOf(expectedId) }
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
