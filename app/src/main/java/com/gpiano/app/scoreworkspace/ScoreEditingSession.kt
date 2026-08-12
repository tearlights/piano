package com.gpiano.app.scoreworkspace

data class ScoreRevisionSnapshot(
    val revisionNumber: Int,
    val xml: String,
    val score: ScoreIr,
    val operation: CorrectionOperation?,
)

data class ScoreEditingSession(
    val sourceName: String,
    val revisions: List<ScoreRevisionSnapshot>,
) {
    init {
        require(revisions.isNotEmpty()) { "编辑会话至少需要一个原始修订版" }
    }

    val current: ScoreRevisionSnapshot
        get() = revisions.last()

    val xml: String
        get() = current.xml

    val score: ScoreIr
        get() = current.score

    val revisionNumber: Int
        get() = current.revisionNumber

    val canUndo: Boolean
        get() = revisions.size > 1

    fun apply(operation: CorrectionOperation): ScoreEditingSession {
        val result = MusicXmlRevisionCompiler.apply(xml, operation)
        return copy(
            revisions = revisions + ScoreRevisionSnapshot(
                revisionNumber = revisionNumber + 1,
                xml = result.xml,
                score = result.score,
                operation = operation,
            ),
        )
    }

    fun undo(): ScoreEditingSession = if (canUndo) copy(revisions = revisions.dropLast(1)) else this

    companion object {
        fun from(document: MusicXmlDocument): ScoreEditingSession = ScoreEditingSession(
            sourceName = document.sourceName,
            revisions = listOf(
                ScoreRevisionSnapshot(
                    revisionNumber = 0,
                    xml = document.xml,
                    score = document.score,
                    operation = null,
                ),
            ),
        )
    }
}
