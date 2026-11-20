package com.anas.secretary.ui

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.anas.secretary.util.Fmt
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.abs

// ---------- Opening the picker from anywhere ----------

class PickRequest(val initial: Long?, val onPicked: (Long) -> Unit)

object PickerHost {
    var request by mutableStateOf<PickRequest?>(null)
}

/** Shows the Secretary date & time sheet. Every screen hosts it with DateTimePickerHost(). */
@Suppress("UNUSED_PARAMETER")
fun pickDateTime(ctx: Context, initial: Long?, onPicked: (Long) -> Unit) {
    PickerHost.request = PickRequest(initial, onPicked)
}

@Composable
fun DateTimePickerHost() {
    val r = PickerHost.request ?: return
    DateTimeSheet(
        initial = r.initial,
        onDismiss = { PickerHost.request = null },
        onPick = { PickerHost.request = null; r.onPicked(it) }
    )
}

// ---------- The sheet ----------

@Composable
fun DateTimeSheet(initial: Long?, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val zone = ZoneId.systemDefault()
    val start = remember {
        (initial?.let { Instant.ofEpochMilli(it) } ?: Instant.now().plusSeconds(3600)).atZone(zone)
    }
    val is24 = remember { DateFormat.is24HourFormat(ctx) }
    var date by remember { mutableStateOf(start.toLocalDate()) }
    var month by remember { mutableStateOf(YearMonth.from(start)) }
    var hour24 by remember { mutableIntStateOf(start.hour) }
    var hour12 by remember { mutableIntStateOf((start.hour + 11) % 12 + 1) }
    var pm by remember { mutableStateOf(start.hour >= 12) }
    var minute by remember { mutableIntStateOf(start.minute) }

    val hour = if (is24) hour24 else (hour12 % 12) + (if (pm) 12 else 0)
    val result = ZonedDateTime.of(date, LocalTime.of(hour, minute), zone).toInstant().toEpochMilli()
    val inPast = result <= System.currentTimeMillis()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                Modifier.navigationBarsPadding().padding(10.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(32.dp)).background(p.card)
                    .clickable(remember { MutableInteractionSource() }, null) {}
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 22.dp)
            ) {
                // Header: what you're choosing
                Text("Remind me", fontSize = 15.sp, color = p.sub)
                Text(
                    Fmt.whenText(result), fontSize = 26.sp, color = if (inPast) p.danger else p.text,
                    modifier = Modifier.padding(top = 2.dp)
                )
                if (inPast) Text("This time has already passed", fontSize = 13.sp, color = p.danger)

                // One-tap choices
                Row(
                    Modifier.padding(top = 16.dp).fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ReplyChip("In 1 hour") { onPick(inOneHour()) }
                    ReplyChip("Tonight 20:00") { onPick(atHour(0, 20)) }
                    ReplyChip("Tomorrow 9:00") { onPick(atHour(1, 9)) }
                    ReplyChip("Tomorrow 20:00") { onPick(atHour(1, 20)) }
                    ReplyChip("Next week") { onPick(atHour(7, 9)) }
                }

                // Calendar
                MonthCalendar(month, date, onMonth = { month = it }, onDay = { date = it })

                // Time wheels
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (is24) {
                        Wheel((0..23).map { "%02d".format(Locale.US, it) }, start.hour, 72.dp) { hour24 = it }
                    } else {
                        Wheel((1..12).map { it.toString() }, (start.hour + 11) % 12, 72.dp) { hour12 = it + 1 }
                    }
                    Text(":", fontSize = 26.sp, color = p.text, modifier = Modifier.padding(horizontal = 4.dp))
                    Wheel((0..59).map { "%02d".format(Locale.US, it) }, start.minute, 72.dp) { minute = it }
                    if (!is24) {
                        Spacer(Modifier.width(8.dp))
                        Wheel(listOf("AM", "PM"), if (start.hour >= 12) 1 else 0, 72.dp) { pm = it == 1 }
                    }
                }

                // Buttons
                Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextAction("Cancel", p.sub, onDismiss)
                    Spacer(Modifier.width(8.dp))
                    PrimaryButton("Set reminder", Modifier.weight(1f).height(50.dp)) { onPick(result) }
                }
            }
        }
    }
}

@Composable
private fun MonthCalendar(month: YearMonth, selected: LocalDate, onMonth: (YearMonth) -> Unit, onDay: (LocalDate) -> Unit) {
    val p = LocalPalette.current
    val locale = Locale.getDefault()
    val firstDow = WeekFields.of(locale).firstDayOfWeek
    val today = LocalDate.now()
    val days = (0 until 7).map { firstDow.plus(it.toLong()) }
    val lead = (month.atDay(1).dayOfWeek.value - firstDow.value + 7) % 7
    val cells: List<LocalDate?> = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }

    Column(Modifier.padding(top = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                month.format(DateTimeFormatter.ofPattern("MMMM yyyy", locale)),
                fontSize = 17.sp, fontWeight = FontWeight.Medium, color = p.text, modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
            val canGoBack = month > YearMonth.from(today)
            Icon(
                Icons.Default.KeyboardArrowLeft, "Previous month",
                tint = if (canGoBack) p.text else p.divider,
                modifier = Modifier.size(40.dp).clip(CircleShape).clickable(enabled = canGoBack) { onMonth(month.minusMonths(1)) }.padding(8.dp)
            )
            Icon(
                Icons.Default.KeyboardArrowRight, "Next month", tint = p.text,
                modifier = Modifier.size(40.dp).clip(CircleShape).clickable { onMonth(month.plusMonths(1)) }.padding(8.dp)
            )
        }
        Row(Modifier.padding(top = 8.dp)) {
            days.forEach { d ->
                Text(
                    d.getDisplayName(TextStyle.NARROW, locale), fontSize = 13.sp, color = p.sub,
                    modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        cells.chunked(7).forEach { week ->
            Row(Modifier.padding(top = 4.dp)) {
                (0 until 7).forEach { i ->
                    val day = week.getOrNull(i)
                    Box(Modifier.weight(1f).height(42.dp), contentAlignment = Alignment.Center) {
                        if (day != null) {
                            val past = day.isBefore(today)
                            val isSel = day == selected
                            val isToday = day == today
                            Box(
                                Modifier.size(38.dp).clip(CircleShape)
                                    .then(
                                        when {
                                            isSel -> Modifier.background(p.accent)
                                            isToday -> Modifier.border(1.5.dp, p.accent, CircleShape)
                                            else -> Modifier
                                        }
                                    )
                                    .clickable(enabled = !past) { onDay(day) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    day.dayOfMonth.toString(), fontSize = 15.sp,
                                    color = when {
                                        isSel -> Color.White
                                        past -> p.divider
                                        isToday -> p.accent
                                        else -> p.text
                                    },
                                    fontWeight = if (isSel || isToday) FontWeight.Medium else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Scroll wheel like Samsung's clock app. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Wheel(labels: List<String>, initial: Int, width: Dp, onSelected: (Int) -> Unit) {
    val p = LocalPalette.current
    val itemH = 46.dp
    val state = rememberLazyListState(initialFirstVisibleItemIndex = initial.coerceIn(0, labels.lastIndex))
    val fling = rememberSnapFlingBehavior(lazyListState = state)
    val selected by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - center) }?.index ?: initial
        }
    }
    LaunchedEffect(selected) { onSelected(selected) }

    Box(Modifier.width(width).height(itemH * 3), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().height(itemH).clip(RoundedCornerShape(14.dp)).background(p.field))
        LazyColumn(
            state = state,
            flingBehavior = fling,
            contentPadding = PaddingValues(vertical = itemH),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            items(labels.size) { i ->
                Box(Modifier.height(itemH).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        labels[i],
                        fontSize = if (i == selected) 24.sp else 19.sp,
                        fontWeight = if (i == selected) FontWeight.Medium else FontWeight.Normal,
                        color = if (i == selected) p.text else p.sub
                    )
                }
            }
        }
    }
}
