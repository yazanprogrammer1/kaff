package dev.kaff.app.data.room

import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import dev.kaff.app.domain.model.RecordFile
import dev.kaff.app.domain.model.TransferDirection
import dev.kaff.app.domain.model.TransferRecord
import dev.kaff.app.domain.repository.HistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Only metadata is stored: never file contents or thumbnails. */
@Entity(tableName = "transfers")
data class TransferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val direction: String,
    val peerName: String,
    val fileCount: Int,
    val totalBytes: Long,
    val firstFileName: String,
    val firstMime: String,
    val success: Boolean,
    val durationMs: Long,
    val createdAtMs: Long,
    val savedUri: String?,
    /** JSON list of [RecordFile]; null for entries written before version 2. */
    @ColumnInfo(defaultValue = "NULL") val filesJson: String? = null,
    @ColumnInfo(defaultValue = "0") val desktop: Boolean = false,
)

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfers ORDER BY createdAtMs DESC LIMIT 500")
    fun observeAll(): Flow<List<TransferEntity>>

    @Insert
    suspend fun insert(entity: TransferEntity)

    @Query("DELETE FROM transfers")
    suspend fun clear()
}

@Database(
    entities = [TransferEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class KaffDatabase : RoomDatabase() {
    abstract fun transfers(): TransferDao
}

@Singleton
class RoomHistoryRepository @Inject constructor(private val dao: TransferDao) : HistoryRepository {

    override val records: Flow<List<TransferRecord>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun add(record: TransferRecord) = dao.insert(record.toEntity())

    override suspend fun clear() = dao.clear()

    private fun TransferEntity.toDomain() = TransferRecord(
        id = id,
        direction = TransferDirection.valueOf(direction),
        peerName = peerName,
        fileCount = fileCount,
        totalBytes = totalBytes,
        firstFileName = firstFileName,
        firstMime = firstMime,
        success = success,
        durationMs = durationMs,
        createdAtMs = createdAtMs,
        savedUri = savedUri,
        files = filesJson?.let { runCatching { json.decodeFromString(FILES, it) }.getOrNull() }.orEmpty(),
        desktop = desktop,
    )

    private fun TransferRecord.toEntity() = TransferEntity(
        direction = direction.name,
        peerName = peerName,
        fileCount = fileCount,
        totalBytes = totalBytes,
        firstFileName = firstFileName,
        firstMime = firstMime,
        success = success,
        durationMs = durationMs,
        createdAtMs = createdAtMs,
        savedUri = savedUri,
        filesJson = files.takeIf { it.isNotEmpty() }?.let { json.encodeToString(FILES, it) },
        desktop = desktop,
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val FILES = ListSerializer(RecordFile.serializer())
    }
}
