package app.castify.phone.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.castify.domain.update.AppUpdater
import app.castify.domain.update.AppUpdater.State

/**
 * Update dialog (mockup `docs/mockups/app-update.html`, P1 / P2 / P4). Nothing when there is
 * no update or once Android's own install confirmation takes over.
 */
@Composable
fun UpdateDialog(updater: AppUpdater) {
    val state by updater.state.collectAsStateWithLifecycle()
    val current = updater.currentVersion.label
    when (val s = state) {
        is State.Available -> UpdateAlert(
            title = "Update available",
            body = "Castify ${s.update.version.label} is ready to install. You have $current.",
            confirm = "Update" to updater::accept,
            dismiss = "Not now" to updater::dismiss,
        )
        is State.Downloading -> AlertDialog(
            // Only Cancel stops it: a tap outside must not drop a running download.
            onDismissRequest = {},
            properties = DialogProperties(dismissOnClickOutside = false),
            title = { Text("Downloading ${s.update.version.label}", fontWeight = FontWeight.ExtraBold) },
            text = {
                Column {
                    val fraction = if (s.totalBytes > 0) (s.downloadedBytes.toFloat() / s.totalBytes).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        color = CastifyColors.Red,
                        trackColor = CastifyColors.Disabled,
                    )
                    Text(
                        "${megabytes(s.downloadedBytes)} / ${megabytes(s.totalBytes)} MB",
                        color = CastifyColors.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp).align(Alignment.End),
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = updater::dismiss) { Text("Cancel") } },
            containerColor = CastifyColors.SurfaceRaised,
        )
        is State.Failed -> UpdateAlert(
            title = "Update failed",
            body = "Couldn't download ${s.update.version.label}. Check your connection and try again.",
            confirm = "Retry" to updater::accept,
            dismiss = "Not now" to updater::dismiss,
        )
        State.Idle, is State.Installing -> Unit
    }
}

@Composable
private fun UpdateAlert(title: String, body: String, confirm: Pair<String, () -> Unit>, dismiss: Pair<String, () -> Unit>) {
    AlertDialog(
        onDismissRequest = dismiss.second,
        title = { Text(title, fontWeight = FontWeight.ExtraBold) },
        text = { Text(body, color = CastifyColors.TextSecondary) },
        confirmButton = { TextButton(onClick = confirm.second) { Text(confirm.first, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = dismiss.second) { Text(dismiss.first, color = CastifyColors.TextPrimary) } },
        containerColor = CastifyColors.SurfaceRaised,
    )
}

private fun megabytes(bytes: Long) = "%.1f".format(bytes / 1_000_000.0)
