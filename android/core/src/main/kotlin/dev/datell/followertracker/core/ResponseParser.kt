package dev.datell.followertracker.core

import kotlinx.serialization.json.*

object ResponseParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun objectBody(body: String): JsonObject = try {
        json.parseToJsonElement(body).jsonObject
    } catch (_: Exception) {
        val status = if (Regex("(?i)(login_required|accounts/login|i/flow/login)").containsMatchIn(body))
            SyncStatus.REAUTH_REQUIRED else SyncStatus.FORMAT_CHANGED
        throw CollectionFailure(status)
    }

    /** Only an explicit Page connection may use a target ID different from the login owner. */
    fun facebookPageCapture(body: String, sessionOwnerId: String?, expected: Account?, now: Long): Pair<Account, MetricSnapshot> {
        val root = objectBody(body)
        if (root["error"] != null) throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
        val id = root.text("stableId") ?: throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
        if (sessionOwnerId?.matches(Regex("[0-9]+")) != true) throw CollectionFailure(SyncStatus.REAUTH_REQUIRED)
        if (root.text("provider") != Provider.FACEBOOK.name || root.text("accountType") != AccountType.PAGE.name ||
            root.text("sessionOwnerId") != sessionOwnerId || id == sessionOwnerId || !id.matches(Regex("[0-9]+")) ||
            expected != null && (expected.provider != Provider.FACEBOOK || expected.accountType != AccountType.PAGE || expected.stableId != id))
            throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
        if (root.text("source") != "facebook-webview-page" || root.text("precision") != Precision.EXACT.name)
            throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
        val profile = root.text("profileURL") ?: throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
        val uri = runCatching { java.net.URI(profile) }.getOrNull()
        if (!Provider.FACEBOOK.allows(profile) || uri?.path != "/profile.php" || uri.rawQuery != "id=$id" || uri.fragment != null)
            throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
        val name = root.text("username") ?: throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
        val displayName = root.text("displayName") ?: throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
        val followers = root.count("followers").takeIf { it <= 9_007_199_254_740_991L }
            ?: throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
        val account = Account(Provider.FACEBOOK, id, name, displayName, profile,
            status = SyncStatus.FOREGROUND_ONLY,
            capabilities = Capabilities(count = Capability.FOREGROUND_ONLY, followers = Capability.UNAVAILABLE,
                following = Capability.UNAVAILABLE, background = Capability.FOREGROUND_ONLY),
            connectedAt = expected?.connectedAt ?: now, lastAttemptAt = now,
            countTransport = CountTransport.PROFILE_BROWSER, accountType = AccountType.PAGE, sessionOwnerId = sessionOwnerId)
        // Page likes, friends and Page follows never substitute for its follower total.
        return account to MetricSnapshot(account.key, now, followers, null, source = "facebook-webview-page")
    }

    fun instagramProfile(body: String, expectedId: String?, now: Long): Pair<Account, MetricSnapshot> {
        val root = objectBody(body)
        checkServiceStatus(root)
        val user = root["user"] as? JsonObject ?: throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
        val id = user.text("pk") ?: user.text("id") ?: throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
        if (expectedId != null && id != expectedId) throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
        val name = user.text("username") ?: throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
        val account = Account(Provider.INSTAGRAM, id, name, user.text("full_name") ?: name,
            "https://www.instagram.com/$name/", connectedAt = now)
        return account to MetricSnapshot(account.key, now, user.count("follower_count"),
            user.count("following_count"), source = "instagram-web-session")
    }

    fun instagramPage(body: String, ownerKey: String): RelationshipPage {
        val root = objectBody(body)
        checkServiceStatus(root)
        val users = root["users"] as? JsonArray ?: throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
        val members = users.map {
            val user = it as? JsonObject ?: throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
            Member(user.text("pk") ?: user.text("id") ?: throw CollectionFailure(SyncStatus.FORMAT_CHANGED),
                user.text("username") ?: throw CollectionFailure(SyncStatus.FORMAT_CHANGED),
                user.text("full_name") ?: user.text("username")!!)
        }
        val next = root.text("next_max_id")?.takeIf(String::isNotBlank)
        val more = (root["more_available"] as? JsonPrimitive)?.booleanOrNull ?: (next != null)
        return RelationshipPage(ownerKey, members, next, more)
    }

    fun instagramWebProfile(body: String, expectedId: String, now: Long): Pair<Account, MetricSnapshot> {
        val root = objectBody(body)
        checkServiceStatus(root)
        val user = (root["data"] as? JsonObject)?.get("user") as? JsonObject
            ?: throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
        val id = user.text("id") ?: user.text("pk") ?: throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
        if (id != expectedId) throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
        val name = user.text("username") ?: throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
        val account = Account(Provider.INSTAGRAM, id, name, user.text("full_name") ?: name,
            "https://www.instagram.com/$name/", connectedAt = now)
        fun count(edge: String, direct: String): Long = if (user.containsKey(edge))
            (user[edge] as? JsonObject)?.count("count") ?: throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
        else user.count(direct)
        return account to MetricSnapshot(account.key, now,
            count("edge_followed_by", "follower_count"), count("edge_follow", "following_count"),
            source = "instagram-web-profile-session")
    }

    fun redditProfile(body: String, expectedId: String?, now: Long): Pair<Account, MetricSnapshot> {
        val root = objectBody(body)
        val user = root["data"] as? JsonObject ?: root
        val id = user.text("id") ?: throw CollectionFailure(SyncStatus.REAUTH_REQUIRED)
        if (expectedId != null && id != expectedId) throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
        val name = user.text("name") ?: throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
        val profile = user["subreddit"] as? JsonObject ?: throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
        val account = Account(Provider.REDDIT, id, name, profile.text("title") ?: name,
            "https://www.reddit.com/user/$name/", connectedAt = now)
        return account to MetricSnapshot(account.key, now, profile.count("subscribers"), null,
            source = "reddit-profile-session")
    }

    fun tiktokProfile(html: String, expectedId: String, now: Long): Pair<Account, MetricSnapshot> {
        val scripts = Regex("<script[^>]*>([\\s\\S]*?)</script>", RegexOption.IGNORE_CASE)
        for (match in scripts.findAll(html)) {
            val root = runCatching { json.parseToJsonElement(match.groupValues[1]).jsonObject }.getOrNull() ?: continue
            val scope = root["__DEFAULT_SCOPE__"] as? JsonObject ?: continue
            val context = scope["webapp.app-context"] as? JsonObject ?: continue
            val signedIn = context["user"] as? JsonObject
                ?: (context["userInfo"] as? JsonObject)?.get("user") as? JsonObject ?: continue
            val owner = signedIn.text("id") ?: signedIn.text("uid") ?: continue
            if (owner != expectedId) throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
            val detail = scope["webapp.user-detail"] as? JsonObject ?: continue
            val info = detail["userInfo"] as? JsonObject ?: continue
            val user = info["user"] as? JsonObject ?: continue
            val stats = info["stats"] as? JsonObject ?: continue
            val id = user.text("id") ?: continue
            if (id != expectedId) throw CollectionFailure(SyncStatus.CHECK_REQUIRED)
            val name = user.text("uniqueId") ?: continue
            val account = Account(Provider.TIKTOK, id, name, user.text("nickname") ?: name,
                "https://www.tiktok.com/@$name", connectedAt = now)
            return account to MetricSnapshot(account.key, now, stats.count("followerCount"),
                stats.count("followingCount"), source = "tiktok-profile-session")
        }
        throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
    }

    private fun checkServiceStatus(root: JsonObject) {
        val message = root.text("message").orEmpty()
        if (root.text("status") == "fail") {
            val status = when {
                message.contains("login", true) -> SyncStatus.REAUTH_REQUIRED
                message.contains("challenge", true) || root["challenge"] != null -> SyncStatus.CHECK_REQUIRED
                message.contains("wait", true) || message.contains("rate", true) -> SyncStatus.RATE_LIMITED
                else -> SyncStatus.FORMAT_CHANGED
            }
            throw CollectionFailure(status)
        }
    }

    private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)
        ?.takeUnless { it is JsonNull }?.content?.takeIf(String::isNotBlank)

    private fun JsonObject.count(name: String): Long {
        val value = (get(name) as? JsonPrimitive)?.longOrNull
        if (value == null || value < 0) throw CollectionFailure(SyncStatus.FORMAT_CHANGED)
        return value
    }
}
