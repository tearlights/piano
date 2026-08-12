package com.gpiano.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "practice_versions",
    foreignKeys = [
        ForeignKey(
            entity = ScoreStructure::class,
            parentColumns = ["id"],
            childColumns = ["structureId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ScoreRevision::class,
            parentColumns = ["id"],
            childColumns = ["baseRevisionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("structureId"),
        Index("baseRevisionId"),
        Index("status"),
        Index(value = ["musicXmlRelativePath"], unique = true),
    ],
)
data class PracticeVersion(
    @PrimaryKey val id: String,
    val structureId: String,
    val baseRevisionId: String,
    val title: String,
    val purpose: String,
    val status: String,
    val fromMeasure: Int,
    val toMeasure: Int,
    val musicXmlRelativePath: String,
    val planJson: String,
    val differenceJson: String,
    val createdAt: Long,
    val updatedAt: Long,
)

object PracticeVersionStatus {
    const val Draft = "draft"
    const val Accepted = "accepted"
    const val Rejected = "rejected"
    val all = setOf(Draft, Accepted, Rejected)
}
