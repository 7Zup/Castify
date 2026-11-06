package app.castify.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.SubcomposeAsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest

/**
 * Video thumbnail, 16:9 set by the caller. No image → gray box with a movie icon.
 * Only `Referer` / `User-Agent` are sent: many sites block image hotlinking otherwise.
 */
@Composable
fun Thumbnail(url: String?, headers: Map<String, String>, modifier: Modifier) {
    val placeholder = @Composable {
        Box(Modifier.fillMaxSize().background(CastifyColors.SurfaceRaised), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Movie, contentDescription = null, tint = CastifyColors.TextSecondary)
        }
    }
    if (url == null) {
        Box(modifier) { placeholder() }
        return
    }
    val networkHeaders = NetworkHeaders.Builder().apply {
        headers.forEach { (k, v) ->
            if (k.equals("Referer", true) || k.equals("User-Agent", true)) set(k, v)
        }
    }.build()
    SubcomposeAsyncImage(
        model = ImageRequest.Builder(LocalContext.current).data(url).httpHeaders(networkHeaders).build(),
        contentDescription = "Video thumbnail",
        contentScale = ContentScale.Crop,
        loading = { placeholder() },
        error = { placeholder() },
        modifier = modifier,
    )
}

fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
