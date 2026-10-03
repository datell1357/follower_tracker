package dev.datell.followertracker.ui

/** Preserve the installed engine's version, as in AI Quota's interactive login view. */
fun loginUserAgent(defaultUserAgent: String): String = defaultUserAgent
    .replace("; wv", "")
    .replace("Version/4.0 ", "")
