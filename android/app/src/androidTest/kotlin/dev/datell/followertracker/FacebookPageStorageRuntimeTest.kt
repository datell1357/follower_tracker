package dev.datell.followertracker

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.datell.followertracker.core.*
import dev.datell.followertracker.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Isolated Room fixtures; never changes the app database or any SNS cookie. */
@RunWith(AndroidJUnit4::class)
class FacebookPageStorageRuntimeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val personal = Account(Provider.FACEBOOK, "42", "fixture.owner", profileUrl = "https://www.facebook.com/profile.php?id=42", connectedAt = 10)
    private fun page(id: String = "99") = Account(Provider.FACEBOOK, id, "fixture.page", "Fixture Page",
        "https://www.facebook.com/profile.php?id=$id", status = SyncStatus.FOREGROUND_ONLY,
        connectedAt = 20, accountType = AccountType.PAGE, sessionOwnerId = "42")
    private fun metric(account: Account, at: Long, count: Long = 5) = MetricSnapshot(account.key, at, count, null, source = "synthetic-page-fixture")

    @Test fun personalAndSeveralPagesHaveIndependentHistoryAndDisconnect() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, TrackerDatabase::class.java).build()
        try {
            val repository = TrackerRepository(db, DataCipher())
            val first = page(); val second = page("88")
            repository.saveObservation(personal, metric(personal, 10))
            repository.saveObservation(first, metric(first, 20, 0))
            repository.saveObservation(second, metric(second, 21, 8))
            repository.saveObservation(first.copy(connectedAt = 30), metric(first, 30, 7))
            assertEquals(3, repository.accounts().size)
            assertEquals(listOf(0L, 7L), repository.history(first.key).map { it.followers })
            assertEquals(20L, repository.account(first.key)?.connectedAt)
            assertEquals(listOf(5L), repository.history(personal.key).map { it.followers })
            assertEquals(listOf(8L), repository.history(second.key).map { it.followers })
            repository.disconnect(first.key)
            assertEquals(setOf(personal.key, second.key), repository.accounts().map { it.key }.toSet())
            assertTrue(repository.history(first.key).isEmpty())
            try {
                val other = personal.copy(stableId = "77")
                repository.saveObservation(other, metric(other, 40)); fail("A second personal account must still be rejected")
            } catch (failure: CollectionFailure) { assertEquals(SyncStatus.CHECK_REQUIRED, failure.status) }
            assertEquals(setOf(personal.key, second.key), repository.widgetOverviews().map { it.account.key }.toSet())
        } finally { db.close() }
    }

    @Test fun versionTwoMigrationPreservesPersonalAccountMetricsAndRelationshipRecords() = runBlocking {
        val name = "page-migration-fixture-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        var db: TrackerDatabase? = null
        try {
            val schema = InstrumentationRegistry.getInstrumentation().context.assets
                .open("dev.datell.followertracker.data.TrackerDatabase/2.json")
                .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
            SQLiteDatabase.openOrCreateDatabase(file, null).use { legacy ->
                legacy.setForeignKeyConstraintsEnabled(true)
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    legacy.execSQL(entity.getString("createSql").replace('$' + "{TABLE_NAME}", table))
                    val indexes = entity.optJSONArray("indices")
                    if (indexes != null) for (j in 0 until indexes.length()) legacy.execSQL(indexes.getJSONObject(j).getString("createSql").replace('$' + "{TABLE_NAME}", table))
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) legacy.execSQL(setup.getString(i))
                legacy.version = 2
                legacy.insertOrThrow("accounts", null, ContentValues().apply {
                    put("key", personal.key); put("provider", personal.provider.name); put("connectedAt", personal.connectedAt); put("json", Json.encodeToString(personal))
                })
                legacy.insertOrThrow("metrics", null, ContentValues().apply {
                    put("accountKey", personal.key); put("observedAt", 10L); put("followers", 5L)
                    put("precision", "EXACT"); put("source", "synthetic-legacy-fixture"); put("adapterVersion", 1)
                })
                legacy.insertOrThrow("scans", null, ContentValues().apply {
                    put("accountKey", personal.key); put("direction", "FOLLOWERS"); put("finishedAt", 11L); put("encrypted", byteArrayOf(1, 2, 3))
                })
                legacy.insertOrThrow("relationship_changes", null, ContentValues().apply {
                    put("id", "fixture-change"); put("accountKey", personal.key); put("detectedAt", 11L)
                    put("state", "MISSING_PENDING"); put("encrypted", byteArrayOf(4, 5, 6))
                })
                legacy.insertOrThrow("relationship_history_cursor", null, ContentValues().apply { put("accountKey", personal.key); put("finishedAt", 11L) })
            }
            val upgraded = Room.databaseBuilder(context, TrackerDatabase::class.java, name)
                .addMigrations(TrackerDatabase.MIGRATION_2_3).build()
            db = upgraded
            val repository = TrackerRepository(upgraded, DataCipher())
            assertEquals(personal, repository.account(personal.key))
            assertEquals(listOf(5L), repository.history(personal.key).map { it.followers })
            assertArrayEquals(byteArrayOf(1, 2, 3), upgraded.dao().scans(personal.key, "FOLLOWERS", 1).single().encrypted)
            assertArrayEquals(byteArrayOf(4, 5, 6), upgraded.dao().relationshipChanges(personal.key).single().encrypted)
            assertEquals(11L, upgraded.dao().historyCursor(personal.key)?.finishedAt)
            repository.saveObservation(page(), metric(page(), 20))
            repository.saveObservation(page("88"), metric(page("88"), 21))
            assertEquals(3, repository.accounts().size)
        } finally { db?.close(); context.deleteDatabase(name) }
    }
}
