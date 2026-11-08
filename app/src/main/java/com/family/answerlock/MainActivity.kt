package com.family.answerlock

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 家长设置页 + 启动/停止守护
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var gradeSp: Spinner
    private lateinit var intervalSp: Spinner
    private lateinit var quizTimeSp: Spinner
    private lateinit var penaltySp: Spinner
    private lateinit var dailySp: Spinner
    private lateinit var pwdEt: EditText
    private lateinit var chkMath: CheckBox
    private lateinit var chkChinese: CheckBox
    private lateinit var chkEnglish: CheckBox
    private lateinit var voiceChk: CheckBox
    private lateinit var statusTv: TextView
    private lateinit var statsTv: TextView
    private lateinit var autoTv: TextView

    private val gradeNames = listOf(
        "一年级", "二年级", "三年级", "四年级", "五年级", "六年级", "七年级", "八年级", "九年级"
    )
    private val intervalValues = listOf(30, 60, 180, 300, 600)
    private val quizTimeValues = listOf(30, 60, 120, 300, 600)
    private val dailyValues = listOf(5, 20, 30, 60)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("guard", MODE_PRIVATE)

        gradeSp = findViewById(R.id.gradeSp)
        intervalSp = findViewById(R.id.intervalSp)
        quizTimeSp = findViewById(R.id.quizTimeSp)
        penaltySp = findViewById(R.id.penaltySp)
        dailySp = findViewById(R.id.dailySp)
        pwdEt = findViewById(R.id.pwdEt)
        chkMath = findViewById(R.id.chkMath)
        chkChinese = findViewById(R.id.chkChinese)
        chkEnglish = findViewById(R.id.chkEnglish)
        voiceChk = findViewById(R.id.voiceChk)
        voiceChk.isChecked = prefs.getBoolean("voice", true)
        statusTv = findViewById(R.id.statusTv)
        statsTv = findViewById(R.id.statsTv)
        autoTv = findViewById(R.id.autoTv)

        gradeSp.adapter = makeAdapter(gradeNames)
        gradeSp.setSelection(prefs.getInt("grade", 3) - 1)
        updateAutoLabel()
        gradeSp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                updateAutoLabel()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        intervalSp.adapter = makeAdapter(listOf("30 秒（演示）", "1 分钟", "3 分钟", "5 分钟", "10 分钟"))
        intervalSp.setSelection(intervalValues.indexOf(prefs.getInt("intervalSec", 60)).coerceAtLeast(0))

        quizTimeSp.adapter = makeAdapter(listOf("30 秒", "60 秒", "2 分钟", "5 分钟", "10 分钟"))
        quizTimeSp.setSelection(quizTimeValues.indexOf(prefs.getInt("quizTimeSec", 60)).coerceAtLeast(0))

        penaltySp.adapter = makeAdapter(listOf("加 1 题", "加 2 题", "加 3 题"))
        penaltySp.setSelection(prefs.getInt("penalty", 2) - 1)

        dailySp.adapter = makeAdapter(listOf("5 分钟（演示）", "20 分钟", "30 分钟", "60 分钟"))
        dailySp.setSelection(dailyValues.indexOf(prefs.getInt("dailyMin", 30)).coerceAtLeast(0))

        val subs = prefs.getString("subjects", "math,chinese,english")!!
        chkMath.isChecked = subs.contains("math")
        chkChinese.isChecked = subs.contains("chinese")
        chkEnglish.isChecked = subs.contains("english")

        findViewById<Button>(R.id.startBtn).setOnClickListener { startGuard() }
        findViewById<Button>(R.id.stopBtn).setOnClickListener {
            QuizService.stop(this)
            updateStatus()
            Toast.makeText(this, "已停止守护", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.testBtn).setOnClickListener {
            saveConfig()
            QuizService.test(this)
            Toast.makeText(this, "测试弹窗已弹出，答对即可关闭", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun makeAdapter(data: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, data)

    /** 显示当前自动匹配到的学期与教材进度 */
    private fun updateAutoLabel() {
        val g = gradeSp.selectedItemPosition + 1
        val sem = QuestionBank.currentSemester()
        val stage = when {
            g <= 6 -> "小学"
            else -> "初中"
        }
        autoTv.text = "📚 已自动匹配：${stage}${g}年级${if (sem == 1) "上" else "下"}学期 · 按人教版知识点出题"
    }

    private fun saveConfig() {
        val subs = mutableListOf<String>()
        if (chkMath.isChecked) subs.add("math")
        if (chkChinese.isChecked) subs.add("chinese")
        if (chkEnglish.isChecked) subs.add("english")
        if (subs.isEmpty()) subs.add("math")

        prefs.edit()
            .putInt("grade", gradeSp.selectedItemPosition + 1)
            .putInt("intervalSec", intervalValues[intervalSp.selectedItemPosition])
            .putInt("quizTimeSec", quizTimeValues[quizTimeSp.selectedItemPosition])
            .putInt("penalty", penaltySp.selectedItemPosition + 1)
            .putInt("dailyMin", dailyValues[dailySp.selectedItemPosition])
            .putString("subjects", subs.joinToString(","))
            .putBoolean("voice", voiceChk.isChecked)
            .putString("pwd", if (pwdEt.text.isNullOrBlank()) "1234" else pwdEt.text.toString())
            .apply()
    }

    private fun startGuard() {
        // 1. 必须先授予「显示在其他应用上层」（悬浮窗）权限
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            Toast.makeText(this, "请先打开「显示在其他应用上层」开关，再回来点启动", Toast.LENGTH_LONG).show()
            return
        }
        // 2. 通知权限（安卓 13+），保证后台常驻通知可见
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        saveConfig()
        QuizService.start(this)
        updateStatus()
        Toast.makeText(this, "✅ 守护已启动，可以放心把手机交给孩子了", Toast.LENGTH_LONG).show()
    }

    private fun updateStatus() {
        val on = prefs.getBoolean("guardOn", false)
        statusTv.text = if (on) {
            val remain = prefs.getInt("remaining", 0)
            "v1.1 · 🟢 守护运行中 · 今日剩余 %d 分 %02d 秒".format(remain / 60, remain % 60)
        } else "v1.1 · ⚪ 未启动（悬浮窗模式）"

        statusTv.text = statusTv.text.toString() + "\n" + selfCheck()

        val total = prefs.getInt("total", 0)
        val ok = prefs.getInt("ok", 0)
        val no = prefs.getInt("no", 0)
        statsTv.text = if (total == 0) "累计答题 0 · 正确率 --"
        else "累计答题 $total · 答对 $ok · 答错 $no · 正确率 ${ok * 100 / total}%"
    }

    /** 权限自检：哪一项是 ❌，就去系统里把那一项打开 */
    private fun selfCheck(): String {
        val overlay = if (Settings.canDrawOverlays(this)) "✅" else "❌"
        val notif = if (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) "✅" else "❌"
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val battery = if (pm.isIgnoringBatteryOptimizations(packageName)) "✅" else "❌"
        val alive = if (QuizService.running) "✅" else "❌"
        return "自检 悬浮窗 $overlay 通知 $notif 省电豁免 $battery 服务 $alive"
    }
}
