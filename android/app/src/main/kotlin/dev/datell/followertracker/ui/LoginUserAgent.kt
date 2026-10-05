package dev.datell.followertracker.ui

/** Preserve the installed engine's version, as in AI Quota's interactive login view. */
fun loginUserAgent(defaultUserAgent: String): String = defaultUserAgent
    .replace("; wv", "")
    .replace("Version/4.0 ", "")

/** Select Facebook's desktop Page representation using the installed engine's real version. */
fun facebookPageUserAgent(userAgent: String): String? = Regex("Chrome/[0-9.]+").find(userAgent)?.value?.let { version ->
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $version Safari/537.36"
}
