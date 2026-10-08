package com.mayushi.trailsense.network

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AnalysisInputHashTest {
    @Test fun `hash binds input mime and attachment bytes`() {
        val first = File.createTempFile("hash-one", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val second = File.createTempFile("hash-two", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 4)) }
        try {
            val noAttachment = AnalysisInputHash.calculate("{\"x\":1}", null, null)
            val one = AnalysisInputHash.calculate("{\"x\":1}", first, "image/jpeg")
            val changedBytes = AnalysisInputHash.calculate("{\"x\":1}", second, "image/jpeg")
            val changedMime = AnalysisInputHash.calculate("{\"x\":1}", first, "image/png")

            assertNotEquals(noAttachment.inputHash, one.inputHash)
            assertNotEquals(one.inputHash, changedBytes.inputHash)
            assertNotEquals(one.inputHash, changedMime.inputHash)
            assertEquals(AnalysisInputHash.sha256(first.readBytes()), one.attachmentSha256)
        } finally {
            first.delete()
            second.delete()
        }
    }
}
