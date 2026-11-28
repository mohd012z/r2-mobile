package com.msa.r2mobile

import java.io.File
import java.net.URL
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Explicitly authorized, single HTTPS download; no redirects or credentials. */
object ServerDownload {
    data class Receipt(val bytes: Long, val sha256: String, val file: File)
    fun validate(raw: String): URL {
        val u = java.net.URI(raw)
        require(u.scheme == "https" && u.host != null && u.userInfo == null && u.fragment == null)
        require(u.port == -1 || u.port == 443)
        return u.toURL()
    }
    @Synchronized fun fetch(raw: String, directory: File, authorized: Boolean,
        expectedSha256: String? = null, cancelled: () -> Boolean = { false }): Receipt {
        require(authorized) { "Authorization required" }
        val url = validate(raw)
        require(expectedSha256 == null || expectedSha256.matches(Regex("[a-fA-F0-9]{64}")))
        require(directory.isDirectory || directory.mkdirs())
        val temp = File.createTempFile("download-", ".part", directory)
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("Accept-Encoding", "identity")
            require(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            val declared = connection.contentLengthLong
            val limit = 64L * 1024 * 1024
            require(declared <= limit)
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            val start = System.nanoTime()
            connection.inputStream.use { input -> temp.outputStream().use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    check(!cancelled() && System.nanoTime()-start < 120_000_000_000L) { "Cancelled or deadline exceeded" }
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n; require(total <= limit)
                    digest.update(buffer, 0, n); output.write(buffer, 0, n)
                }
                output.fd.sync()
            } }
            require(declared < 0 || declared == total)
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            require(expectedSha256 == null || hash.equals(expectedSha256, true)) { "Digest mismatch" }
            check(!cancelled())
            val result = File(directory, java.util.UUID.randomUUID().toString() + ".bin")
            Files.move(temp.toPath(), result.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return Receipt(total, hash, result)
        } finally { connection.disconnect(); temp.delete() }
    }
}