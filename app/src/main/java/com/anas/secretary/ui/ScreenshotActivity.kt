package com.anas.secretary.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import com.anas.secretary.data.Repo
import com.anas.secretary.util.Images
import com.anas.secretary.util.Notifs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Save this screenshot?" — no text scanning, just your picture, a name and an optional reminder. */
class ScreenshotActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val uri: Uri? = intent.getStringExtra("uri")?.let { Uri.parse(it) }
            ?: IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        val mode = intent.getStringExtra("mode") ?: "task"
        uri?.let { Notifs.cancel(this, Notifs.screenshotId(it)) }
        setContent {
            SecretaryTheme {
                ScreenshotScreen(uri, mode) { finish() }
                DateTimePickerHost()
            }
        }
    }
}

@Composable
fun ScreenshotScreen(uri: Uri?, mode: String, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var due by remember { mutableStateOf<Long?>(null) }
    var viewing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    fun save(asTask: Boolean) {
        if (saving || uri == null) return
        saving = true
        scope.launch {
            val path = withContext(Dispatchers.IO) { Images.saveUri(ctx, uri) }
            val name = title.trim()
            if (asTask) Repo.addTask(name.ifBlank { "Check screenshot" }, "", due, "screenshot", path)
            else Repo.addSaved(null, "", name, "Screenshot", null, path)
            Toast.makeText(ctx, if (asTask) "Added to tasks" else "Saved", Toast.LENGTH_SHORT).show()
            onDone()
        }
    }

    Column(
        Modifier.fillMaxSize().background(p.bg).systemBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (mode == "save") "Keep this screenshot?" else "Add this screenshot?", fontSize = 28.sp, color = p.text)
            Text("It stays private on your phone", fontSize = 14.sp, color = p.sub, modifier = Modifier.padding(top = 4.dp))
        }

        if (uri == null) {
            Text("This picture couldn't be opened.", color = p.sub)
            BigButton("Close", onDone)
        } else {
        Thumb(
            uri,
            Modifier.fillMaxWidth().height(340.dp).clip(RoundedCornerShape(26.dp)).clickable { viewing = true },
            alignTop = true
        )
        OutlinedTextField(
            value = title, onValueChange = { title = it },
            placeholder = { Text("What's this for? (optional)") },
            shape = RoundedCornerShape(20.dp), colors = oneUiFieldColors(), modifier = Modifier.fillMaxWidth()
        )
        if (mode != "save") DueRow(due) { due = it }
        Spacer(Modifier.height(4.dp))
        if (mode == "save") {
            BigButton(if (saving) "Saving…" else "Keep in Saved") { save(asTask = false) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextAction("Add to tasks instead") { save(asTask = true) }
                TextAction("Not now", p.sub, onDone)
            }
        } else {
            BigButton(if (saving) "Saving…" else "Add to tasks") { save(asTask = true) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextAction("Keep in Saved instead") { save(asTask = false) }
                TextAction("Not now", p.sub, onDone)
            }
        }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (viewing && uri != null) ImageViewer(uri) { viewing = false }
}
