package com.anas.secretary.ui

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anas.secretary.data.Repo
import com.anas.secretary.data.SavedItem
import com.anas.secretary.logic.LinkPreview
import com.anas.secretary.util.Fmt
import com.anas.secretary.util.Prefs
import kotlinx.coroutines.launch

// ======================= SAVED =======================

private object PreviewRetry { val tried = HashSet<Long>() }

/** Every saved item lands in a group named after the app it came from. */
object SavedGroups {
    private val sites = listOf(
        "instagram.com" to "Instagram", "tiktok.com" to "TikTok", "youtube.com" to "YouTube", "youtu.be" to "YouTube",
        "x.com" to "X", "twitter.com" to "X", "facebook.com" to "Facebook", "fb.watch" to "Facebook",
        "snapchat.com" to "Snapchat", "reddit.com" to "Reddit", "threads.net" to "Threads", "linkedin.com" to "LinkedIn",
        "pinterest.com" to "Pinterest", "pin.it" to "Pinterest", "maps.app.goo.gl" to "Maps", "wa.me" to "WhatsApp"
    )
    private val packages = mapOf(
        "com.android.chrome" to "Chrome", "com.sec.android.app.sbrowser" to "Samsung Internet",
        "com.whatsapp" to "WhatsApp", "com.whatsapp.w4b" to "WhatsApp", "org.telegram.messenger" to "Telegram",
        "com.google.android.gm" to "Gmail", "com.instagram.android" to "Instagram", "com.zhiliaoapp.musically" to "TikTok",
        "com.ss.android.ugc.trill" to "TikTok", "com.google.android.youtube" to "YouTube", "com.twitter.android" to "X",
        "com.snapchat.android" to "Snapchat"
    )
    private val colors = mapOf(
        "Instagram" to 0xFFE1306C, "TikTok" to 0xFF25F4EE, "YouTube" to 0xFFFF3B30, "X" to 0xFF8E8E93,
        "Facebook" to 0xFF1877F2, "Snapchat" to 0xFFFFFC00, "Reddit" to 0xFFFF4500, "WhatsApp" to 0xFF25D366,
        "Telegram" to 0xFF2AABEE, "Chrome" to 0xFF34A853, "Samsung Internet" to 0xFF7F77DD,
        "Screenshots" to 0xFF8E8E93, "Notes" to 0xFFF2A516
    )
    private val spare = listOf(0xFF3E91FF, 0xFFFF6B9A, 0xFF00BCD4, 0xFFF2A516, 0xFF7F77DD, 0xFF34C759)
    private val short = mapOf(
        "Instagram" to "IG", "TikTok" to "TT", "YouTube" to "YT", "Facebook" to "FB", "Snapchat" to "SC",
        "WhatsApp" to "WA", "Screenshots" to "SS", "Samsung Internet" to "SI"
    )

    fun name(s: SavedItem): String {
        s.grp?.let { return it }
        if (s.url == null) return if (s.source == "Screenshot" || s.image != null) "Screenshots" else "Notes"
        val host = LinkPreview.domain(s.url).lowercase()
        sites.firstOrNull { (d, _) -> host == d || host.endsWith(".$d") }?.let { return it.second }
        val src = s.source.trim()
        if (src.isNotEmpty()) {
            packages[src]?.let { return it }
            // A package name means the app's label couldn't be read: use its last word
            if (!src.contains(' ') && src.count { it == '.' } >= 1) {
                return src.substringAfterLast('.').replaceFirstChar { it.uppercase() }
            }
            return src
        }
        return "Links"
    }

    fun color(group: String): Color =
        Color((colors[group] ?: spare[Math.floorMod(group.hashCode(), spare.size)]).toInt())

    fun letters(group: String): String = short[group] ?: group.split(' ').filter { it.isNotEmpty() }.let {
        if (it.size >= 2) "${it[0][0]}${it[1][0]}".uppercase() else group.take(2).replaceFirstChar { c -> c.uppercase() }
    }
}

@Composable
fun SavedScreen(v: Int) {
    val ctx = LocalContext.current
    val list = remember(v) { Repo.saved() }
    var viewing by remember { mutableStateOf<String?>(null) }
    var size by remember { mutableStateOf(Prefs.savedSize) }
    var filter by remember { mutableStateOf<String?>(null) }
    var acting by remember { mutableStateOf<SavedItem?>(null) }
    var editing by remember { mutableStateOf<SavedItem?>(null) }
    var moving by remember { mutableStateOf<SavedItem?>(null) }
    var why by remember { mutableStateOf<String?>(null) }
    val retrying = remember { mutableStateListOf<Long>() }
    val scope = rememberCoroutineScope()

    // Fill in previews that failed or didn't finish (e.g. you were offline)
    LaunchedEffect(v) {
        val todo = list.filter { it.url != null && it.image == null && it.id !in PreviewRetry.tried }
        todo.forEach { s ->
            PreviewRetry.tried += s.id
            val pv = LinkPreview.fetch(ctx, s.url!!)
            if (pv != null) Repo.updateSavedPreview(s.id, pv.title, pv.imagePath)
        }
        if (todo.isNotEmpty()) Repo.changed() // refresh so "Loading preview…" disappears
    }

    val groups = remember(list) { list.groupBy { SavedGroups.name(it) } } // newest group first
    val current = filter?.takeIf { it in groups }

    fun open(s: SavedItem) {
        if (s.url != null) openUrl(ctx, s.url) else s.image?.let { viewing = it }
    }
    fun retry(s: SavedItem) {
        val link = s.url ?: return
        retrying += s.id
        scope.launch {
            val pv = LinkPreview.fetch(ctx, link)
            retrying -= s.id
            if (pv != null && (pv.imagePath != null || pv.title != null)) Repo.updateSavedPreview(s.id, pv.title, pv.imagePath)
            if (pv?.imagePath == null) why = LinkPreview.lastLog.ifBlank { "No details" }
        }
    }

    val subtitle = when (list.size) {
        0 -> "Links and screenshots you keep"
        1 -> "1 item"
        else -> "${list.size} items  ·  ${groups.size} ${if (groups.size == 1) "app" else "apps"}"
    }

    OneUiScreen(
        "Saved", subtitle, page = "Saved", section = false,
        extra = if (list.isEmpty()) null else {
            {
                Column {
                    Text(subtitle, fontSize = 14.sp, color = LocalPalette.current.text.copy(alpha = 0.75f), modifier = Modifier.padding(start = 8.dp, bottom = 10.dp))
                    SizeBar(size) { size = it; Prefs.savedSize = it }
                    GroupChips(groups, list.size, current) { filter = it }
                }
            }
        }
    ) {
        if (list.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    Icons.Default.Star, "Save links from any app",
                    "In TikTok, Instagram, YouTube or your browser, tap Share and pick Save to Daybrief."
                )
            }
        }
        val shown = if (current == null) groups.keys.toList() else listOf(current)
        shown.forEach { g ->
            val items = groups[g].orEmpty()
            val limit = if (current == null) (if (size == "grid") 4 else 2) else items.size
            val visible = items.take(limit)
            item(key = "head_$g") {
                SectionHead(g, items.size, if (current == null && items.size > limit) items.size else 0, Modifier.animateItem()) {
                    filter = g
                }
            }
            val onMore: (SavedItem) -> Unit = { acting = it }
            when (size) {
                // Big picture tiles, two per row
                "grid" -> visible.chunked(2).forEach { row ->
                    item(key = "grid_${row.first().id}") {
                        Row(Modifier.animateItem().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { s ->
                                SavedTile(s, g, s.id in retrying, Modifier.weight(1f), { open(s) }) { onMore(s) }
                            }
                            repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                "cards" -> visible.forEach { s ->
                    item(key = s.id) {
                        Box(Modifier.animateItem().padding(bottom = 10.dp)) {
                            SavedCardMedium(s, g, s.id in retrying, { open(s) }) { onMore(s) }
                        }
                    }
                }
                else -> item(key = "list_$g") {
                    GroupBox(Modifier.animateItem().padding(bottom = 6.dp)) {
                        visible.forEachIndexed { i, s ->
                            if (i > 0) Box(Modifier.padding(start = 84.dp, end = 12.dp).fillMaxWidth().height(1.dp)
                                .background(LocalPalette.current.divider))
                            SavedRow(s, g, s.id in retrying, { open(s) }) { onMore(s) }
                        }
                    }
                }
            }
            item(key = "gap_$g") { Spacer(Modifier.height(10.dp)) }
        }
    }

    acting?.let { s ->
        SavedActions(
            s, SavedGroups.name(s),
            onDismiss = { acting = null },
            onOpen = { acting = null; open(s) },
            onEdit = { acting = null; editing = s },
            onTask = {
                acting = null
                Repo.addTask(
                    s.note.ifBlank { "Check: " + (s.title ?: LinkPreview.domain(s.url).ifBlank { s.body.take(60) }) },
                    s.url ?: s.body, null, "saved"
                )
                Toast.makeText(ctx, "Added to tasks", Toast.LENGTH_SHORT).show()
            },
            onRetry = { acting = null; retry(s) },
            onMove = { acting = null; moving = s },
            onDelete = { acting = null; Repo.deleteSaved(s.id) }
        )
    }
    editing?.let { s -> EditSavedDialog(s) { editing = null } }
    moving?.let { s ->
        MoveGroupDialog(s, groups.keys.toList()) { moving = null }
    }
    why?.let { log -> WhyNoPreviewDialog(log) { why = null } }
    viewing?.let { ImageViewer(it) { viewing = null } }
}

// ---------- Top: size switch and app chips ----------

@Composable
private fun SizeBar(size: String, onSize: (String) -> Unit) {
    val p = LocalPalette.current
    val sizes = listOf("small" to "Small", "grid" to "Grid", "cards" to "Cards")
    val sel = sizes.indexOfFirst { it.first == size }.coerceAtLeast(0)
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(sizes[sel].second, fontSize = 13.sp, color = p.sub)
        Spacer(Modifier.weight(1f))
        Box(Modifier.clip(CircleShape).background(p.card).padding(3.dp)) {
            val x by animateDpAsState(44.dp * sel, spring(dampingRatio = 0.75f, stiffness = 500f), label = "size")
            Box(Modifier.offset(x = x).size(44.dp, 34.dp).clip(CircleShape).background(p.field))
            Row {
                sizes.forEachIndexed { i, (key, _) ->
                    val c by animateColorAsState(if (i == sel) p.text else p.sub, label = "sizeIcon")
                    Box(Modifier.size(44.dp, 34.dp).clip(CircleShape).clickable { onSize(key) }, contentAlignment = Alignment.Center) {
                        SizeIcon(key, c)
                    }
                }
            }
        }
    }
}

/** Small drawn icons: list rows, a 3×3 grid, or two stacked cards. */
@Composable
private fun SizeIcon(kind: String, color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width
        val r = CornerRadius(w * 0.08f)
        when (kind) {
            "grid" -> {
                val c = w / 3.4f; val gap = (w - c * 3) / 2
                for (i in 0..2) for (j in 0..2) drawRoundRect(color, Offset(i * (c + gap), j * (c + gap)), Size(c, c), r)
            }
            "cards" -> {
                drawRoundRect(color, Offset(0f, 0f), Size(w, w * 0.55f), r)
                drawRoundRect(color, Offset(0f, w * 0.68f), Size(w, w * 0.32f), r)
            }
            else -> for (j in 0..2) {
                val y = j * w * 0.37f
                drawRoundRect(color, Offset(0f, y), Size(w * 0.26f, w * 0.26f), r)
                drawRoundRect(color, Offset(w * 0.38f, y + w * 0.08f), Size(w * 0.62f, w * 0.1f), r)
            }
        }
    }
}

@Composable
private fun GroupChips(groups: Map<String, List<SavedItem>>, total: Int, current: String?, onPick: (String?) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp),
        modifier = Modifier.padding(bottom = 6.dp)
    ) {
        item(key = "all") { Chip("All", total, current == null, null) { onPick(null) } }
        items(groups.keys.toList(), key = { it }) { g -> Chip(g, groups[g].orEmpty().size, current == g, g) { onPick(g) } }
    }
}

@Composable
private fun Chip(label: String, count: Int, on: Boolean, group: String?, onClick: () -> Unit) {
    val p = LocalPalette.current
    val bg by animateColorAsState(if (on) p.text else p.card, label = "chipBg")
    val fg by animateColorAsState(if (on) p.bg else p.text, label = "chipFg")
    Row(
        Modifier.clip(CircleShape).background(bg).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (group != null) { GroupBadge(group, 18.dp); Spacer(Modifier.width(7.dp)) }
        Text(label, fontSize = 14.sp, color = fg)
        Text("  $count", fontSize = 12.sp, color = if (on) fg.copy(alpha = 0.55f) else p.sub)
    }
}

@Composable
private fun GroupBadge(group: String, size: Dp) {
    val c = SavedGroups.color(group)
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(c),
        contentAlignment = Alignment.Center
    ) {
        Text(
            SavedGroups.letters(group), fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Bold,
            color = if (c.luminance() > 0.5f) Color.Black else Color.White, maxLines = 1
        )
    }
}

@Composable
private fun SectionHead(group: String, count: Int, more: Int, modifier: Modifier, onMore: () -> Unit) {
    val p = LocalPalette.current
    Row(modifier.fillMaxWidth().padding(start = 6.dp, end = 4.dp, top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        GroupBadge(group, 26.dp)
        Text(group, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = p.text, modifier = Modifier.padding(start = 10.dp))
        Text("  $count", fontSize = 13.sp, color = p.sub)
        Spacer(Modifier.weight(1f))
        if (more > 0) TextAction("See all $more", onClick = onMore)
    }
}

// ---------- The three sizes ----------

private fun headline(s: SavedItem): String? =
    s.title?.takeUnless { LinkPreview.isGeneric(it) } ?: if (s.url == null) s.note.ifBlank { null } else null

/** The note the person wrote, when it isn't already the headline. */
private fun ownNote(s: SavedItem): String? = s.note.trim().ifBlank { null }?.takeIf { it != headline(s) }

private fun loading(s: SavedItem) = s.url != null && headline(s) == null && s.image == null && s.id !in PreviewRetry.tried

/** The picture, or a tinted box with the app's letters when there is none. */
@Composable
private fun SavedThumb(s: SavedItem, group: String, busy: Boolean, modifier: Modifier) {
    if (s.image != null) {
        Thumb(s.image, modifier, alignTop = s.url == null)
    } else {
        Box(modifier.background(SavedGroups.color(group).copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = SavedGroups.color(group))
            else Text(SavedGroups.letters(group), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = SavedGroups.color(group))
        }
    }
}

@Composable
private fun SubLine(s: SavedItem) {
    val p = LocalPalette.current
    // The note gets its own lines: squeezed next to the date it was cut off after a few words
    ownNote(s)?.let { n ->
        Text(n, fontSize = 13.sp, color = p.accent, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 17.sp,
            style = androidx.compose.ui.text.TextStyle(textDirection = androidx.compose.ui.text.style.TextDirection.Content),
            modifier = Modifier.padding(top = 3.dp))
    }
    Text(Fmt.short(s.created), fontSize = 12.sp, color = p.sub, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
}

@Composable
private fun TitleText(s: SavedItem, size: Int, weight: FontWeight = FontWeight.Normal) {
    val p = LocalPalette.current
    val h = headline(s)
    when {
        h != null -> Text(h, fontSize = size.sp, fontWeight = weight, color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = (size + 5).sp)
        loading(s) -> Text("Loading preview…", fontSize = size.sp, color = p.sub)
        // No title: a short form of the link (instagram.com/p/DYhq…) instead of the whole https:// address
        s.url != null -> Text(
            s.url.substringAfter("://").removePrefix("www.").substringBefore('?').trimEnd('/'),
            fontSize = (size - 1).sp, color = p.accent, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        else -> Text(s.body.ifBlank { "Screenshot" }, fontSize = size.sp, color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MoreButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(Icons.Default.MoreVert, "More", tint = LocalPalette.current.sub, modifier = Modifier.size(20.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedRow(s: SavedItem, group: String, busy: Boolean, onOpen: () -> Unit, onMore: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onMore)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SavedThumb(s, group, busy, Modifier.size(60.dp).clip(RoundedCornerShape(12.dp)))
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
            TitleText(s, 15)
            SubLine(s)
        }
        MoreButton(onMore)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedTile(s: SavedItem, group: String, busy: Boolean, modifier: Modifier, onOpen: () -> Unit, onMore: () -> Unit) {
    Box(modifier.aspectRatio(0.82f).clip(RoundedCornerShape(22.dp)).combinedClickable(onClick = onOpen, onLongClick = onMore)) {
        SavedThumb(s, group, busy, Modifier.fillMaxSize())
        val h = headline(s)
        val n = ownNote(s)
        if (h != null || n != null) Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                .padding(start = 12.dp, end = 12.dp, top = 24.dp, bottom = 11.dp)
        ) {
            if (h != null) Text(h, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 17.sp)
            // Your own note, in a light blue so it reads apart from the link's title
            if (n != null) Text(
                n, fontSize = 12.sp, color = Color(0xFF9CC7FF), maxLines = if (h == null) 3 else 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp,
                style = androidx.compose.ui.text.TextStyle(textDirection = androidx.compose.ui.text.style.TextDirection.Content),
                modifier = Modifier.padding(top = if (h != null) 3.dp else 0.dp)
            )
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(8.dp).size(32.dp).clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f)).clickable(onClick = onMore),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Default.MoreVert, "More", tint = Color.White, modifier = Modifier.size(16.dp)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedCardMedium(s: SavedItem, group: String, busy: Boolean, onOpen: () -> Unit, onMore: () -> Unit) {
    GroupBox(Modifier.combinedClickable(onClick = onOpen, onLongClick = onMore)) {
        SavedThumb(s, group, busy, Modifier.fillMaxWidth().height(120.dp))
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                TitleText(s, 15, FontWeight.Medium)
                SubLine(s)
            }
            MoreButton(onMore)
        }
    }
}

// ---------- Actions sheet and dialogs ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedActions(
    s: SavedItem, group: String, onDismiss: () -> Unit, onOpen: () -> Unit, onEdit: () -> Unit, onTask: () -> Unit,
    onRetry: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit
) {
    val p = LocalPalette.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = p.card, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 28.dp)) {
            Row(Modifier.padding(start = 4.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                SavedThumb(s, group, false, Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)))
                Column(Modifier.padding(start = 12.dp)) {
                    Text(headline(s) ?: LinkPreview.domain(s.url).ifBlank { "Saved item" }, fontSize = 15.sp,
                        fontWeight = FontWeight.Medium, color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("$group  ·  ${Fmt.short(s.created)}", fontSize = 12.sp, color = p.sub, modifier = Modifier.padding(top = 3.dp))
                }
            }
            ownNote(s)?.let { n ->
                Text(n, fontSize = 14.sp, color = p.text, lineHeight = 19.sp,
                    style = androidx.compose.ui.text.TextStyle(textDirection = androidx.compose.ui.text.style.TextDirection.Content),
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 14.dp).fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp)).background(p.field).padding(12.dp))
            }
            val actions = buildList {
                add(Triple(if (s.url != null) "Open" else "View picture", p.text, onOpen))
                add(Triple(if (s.note.isBlank()) "Add a note" else "Edit", p.text, onEdit))
                add(Triple("Add to tasks", p.text, onTask))
                if (s.url != null && s.image == null) add(Triple("Load the preview again", p.text, onRetry))
                add(Triple("Move to group…", p.text, onMove))
                add(Triple("Delete", p.danger, onDelete))
            }
            Column(Modifier.clip(RoundedCornerShape(18.dp)).background(p.field)) {
                actions.forEachIndexed { i, (label, color, action) ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(p.divider))
                    Text(
                        label, fontSize = 16.sp, color = color,
                        modifier = Modifier.fillMaxWidth().clickable(onClick = action).padding(horizontal = 18.dp, vertical = 15.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EditSavedDialog(s: SavedItem, onDone: () -> Unit) {
    val p = LocalPalette.current
    val title = s.title?.takeUnless { LinkPreview.isGeneric(it) }
    var note by remember { mutableStateOf(s.note) }
    var name by remember { mutableStateOf(title ?: "") }
    AlertDialog(
        onDismissRequest = onDone,
        shape = RoundedCornerShape(28.dp), containerColor = p.card,
        title = { Text("Edit", fontSize = 20.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (s.url != null) OutlinedTextField(
                    name, { name = it }, placeholder = { Text("Title") }, singleLine = true,
                    shape = RoundedCornerShape(16.dp), colors = oneUiFieldColors(), modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    note, { note = it }, placeholder = { Text("Your note") }, minLines = 3,
                    shape = RoundedCornerShape(16.dp), colors = oneUiFieldColors(), modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextAction("Save") {
                Repo.updateSaved(s.id, note.trim(), if (s.url != null) name.trim().ifBlank { null } else s.title)
                onDone()
            }
        },
        dismissButton = { TextAction("Cancel", p.sub, onDone) }
    )
}

@Composable
private fun MoveGroupDialog(s: SavedItem, groups: List<String>, onDone: () -> Unit) {
    val p = LocalPalette.current
    val now = SavedGroups.name(s)
    var custom by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDone,
        shape = RoundedCornerShape(28.dp), containerColor = p.card,
        title = { Text("Move to group", fontSize = 20.sp) },
        text = {
            Column {
                Column(Modifier.heightIn(max = 300.dp).clip(RoundedCornerShape(16.dp)).background(p.field)) {
                    if (s.grp != null) GroupOption("Automatic (by app)", null, false) { Repo.setSavedGroup(s.id, null); onDone() }
                    groups.forEach { g ->
                        GroupOption(g, g, g == now) { Repo.setSavedGroup(s.id, g); onDone() }
                    }
                }
                OutlinedTextField(
                    custom, { custom = it }, placeholder = { Text("New group name") }, singleLine = true,
                    shape = RoundedCornerShape(16.dp), colors = oneUiFieldColors(),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                )
            }
        },
        confirmButton = {
            TextAction("Move") { if (custom.isNotBlank()) Repo.setSavedGroup(s.id, custom); onDone() }
        },
        dismissButton = { TextAction("Cancel", p.sub, onDone) }
    )
}

@Composable
private fun GroupOption(label: String, group: String?, on: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (group != null) GroupBadge(group, 22.dp) else Icon(Icons.Default.Refresh, null, tint = p.sub, modifier = Modifier.size(22.dp))
        Text(label, fontSize = 15.sp, color = p.text, modifier = Modifier.weight(1f).padding(start = 12.dp))
        if (on) Icon(Icons.Default.Check, null, tint = p.accent, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun WhyNoPreviewDialog(log: String, onDone: () -> Unit) {
    val p = LocalPalette.current
    AlertDialog(
        onDismissRequest = onDone,
        shape = RoundedCornerShape(28.dp), containerColor = p.card,
        title = { Text("Why there's no preview", fontSize = 20.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The site didn't share a picture. Take a screenshot of this and send it to me so I can fix it:", fontSize = 14.sp, color = p.sub)
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(log, fontSize = 12.sp, color = p.text,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(p.field).padding(10.dp))
                }
            }
        },
        confirmButton = { TextAction("OK", onClick = onDone) }
    )
}
