package com.anas.secretary.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anas.secretary.data.Repo
import com.anas.secretary.logic.DateExtractor
import com.anas.secretary.logic.LinkPreview
import com.anas.secretary.logic.Preview
import com.anas.secretary.util.Bg
import com.anas.secretary.util.Fmt
import kotlinx.coroutines.launch

/** Appears in the Share menu of every app as "Save to Secretary". */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().trim()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty().trim()
        val url = Regex("""https?://\S+""").find(text)?.value
        val source = referrer?.host?.let { Fmt.appLabel(this, it) }.orEmpty()
        setContent {
            SecretaryTheme {
                ShareScreen(url, text, subject, source) { finish() }
                DateTimePickerHost()
            }
        }
    }
}

@Composable
fun ShareScreen(url: String?, text: String, subject: String, source: String, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    var note by remember { mutableStateOf("") }
    var makeTask by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Preview?>(null) }
    var loading by remember { mutableStateOf(url != null) }
    val detected = DateExtractor.extract(note)

    LaunchedEffect(url) {
        if (url != null) {
            preview = LinkPreview.fetch(ctx, url)
            loading = false
        }
    }
    LaunchedEffect(detected?.at) { if (detected != null) makeTask = true }

    Column(
        Modifier.fillMaxSize().background(p.bg).systemBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Save to Daybrief", fontSize = 28.sp, color = p.text)
            if (source.isNotBlank()) Text("From $source", fontSize = 14.sp, color = p.sub, modifier = Modifier.padding(top = 4.dp))
        }

        // Preview card
        GroupBox {
            val pv = preview
            when {
                pv?.imagePath != null -> Thumb(pv.imagePath, Modifier.fillMaxWidth().height(200.dp))
                loading -> Box(Modifier.fillMaxWidth().height(200.dp).background(p.field), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = p.accent, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
                }
            }
            Column(Modifier.padding(16.dp)) {
                Text(
                    pv?.site ?: LinkPreview.domain(url).ifBlank { "Shared text" },
                    fontSize = 12.sp, color = p.sub
                )
                Text(
                    pv?.title ?: subject.ifBlank { if (loading) "Loading preview…" else (url ?: text) },
                    fontSize = 16.sp, fontWeight = FontWeight.Medium, color = p.text,
                    maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        OutlinedTextField(
            value = note, onValueChange = { note = it },
            placeholder = { Text("Add a note, like \"try this recipe on Friday\"") },
            shape = RoundedCornerShape(20.dp), colors = oneUiFieldColors(),
            modifier = Modifier.fillMaxWidth(), minLines = 3
        )

        GroupBox {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Add to tasks", fontSize = 16.sp, color = p.text)
                    Text(
                        if (detected != null) "Reminder ${Fmt.whenText(detected.at)}" else "Puts it on your to-do list",
                        fontSize = 13.sp, color = if (detected != null) p.accent else p.sub
                    )
                }
                Switch(checked = makeTask, onCheckedChange = { makeTask = it })
            }
        }

        Spacer(Modifier.height(8.dp))
        BigButton("Save") {
            val extra = if (url != null) text.replace(url, "").trim().ifBlank { subject } else text
            val pv = preview
            val id = Repo.addSaved(url, extra, note.trim(), source, pv?.title, pv?.imagePath)
            if (makeTask) Repo.addTask(
                note.trim().ifBlank { "Check: " + (pv?.title ?: subject.ifBlank { url ?: text.take(60) }) },
                url ?: text, detected?.at, "share", null
            )
            // Preview not ready yet: finish it in the background
            if (url != null && pv == null && loading) {
                Bg.scope.launch {
                    LinkPreview.fetch(ctx.applicationContext, url)?.let { Repo.updateSavedPreview(id, it.title, it.imagePath) }
                }
            }
            Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show()
            onDone()
        }
        Box(Modifier.fillMaxWidth().padding(bottom = 24.dp), contentAlignment = Alignment.Center) {
            TextAction("Cancel", p.sub, onDone)
        }
    }
}
