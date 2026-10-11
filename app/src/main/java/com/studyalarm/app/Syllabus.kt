package com.studyalarm.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Topic(val id: String, var name: String, var status: Int = 0, var lec: Boolean = false, var self: Boolean = false, var prac: Boolean = false, var rev: Boolean = false)
data class Chapter(val id: String, var name: String, val topics: MutableList<Topic> = mutableListOf())
data class Subject(val id: String, var name: String, val chapters: MutableList<Chapter> = mutableListOf())

/** status: 0 not started, 1 in progress, 2 completed. Lecture / self-study / practice / revision are tracked separately. */
object Syl {
    private fun p(c: Context) = c.getSharedPreferences("study", Context.MODE_PRIVATE)
    fun load(c: Context): MutableList<Subject> {
        val a = JSONArray(p(c).getString("syllabus", "[]"))
        return MutableList(a.length()) { i ->
            val s = a.getJSONObject(i); val ca = s.getJSONArray("c")
            Subject(s.getString("id"), s.getString("n"), MutableList(ca.length()) { j ->
                val ch = ca.getJSONObject(j); val ta = ch.getJSONArray("t")
                Chapter(ch.getString("id"), ch.getString("n"), MutableList(ta.length()) { k ->
                    val t = ta.getJSONObject(k)
                    Topic(t.getString("id"), t.getString("n"), t.getInt("s"), t.getBoolean("l"), t.getBoolean("f"), t.getBoolean("p"), t.getBoolean("r"))
                })
            })
        }
    }
    fun save(c: Context, l: List<Subject>) {
        val a = JSONArray()
        l.forEach { s ->
            val ca = JSONArray()
            s.chapters.forEach { ch ->
                val ta = JSONArray()
                ch.topics.forEach { t -> ta.put(JSONObject().put("id", t.id).put("n", t.name).put("s", t.status).put("l", t.lec).put("f", t.self).put("p", t.prac).put("r", t.rev)) }
                ca.put(JSONObject().put("id", ch.id).put("n", ch.name).put("t", ta))
            }
            a.put(JSONObject().put("id", s.id).put("n", s.name).put("c", ca))
        }
        p(c).edit().putString("syllabus", a.toString()).apply()
    }
    fun topics(s: Subject) = s.chapters.flatMap { it.topics }
    fun pct(t: List<Topic>) = if (t.isEmpty()) 0f else t.count { it.status == 2 } / t.size.toFloat()
}

/** Timer based on timestamps: runningSince == 0 means paused. Survives app close and restart. */
data class TimerState(val subject: String, val runningSince: Long, val accrued: Long) {
    fun elapsed(now: Long = System.currentTimeMillis()) = accrued + if (runningSince > 0) maxOf(0L, now - runningSince) else 0L
}
object TimerStore {
    private fun p(c: Context) = c.getSharedPreferences("study", Context.MODE_PRIVATE)
    fun get(c: Context): TimerState? {
        val s = p(c).getString("timer", null) ?: return null
        val o = JSONObject(s); return TimerState(o.getString("sub"), o.getLong("run"), o.getLong("acc"))
    }
    fun set(c: Context, t: TimerState?) {
        val e = p(c).edit()
        if (t == null) e.remove("timer") else e.putString("timer", JSONObject().put("sub", t.subject).put("run", t.runningSince).put("acc", t.accrued).toString())
        e.apply()
    }
}
