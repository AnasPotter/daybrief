package com.anas.secretary.ui


import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import com.anas.secretary.logic.DateExtractor
import com.anas.secretary.util.Fmt
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

// ======================= Theme (Samsung One UI look) =======================

data class Palette(
    val bg: Color, val card: Color, val text: Color, val sub: Color, val divider: Color,
    val accent: Color, val pill: Color, val pillText: Color, val danger: Color, val field: Color,
    /** Quick-reply chips and other "good" pills, and the text on them. */
    val good: Color = Color(0xFF1F3A2C), val onGood: Color = Color(0xFFBFE8CF),
    /** My own message bubble, and its text. */
    val bubble: Color = Color(0xFF1F5C3D), val onBubble: Color = Color(0xFFE8FFF1),
    /** Text that needs attention (overdue, needs reply), on track, and warnings. */
    val alert: Color = Color(0xFFFF8A8A), val success: Color = Color(0xFF3DBE74), val warn: Color = Color(0xFFF2A516),
    /** Send buttons. */
    val send: Color = Color(0xFF25B35A)
)

val DarkPalette = Palette(
    bg = Color(0xFF000000), card = Color(0xFF1C1C1E), text = Color(0xFFFAFAFA), sub = Color(0xFF9E9E9E),
    divider = Color(0xFF2C2C2E), accent = Color(0xFF3E91FF), pill = Color(0xFF13284A),
    pillText = Color(0xFF8FBCFF), danger = Color(0xFFFF6B6B), field = Color(0xFF2C2C2E)
)
val LightPalette = Palette(
    bg = Color(0xFFF2F2F2), card = Color(0xFFFFFFFF), text = Color(0xFF111111), sub = Color(0xFF727272),
    divider = Color(0xFFE6E6E6), accent = Color(0xFF0A7CFF), pill = Color(0xFFE3EFFF),
    pillText = Color(0xFF0A5FD1), danger = Color(0xFFE5484D), field = Color(0xFFF2F2F2),
    good = Color(0xFFE3F6E8), onGood = Color(0xFF145A32), bubble = Color(0xFFD9FDD3), onBubble = Color(0xFF0B3D20),
    alert = Color(0xFFD93636), success = Color(0xFF1E8E4F), warn = Color(0xFFB7791F)
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

/** Purple used for everything work-related. */
val WorkColor = Color(0xFF7F77DD)

@Composable
fun WorkBadge() {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(WorkColor.copy(alpha = 0.22f)).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) { Text("Work", fontSize = 11.sp, color = Color(0xFFB9B2F2), fontWeight = FontWeight.Medium) }
}

@Composable
fun SecretaryTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val p = if (dark) DarkPalette else LightPalette
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = p.accent, onPrimary = Color.White, background = p.bg, onBackground = p.text,
        surface = p.card, onSurface = p.text, onSurfaceVariant = p.sub, surfaceVariant = p.field,
        outline = p.sub, outlineVariant = p.divider, error = p.danger,
        surfaceContainer = p.card, surfaceContainerHigh = p.card, surfaceContainerHighest = p.field,
        secondaryContainer = p.pill, onSecondaryContainer = p.pillText
    )
    // Each piece of text picks its direction from its own first letter, so Arabic reads right-to-left
    // and English left-to-right, even when they're mixed in one note.
    val t = remember {
        val b = Typography()
        fun TextStyle.c() = copy(textDirection = TextDirection.Content)
        b.copy(
            displayLarge = b.displayLarge.c(), displayMedium = b.displayMedium.c(), displaySmall = b.displaySmall.c(),
            headlineLarge = b.headlineLarge.c(), headlineMedium = b.headlineMedium.c(), headlineSmall = b.headlineSmall.c(),
            titleLarge = b.titleLarge.c(), titleMedium = b.titleMedium.c(), titleSmall = b.titleSmall.c(),
            bodyLarge = b.bodyLarge.c(), bodyMedium = b.bodyMedium.c(), bodySmall = b.bodySmall.c(),
            labelLarge = b.labelLarge.c(), labelMedium = b.labelMedium.c(), labelSmall = b.labelSmall.c()
        )
    }
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = t, content = content)
    }
}

// ======================= Layout pieces =======================

/** One UI screen: big title in the top area, small title appears when you scroll. */
@Composable
fun OneUiScreen(
    title: String,
    subtitle: String?,
    state: LazyListState = rememberLazyListState(),
    extra: (@Composable () -> Unit)? = null,
    /** A main tab's name (e.g. "Track"): shows the new left header with the avatar, then [title] as a section title. */
    page: String? = null,
    content: LazyListScope.() -> Unit
) {
    val p = LocalPalette.current
    val collapsed by remember { derivedStateOf { state.firstVisibleItemIndex > 0 } }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 150.dp)
        ) {
            if (page != null) {
                item(key = "header") { MainHeader(page) }
                if (extra != null) item(key = "extra") { Box(Modifier.padding(bottom = 18.dp)) { extra() } }
                item(key = "section") {
                    Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 14.dp)) {
                        Text(title, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = p.text)
                        if (subtitle != null) Text(subtitle, fontSize = 14.sp, color = p.text.copy(alpha = 0.75f), modifier = Modifier.padding(top = 3.dp))
                    }
                }
            } else {
                item(key = "header") {
                    Box(Modifier.fillMaxWidth().statusBarsPadding()) {
                        Column(
                            Modifier.fillMaxWidth().height(230.dp),
                            verticalArrangement = Arrangement.Bottom,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(title, fontSize = 38.sp, color = p.text)
                            if (subtitle != null) {
                                Text(subtitle, fontSize = 14.sp, color = p.sub, modifier = Modifier.padding(top = 6.dp))
                            }
                            Spacer(Modifier.height(if (extra != null) 24.dp else 44.dp))
                        }
                        Row(Modifier.align(Alignment.TopEnd).padding(top = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) { SearchButton(); Spacer(Modifier.width(6.dp)); Avatar() }
                    }
                }
                if (extra != null) item(key = "extra") { Box(Modifier.padding(bottom = 8.dp)) { extra() } }
            }
            content()
        }
        AnimatedVisibility(visible = collapsed, enter = fadeIn(), exit = fadeOut()) {
            // Solid, with a line under it, so the list never shows under the title
            Column(Modifier.fillMaxWidth().background(glowTop())) {
                Box(
                    Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 24.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(page ?: title, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = p.text)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(p.divider))
            }
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text, fontSize = 13.sp, color = LocalPalette.current.sub,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp)
    )
}

/** Rounded container that groups rows, like Samsung Settings. */
@Composable
fun GroupBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(glassCard()),
        content = content
    )
}

@Composable
fun GroupDivider(start: Dp = 68.dp) {
    Box(
        Modifier.padding(start = start, end = 18.dp).fillMaxWidth().height(1.dp)
            .background(LocalPalette.current.divider)
    )
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String) {
    val p = LocalPalette.current
    GroupBox {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(p.pill),
                contentAlignment = Alignment.Center
            ) { Icon(icon, null, tint = p.pillText, modifier = Modifier.size(28.dp)) }
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = p.text,
                modifier = Modifier.padding(top = 14.dp))
            Text(body, fontSize = 14.sp, color = p.sub, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
fun Pill(icon: ImageVector, text: String) {
    val p = LocalPalette.current
    Row(
        Modifier.clip(RoundedCornerShape(14.dp)).background(p.pill).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = p.pillText, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 12.sp, color = p.pillText, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        modifier.clip(RoundedCornerShape(22.dp)).background(p.accent).clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
}

@Composable
fun BigButton(text: String, onClick: () -> Unit) {
    PrimaryButton(text, Modifier.fillMaxWidth().height(52.dp), onClick)
}

@Composable
fun TextAction(text: String, color: Color? = null, onClick: () -> Unit) {
    val p = LocalPalette.current
    Text(
        text, color = color ?: p.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    )
}

/** Round checkbox like Samsung Reminder, with a little bounce. */
@Composable
fun RoundCheck(checked: Boolean, onToggle: () -> Unit) {
    val view = LocalView.current
    val p = LocalPalette.current
    val bg by animateColorAsState(if (checked) p.accent else Color.Transparent, tween(200), label = "checkBg")
    val scale by animateFloatAsState(if (checked) 1f else 0.4f, spring(dampingRatio = 0.45f, stiffness = 500f), label = "checkScale")
    Box(
        Modifier.size(26.dp).clip(CircleShape).background(bg)
            .then(if (checked) Modifier else Modifier.border(2.dp, p.sub, CircleShape))
            .clickable { if (!checked) Feel.confirm(view); onToggle() },
        contentAlignment = Alignment.Center
    ) {
        if (checked) Icon(
            Icons.Default.Check, null, tint = Color.White,
            modifier = Modifier.size(16.dp).graphicsLayer { scaleX = scale; scaleY = scale }
        )
    }
}

private val iconCache = HashMap<String, ImageBitmap?>()

/** The real icon of the app a notification came from (WhatsApp, Gmail…). */
@Composable
fun AppIcon(pkg: String, size: Dp = 40.dp) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val bmp = remember(pkg) {
        iconCache.getOrPut(pkg) {
            try { ctx.packageManager.getApplicationIcon(pkg).toBitmap(120, 120).asImageBitmap() } catch (e: Exception) { null }
        }
    }
    if (bmp != null) {
        Image(bmp, null, Modifier.size(size))
    } else {
        Box(Modifier.size(size).clip(CircleShape).background(p.accent), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Notifications, null, tint = Color.White, modifier = Modifier.size(size / 2))
        }
    }
}

/** Shows a saved image file or a content:// uri. */
@Composable
fun Thumb(model: Any?, modifier: Modifier, alignTop: Boolean = false) {
    val data = if (model is String) File(model) else model
    AsyncImage(
        model = data, contentDescription = null, contentScale = ContentScale.Crop,
        alignment = if (alignTop) Alignment.TopCenter else Alignment.Center,
        modifier = modifier.background(LocalPalette.current.field)
    )
}

@Composable
fun ImageViewer(model: Any, onDismiss: () -> Unit) {
    val data = if (model is String) File(model) else model
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onDismiss)) {
            AsyncImage(
                model = data, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().systemBarsPadding()
            )
        }
    }
}

@Composable
fun oneUiFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = LocalPalette.current.accent,
    unfocusedBorderColor = LocalPalette.current.divider,
    focusedContainerColor = LocalPalette.current.field,
    unfocusedContainerColor = LocalPalette.current.field
)

/** Quick-reply button used by the secretary cards. */
@Composable
fun ReplyChip(text: String, primary: Boolean = false, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (primary) p.accent else p.field)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        Text(
            text, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            color = if (primary) Color.White else p.text
        )
    }
}

// ======================= Dates & task dialog =======================

/** Reminder row: shows the chosen time, or "Add reminder". */
@Composable
fun DueRow(due: Long?, onChange: (Long?) -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.field)
            .clickable { pickDateTime(ctx, due) { onChange(it) } }.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.DateRange, null, tint = if (due != null) p.accent else p.sub, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            if (due != null) Fmt.whenText(due) else "Add reminder",
            color = if (due != null) p.text else p.sub, fontSize = 15.sp, modifier = Modifier.weight(1f)
        )
        if (due != null) {
            Icon(Icons.Default.Close, "Remove reminder", tint = p.sub,
                modifier = Modifier.size(20.dp).clickable { onChange(null) })
        }
    }
}

@Composable
fun TaskDialog(
    heading: String,
    initTitle: String = "",
    initNotes: String = "",
    initDue: Long? = null,
    image: String? = null,
    initWork: Boolean = false,
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onSave: (title: String, notes: String, due: Long?, work: Boolean) -> Unit
) {
    val p = LocalPalette.current
    var work by remember { mutableStateOf(initWork) }
    var title by remember { mutableStateOf(initTitle) }
    var notes by remember { mutableStateOf(initNotes) }
    var due by remember { mutableStateOf(initDue) }
    val detected = if (due == null) DateExtractor.extract(title) else null

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = p.card,
        title = { Text(heading, fontSize = 20.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (image != null) {
                    Thumb(image, Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(16.dp)), alignTop = true)
                }
                OutlinedTextField(
                    value = title, onValueChange = { title = it }, placeholder = { Text("What do you need to do?") },
                    shape = RoundedCornerShape(16.dp), colors = oneUiFieldColors(), modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it }, placeholder = { Text("Notes") },
                    shape = RoundedCornerShape(16.dp), colors = oneUiFieldColors(),
                    modifier = Modifier.fillMaxWidth(), maxLines = 4
                )
                DueRow(due) { due = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(false to "Personal", true to "Work").forEach { (w, label) ->
                        val on = work == w
                        Box(
                            Modifier.clip(RoundedCornerShape(50))
                                .background(if (on) (if (w) WorkColor else p.accent) else p.field)
                                .clickable { work = w }.padding(horizontal = 16.dp, vertical = 8.dp)
                        ) { Text(label, fontSize = 14.sp, color = if (on) Color.White else p.text, fontWeight = FontWeight.Medium) }
                    }
                }
                if (detected != null) {
                    Box(Modifier.clip(RoundedCornerShape(14.dp)).clickable { due = detected.at }) {
                        Pill(Icons.Default.DateRange, "Use ${Fmt.whenText(detected.at)}")
                    }
                }
            }
        },
        confirmButton = {
            TextAction("Save") { if (title.isNotBlank()) onSave(title.trim(), notes.trim(), due, work) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextAction("Delete", p.danger, onDelete)
                TextAction("Cancel", p.sub, onDismiss)
            }
        }
    )
}
