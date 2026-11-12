package app.castify.phone.ui

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.castify.domain.model.NowPlaying
import app.castify.domain.subtitles.SubtitleMenu

/**
 * `CC` button: white = subtitles shown, gray = off, dimmed = the video has none.
 * Always clickable, so "No subtitles available" can be explained.
 */
@Composable
fun SubtitlesButton(now: NowPlaying, onClick: () -> Unit, size: Int = 44) {
    val tint = when {
        now.subtitles.isEmpty() -> CastifyColors.Disabled
        now.selectedSubtitleId != null -> CastifyColors.TextPrimary
        else -> CastifyColors.TextSecondary
    }
    val state = when {
        now.subtitles.isEmpty() -> "none available"
        now.selectedSubtitleId != null -> "on"
        else -> "off"
    }
    IconButton(onClick = onClick, modifier = Modifier.size(size.dp)) {
        Icon(Icons.Filled.ClosedCaption, contentDescription = "Subtitles, $state", tint = tint, modifier = Modifier.size((size * 0.6).dp))
    }
}

/** Same list and order as the TV menu ([SubtitleMenu]); picking applies at once on the TV. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitlesSheet(now: NowPlaying, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CastifyColors.Surface) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                "Subtitles",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            if (now.subtitles.isEmpty()) {
                Text(
                    "No subtitles available for this video",
                    color = CastifyColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
                return@Column
            }
            LazyColumn {
                items(SubtitleMenu.options(now.subtitles), key = { it.trackId ?: "off" }) { option ->
                    val selected = option.trackId == now.selectedSubtitleId
                    ListItem(
                        headlineContent = {
                            Text(option.name, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                        },
                        trailingContent = {
                            if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = CastifyColors.Red)
                        },
                        colors = ListItemDefaults.colors(containerColor = CastifyColors.Surface),
                        modifier = Modifier.clickable { onPick(option.trackId); onDismiss() },
                    )
                }
            }
        }
    }
}

/**
 * Quality pill of the full remote (mockup `docs/mockups/quality.html`, P1): the playing
 * quality, red outline; greyed when the video has only one.
 */
@Composable
fun QualityButton(now: NowPlaying, onClick: () -> Unit) {
    val several = now.qualities.size > 1
    val label = now.currentQuality?.label ?: "HD"
    androidx.compose.material3.TextButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = "Quality: $label" },
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.ExtraBold,
            color = if (several) CastifyColors.TextPrimary else CastifyColors.Disabled,
            modifier = Modifier
                .border(1.5.dp, if (several) CastifyColors.Red else CastifyColors.Disabled, androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

/** The video's qualities, best first, the playing one checked (mockup P2). A tap switches the TV. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualitySheet(now: NowPlaying, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CastifyColors.Surface) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                "Quality",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            if (now.qualities.size <= 1) {
                Text(
                    "Only one quality for this video",
                    color = CastifyColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
                return@Column
            }
            val current = now.currentQuality?.height
            now.qualities.forEachIndexed { index, quality ->
                ListItem(
                    headlineContent = { Text(quality.label, fontWeight = if (quality.height == current) FontWeight.Bold else FontWeight.Normal) },
                    supportingContent = if (index == 0) ({ Text("best", color = CastifyColors.TextSecondary) }) else null,
                    trailingContent = if (quality.height == current) ({ Text("✓", color = CastifyColors.Red, fontWeight = FontWeight.Bold) }) else null,
                    colors = ListItemDefaults.colors(containerColor = CastifyColors.Surface),
                    modifier = Modifier.clickable { onPick(quality.height); onDismiss() },
                )
            }
        }
    }
}
