package org.jellyfin.mobile.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.jellyfin.mobile.data.entity.BookProgressEntity
import java.util.UUID

@Dao
interface BookProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProgress(entity: BookProgressEntity)

    @Query("SELECT * FROM book_progress WHERE item_id = :itemId")
    suspend fun getProgress(itemId: UUID): BookProgressEntity?

    @Query("DELETE FROM book_progress WHERE item_id = :itemId")
    suspend fun deleteProgress(itemId: UUID)
}
