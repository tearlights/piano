package com.gpiano.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "practice_attempts",
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
            childColumns = ["sourceRevisionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PracticeVersion::class,
            parentColumns = ["id"],
            childColumns = ["practiceVersionId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("structureId"),
        Index("sourceRevisionId"),
        Index("practiceVersionId"),
        Index("finishedAt"),
    ],
)
data class PracticeAttempt(
    @PrimaryKey val id: String,
    val structureId: String,
    val sourceRevisionId: String,
    val practiceVersionId: String?,
    val fromMeasure: Int,
    val toMeasure: Int,
    val hand: String,
    val speed: Double,
    val inputKind: String,
    val deviceId: Int?,
    val deviceName: String,
    val startedAt: Long,
    val finishedAt: Long,
    val expectedCount: Int,
    val playedCount: Int,
    val pitchMatchedCount: Int,
    val rhythmMatchedCount: Int,
    val missedCount: Int,
    val extraCount: Int,
    val wrongPitchCount: Int,
    val continuityPercent: Int,
    val pitchAccuracyPercent: Int,
    val rhythmAccuracyPercent: Int,
    val resultJson: String,
)

object PracticeAttemptInputKind {
    const val Midi = "midi"
    const val ScreenTest = "screen_test"
    val all = setOf(Midi, ScreenTest)
}

@Entity(
    tableName = "midi_performance_events",
    primaryKeys = ["attemptId", "sequence"],
    foreignKeys = [
        ForeignKey(
            entity = PracticeAttempt::class,
            parentColumns = ["id"],
            childColumns = ["attemptId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("attemptId")],
)
data class MidiPerformanceEvent(
    val attemptId: String,
    val sequence: Int,
    val channel: Int,
    val midiPitch: Int,
    val velocity: Int,
    val onsetNanos: Long,
    val durationNanos: Long?,
)
