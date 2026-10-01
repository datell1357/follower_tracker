package dev.datell.followertracker.sync

import android.content.Context
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebStorage
import dev.datell.followertracker.core.*
import dev.datell.followertracker.data.DataCipher
import java.net.URLDecoder
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SessionMetadata(val userAgent: String, val expectedId: String?, val savedAt: Long)

class SessionStore(context: Context, private val cipher: DataCipher) : SessionAccess {
    private val preferences = context.getSharedPreferences("session_metadata", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    @Synchronized
    fun save(provider: Provider, metadata: SessionMetadata) {
        val encrypted = cipher.seal(json.encodeToString(metadata).toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString(provider.name, Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
        CookieManager.getInstance().flush()
    }
    fun metadata(provider: Provider): SessionMetadata? = preferences.getString(provider.name, null)?.let {
        json.decodeFromString(cipher.open(Base64.decode(it, Base64.NO_WRAP)).toString(Charsets.UTF_8))
    }
    override fun header(provider: Provider, url: String): String {
        if (!provider.allows(url)) throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
        return CookieManager.getInstance().getCookie(url).orEmpty()
    }
    fun identity(provider: Provider): String? {
        val cookies = header(provider, provider.loginUrl).split(';').mapNotNull {
            val pieces = it.trim().split('=', limit = 2)
            if (pieces.size == 2) pieces[0] to pieces[1] else null
        }.toMap()
        return when (provider) {
            Provider.INSTAGRAM -> cookies["ds_user_id"]?.takeIf { it.matches(Regex("[0-9]+")) }
            Provider.FACEBOOK -> cookies["c_user"]?.takeIf { it.matches(Regex("[0-9]+")) }
            Provider.X -> cookies["twid"]?.let { encoded ->
                val decoded = URLDecoder.decode(encoded, "UTF-8").trim('"')
                Regex("^u=([0-9]+)$").matchEntire(decoded)?.groupValues?.get(1)
            }
            else -> metadata(provider)?.expectedId
        }
    }
    override fun hasAuthentication(provider: Provider): Boolean {
        val names = header(provider, provider.loginUrl).split(';').map { it.trim().substringBefore('=') }.toSet()
        return when (provider) {
            Provider.INSTAGRAM -> "sessionid" in names && identity(provider) != null
            Provider.TIKTOK -> names.any { it in setOf("sessionid", "sessionid_ss", "sid_tt", "sid_guard") }
            Provider.X -> "auth_token" in names && identity(provider) != null
            Provider.FACEBOOK -> "c_user" in names && "xs" in names
            Provider.REDDIT -> "reddit_session" in names || "token_v2" in names
        }
    }
    @Synchronized
    override fun acceptResponseCookies(provider: Provider, url: String, values: List<String>, expectedHeader: String) {
        if (!provider.allows(url)) throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
        if (metadata(provider) == null || header(provider, url) != expectedHeader) return
        values.forEach { CookieManager.getInstance().setCookie(url, it) }
        CookieManager.getInstance().flush()
    }
    @Synchronized
    fun disconnect(provider: Provider) {
        check(preferences.edit().remove(provider.name).commit())
        val manager = CookieManager.getInstance()
        val paths = listOf("/", "/api/", "/api/v1/", "/accounts/", "/login/")
        val known = when (provider) {
            Provider.INSTAGRAM -> setOf("sessionid", "ds_user_id", "csrftoken")
            Provider.TIKTOK -> setOf("sessionid", "sessionid_ss", "sid_tt", "sid_guard")
            Provider.X -> setOf("auth_token", "twid", "ct0")
            Provider.FACEBOOK -> setOf("c_user", "xs")
            Provider.REDDIT -> setOf("reddit_session", "token_v2")
        }
        for (host in listOf(provider.domain, "www.${provider.domain}", "m.${provider.domain}")) {
            for (path in paths) {
                val url = "https://$host$path"
                val observed = manager.getCookie(url).orEmpty().split(';').map { it.trim().substringBefore('=') }.filter(String::isNotBlank)
                for (name in observed + known) {
                    manager.setCookie(url, "$name=; Max-Age=0; Path=$path; Secure")
                    manager.setCookie(url, "$name=; Max-Age=0; Path=$path; Domain=.${provider.domain}; Secure")
                }
            }
            WebStorage.getInstance().deleteOrigin("https://$host")
        }
        manager.flush()
    }
}
