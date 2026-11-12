package app.castify.phone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.castify.domain.update.ReleaseNote

/**
 * "What's new" after an update (mockup `docs/mockups/whats-new.html`, P1): numbered items in
 * plain words, `OK` closes it, `All versions` opens [ReleaseNotesScreen].
 */
@Composable
fun WhatsNewDialog(note: ReleaseNote, onOk: () -> Unit, onAllVersions: () -> Unit) {
    Dialog(onDismissRequest = onOk) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(CastifyColors.SurfaceRaised, RoundedCornerShape(16.dp))
                .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 12.dp),
        ) {
            Text("CASTIFY ${note.version}", style = MaterialTheme.typography.labelMedium, color = CastifyColors.Red, fontWeight = FontWeight.ExtraBold)
            Text(
                "What's new",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            note.items.forEachIndexed { index, item ->
                Row(Modifier.padding(bottom = 14.dp), verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier.size(26.dp).background(CastifyColors.Red.copy(alpha = 0.18f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${index + 1}", color = CastifyColors.Red, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge)
                    }
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(item.title, fontWeight = FontWeight.Bold)
                        Text(item.detailFor(tv = false), style = MaterialTheme.typography.bodyMedium, color = CastifyColors.TextSecondary)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onAllVersions) { Text("All versions", color = CastifyColors.TextSecondary) }
                Box(Modifier.weight(1f))
                Button(
                    onClick = onOk,
                    colors = ButtonDefaults.buttonColors(containerColor = CastifyColors.Red, contentColor = CastifyColors.TextPrimary),
                    shape = RoundedCornerShape(4.dp),
                ) { Text("OK", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/** Every version's notes, newest first, the installed one marked (mockup P3). Back closes. */
@Composable
fun ReleaseNotesScreen(notes: List<ReleaseNote>, installedLabel: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(
        Modifier
            .fillMaxSize()
            .background(CastifyColors.Background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("What's new", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(notes, key = { it.version.toString() }) { note ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(note.version.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                        Text(note.date, style = MaterialTheme.typography.bodySmall, color = CastifyColors.TextSecondary, modifier = Modifier.padding(start = 8.dp))
                        if (note.version.label == installedLabel) {
                            Text(
                                "INSTALLED",
                                style = MaterialTheme.typography.labelSmall,
                                color = CastifyColors.ReadyDot,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    note.items.forEach { item ->
                        Column(Modifier.padding(start = 4.dp)) {
                            Text(item.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            Text(item.detailFor(tv = false), style = MaterialTheme.typography.bodySmall, color = CastifyColors.TextSecondary)
                        }
                    }
                }
                HorizontalDivider(color = CastifyColors.SurfaceRaised, modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
    }
}
