package com.gpiano.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "score_revisions",
    foreignKeys = [
        ForeignKey(
            entity = ScoreStructure::class,
            parentColumns = ["id"],
            childColumns = ["structureId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("structureId"),
        Index("parentRevisionId"),
        Index(value = ["structureId", "revisionNumber"], unique = true),
        Index(value = ["musicXmlRelativePath"], unique = true),
    ],
)
data class ScoreRevision(
    @PrimaryKey val id: String,
    val structureId: String,
    val parentRevisionId: String?,
    val revisionNumber: Int,
    val kind: String,
    val musicXmlRelativePath: String,
    val operationJson: String?,
    val createdAt: Long,
)
