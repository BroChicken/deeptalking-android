package com.deeptalking.engine.cosyvoice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LibraryPathTest {

    private val sep = File.pathSeparator

    @Test
    fun resolvesExistingLibraryAcrossSearchPath() {
        val dir = Files.createTempDirectory("cosy-lib").toFile()
        val libDir = File(dir, "arm64-v8a").apply { mkdirs() }
        val lib = File(libDir, "libcosyvoice.so").apply { writeText("stub") }
        val otherDir = File(dir, "empty").apply { mkdirs() }

        val resolved = resolveLibraryFile(
            listOf(otherDir.absolutePath, libDir.absolutePath).joinToString(sep),
            "libcosyvoice.so",
            sep,
        )

        assertEquals(lib.absolutePath, resolved?.absolutePath)
    }

    @Test
    fun returnsNullWhenNoCandidateExists() {
        val dir = Files.createTempDirectory("cosy-lib-none").toFile()
        assertNull(resolveLibraryFile(dir.absolutePath, "libcosyvoice.so", sep))
        assertNull(resolveLibraryFile(null, "libcosyvoice.so", sep))
        assertNull(resolveLibraryFile("", "libcosyvoice.so", sep))
    }
}
