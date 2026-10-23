package dev.datell.followertracker.sync

import android.content.Context
import dev.datell.followertracker.core.*
import dev.datell.followertracker.data.DataCipher
import java.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Encrypted at rest with the existing Android Keystore key; app backup is disabled. */
class OfficialApiStore internal constructor(private val readValue: (String) -> String?,
    private val writeValue: (String, String?) -> Unit,
    private val seal: (ByteArray) -> ByteArray, private val open: (ByteArray) -> ByteArray) {
    constructor(context: Context, cipher: DataCipher = DataCipher()) : this(
        { key -> context.getSharedPreferences("official_api", Context.MODE_PRIVATE).getString(key, null) },
        { key, value -> check(context.getSharedPreferences("official_api", Context.MODE_PRIVATE).edit().putString(key, value).commit()) },
        cipher::seal, cipher::open)

    private val cache = mutableMapOf<Provider, Pair<String, Map<String, String>>>()
    @Synchronized fun youtubeKey(): String? = values(Provider.YOUTUBE)["key"]
    @Synchronized fun pageToken(id: String): String? = values(Provider.FACEBOOK)[id]
    @Synchronized fun saveYouTubeKey(key: String) = save(Provider.YOUTUBE, mapOf("key" to key))
    @Synchronized fun savePageToken(id: String, token: String) {
        require(id.matches(Regex("[0-9]{1,32}")))
        save(Provider.FACEBOOK, values(Provider.FACEBOOK) + (id to token))
    }
    @Synchronized fun removeAccount(account: Account) {
        if (account.provider == Provider.FACEBOOK && account.accountType == AccountType.PAGE)
            save(Provider.FACEBOOK, values(Provider.FACEBOOK) - account.stableId)
        if (account.provider == Provider.YOUTUBE) clear(Provider.YOUTUBE)
    }
    @Synchronized fun clear(provider: Provider) { writeValue(provider.name, null); cache.remove(provider) }
    @Synchronized fun guard(provider: Provider): () -> Boolean {
        val captured = readValue(provider.name)
        return { synchronized(this) { captured == readValue(provider.name) } }
    }
    private fun values(provider: Provider): Map<String, String> {
        val encoded = readValue(provider.name) ?: return emptyMap()
        cache[provider]?.takeIf { it.first == encoded }?.let { return it.second }
        return try {
            Json.decodeFromString<Map<String, String>>(open(Base64.getDecoder().decode(encoded)).toString(Charsets.UTF_8))
                .also { cache[provider] = encoded to it }
        } catch (_: Exception) { throw CollectionFailure(SyncStatus.CHECK_REQUIRED) }
    }
    private fun save(provider: Provider, values: Map<String, String>) {
        val encoded = Base64.getEncoder().encodeToString(seal(Json.encodeToString(values).toByteArray(Charsets.UTF_8)))
        writeValue(provider.name, encoded)
        cache[provider] = encoded to values
    }
}
