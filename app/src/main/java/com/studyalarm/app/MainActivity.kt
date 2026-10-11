package com.studyalarm.app

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.*

class MainActivity : Activity() {
    private lateinit var box: LinearLayout
    private lateinit var head: TextView
    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Notifier.channels(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        head = TextView(this).apply { textSize = 18f }
        val add = Button(this).apply { text = "+ Add study session"; setOnClickListener { showAdd() } }
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(head); root.addView(add); root.addView(box)
        setContentView(ScrollView(this).apply { addView(root) })
        askPermissions()
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun askPermissions() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        else if (Build.VERSION.SDK_INT >= 34 && !Store.flag(this, "fs")) {
            val nm = getSystemService(NotificationManager::class.java)
            if (!nm.canUseFullScreenIntent()) {
                Store.setFlag(this, "fs")
                Toast.makeText(this, "Allow full-screen alarms so the alarm shows on the lock screen", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            }
        }
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r); askPermissions()
    }

    private fun refresh() {
        val log = Store.log(this)
        val sb = StringBuilder("Total studied: ${fmt(Store.totalMin(this))}")
        log.keys().forEach { sb.append("\n  • $it: ${fmt(log.getInt(it))}") }
        head.text = sb.toString()
        box.removeAllViews()
        Store.load(this).forEach { s ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(14), 0, dp(6)) }
            row.addView(TextView(this).apply {
                textSize = 16f
                text = "${s.subject} — ${s.chapter}\n${t(s.sh, s.sm)} → ${t(s.eh, s.em)} (${fmt(s.minutes)}) • ${if (s.daily) "daily" else "once"}"
            })
            val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            r2.addView(Switch(this).apply {
                text = "Alarm  "; isChecked = s.enabled
                setOnCheckedChangeListener { _, on -> toggle(s.id, on) }
            })
            r2.addView(Button(this).apply { text = "Delete"; setOnClickListener { delete(s.id) } })
            row.addView(r2)
            box.addView(row)
        }
    }

    private fun toggle(id: Int, on: Boolean) {
        val l = Store.load(this); val s = l.find { it.id == id } ?: return
        s.enabled = on
        if (on) { s.startAt = Scheduler.nextStart(s); Scheduler.schedule(this, s, s.startAt) } else Scheduler.cancel(this, id)
        Store.save(this, l)
    }

    private fun delete(id: Int) {
        Scheduler.cancel(this, id)
        val l = Store.load(this); l.removeAll { it.id == id }; Store.save(this, l); refresh()
    }

    private fun showAdd() {
        var sh = 18; var sm = 0; var eh = 19; var em = 0
        val subject = EditText(this).apply { hint = "Subject (e.g. Physics)"; setSingleLine() }
        val chapter = EditText(this).apply { hint = "Chapter (e.g. Ch 3 Motion)"; setSingleLine() }
        val bs = Button(this); val be = Button(this)
        fun lbl() { bs.text = "Start time: ${t(sh, sm)}"; be.text = "End time: ${t(eh, em)}" }
        lbl()
        bs.setOnClickListener { TimePickerDialog(this, { _, h, m -> sh = h; sm = m; lbl() }, sh, sm, false).show() }
        be.setOnClickListener { TimePickerDialog(this, { _, h, m -> eh = h; em = m; lbl() }, eh, em, false).show() }
        val daily = CheckBox(this).apply { text = "Repeat daily"; isChecked = true }
        val lay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
            listOf(subject, chapter, bs, be, daily).forEach { addView(it, LinearLayout.LayoutParams(MATCH_PARENT, -2)) }
        }
        AlertDialog.Builder(this).setTitle("New study session").setView(lay)
            .setPositiveButton("Save") { _, _ ->
                val s = Session((System.currentTimeMillis() / 1000 % 1000000).toInt(),
                    subject.text.toString().trim(), chapter.text.toString().trim(),
                    sh, sm, eh, em, daily.isChecked, true, 0L)
                if (s.subject.isEmpty() || s.minutes == 0) {
                    Toast.makeText(this, "Enter a subject and different start/end times", Toast.LENGTH_LONG).show()
                } else {
                    s.startAt = Scheduler.nextStart(s)
                    Scheduler.schedule(this, s, s.startAt)
                    val l = Store.load(this); l.add(s); Store.save(this, l); refresh()
                    Toast.makeText(this, "Alarm set for ${t(sh, sm)}", Toast.LENGTH_SHORT).show()
                }
            }.setNegativeButton("Cancel", null).show()
    }
}
