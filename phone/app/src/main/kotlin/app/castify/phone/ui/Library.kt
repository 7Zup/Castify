package app.castify.phone.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.TextField
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import app.castify.phone.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.castify.domain.browser.Favorite
import app.castify.domain.browser.HistoryEntry
import app.castify.domain.browser.Tab
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---------- shared bits ----------

private val tileColors = listOf(CastifyColors.Red, Color(0xFF66AADD), Color(0xFFF5C542), Color(0xFF8BC34A), Color(0xFFB388FF), Color(0xFFFF8A65))

/** Letter tile for a site (no favicons): first letter of the title, color from the host. */
@Composable
fun SiteTile(title: String, url: String?, size: Int, modifier: Modifier = Modifier) {
    val host = url?.substringAfter("://")?.substringBefore('/').orEmpty()
    val color = tileColors[(host.hashCode() and Int.MAX_VALUE) % tileColors.size]
    Box(
        modifier.size(size.dp).background(CastifyColors.Surface, RoundedCornerShape((size / 4).dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title.trim().firstOrNull()?.uppercase() ?: "•",
            color = color,
            fontWeight = FontWeight.ExtraBold,
            fontSize = (size * 0.42).sp,
        )
    }
}

/** Tabs counter, same optical size as the Material icons around it. */
@Composable
fun TabsCountIcon(count: Int) {
    Box(
        Modifier.size(21.dp).border(2.dp, CastifyColors.TextSecondary, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        // No font padding and a line height equal to the size: the digit is truly centered vertically.
        val size = if (count > 9) 9.sp else 11.sp
        Text(
            if (count > 99) ":D" else count.toString(),
            fontWeight = FontWeight.ExtraBold,
            style = TextStyle(
                fontSize = size,
                lineHeight = size,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            ),
        )
    }
}

@Composable
private fun ScreenHeader(title: String, onBack: (() -> Unit)?, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
private fun EmptyText(text: String) {
    Text(
        text,
        color = CastifyColors.TextSecondary,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
    )
}

// ---------- ⋮ menu ----------

/** ⋮ menu: icon row (Forward, ★) then New tab, Favorites, History. */
@Composable
fun BrowserMenu(
    onHome: Boolean,
    canGoForward: Boolean,
    isFavorite: Boolean,
    onForward: () -> Unit,
    onToggleFavorite: () -> Unit,
    onNewTab: () -> Unit,
    onFavorites: () -> Unit,
    onHistory: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(width = 30.dp, height = 48.dp)) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Menu", tint = CastifyColors.TextSecondary, modifier = Modifier.size(28.dp))
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = CastifyColors.SurfaceRaised,
            modifier = Modifier.width(220.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceAround) {
                IconButton(onClick = { open = false; onForward() }, enabled = canGoForward && !onHome, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward", modifier = Modifier.size(26.dp))
                }
                IconButton(onClick = { open = false; onToggleFavorite() }, enabled = !onHome, modifier = Modifier.size(48.dp)) {
                    Icon(
                        if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                        tint = if (isFavorite) CastifyColors.Red else if (onHome) CastifyColors.Disabled else CastifyColors.TextPrimary,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
            HorizontalDivider(color = CastifyColors.Disabled.copy(alpha = 0.4f))
            MenuItem("New tab", Icons.Filled.Add) { open = false; onNewTab() }
            HorizontalDivider(color = CastifyColors.Disabled.copy(alpha = 0.4f))
            MenuItem("Favorites", Icons.Filled.Star) { open = false; onFavorites() }
            MenuItem("History", Icons.Filled.History) { open = false; onHistory() }
        }
    }
}

@Composable
private fun MenuItem(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, fontSize = 15.sp) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = CastifyColors.TextSecondary) },
        onClick = onClick,
    )
}

// ---------- home (new tab) ----------

/**
 * New tab (mockup `docs/mockups/home-screen-final.html`): Castify logo, the search field
 * (the top bar has none on this screen), favorites as tiles (up to 7 + Add), then the 5 most
 * recently visited pages.
 */
@Composable
fun HomeScreen(
    address: String,
    onAddressChange: (String) -> Unit,
    onSubmit: () -> Unit,
    favorites: List<Favorite>,
    recent: List<HistoryEntry>,
    onOpenUrl: (String) -> Unit,
    onAllFavorites: () -> Unit,
    onHistory: () -> Unit,
    onAddFavoriteHint: () -> Unit,
    offline: Boolean = false,
    onRetryNetwork: () -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    val submit = { onSubmit(); focusManager.clearFocus() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(bottom = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // The launcher foreground has the mark in its central ~46% (adaptive icon safe zone).
                Image(
                    painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(150.dp).layout { measurable, constraints ->
                        // Crop the empty margin around the mark: keep the middle 96 dp.
                        val placeable = measurable.measure(constraints)
                        val crop = 27.dp.roundToPx()
                        layout(placeable.width, placeable.height - 2 * crop) { placeable.place(0, -crop) }
                    },
                )
                Text("Castify", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(bottom = 20.dp))
                TextField(
                    value = address,
                    onValueChange = onAddressChange,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Address bar" },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    placeholder = { Text("Search or type a website") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = CastifyColors.TextSecondary) },
                    trailingIcon = if (address.isNotBlank()) ({
                        IconButton(onClick = submit) { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Go") }
                    }) else null,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = CastifyColors.Surface,
                        unfocusedContainerColor = CastifyColors.Surface,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                )
            }
        }
        if (offline) item { OfflineBanner(onRetryNetwork, Modifier.padding(bottom = 18.dp)) }
        item { SectionTitle("FAVORITES", "See all", onAllFavorites) }
        item {
            val tiles = favorites.take(7)
            Column(Modifier.padding(bottom = 26.dp)) {
                (tiles.map { it as Any } + "add").chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                        row.forEach { item ->
                            Column(
                                Modifier.weight(1f).clickable {
                                    if (item is Favorite) onOpenUrl(item.url) else onAddFavoriteHint()
                                },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                if (item is Favorite) {
                                    SiteTile(item.title, item.url, 52)
                                    TileLabel(item.title)
                                } else {
                                    Box(Modifier.size(52.dp).background(CastifyColors.Surface, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Filled.Add, contentDescription = null, tint = CastifyColors.TextSecondary)
                                    }
                                    TileLabel("Add")
                                }
                            }
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        item { SectionTitle("RECENT", "History", onHistory) }
        if (recent.isEmpty()) item { EmptyText("Pages you visit will show up here.") }
        items(recent.take(5), key = { it.url }) { entry ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpenUrl(entry.url) }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(if (entry.isSearch) Icons.Filled.Search else Icons.Filled.History, contentDescription = null, tint = CastifyColors.TextSecondary)
                Text(entry.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            HorizontalDivider(color = CastifyColors.Surface)
        }
    }
}

@Composable
private fun SectionTitle(title: String, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = CastifyColors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
        Text(action, color = CastifyColors.Red, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onAction))
    }
}

@Composable
private fun TileLabel(text: String) =
    Text(text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))

// ---------- tabs ----------

/** Tab grid, Chrome style. Only the active tab is loaded; previews are saved screenshots. */
@Composable
fun TabsScreen(
    tabs: List<Tab>,
    activeId: String,
    loadPreview: (String) -> Bitmap?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onCloseAll: () -> Unit,
    onNewTab: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var confirmCloseAll by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(CastifyColors.Background)) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("${tabs.size} tab${if (tabs.size > 1) "s" else ""}", onBack = null) {
                IconButton(onClick = onNewTab) { Icon(Icons.Filled.Add, contentDescription = "New tab", tint = CastifyColors.TextPrimary, modifier = Modifier.size(28.dp)) }
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Tabs menu", tint = CastifyColors.TextSecondary) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = CastifyColors.SurfaceRaised) {
                        DropdownMenuItem(text = { Text("Close all tabs") }, onClick = { menu = false; confirmCloseAll = true })
                    }
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(tabs, key = { it.id }) { tab -> TabCard(tab, tab.id == activeId, loadPreview, onSelect, onClose) }
            }
        }
        Text(
            "+  New tab",
            color = CastifyColors.Background,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 20.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(CastifyColors.TextPrimary)
                .clickable(onClick = onNewTab)
                .padding(horizontal = 22.dp, vertical = 12.dp),
        )
    }
    if (confirmCloseAll) {
        AlertDialog(
            onDismissRequest = { confirmCloseAll = false },
            title = { Text("Close all ${tabs.size} tabs?") },
            confirmButton = { TextButton(onClick = { confirmCloseAll = false; onCloseAll() }) { Text("Close all") } },
            dismissButton = { TextButton(onClick = { confirmCloseAll = false }) { Text("Cancel") } },
            containerColor = CastifyColors.SurfaceRaised,
        )
    }
}

@Composable
private fun TabCard(tab: Tab, active: Boolean, loadPreview: (String) -> Bitmap?, onSelect: (String) -> Unit, onClose: (String) -> Unit) {
    val title = tab.title ?: tab.url?.substringAfter("://")?.substringBefore('/') ?: "New tab"
    val preview = remember(tab.id, tab.url) { if (tab.url != null) loadPreview(tab.id) else null }
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(CastifyColors.Surface)
            .border(BorderStroke(2.dp, if (active) CastifyColors.Red else Color.Transparent), RoundedCornerShape(10.dp))
            .clickable { onSelect(tab.id) }
            .semantics(mergeDescendants = true) { contentDescription = "Tab $title${if (active) ", current" else ""}" },
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SiteTile(title, tab.url, 16)
            Text(title, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 6.dp))
            IconButton(onClick = { onClose(tab.id) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Close tab", tint = CastifyColors.TextSecondary, modifier = Modifier.size(16.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(150.dp).background(CastifyColors.Background)) {
            when {
                preview != null -> Image(preview.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
                tab.url == null -> Column(Modifier.padding(10.dp)) {
                    Text("FAVORITES", fontSize = 9.sp, color = CastifyColors.TextSecondary, fontWeight = FontWeight.ExtraBold)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(3) { Box(Modifier.size(22.dp).background(CastifyColors.Surface, RoundedCornerShape(6.dp))) }
                    }
                }
                else -> SiteTile(title, tab.url, 48, Modifier.align(Alignment.Center))
            }
            if (tab.videoCount > 0) {
                Text(
                    "${tab.videoCount} video${if (tab.videoCount > 1) "s" else ""}",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                        .background(CastifyColors.Red, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

// ---------- favorites ----------

/** Favorites: name only (user: the address is not needed). ⋮ = open in new tab, rename, remove. */
@Composable
fun FavoritesScreen(
    favorites: List<Favorite>,
    onOpen: (String) -> Unit,
    onOpenInNewTab: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var renaming by remember { mutableStateOf<Favorite?>(null) }
    Column(Modifier.fillMaxSize().background(CastifyColors.Background)) {
        ScreenHeader("Favorites", onBack)
        if (favorites.isEmpty()) EmptyText("No favorites yet. Tap ★ in the menu to add the page you are on.")
        LazyColumn(Modifier.weight(1f).navigationBarsPadding()) {
            items(favorites, key = { it.id }) { fav ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(fav.url) }.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SiteTile(fav.title, fav.url, 32)
                    Text(fav.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Options for ${fav.title}", tint = CastifyColors.TextSecondary) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = CastifyColors.SurfaceRaised) {
                            DropdownMenuItem(text = { Text("Open in new tab") }, onClick = { menu = false; onOpenInNewTab(fav.url) })
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = fav })
                            DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; onRemove(fav.id) })
                        }
                    }
                }
            }
        }
    }
    renaming?.let { fav ->
        var text by remember(fav.id) { mutableStateOf(fav.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename favorite") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { onRename(fav.id, text); renaming = null }, enabled = text.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
            containerColor = CastifyColors.SurfaceRaised,
        )
    }
}

// ---------- history ----------

private val dayFormat = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

/** History of every visited page (searches shown as their query): search, grouped by day, ✕ per entry, Clear. */
@Composable
fun HistoryScreen(
    entries: List<HistoryEntry>,
    search: (String) -> List<HistoryEntry>,
    onOpen: (String) -> Unit,
    onRemove: (HistoryEntry) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var query by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val shown = remember(entries, query) { search(query) }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)

    Column(Modifier.fillMaxSize().background(CastifyColors.Background)) {
        ScreenHeader("History", onBack) {
            if (entries.isNotEmpty()) {
                Text(
                    "Clear",
                    color = CastifyColors.Red,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { confirmClear = true }.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        TextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Search history") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = CastifyColors.TextSecondary) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") }
            },
            keyboardOptions = KeyboardOptions.Default,
            shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = CastifyColors.SurfaceRaised,
                unfocusedContainerColor = CastifyColors.SurfaceRaised,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = CastifyColors.Red,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        when {
            entries.isEmpty() -> EmptyText("No history yet. Addresses you type appear here.")
            shown.isEmpty() -> EmptyText("No match for “$query”")
        }
        LazyColumn(Modifier.weight(1f).navigationBarsPadding()) {
            shown.groupBy { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }.forEach { (day, dayEntries) ->
                item(key = "day-$day") {
                    val label = when (day) {
                        today -> "TODAY"
                        today.minusDays(1) -> "YESTERDAY"
                        else -> day.format(dayFormat).uppercase(Locale.US)
                    }
                    Text(label, color = CastifyColors.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp))
                }
                items(dayEntries, key = { it.url }) { entry ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(entry.url) }.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.size(32.dp).background(CastifyColors.Surface, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(if (entry.isSearch) Icons.Filled.Search else Icons.Filled.History, contentDescription = null, tint = CastifyColors.TextSecondary, modifier = Modifier.size(18.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(entry.text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val time = Instant.ofEpochMilli(entry.at).atZone(zone).format(timeFormat)
                            Text(if (entry.isSearch) "$time · search" else time, color = CastifyColors.TextSecondary, fontSize = 12.sp)
                        }
                        IconButton(onClick = { onRemove(entry) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove from history", tint = CastifyColors.TextSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all history?") },
            confirmButton = { TextButton(onClick = { confirmClear = false; onClear() }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
            containerColor = CastifyColors.SurfaceRaised,
        )
    }
}
