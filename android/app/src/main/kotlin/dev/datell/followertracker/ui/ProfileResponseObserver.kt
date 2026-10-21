package dev.datell.followertracker.ui

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.datell.followertracker.core.Provider

/** Runs before the site's scripts, only in Instagram documents. No native bridge or additional requests. */
fun installProfileResponseObserver(web: WebView, provider: Provider): Boolean {
    if (provider != Provider.INSTAGRAM || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return false
    val script = web.context.assets.open("web-profile-observer.js").bufferedReader().use { it.readText() }
    WebViewCompat.addDocumentStartJavaScript(web, "$script;FollowerTrackerObserver.install('INSTAGRAM');",
        setOf("https://instagram.com", "https://*.instagram.com"))
    return true
}
