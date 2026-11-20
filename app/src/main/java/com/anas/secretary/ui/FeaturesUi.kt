package com.anas.secretary.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anas.secretary.util.Features
import com.anas.secretary.util.Features.F
import com.anas.secretary.util.Prefs

/** Icon and colour for each part, shared by the Features page and the first-run picker. */
fun featureLook(f: F): Pair<ImageVector, Color> = when (f) {
    F.Messages -> Icons.Default.Notifications to Color(0xFF3E91FF)
    F.Calendar -> Icons.Default.DateRange to Color(0xFFFF7A2F)
    F.People -> Icons.Default.Person to Color(0xFFFF4F7B)
    F.Things -> Icons.Default.CheckCircle to Color(0xFF2ED3B7)
    F.Health -> Icons.Default.Favorite to Color(0xFFA97CFF)
    F.Saved -> Icons.Default.Star to Color(0xFF5BD16B)
    F.Edge -> Icons.Default.Info to Color(0xFFFFB800)
    F.Day -> Icons.Default.Home to Color(0xFF7F77DD)
}

/** Settings → Features: one switch per part, with what it needs. */
@Composable
fun FeaturesList() {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    listOf(F.entries.take(6), F.entries.drop(6)).forEachIndexed { gi, group ->
        if (gi > 0) Spacer(Modifier.height(12.dp))
        GroupBox {
            group.forEachIndexed { i, f ->
                if (i > 0) GroupDivider()
                val on = Features.on(f)
                val (icon, tint) = featureLook(f)
                Row(
                    Modifier.fillMaxWidth().clickable { Features.set(ctx, f, !on) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(tint.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = tint, modifier = Modifier.size(19.dp))
                    }
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(f.label, fontSize = 15.sp, color = p.text)
                        Text(f.about, fontSize = 12.sp, color = p.sub)
                        f.needs?.let {
                            Text(it, fontSize = 10.5.sp, color = p.sub, modifier = Modifier.padding(top = 4.dp)
                                .clip(RoundedCornerShape(8.dp)).background(p.field).padding(horizontal = 7.dp, vertical = 2.dp))
                        }
                    }
                    Switch(checked = on, onCheckedChange = { Features.set(ctx, f, it) })
                }
            }
        }
    }
    Text(
        "A part that's off is hidden and does nothing in the background. Your data stays, so turning it back on brings it all back.",
        fontSize = 12.5.sp, color = p.sub, modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)
    )
}

const val SPONSOR_URL = "https://ko-fi.com/anaspotter"

fun openSponsor(ctx: Context) {
    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SPONSOR_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
}

@Composable
fun HeartBadge(size: Int = 56) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFFFF6B9A), Color(0xFFFF3D6E)))),
        contentAlignment = Alignment.Center
    ) { Icon(Icons.Default.Favorite, null, tint = Color.White, modifier = Modifier.size((size / 2).dp)) }
}

/** "Enjoying Daybrief?" with a button to the Ko-fi page. No payment happens inside the app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = p.card, shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)) {
        Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, bottom = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            HeartBadge()
            Text("Enjoying Daybrief?", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = p.text, modifier = Modifier.padding(top = 12.dp))
            Text(
                "It's free, has no ads and keeps your data on your phone. If it helps you, you can support it. Pay with PayPal, a card or Google Pay.",
                fontSize = 14.sp, color = p.sub, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp)
            )
            PrimaryButton("Support on Ko-fi", Modifier.fillMaxWidth().padding(top = 16.dp)) { openSponsor(ctx); onDismiss() }
            Box(Modifier.padding(top = 6.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onDismiss).padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text("Maybe later", fontSize = 14.sp, color = p.sub)
            }
        }
    }
}

/** Shown once in Brief after 30 days of use; × hides it for good. */
@Composable
fun SupportCard(onOpen: () -> Unit, onClose: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.padding(bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(p.card)
            .clickable(onClick = onOpen).padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HeartBadge(40)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text("30 days with Daybrief", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = p.text)
            Text("If it helps, you can support it on Ko-fi.", fontSize = 12.5.sp, color = p.sub)
        }
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Close, "Hide", tint = p.sub, modifier = Modifier.size(20.dp))
        }
    }
}

/** True from 30 days after the app was first installed, until the card is closed. */
fun supportCardDue(ctx: Context): Boolean {
    if (Prefs.supportDismissed) return false
    val first = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).firstInstallTime } catch (_: Exception) { return false }
    return System.currentTimeMillis() - first > 30 * 86_400_000L
}
