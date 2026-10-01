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
    @Query("DELETE FROM accounts WHERE `key` = :key") suspend fun deleteAccount(key: String)
}

@Database(entities = [AccountEntity::class, MetricEntity::class, ScanEntity::class], version = 1, exportSchema = true)
abstract class TrackerDatabase : RoomDatabase() { abstract fun dao(): TrackerDao }
