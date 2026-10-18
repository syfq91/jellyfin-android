package org.jellyfin.mobile.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "book_progress")
data class BookProgressEntity(
    @PrimaryKey
    @ColumnInfo(name = "item_id")
    val itemId: UUID,
    @ColumnInfo(name = "locator_json")
    val locatorJson: String,
    @ColumnInfo(name = "total_progression")
    val totalProgression: Double,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
)
