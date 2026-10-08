package com.eenglish.listening.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "sessions", indices = [Index(value = ["partId", "startedAt"])])
data class SessionEntity(
    @PrimaryKey val id: String,
    val partId: String,
    val status: String,
    val startedAt: Long,
    val submittedAt: Long?,
    val correctCount: Int?,
    val totalCount: Int,
    val questionsJson: String,
)

@Entity(tableName = "answers", primaryKeys = ["sessionId", "questionId"],
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"])],
    indices = [Index("sessionId")])
data class AnswerEntity(val sessionId: String, val questionId: String, val selectedAnswer: String)

data class AttemptEntity(
    @Embedded val session: SessionEntity,
    @Relation(parentColumn = "id", entityColumn = "sessionId") val answers: List<AnswerEntity>,
)

@Dao
interface PracticeDao {
    @Transaction @Query("SELECT * FROM sessions ORDER BY startedAt DESC, id DESC")
    fun observeAll(): Flow<List<AttemptEntity>>
    @Transaction @Query("SELECT * FROM sessions ORDER BY startedAt DESC, id DESC")
    suspend fun all(): List<AttemptEntity>
    @Query("SELECT * FROM sessions WHERE partId = :partId ORDER BY startedAt DESC, id DESC LIMIT 1")
    suspend fun latest(partId: String): SessionEntity?
    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun session(id: String): SessionEntity?
    @Query("SELECT * FROM answers WHERE sessionId = :id")
    suspend fun answers(id: String): List<AnswerEntity>
    @Insert suspend fun insert(session: SessionEntity)
    @Upsert suspend fun save(answer: AnswerEntity)
    @Query("UPDATE sessions SET status = 'SUBMITTED', submittedAt = :time, correctCount = :correct WHERE id = :id AND status = 'IN_PROGRESS'")
    suspend fun submit(id: String, time: Long, correct: Int): Int
}

@Database(entities = [SessionEntity::class, AnswerEntity::class, HighlightEntity::class], version = 2, exportSchema = true)
abstract class PracticeDatabase : RoomDatabase() {
    abstract fun practiceDao(): PracticeDao
    abstract fun highlightDao(): HighlightDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS `highlights` (
                    `partId` TEXT NOT NULL, `scope` TEXT NOT NULL, `textId` TEXT NOT NULL,
                    `language` TEXT NOT NULL, `contentHash` TEXT NOT NULL,
                    `startOffset` INTEGER NOT NULL, `endOffset` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`partId`, `scope`, `textId`, `language`, `contentHash`, `startOffset`, `endOffset`))""")
            }
        }
    }
}
