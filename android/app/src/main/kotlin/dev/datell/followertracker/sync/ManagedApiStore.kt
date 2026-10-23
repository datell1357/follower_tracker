package dev.datell.followertracker.sync

import android.content.Context
import dev.datell.followertracker.core.*
import dev.datell.followertracker.data.DataCipher
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable data class PendingFacebookLogin(val id: String, val expectedKey: String? = null)
@Serializable internal data class ManagedConnectionState(
    val origin: String = "", val installationToken: String = "", val keys: Set<String> = emptySet(),
    val revocations: Set<String> = emptySet(), val cancelledFlows: Set<String> = emptySet(),
    val pending: PendingFacebookLogin? = null, val revision: Long = 0,
)

/** Only a scoped installation credential is stored on the phone, never a Google key or Meta token. */
class ManagedApiStore internal constructor(private val read: () -> String?, private val write: (String) -> Unit,
    private val seal: (ByteArray) -> ByteArray, private val open: (ByteArray) -> ByteArray) {
    constructor(context: Context, cipher: DataCipher = DataCipher()) : this(
        { context.getSharedPreferences("managed_api", Context.MODE_PRIVATE).getString("state", null) },
        { value -> check(context.getSharedPreferences("managed_api", Context.MODE_PRIVATE).edit().putString("state", value).commit()) },
        cipher::seal, cipher::open)
    private var cached: Pair<String?, ManagedConnectionState>? = null
    @Synchronized internal fun state(): ManagedConnectionState {
        val encoded = read()
        cached?.takeIf { it.first == encoded }?.let { return it.second }
        val state = if (encoded == null) ManagedConnectionState() else
            Json.decodeFromString<ManagedConnectionState>(open(Base64.getDecoder().decode(encoded)).toString(Charsets.UTF_8))
        cached = encoded to state
        return state
    }
    @Synchronized private fun save(value: ManagedConnectionState) {
        val encoded = Base64.getEncoder().encodeToString(seal(Json.encodeToString(value).toByteArray()))
        write(encoded); cached = encoded to value
    }
    @Synchronized fun installation(origin: String, token: String) {
        require(token.matches(Regex("[A-Za-z0-9_-]{43}")))
        save(state().copy(origin = origin, installationToken = token, revision = state().revision + 1))
    }
    @Synchronized internal fun forgetInstallation() {
        save(state().copy(origin = "", installationToken = "", pending = null,
            revocations = emptySet(), cancelledFlows = emptySet(), revision = state().revision + 1))
    }
    @Synchronized fun owns(account: Account) = account.key in state().keys
    @Synchronized fun bind(account: Account) {
        save(state().copy(keys = state().keys + account.key, revision = state().revision + 1))
    }
    @Synchronized fun pending() = state().pending
    @Synchronized fun pending(value: PendingFacebookLogin?) { save(state().copy(pending = value)) }
    @Synchronized fun cancelLogin() {
        val current = state()
        save(current.copy(pending = null, cancelledFlows = current.cancelledFlows + listOfNotNull(current.pending?.id)))
    }
    @Synchronized fun remove(account: Account) {
        val current = state()
        save(current.copy(keys = current.keys - account.key, revision = current.revision + 1,
            revocations = if (current.installationToken.isNotEmpty() && account.provider == Provider.FACEBOOK && account.key in current.keys)
                current.revocations + account.stableId else current.revocations))
    }
    @Synchronized fun clear(provider: Provider) {
        val current = state()
        val keys = current.keys.filter { it.startsWith(provider.name + ":") }.toSet()
        save(current.copy(keys = current.keys - keys, revision = current.revision + 1,
            revocations = if (provider == Provider.FACEBOOK && current.installationToken.isNotEmpty())
                current.revocations + "*" else current.revocations,
            pending = if (provider == Provider.FACEBOOK) null else current.pending,
            cancelledFlows = if (provider == Provider.FACEBOOK) current.cancelledFlows + listOfNotNull(current.pending?.id) else current.cancelledFlows))
    }
    @Synchronized internal fun acknowledgeRevocation(id: String) { save(state().copy(revocations = state().revocations - id)) }
    @Synchronized internal fun acknowledgeCancel(id: String) { save(state().copy(cancelledFlows = state().cancelledFlows - id)) }
    @Synchronized fun guard(): () -> Boolean {
        val revision = state().revision
        return { synchronized(this) { state().revision == revision } }
    }
}
