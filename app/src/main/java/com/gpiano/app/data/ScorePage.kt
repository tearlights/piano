package com.gpiano.app.data

import androidx.room.Entity

@Entity(tableName = "score_pages", primaryKeys = ["scoreId", "sourceIndex"])
data class ScorePage(
    val scoreId: String,
    val sourceIndex: Int,
    val displayIndex: Int,
    val relativePath: String? = null,
)
