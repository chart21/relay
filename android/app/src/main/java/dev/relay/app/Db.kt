package dev.relay.app

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity
data class Msg(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long = System.currentTimeMillis(),
    val outgoing: Boolean,
    val text: String,
    val kind: String = "text", // text | voice | system | event
    val audioPath: String? = null,
    val sessionKey: String? = null,
    val reqId: String? = null,
)

@Dao
interface MsgDao {
    @Query("select * from Msg order by id") fun all(): Flow<List<Msg>>
    @Insert suspend fun insert(m: Msg): Long
    @Query("update Msg set text=:t where reqId=:r and outgoing=1") suspend fun setText(r: String, t: String)
    @Query("delete from Msg") suspend fun clear()
}

@Database(entities = [Msg::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() { abstract fun msgs(): MsgDao }
