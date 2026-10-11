package com.studyalarm.app

import android.app.*
import android.content.*
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import org.json.*
import java.util.Calendar

fun t(h: Int, m: Int) = String.format("%d:%02d %s", if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "AM" else "PM")
fun fmt(min: Int) = "${min / 60}h ${min % 60}m"

data class Session(
    val id: Int, val subject: String, val chapter: String,
    val sh: Int, val sm: Int, val eh: Int, val em: Int,
    val daily: Boolean, var enabled: Boolean, var startAt: Long
) {
    val minutes get() = ((eh * 60 + em) - (sh * 60 + sm) + 1440) % 1440
    fun toJson() = JSONObject().put("id", id).put("subject", subject).put("chapter", chapter)
        .put("sh", sh).put("sm", sm).put("eh", eh).put("em", em)
        .put("daily", daily).put("enabled", enabled).put("startAt", startAt)
    companion object {
        fun from(o: JSONObject) = Session(o.getInt("id"), o.getString("subject"), o.getString("chapter"),
            o.getInt("sh"), o.getInt("sm"), o.getInt("eh"), o.getInt("em"),
            o.getBoolean("daily"), o.getBoolean("enabled"), o.getLong("startAt"))
    }
}

object Store {
    private fun p(c: Context) = c.getSharedPreferences("study", Context.MODE_PRIVATE)
    fun load(c: Context): MutableList<Session> {
        val a = JSONArray(p(c).getString("sessions", "[]"))
        return MutableList(a.length()) { Session.from(a.getJSONObject(it)) }
    }
    fun save(c: Context, l: List<Session>) {
        val a = JSONArray(); l.forEach { a.put(it.toJson()) }
        p(c).edit().putString("sessions", a.toString()).apply()
    }
    fun log(c: Context) = JSONObject(p(c).getString("log", "{}"))
    fun addStudied(c: Context, subject: String, min: Int) {
        val o = log(c); o.put(subject, o.optInt(subject) + min)
        p(c).edit().putString("log", o.toString()).apply()
    }
    fun totalMin(c: Context): Int { val o = log(c); var s = 0; o.keys().forEach { s += o.getInt(it) }; return s }
    fun flag(c: Context, k: String) = p(c).getBoolean(k, false)
    fun setFlag(c: Context, k: String) = p(c).edit().putBoolean(k, true).apply()
}

object Scheduler {
    fun nextStart(s: Session): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, s.sh); c.set(Calendar.MINUTE, s.sm)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }
    private fun pi(c: Context, id: Int, type: Int): PendingIntent {
        val i = Intent(c, AlarmReceiver::class.java).putExtra("id", id).putExtra("type", type)
        return PendingIntent.getBroadcast(c, id * 2 + type, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    // setAlarmClock = most reliable alarm: fires in Doze, no special permission needed
    fun schedule(c: Context, s: Session, start: Long) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val show = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        am.setAlarmClock(AlarmManager.AlarmClockInfo(start, show), pi(c, s.id, 0))
        am.setAlarmClock(AlarmManager.AlarmClockInfo(start + s.minutes * 60000L, show), pi(c, s.id, 1))
    }
    fun cancel(c: Context, id: Int) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pi(c, id, 0)); am.cancel(pi(c, id, 1))
    }
}

object Notifier {
    const val ALARM = "study_alarm"
    const val INFO = "study_info"
    fun channels(c: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = c.getSystemService(NotificationManager::class.java)
        val a = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val ch = NotificationChannel(ALARM, "Study alarms", NotificationManager.IMPORTANCE_HIGH)
        ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), a)
        ch.enableVibration(true); ch.vibrationPattern = longArrayOf(0, 700, 400, 700)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(ch)
        nm.createNotificationChannel(NotificationChannel(INFO, "Study info", NotificationManager.IMPORTANCE_DEFAULT))
    }
    private fun builder(c: Context, ch: String) =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, ch) else Notification.Builder(c)

    /** Loud, repeating alarm + heads-up + full-screen on lock screen, until Stop is pressed */
    fun ring(c: Context, nid: Int, title: String, text: String) {
        channels(c)
        val open = Intent(c, AlarmActivity::class.java).putExtra("nid", nid)
            .putExtra("title", title).putExtra("text", text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val full = PendingIntent.getActivity(c, nid, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getBroadcast(c, 50_000_000 + nid,
            Intent(c, AlarmReceiver::class.java).putExtra("type", 2).putExtra("nid", nid),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = builder(c, ALARM)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title).setContentText(text)
            .setCategory(Notification.CATEGORY_ALARM).setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(full).setFullScreenIntent(full, true)
            .addAction(0, "STOP", stop)
        if (Build.VERSION.SDK_INT < 26) {
            b.setPriority(Notification.PRIORITY_MAX)
            b.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
        } else b.setTimeoutAfter(5 * 60 * 1000L)
        val n = b.build()
        n.flags = n.flags or Notification.FLAG_INSISTENT
        c.getSystemService(NotificationManager::class.java).notify(nid, n)
    }
    fun info(c: Context, nid: Int, title: String, text: String) {
        channels(c)
        val n = builder(c, INFO).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(text).setAutoCancel(true).build()
        c.getSystemService(NotificationManager::class.java).notify(nid, n)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val nm = c.getSystemService(NotificationManager::class.java)
        val type = i.getIntExtra("type", 0)
        if (type == 2) { nm.cancel(i.getIntExtra("nid", 0)); return }
        val list = Store.load(c)
        val s = list.find { it.id == i.getIntExtra("id", -1) } ?: return
        if (type == 0) {
            Notifier.ring(c, s.id * 2, "📚 Study time: ${s.subject}",
                "${s.chapter} • ${t(s.sh, s.sm)} - ${t(s.eh, s.em)}")
        } else {
            Store.addStudied(c, s.subject, s.minutes)
            nm.cancel(s.id * 2)
            Notifier.info(c, s.id * 2 + 1, "Session finished ✅",
                "${s.subject}: +${fmt(s.minutes)}. Total studied: ${fmt(Store.totalMin(c))}")
            if (s.daily) Scheduler.schedule(c, s, Scheduler.nextStart(s)) else s.enabled = false
            Store.save(c, list)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val list = Store.load(c)
        list.forEach { s ->
            if (!s.enabled) return@forEach
            if (s.daily) Scheduler.schedule(c, s, Scheduler.nextStart(s))
            else if (s.startAt > System.currentTimeMillis()) Scheduler.schedule(c, s, s.startAt)
            else s.enabled = false
        }
        Store.save(c, list)
    }
}
