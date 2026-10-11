package com.studyalarm.app

import android.Manifest
import android.app.NotificationManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Calendar
import java.util.UUID
import kotlin.math.roundToInt

fun uid() = UUID.randomUUID().toString().take(8)
fun clock(ms: Long): String { val s = ms / 1000; return String.format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60) }
fun dayStart(ago: Int): Long {
    val c = Calendar.getInstance(); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    c.add(Calendar.DAY_OF_YEAR, -ago); return c.timeInMillis
}

class AppState(val c: Context) {
    var ver by mutableIntStateOf(0)
    var alarms by mutableStateOf(Store.load(c))
    val syl = Syl.load(c)
    var timer by mutableStateOf(TimerStore.get(c))
    var prof by mutableStateOf(ProfStore.get(c))
    fun reload() { alarms = Store.load(c); syl.clear(); syl.addAll(Syl.load(c)); timer = TimerStore.get(c); prof = ProfStore.get(c); ver++ }
    // alarms
    fun addAlarm(s: Session) { s.startAt = Scheduler.nextStart(s); Scheduler.schedule(c, s, s.startAt); val l = Store.load(c); l.add(s); Store.save(c, l); reload() }
    fun toggle(id: Int, on: Boolean) {
        val l = Store.load(c); val s = l.find { it.id == id } ?: return; s.enabled = on
        if (on) { s.startAt = Scheduler.nextStart(s); Scheduler.schedule(c, s, s.startAt) } else Scheduler.cancel(c, id)
        Store.save(c, l); reload()
    }
    fun deleteAlarm(id: Int) { Scheduler.cancel(c, id); val l = Store.load(c); l.removeAll { it.id == id }; Store.save(c, l); reload() }
    // timer
    private fun applyTimer(t: TimerState?) { TimerStore.set(c, t); Notifier.timer(c, t); timer = t; ver++ }
    fun startTimer(sub: String) = applyTimer(TimerState(sub, System.currentTimeMillis(), 0L))
    fun pauseTimer() { val t = timer ?: return; if (t.runningSince > 0) applyTimer(TimerState(t.subject, 0L, t.elapsed())) }
    fun resumeTimer() { val t = timer ?: return; if (t.runningSince == 0L) applyTimer(TimerState(t.subject, System.currentTimeMillis(), t.accrued)) }
    fun stopTimer() { val t = timer ?: return; val min = (t.elapsed() / 60000.0).roundToInt(); if (min >= 1) Store.addStudied(c, t.subject, min); applyTimer(null) }
    fun setProfile(p: Profile) { ProfStore.set(c, p); prof = p; ver++ }
    fun addManual(sub: String, min: Int) { Store.addStudied(c, sub, min); ver++ }
    fun deleteEntry(i: Int) { Store.deleteEntry(c, i); ver++ }
    fun delete(e: Any) {
        when (e) {
            is Subject -> syl.remove(e)
            is Chapter -> syl.forEach { it.chapters.remove(e) }
            is Topic -> syl.forEach { s -> s.chapters.forEach { it.topics.remove(e) } }
        }
        saveSyl()
    }
    // syllabus
    fun saveSyl() { Syl.save(c, syl); ver++ }
    fun addSubject(n: String) { syl.add(Subject(uid(), n)); saveSyl() }
    fun addChapter(sid: String, n: String) { syl.find { it.id == sid }?.chapters?.add(Chapter(uid(), n)); saveSyl() }
    fun addTopic(cid: String, n: String) { syl.forEach { s -> s.chapters.find { it.id == cid }?.topics?.add(Topic(uid(), n)) }; saveSyl() }
}

class MainActivity : ComponentActivity() {
    private lateinit var app: AppState
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Notifier.channels(this); app = AppState(this)
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF2457D6))) { Root(app) } }
    }
    override fun onResume() { super.onResume(); if (::app.isInitialized) app.reload() }
}

@Composable
fun Root(app: AppState) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else if (Build.VERSION.SDK_INT >= 34 && !Store.flag(ctx, "fs")) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (!nm.canUseFullScreenIntent()) {
                Store.setFlag(ctx, "fs")
                Toast.makeText(ctx, "Allow full-screen alarms so the alarm shows on the lock screen", Toast.LENGTH_LONG).show()
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}")))
            }
        }
    }
    val items = listOf("🏠" to "Home", "⏰" to "Alarms", "⏱" to "Timer", "📚" to "Syllabus")
    Scaffold(bottomBar = {
        NavigationBar { items.forEachIndexed { i, (ic, label) -> NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(ic) }, label = { Text(label) }) } }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) { 0 -> Home(app) { tab = it }; 1 -> AlarmsScreen(app); 2 -> TimerScreen(app); else -> SyllabusScreen(app) }
        }
    }
}

@Composable
fun Card2(content: @Composable ColumnScope.() -> Unit) =
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content) }

@Composable
fun Home(app: AppState, go: (Int) -> Unit) {
    app.ver
    var setup by remember { mutableStateOf(false) }
    val pr = app.prof
    val total = Store.totalMin(app.c); val today = Store.minutesSince(app.c, dayStart(0)); val week = Store.minutesSince(app.c, dayStart(6))
    val next = app.alarms.filter { it.enabled }.minByOrNull { if (it.daily) Scheduler.nextStart(it) else it.startAt }
    val dl = if (pr.examDate > 0) Math.ceil((pr.examDate - dayStart(0)) / 86400000.0).toInt() else null
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Card2 {
            Text("${pr.course} ${pr.level} · ${pr.group}" + if (pr.attempt.isNotBlank()) " · ${pr.attempt}" else "")
            Text(if (dl == null) "Set your exam date" else if (dl >= 0) "$dl days to exam" else "Exam date has passed", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            OutlinedButton({ setup = true }) { Text("Exam setup") } } }
        item { Card2 { Text("Total hours studied"); Text(fmt(total), fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Text("Today ${fmt(today)} of ${String.format("%.1f", pr.target)}h  ·  Last 7 days ${fmt(week)}")
            LinearProgressIndicator(progress = { (today / (pr.target * 60f)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text("🔥 ${Store.streak(app.c)}-day streak") } }
        item { Card2 { Text("Next alarm", fontWeight = FontWeight.Bold)
            Text(if (next == null) "No alarm set. Add one in the Alarms tab." else "${next.subject} — ${next.chapter}\nat ${t(next.sh, next.sm)}") } }
        item { Button({ go(2) }, Modifier.fillMaxWidth()) { Text("Start study timer") } }
        item { Card2 { Text("Syllabus progress", fontWeight = FontWeight.Bold)
            if (app.syl.isEmpty()) { Text("Add your subjects, chapters and topics to track progress."); OutlinedButton({ go(3) }) { Text("Open Syllabus") } }
            app.syl.forEach { s -> val p = Syl.pct(Syl.topics(s)); Text("${s.name} — ${(p * 100).toInt()}%"); LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) } } }
    }
    if (setup) ExamSetup(app) { setup = false }
}

@Composable
fun Choice(opts: List<String>, cur: String, set: (String) -> Unit) =
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { opts.forEach { o -> FilterChip(cur == o, { set(o) }, { Text(o) }) } }

@Composable
fun ExamSetup(app: AppState, close: () -> Unit) {
    val ctx = LocalContext.current; val p = app.prof
    var course by remember { mutableStateOf(p.course) }; var level by remember { mutableStateOf(p.level) }; var group by remember { mutableStateOf(p.group) }
    var attempt by remember { mutableStateOf(p.attempt) }; var date by remember { mutableLongStateOf(p.examDate) }; var target by remember { mutableStateOf(p.target.toString()) }
    AlertDialog(onDismissRequest = close, title = { Text("Exam setup") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice(listOf("CA", "CMA"), course) { course = it }
            Choice(listOf("Foundation", "Intermediate", "Final"), level) { level = it }
            Choice(listOf("Group 1", "Group 2", "Both"), group) { group = it }
            OutlinedTextField(attempt, { attempt = it }, label = { Text("Session / attempt (e.g. May 2027)") }, singleLine = true)
            OutlinedButton({
                val cal = Calendar.getInstance(); if (date > 0) cal.timeInMillis = date
                DatePickerDialog(ctx, { _, y, mo, d -> val x = Calendar.getInstance(); x.set(y, mo, d, 0, 0, 0); x.set(Calendar.MILLISECOND, 0); date = x.timeInMillis },
                    cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
            }) { Text(if (date > 0) "Exam date: " + java.text.DateFormat.getDateInstance().format(java.util.Date(date)) else "Pick exam date (use the official notice)") }
            OutlinedTextField(target, { target = it }, label = { Text("Daily study target (hours)") }, singleLine = true)
        }
    }, confirmButton = {
        TextButton({ app.setProfile(Profile(course, level, group, attempt.trim(), date, (target.toFloatOrNull() ?: 6f).coerceIn(0.5f, 18f))); close() }) { Text("Save") }
    }, dismissButton = { TextButton(close) { Text("Cancel") } })
}

@Composable
fun AlarmsScreen(app: AppState) {
    app.ver
    var show by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Button({ show = true }, Modifier.fillMaxWidth()) { Text("+ New study alarm") } }
        if (app.alarms.isEmpty()) item { Text("No alarms yet. Add one to get a loud alarm and a lock-screen alert at study time.") }
        items(app.alarms, key = { it.id }) { s ->
            Card2 {
                Text("${s.subject}${if (s.chapter.isNotEmpty()) " — " + s.chapter else ""}", fontWeight = FontWeight.Bold)
                Text("${t(s.sh, s.sm)} → ${t(s.eh, s.em)} (${fmt(s.minutes)}) · ${if (s.daily) "daily" else "once"}")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Alarm "); Switch(checked = s.enabled, onCheckedChange = { on -> app.toggle(s.id, on) })
                    Spacer(Modifier.weight(1f)); TextButton({ app.deleteAlarm(s.id) }) { Text("Delete") }
                }
            }
        }
    }
    if (show) AddAlarm(app) { show = false }
}

@Composable
fun AddAlarm(app: AppState, close: () -> Unit) {
    val ctx = LocalContext.current
    var sub by remember { mutableStateOf("") }; var ch by remember { mutableStateOf("") }
    var sh by remember { mutableIntStateOf(18) }; var sm by remember { mutableIntStateOf(0) }
    var eh by remember { mutableIntStateOf(19) }; var em by remember { mutableIntStateOf(0) }
    var daily by remember { mutableStateOf(true) }
    AlertDialog(onDismissRequest = close, title = { Text("New study alarm") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(sub, { sub = it }, label = { Text("Subject") }, singleLine = true)
            OutlinedTextField(ch, { ch = it }, label = { Text("Chapter") }, singleLine = true)
            OutlinedButton({ TimePickerDialog(ctx, { _, h, m -> sh = h; sm = m }, sh, sm, false).show() }) { Text("Start: ${t(sh, sm)}") }
            OutlinedButton({ TimePickerDialog(ctx, { _, h, m -> eh = h; em = m }, eh, em, false).show() }) { Text("End: ${t(eh, em)}") }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(daily, { daily = it }); Text("Repeat daily") }
        }
    }, confirmButton = {
        TextButton({
            val s = Session((System.currentTimeMillis() / 1000 % 1000000).toInt(), sub.trim(), ch.trim(), sh, sm, eh, em, daily, true, 0L)
            if (s.subject.isEmpty() || s.minutes == 0) Toast.makeText(ctx, "Enter a subject and different start/end times", Toast.LENGTH_LONG).show()
            else { app.addAlarm(s); close() }
        }) { Text("Save") }
    }, dismissButton = { TextButton(close) { Text("Cancel") } })
}

@Composable
fun TimerScreen(app: AppState) {
    app.ver
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    var sub by remember { mutableStateOf("") }; var confirm by remember { mutableStateOf(false) }
    var mins by remember { mutableStateOf("") }; var del by remember { mutableStateOf<Int?>(null) }
    val tm = app.timer
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(clock(tm?.elapsed(now) ?: 0L), fontSize = 56.sp, fontWeight = FontWeight.Bold)
        if (tm == null) {
            OutlinedTextField(sub, { sub = it }, label = { Text("What are you studying?") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (app.syl.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(app.syl) { s -> AssistChip(onClick = { sub = s.name }, label = { Text(s.name) }) } }
            Button({ app.startTimer(sub.trim().ifEmpty { "Study" }) }, Modifier.fillMaxWidth()) { Text("Start") }
            Card2 {
                Text("Add a session manually", fontWeight = FontWeight.Bold)
                OutlinedTextField(mins, { mins = it.filter { ch -> ch.isDigit() } }, label = { Text("Minutes studied") }, singleLine = true)
                Button({
                    val n = mins.toIntOrNull() ?: 0
                    if (n <= 0) Toast.makeText(ctx, "Enter minutes", Toast.LENGTH_SHORT).show() else { app.addManual(sub.trim().ifEmpty { "Study" }, n); mins = "" }
                }) { Text("Add to ${sub.ifBlank { "Study" }}") }
            }
        } else {
            Text(tm.subject, fontSize = 20.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (tm.runningSince > 0) Button({ app.pauseTimer() }) { Text("Pause") } else Button({ app.resumeTimer() }) { Text("Resume") }
                OutlinedButton({ confirm = true }) { Text("Stop & save") }
            }
            Text("Safe to close the app. Time is counted from the clock, and a notification shows it on your lock screen.")
        }
        Text("Recent sessions", fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
        val recent = Store.recent(app.c, 15)
        if (recent.isEmpty()) Text("No sessions yet.", modifier = Modifier.fillMaxWidth())
        recent.forEach { e ->
            Card2 {
                Text("${fmt(e.m)} · ${e.s.ifEmpty { "Study" }}", fontWeight = FontWeight.Bold)
                Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(e.t)))
                TextButton({ del = e.idx }) { Text("Delete") }
            }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Stop and save?") },
        text = { Text("${fmt(((tm?.elapsed(now) ?: 0L) / 60000).toInt())} will be added to your total.") },
        confirmButton = { TextButton({ app.stopTimer(); confirm = false }) { Text("Save") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Keep running") } })
    del?.let { idx -> AlertDialog(onDismissRequest = { del = null }, title = { Text("Delete this session?") }, text = { Text("Its time will be removed from your totals.") },
        confirmButton = { TextButton({ app.deleteEntry(idx); del = null }) { Text("Delete") } }, dismissButton = { TextButton({ del = null }) { Text("Cancel") } }) }
}

@Composable
fun NameDialog(label: String, dismiss: () -> Unit, ok: (String) -> Unit) {
    var v by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text(label) }, text = { OutlinedTextField(v, { v = it }, singleLine = true) },
        confirmButton = { TextButton({ if (v.isNotBlank()) ok(v.trim()) }) { Text("Add") } }, dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}

@Composable
fun SyllabusScreen(app: AppState) {
    app.ver
    var open by remember { mutableStateOf(setOf<String>()) }
    var ask by remember { mutableStateOf<Pair<String, (String) -> Unit>?>(null) }
    var edit by remember { mutableStateOf<Any?>(null) }
    fun tog(k: String) { open = if (k in open) open - k else open + k }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Button({ ask = "Subject name" to { n: String -> app.addSubject(n) } }, Modifier.fillMaxWidth()) { Text("+ Add subject") } }
        if (app.syl.isEmpty()) item { Text("Add a subject, then chapters and topics. For each topic you can track lecture, self-study, practice and revision separately.") }
        items(app.syl, key = { it.id }) { su ->
            val sp = Syl.pct(Syl.topics(su))
            Card2 {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text((if (su.id in open) "▾ " else "▸ ") + "${su.name} — ${(sp * 100).toInt()}%", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f).clickable { tog(su.id) }.padding(vertical = 6.dp))
                    TextButton({ edit = su }) { Text("Edit") }
                }
                LinearProgressIndicator(progress = { sp }, modifier = Modifier.fillMaxWidth())
                if (su.id in open) {
                    su.chapters.forEach { ch ->
                        val cp = Syl.pct(ch.topics)
                        Column(Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text((if (ch.id in open) "▾ " else "▸ ") + "${ch.name} — ${(cp * 100).toInt()}%", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).clickable { tog(ch.id) }.padding(vertical = 6.dp))
                                TextButton({ edit = ch }) { Text("Edit") }
                            }
                            LinearProgressIndicator(progress = { cp }, modifier = Modifier.fillMaxWidth())
                            if (ch.id in open) {
                                ch.topics.forEach { tp -> TopicRow(app, tp) { edit = tp } }
                                TextButton({ ask = "Topic name" to { n: String -> app.addTopic(ch.id, n) } }) { Text("+ Topic") }
                            }
                        }
                    }
                    TextButton({ ask = "Chapter name" to { n: String -> app.addChapter(su.id, n) } }) { Text("+ Chapter") }
                }
            }
        }
    }
    ask?.let { (label, cb) -> NameDialog(label, { ask = null }) { cb(it); ask = null } }
    edit?.let { e -> EditDialog(app, e) { edit = null } }
}

val LV = listOf("Low", "Medium", "High")

@Composable
fun TopicRow(app: AppState, tp: Topic, onEdit: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tp.name, Modifier.weight(1f), fontWeight = FontWeight.Medium)
            TextButton(onEdit) { Text("Edit") }
            OutlinedButton({ tp.status = (tp.status + 1) % 3; app.saveSyl() }) { Text(listOf("Not started", "In progress", "Completed")[tp.status]) }
        }
        Text("Difficulty: ${LV[tp.diff - 1]} · Confidence: ${LV[tp.conf - 1]}", fontSize = 12.sp)
        if (tp.note.isNotBlank()) Text("Note: ${tp.note}", fontSize = 12.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(tp.lec, { tp.lec = !tp.lec; app.saveSyl() }, { Text("Lecture") })
            FilterChip(tp.self, { tp.self = !tp.self; app.saveSyl() }, { Text("Self-study") })
            FilterChip(tp.prac, { tp.prac = !tp.prac; app.saveSyl() }, { Text("Practice") })
            FilterChip(tp.rev, { tp.rev = !tp.rev; app.saveSyl() }, { Text("Revision") })
        }
    }
}

@Composable
fun EditDialog(app: AppState, e: Any, dismiss: () -> Unit) {
    var name by remember { mutableStateOf(when (e) { is Subject -> e.name; is Chapter -> e.name; is Topic -> e.name; else -> "" }) }
    val tp = e as? Topic
    var note by remember { mutableStateOf(tp?.note ?: "") }
    var diff by remember { mutableIntStateOf(tp?.diff ?: 2) }; var conf by remember { mutableIntStateOf(tp?.conf ?: 2) }
    var sure by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Edit") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
            if (tp != null) {
                Text("Difficulty"); Choice(LV, LV[diff - 1]) { diff = LV.indexOf(it) + 1 }
                Text("Confidence"); Choice(LV, LV[conf - 1]) { conf = LV.indexOf(it) + 1 }
                OutlinedTextField(note, { note = it }, label = { Text("Notes / remarks") })
            }
            TextButton({ if (sure) { app.delete(e); dismiss() } else { sure = true } }) {
                Text(if (sure) "Tap again to delete (cannot undo)" else "Delete", color = MaterialTheme.colorScheme.error)
            }
        }
    }, confirmButton = {
        TextButton({
            if (name.isNotBlank()) {
                when (e) {
                    is Subject -> e.name = name.trim()
                    is Chapter -> e.name = name.trim()
                    is Topic -> { e.name = name.trim(); e.note = note; e.diff = diff; e.conf = conf }
                }
                app.saveSyl(); dismiss()
            }
        }) { Text("Save") }
    }, dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}
