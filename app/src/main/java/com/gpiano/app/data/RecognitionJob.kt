package com.gpiano.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "recognition_jobs",
    foreignKeys = [
        ForeignKey(
            entity = Score::class,
            parentColumns = ["id"],
            childColumns = ["sourceScoreId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ScoreStructure::class,
            parentColumns = ["id"],
            childColumns = ["resultStructureId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("sourceScoreId"),
        Index("resultStructureId"),
        Index("status"),
    ],
)
data class RecognitionJob(
    @PrimaryKey val id: String,
    val sourceScoreId: String,
    val sourcePageIndex: Int,
    val provider: String,
    val remoteJobId: String?,
    val inputSha256: String,
    val status: String,
    val stage: String,
    val attempt: Int,
    val resultStructureId: String?,
    val errorCode: String?,
    val errorMessage: String?,
    val diagnosticsJson: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

object RecognitionJobStatus {
    const val Pending = "pending"
    const val Running = "running"
    const val Ready = "ready"
    const val Failed = "failed"
    const val Cancelled = "cancelled"

    val all = setOf(Pending, Running, Ready, Failed, Cancelled)
}

