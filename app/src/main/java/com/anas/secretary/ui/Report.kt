package com.anas.secretary.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anas.secretary.util.CrashLog
import com.anas.secretary.util.Fmt

/** A small coloured label at the top of a one-off Brief card. */
@Composable
private fun CardTag(text: String, color: Color) {
    Text(
        text, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = color, letterSpacing = 0.3.sp,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.15f)).padding(horizontal = 9.dp, vertical = 4.dp)
    )
}

@Composable
private fun CardButtons(yes: String, no: String, onYes: () -> Unit, onNo: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.padding(top = 4.dp)) {
        BigButton(yes, onYes)
        Box(
            Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(22.dp)).clickable(onClick = onNo).padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) { Text(no, fontSize = 14.5.sp, color = p.text) }
    }
}

/** Asked once after the update (and after setup): should Brief follow what you do in the notification shade? */
@Composable
fun FollowShadeCard(onAnswer: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier.padding(bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(glassCard()).padding(18.dp)
    ) {
        CardTag("NEW IN THIS VERSION", p.accent)
        Text("Follow your notifications?", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = p.text, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
        Text(
            "When you clear, open or answer a notification on your phone, it leaves Brief too. No more cards for things you already handled.",
            fontSize = 14.sp, color = p.sub, lineHeight = 20.sp
        )
        Row(
            Modifier.padding(vertical = 14.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.field).padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Swipe away in the shade", fontSize = 12.5.sp, color = p.text, modifier = Modifier.weight(1f))
            Text("→", fontSize = 14.sp, color = p.sub, modifier = Modifier.padding(horizontal = 8.dp))
            Text("Gone from Brief", fontSize = 12.5.sp, color = p.text, modifier = Modifier.weight(1f))
        }
        CardButtons("Turn on", "Not now", { onAnswer(true) }, { onAnswer(false) })
        Text(
            "You can change this any time in Settings → Smart prompts", fontSize = 12.sp, color = p.sub,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

/** First card after the app closed unexpectedly. Dismiss deletes the saved error. */
@Composable
fun CrashCard(at: Long, onSee: () -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val warn = Color(0xFFF2A516)
    Column(
        Modifier.padding(bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(glassCard()).padding(18.dp)
    ) {
        CardTag("SORRY ABOUT THAT", warn)
        Text("Daybrief closed unexpectedly", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = p.text, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
        Text(
            "It happened ${Fmt.whenText(at).replaceFirstChar { it.lowercase() }}. A short report helps fix it. You'll see exactly what's in it before anything is sent.",
            fontSize = 14.sp, color = p.sub, lineHeight = 20.sp, modifier = Modifier.padding(bottom = 10.dp)
        )
        CardButtons("See the report", "Dismiss", onSee, onDismiss)
    }
}

/** What's in the report, a note, and two ways to send it. Nothing leaves the phone until the person sends it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportSheet(onDone: (sent: Boolean) -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val trace = remember { CrashLog.lastTrace(ctx) }
    var note by remember { mutableStateOf("") }
    var withModel by remember { mutableStateOf(false) }
    val text = CrashLog.report(note, withModel, trace)
    ModalBottomSheet(onDismissRequest = { onDone(false) }, containerColor = p.card, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp).navigationBarsPadding().imePadding().verticalScroll(rememberScrollState())) {
            Text("Report a problem", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = p.text)
            BasicTextField(
                value = note, onValueChange = { note = it.take(1000) },
                textStyle = TextStyle(fontSize = 15.sp, color = p.text, textDirection = TextDirection.Content),
                cursorBrush = SolidColor(p.accent),
                modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
                decorationBox = { inner ->
                    Box(Modifier.clip(RoundedCornerShape(16.dp)).background(p.field).padding(14.dp)) {
                        if (note.isEmpty()) Text("What happened? (optional)", fontSize = 15.sp, color = p.sub)
                        inner()
                    }
                }
            )
            Text("What's in the report", fontSize = 12.5.sp, color = p.sub, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
            Box(
                Modifier.fillMaxWidth().heightIn(max = 180.dp).clip(RoundedCornerShape(14.dp)).background(p.field)
                    .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp)
            ) {
                Text(text.replace("```\n", "").replace("\n```", "").trim(), fontSize = 11.5.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace, color = p.sub)
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).clickable { withModel = !withModel }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Include phone model (${android.os.Build.MODEL})", fontSize = 14.sp, color = p.text, modifier = Modifier.weight(1f))
                Switch(checked = withModel, onCheckedChange = { withModel = it })
            }
            Text("No messages, names or notification text are ever in it.", fontSize = 12.sp, color = p.sub, modifier = Modifier.padding(top = 2.dp))
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(22.dp)).background(p.field)
                        // Opening the share list doesn't mean it was sent: keep the saved crash (the card's Dismiss removes it)
                        .clickable { if (shareReport(ctx, text)) onDone(false) },
                    contentAlignment = Alignment.Center
                ) { Text("Share…", fontSize = 14.sp, color = p.text, fontWeight = FontWeight.Medium) }
                PrimaryButton("Open on GitHub", Modifier.weight(1f).height(52.dp)) { if (openIssue(ctx, note, withModel, trace)) onDone(true) }
            }
        }
    }
}

/** A new issue on the app's GitHub page with everything filled in; the person presses Submit there. */
private fun openIssue(ctx: Context, note: String, withModel: Boolean, trace: String?): Boolean {
    val title = note.trim().lineSequence().firstOrNull()?.take(80)?.ifBlank { null } ?: if (trace != null) "The app closed unexpectedly" else "Problem report"
    val url = issueUrl(com.anas.secretary.BuildConfig.UPDATE_REPO, title, note, withModel, trace)
    return try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }
    catch (_: Exception) { Toast.makeText(ctx, "No browser found", Toast.LENGTH_SHORT).show(); false }
}

/** Keeps the link short enough for GitHub (about 8 KB): the note is capped, then whole trace lines are dropped from the end. */
internal fun issueUrl(repo: String, title: String, note: String, withModel: Boolean, trace: String?, maxEncoded: Int = 6500): String {
    val head = "https://github.com/$repo/issues/new?title=" + Uri.encode(title) + "&body="
    var lines = trace?.lines()
    var shortNote = note.trim().take(500)
    while (true) {
        val cut = lines != null && lines.size < (trace?.lines()?.size ?: 0)
        val t = lines?.let { (if (cut) it + "  …" else it).joinToString("\n") }
        val body = Uri.encode(CrashLog.report(shortNote, withModel, t))
        if (head.length + body.length <= maxEncoded) return head + body
        when {
            lines != null && lines.size > 3 -> lines = lines.dropLast(1)
            shortNote.length > 100 -> shortNote = shortNote.take(shortNote.length / 2)
            else -> return head + body.take(maxEncoded - head.length).substringBeforeLast('%')
        }
    }
}

private fun shareReport(ctx: Context, body: String): Boolean = try {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "Daybrief problem report").putExtra(Intent.EXTRA_TEXT, body)
    ctx.startActivity(Intent.createChooser(send, "Send the report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
} catch (_: Exception) { false }
