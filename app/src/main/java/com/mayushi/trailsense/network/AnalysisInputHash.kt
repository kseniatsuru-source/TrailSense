package com.mayushi.trailsense.network

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

data class AnalysisInputDigest(
    val inputHash: String,
    val attachmentSha256: String?,
)

object AnalysisInputHash {
    fun calculate(inputJson: String, attachment: File?, mimeType: String?): AnalysisInputDigest {
        val attachmentDigest = attachment?.let(::sha256)
        val normalizedMime = attachment?.let { mimeType?.trim()?.lowercase().orEmpty() }.orEmpty()
        val envelope = buildString {
            append("trailsense-analysis-input-v1\n")
            append(inputJson)
            append("\nattachment_mime=")
            append(normalizedMime)
            append("\nattachment_sha256=")
            append(attachmentDigest.orEmpty())
        }
        return AnalysisInputDigest(sha256(envelope.toByteArray(Charsets.UTF_8)), attachmentDigest)
    }

    fun sha256(file: File): String = file.inputStream().use(::sha256)

    fun sha256(bytes: ByteArray): String = sha256(bytes.inputStream())

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
