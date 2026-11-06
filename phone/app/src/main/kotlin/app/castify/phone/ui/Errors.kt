package app.castify.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.castify.domain.status.PageError
import app.castify.phone.PageErrorUi

/**
 * A page that can't load (mockup `docs/mockups/offline-errors.html`, P1 / P2), shown in place
 * of Android's raw error page. Retry only: nothing reloads by itself (user rule).
 */
@Composable
fun PageErrorScreen(error: PageErrorUi, onRetry: () -> Unit, onHome: () -> Unit) {
    val offline = error.kind == PageError.OFFLINE
    Column(
        Modifier.fillMaxSize().background(CastifyColors.Background).padding(horizontal = 36.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(72.dp).background(CastifyColors.Surface, CircleShape), contentAlignment = Alignment.Center) {
            Icon(
                if (offline) Icons.Filled.WifiOff else Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = CastifyColors.TextSecondary,
                modifier = Modifier.size(34.dp),
            )
        }
        Text(
            if (offline) "No internet connection" else "Can't reach ${error.host}",
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            if (offline) "Check that your phone is on Wi-Fi, then try again."
            else "The website isn't answering. It may be down, or the address may be wrong.",
            color = CastifyColors.TextSecondary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(
            onClick = onRetry,
            shape = RoundedCornerShape(4.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CastifyColors.Red, contentColor = CastifyColors.TextPrimary),
            modifier = Modifier.padding(top = 26.dp),
        ) { Text("Retry", fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 18.dp)) }
        if (!offline) {
            OutlinedButton(onClick = onHome, shape = RoundedCornerShape(4.dp), modifier = Modifier.padding(top = 8.dp)) {
                Text("Go to home", color = CastifyColors.TextPrimary)
            }
        }
    }
}

/** Home screen while offline (mockup P3): favorites and history stay usable. */
@Composable
fun OfflineBanner(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(CastifyColors.Surface, RoundedCornerShape(4.dp))
            .padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 3.dp, height = 20.dp).background(CastifyColors.Red))
        Text("No internet connection", fontSize = 13.sp, modifier = Modifier.weight(1f).padding(start = 10.dp))
        TextButton(onClick = onRetry) { Text("Retry", color = CastifyColors.Red, fontWeight = FontWeight.Bold) }
    }
}
