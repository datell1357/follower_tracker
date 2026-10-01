package dev.datell.followertracker.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "accounts", indices = [Index(value = ["provider"], unique = true)])
data class AccountEntity(@PrimaryKey val key: String, val provider: String, val connectedAt: Long, val json: String)

@Entity(tableName = "metrics", primaryKeys = ["accountKey", "observedAt"],
    foreignKeys = [ForeignKey(entity = AccountEntity::class, parentColumns = ["key"], childColumns = ["accountKey"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("accountKey")])
data class MetricEntity(val accountKey: String, val observedAt: Long, val followers: Long, val following: Long?, val precision: String, val source: String, val adapterVersion: Int)

@Entity(tableName = "scans", primaryKeys = ["accountKey", "direction", "finishedAt"],
    foreignKeys = [ForeignKey(entity = AccountEntity::class, parentColumns = ["key"], childColumns = ["accountKey"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("accountKey")])
data class ScanEntity(val accountKey: String, val direction: String, val finishedAt: Long, val encrypted: ByteArray)

@Entity(tableName = "relationship_changes",
    foreignKeys = [ForeignKey(entity = AccountEntity::class, parentColumns = ["key"], childColumns = ["accountKey"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["accountKey", "detectedAt"]), Index(value = ["accountKey", "state"])])
data class RelationshipChangeEntity(@PrimaryKey val id: String, val accountKey: String,
    val detectedAt: Long, val state: String, val encrypted: ByteArray)

@Entity(tableName = "relationship_history_cursor",
    foreignKeys = [ForeignKey(entity = AccountEntity::class, parentColumns = ["key"], childColumns = ["accountKey"], onDelete = ForeignKey.CASCADE)])
data class RelationshipHistoryCursorEntity(@PrimaryKey val accountKey: String, val finishedAt: Long)

@Dao
interface TrackerDao {
    @Query("SELECT * FROM accounts ORDER BY connectedAt") fun observeAccounts(): Flow<List<AccountEntity>>
    @Query("SELECT * FROM accounts ORDER BY connectedAt") suspend fun accounts(): List<AccountEntity>
    @Query("SELECT * FROM accounts WHERE `key` = :key") suspend fun account(key: String): AccountEntity?
    @Upsert suspend fun putAccount(account: AccountEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun putMetric(metric: MetricEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun putScan(scan: ScanEntity)
    @Query("SELECT * FROM metrics WHERE accountKey = :key ORDER BY observedAt DESC LIMIT :limit") suspend fun metrics(key: String, limit: Int): List<MetricEntity>
    @Query("SELECT * FROM scans WHERE accountKey = :key AND direction = :direction ORDER BY finishedAt DESC LIMIT :limit") suspend fun scans(key: String, direction: String, limit: Int): List<ScanEntity>
    @Query("SELECT * FROM scans WHERE accountKey = :key AND direction = 'FOLLOWERS' AND finishedAt > :after ORDER BY finishedAt LIMIT 1") suspend fun nextFollowersScan(key: String, after: Long): ScanEntity?
    @Query("SELECT * FROM relationship_changes WHERE accountKey = :key ORDER BY detectedAt DESC, id") suspend fun relationshipChanges(key: String): List<RelationshipChangeEntity>
    @Query("SELECT * FROM relationship_changes WHERE accountKey = :key AND state != 'REOBSERVED' ORDER BY detectedAt DESC, id") suspend fun pendingRelationshipChanges(key: String): List<RelationshipChangeEntity>
    @Upsert suspend fun putRelationshipChange(change: RelationshipChangeEntity)
    @Query("SELECT * FROM relationship_history_cursor WHERE accountKey = :key") suspend fun historyCursor(key: String): RelationshipHistoryCursorEntity?
    @Upsert suspend fun putHistoryCursor(cursor: RelationshipHistoryCursorEntity)
    @Query("DELETE FROM accounts WHERE `key` = :key") suspend fun deleteAccount(key: String)
}

@Database(entities = [AccountEntity::class, MetricEntity::class, ScanEntity::class,
    RelationshipChangeEntity::class, RelationshipHistoryCursorEntity::class], version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)], exportSchema = true)
abstract class TrackerDatabase : RoomDatabase() { abstract fun dao(): TrackerDao }
