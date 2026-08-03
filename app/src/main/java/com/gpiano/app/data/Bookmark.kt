package com.gpiano.app.data

import androidx.room.Entity

@Entity(tableName = "bookmarks", primaryKeys = ["scoreId", "page"])
data class Bookmark(
    val scoreId: String,
    val page: Int,
    val createdAt: Long,
)
