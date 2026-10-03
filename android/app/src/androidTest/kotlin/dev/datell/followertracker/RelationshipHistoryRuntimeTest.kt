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

@RunWith(AndroidJUnit4::class)
class RelationshipHistoryRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val owner = Account(Provider.INSTAGRAM, "history_fixture_owner", "fixture_owner",
        profileUrl = "https://www.instagram.com/fixture_owner/", connectedAt = 1)
    private fun scan(at: Long, present: Boolean, direction: Direction = Direction.FOLLOWERS, complete: Boolean = true) =
        RelationshipSnapshot(owner.key, direction, at, at + 1,
            if (present) listOf(Member("history_fixture_member", "private_fixture_username")) else emptyList(), complete, true, "synthetic-complete")
    private val cipher = DataCipher()

    @Test fun persistentEpisodesSurviveRepeatedScansFailuresReappearanceAndReconnection() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, TrackerDatabase::class.java).build()
        try {
            val repository = TrackerRepository(database, cipher)
            repository.saveObservation(owner, MetricSnapshot(owner.key, 1, 1, 1, source = "synthetic-runtime-fixture"))
            for (time in listOf(100L, 200L, 300L, 400L, 500L, 600L)) {
                repository.saveScans(scan(time, time == 100L), scan(time, true, Direction.FOLLOWING))
            }
            val first = repository.relationshipChanges(owner.key).single()
            assertEquals(201L, first.detectedAt); assertEquals(601L, first.checkedAt)
            assertEquals(5, first.absenceChecks); assertEquals(RelationshipChangeState.REPEATED_ABSENCE, first.state)
            assertNotNull(UUID.fromString(first.id))
            val stored = database.dao().relationshipChanges(owner.key).single()
            assertFalse(stored.encrypted.toString(Charsets.UTF_8).contains("private_fixture_username"))
            try { repository.saveScans(scan(700, false, complete = false), scan(700, true, Direction.FOLLOWING)); fail("Partial scans cannot alter history") }
            catch (_: IllegalArgumentException) { }
            repository.updateListStatus(owner.key, SyncStatus.LIST_INCOMPLETE)
            assertEquals(listOf(first), repository.relationshipChanges(owner.key))
            repository.saveScans(scan(800, true), scan(800, true, Direction.FOLLOWING))
            assertEquals(RelationshipChangeState.REOBSERVED, repository.relationshipChanges(owner.key).single().state)
            repository.saveScans(scan(900, false), scan(900, true, Direction.FOLLOWING))
            val episodes = repository.relationshipChanges(owner.key)
            assertEquals(2, episodes.size)
            assertEquals(listOf(901L, 201L), episodes.map { it.detectedAt })
            repository.disconnect(owner.key)
            assertTrue(database.dao().relationshipChanges(owner.key).isEmpty())
            assertNull(database.dao().historyCursor(owner.key))
            val reconnected = owner.copy(connectedAt = 2)
            repository.saveObservation(reconnected, MetricSnapshot(owner.key, 2, 1, 1, source = "synthetic-runtime-fixture"))
            repository.saveScans(scan(1000, false), scan(1000, true, Direction.FOLLOWING), expectedConnectedAt = 1)
            assertTrue(repository.relationshipChanges(owner.key).isEmpty())
            assertNull(repository.report(owner.key))
        } finally { database.close() }
    }

    @Test fun versionOneDatabaseMigratesWithoutLossAndRebuildsAllStoredScanHistory() = runBlocking {
        val name = "relationship-migration-fixture-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        var database: TrackerDatabase? = null
        try {
            val schema = InstrumentationRegistry.getInstrumentation().context.assets
                .open("dev.datell.followertracker.data.TrackerDatabase/1.json")
                .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
            SQLiteDatabase.openOrCreateDatabase(file, null).use { legacy ->
                val entities = schema.getJSONArray("entities")
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index)
                    val table = entity.getString("tableName")
                    legacy.execSQL(entity.getString("createSql").replace('$' + "{TABLE_NAME}", table))
                    val indices = entity.getJSONArray("indices")
                    for (position in 0 until indices.length()) {
                        legacy.execSQL(indices.getJSONObject(position).getString("createSql").replace('$' + "{TABLE_NAME}", table))
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (index in 0 until setup.length()) legacy.execSQL(setup.getString(index))
                legacy.version = 1
                legacy.insertOrThrow("accounts", null, ContentValues().apply {
                    put("key", owner.key); put("provider", owner.provider.name); put("connectedAt", owner.connectedAt); put("json", Json.encodeToString(owner))
                })
                legacy.insertOrThrow("metrics", null, ContentValues().apply {
                    put("accountKey", owner.key); put("observedAt", 50L); put("followers", 1L); put("following", 1L)
                    put("precision", "EXACT"); put("source", "synthetic-legacy-fixture"); put("adapterVersion", 1)
                })
                for (time in listOf(100L, 200L, 300L, 400L, 500L, 600L)) for (direction in Direction.entries) {
                    val snapshot = scan(time, time == 100L || direction == Direction.FOLLOWING, direction)
                    legacy.insertOrThrow("scans", null, ContentValues().apply {
                        put("accountKey", owner.key); put("direction", direction.name); put("finishedAt", snapshot.finishedAt)
                        put("encrypted", cipher.seal(Json.encodeToString(snapshot).toByteArray(Charsets.UTF_8)))
                    })
                }
            }
            val upgraded = Room.databaseBuilder(context, TrackerDatabase::class.java, name)
                .addMigrations(TrackerDatabase.MIGRATION_2_3).build()
            database = upgraded
            val repository = TrackerRepository(upgraded, cipher)
            assertEquals(owner, repository.account(owner.key))
            assertEquals(listOf(50L), repository.history(owner.key).map { it.observedAt })
            assertEquals(6, upgraded.dao().scans(owner.key, Direction.FOLLOWERS.name, 20).size)
            assertEquals(601L, repository.report(owner.key)?.comparedAt)
            val restored = repository.relationshipChanges(owner.key).single()
            assertEquals(201L, restored.detectedAt); assertEquals(601L, restored.checkedAt); assertEquals(5, restored.absenceChecks)
            assertEquals(RelationshipChangeState.REPEATED_ABSENCE, restored.state)
            assertEquals(listOf(restored), repository.relationshipChanges(owner.key))
            assertEquals(601L, upgraded.dao().historyCursor(owner.key)?.finishedAt)
            assertEquals(2, upgraded.openHelper.readableDatabase.version)
        } finally { database?.close(); context.deleteDatabase(name) }
    }
}
