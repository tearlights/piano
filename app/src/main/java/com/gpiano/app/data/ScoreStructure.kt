package com.gpiano.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "score_structures",
    foreignKeys = [
        ForeignKey(
            entity = Score::class,
            parentColumns = ["id"],
            childColumns = ["sourceScoreId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("sourceScoreId"),
        Index(value = ["sourceKey"], unique = true),
        Index("currentRevisionId"),
    ],
)
data class ScoreStructure(
    @PrimaryKey val id: String,
    val sourceScoreId: String?,
    val sourceKey: String,
    val title: String,
    val formatVersion: Int,
    val currentRevisionId: String,
    val recognitionStatus: String,
    val recognitionConfidence: Double?,
    val sourceMapRelativePath: String?,
    val createdAt: Long,
    val updatedAt: Long,
)
