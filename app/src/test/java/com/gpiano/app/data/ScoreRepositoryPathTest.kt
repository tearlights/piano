package com.gpiano.app.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ScoreRepositoryPathTest {
    @Test
    fun `repository paths must stay inside app file root`() {
        val root = Files.createTempDirectory("gpiano-path-test").toFile()
        try {
            assertEquals(
                File(root, "scores/id/page-1.png").canonicalFile,
                resolveRepositoryPath(root, "scores/id/page-1.png"),
            )
            assertThrows(IllegalArgumentException::class.java) {
                resolveRepositoryPath(root, "../outside.txt")
            }
            assertThrows(IllegalArgumentException::class.java) {
                resolveRepositoryPath(root, File(root.parentFile, "outside.txt").absolutePath)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `backup restore uses isolated generation paths`() {
        val generation = "12345678-1234-1234-1234-123456789abc"

        assertEquals(
            "restore-generations/$generation/scores/id/page-1.png",
            restoreGenerationPath(generation, "scores/id/page-1.png"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            restoreGenerationPath(generation, "../scores/id.pdf")
        }
        assertThrows(IllegalArgumentException::class.java) {
            restoreGenerationPath("bad", "scores/id.pdf")
        }
    }
}
