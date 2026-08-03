package com.gpiano.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scores")
data class Score(
    @PrimaryKey val id: String,
    val title: String,
    val fileName: String,
    val relativePath: String,
    val mimeType: String,
    val importedAt: Long,
    val isFavorite: Boolean = false,
    val folderId: String? = null,
    val lastOpenedAt: Long? = null,
)

enum class ScoreFormat { Pdf, Image }

fun Score.format(): ScoreFormat = if (mimeType == "application/pdf") ScoreFormat.Pdf else ScoreFormat.Image
