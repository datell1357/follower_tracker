package dev.datell.followertracker.ui

import dev.datell.followertracker.core.Provider
import java.net.URI

enum class FacebookPageAction { WAIT, OPEN_TARGET, CAPTURE }

/** One explicit link submission, including its return from an interactive login. */
data class FacebookPageConnectionRequest(
    val target: String,
    val returnAfterLogin: Boolean,
    val returnedAfterLogin: Boolean = false,
    val captureAttempted: Boolean = false,
) {
    val requiresConfirmation: Boolean get() = isFacebookPageShareLink(target)
    fun observing(current: String): FacebookPageConnectionRequest =
        if (!captureAttempted && Provider.FACEBOOK.allows(current) &&
            !canAutoConnect(Provider.FACEBOOK, current, authenticated = true, loading = false, busy = false))
            copy(returnAfterLogin = true) else this

    fun next(current: String, authenticated: Boolean, loading: Boolean, busy: Boolean): FacebookPageAction {
        if (captureAttempted || !canAutoConnect(Provider.FACEBOOK, current, authenticated, loading, busy)) return FacebookPageAction.WAIT
        if (returnAfterLogin && !returnedAfterLogin) return FacebookPageAction.OPEN_TARGET
        if (sameFacebookPageTarget(target, current)) return FacebookPageAction.CAPTURE
        // Opaque share tokens do not identify a Page. Confirm the resolved destination explicitly.
        if (requiresConfirmation && facebookPageDesktopUrl(current) != null) return FacebookPageAction.WAIT
        return FacebookPageAction.WAIT
    }

    companion object {
        fun create(input: String, authenticated: Boolean): FacebookPageConnectionRequest? {
            if (input.length > 16_384) return null
            val target = facebookPageUrl(input) ?: return null
            if (facebookPageDesktopUrl(target) == null && !isFacebookPageShareLink(target)) return null
            if (!canAutoConnect(Provider.FACEBOOK, target, authenticated = true, loading = false, busy = false)) return null
            return FacebookPageConnectionRequest(target, returnAfterLogin = !authenticated)
        }
    }
}

private fun isFacebookPageShareLink(url: String): Boolean = Provider.FACEBOOK.allows(url) &&
    runCatching { URI(url).path.matches(Regex("/share/[A-Za-z0-9]{1,512}/?")) }.getOrDefault(false)
