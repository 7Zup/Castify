package app.castify.phone.data

import app.castify.domain.port.ManifestFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Downloads HLS/DASH manifests (capped at 2 MB) and media byte ranges, with the page's headers. */
class HttpManifestFetcher : ManifestFetcher {

    override suspend fun fetch(url: String, headers: Map<String, String>): Result<ManifestFetcher.Fetched> =
        withContext(Dispatchers.IO) {
            runCatching {
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 5_000
                    connection.readTimeout = 8_000
                    connection.instanceFollowRedirects = true
                    headers.filterKeys { !it.equals("Range", true) && !it.equals("Accept-Encoding", true) }
                        .forEach { (k, v) -> connection.setRequestProperty(k, v) }
                    val code = connection.responseCode
                    if (code !in 200..299) throw IOException("HTTP $code")
                    val body = connection.inputStream.use { input ->
                        val bytes = input.readNBytesCompat(MAX_BYTES)
                        String(bytes, Charsets.UTF_8)
                    }
                    ManifestFetcher.Fetched(finalUrl = connection.url.toString(), body = body)
                } finally {
                    connection.disconnect()
                }
            }
        }

    override suspend fun fetchRange(
        url: String,
        headers: Map<String, String>,
        start: Long,
        endInclusive: Long,
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 5_000
                connection.readTimeout = 8_000
                connection.instanceFollowRedirects = true
                headers.filterKeys { !it.equals("Range", true) && !it.equals("Accept-Encoding", true) }
                    .forEach { (k, v) -> connection.setRequestProperty(k, v) }
                connection.setRequestProperty("Range", "bytes=$start-$endInclusive")
                val code = connection.responseCode
                // 200 = server ignored the range: refuse rather than download a whole movie.
                if (code != 206) throw IOException("HTTP $code for a range request")
                val length = (endInclusive - start + 1).coerceAtMost(MAX_RANGE_BYTES.toLong()).toInt()
                connection.inputStream.use { it.readNBytesCompat(length) }
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < max) {
            val n = read(buffer, 0, minOf(buffer.size, max - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
        const val MAX_RANGE_BYTES = 16 * 1024 * 1024
    }
}
