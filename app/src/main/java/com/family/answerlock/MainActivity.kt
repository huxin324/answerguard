package com.family.answerlock

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 家长页（v2.3）：
 * 打开 App → 年级 / 弹题间隔 / 出题科目 / 语音 / 答题倒计时 / 加题 / 今日上限 / 密码
 * 全部直接可见可点（不再折叠隐藏），选好点一次「开始守护」即可。
 *
 * 权限（悬浮窗/通知/省电/闹钟）不需要家长挨个找按钮：
 * 点「开始守护」后自动逐个弹出系统授权框，每授权一项回来就自动继续下一项，全程只点一次。
 * 仅「诊断与修复」工具仍折叠，日常看不到。
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
    private lateinit var autoTv: TextView
    private lateinit var diagTv: TextView
    private lateinit var diagBox: View
    private lateinit var hintCard: View
    private lateinit var hintTv: TextView
    private lateinit var adminBtn: Button
    private lateinit var adminCard: View
    private lateinit var adminStateTv: TextView
    private lateinit var adminDescTv: TextView
    private lateinit var startBtn: Button
    private lateinit var startHintTv: TextView

    /** 上一次「防清理」是否已开启：只在状态变化时才重刷按钮配色，避免每秒重建 drawable */
    private var lastAdminOn: Boolean? = null

    /** 「开始守护」的一键链式启动标记：授权回来 onResume 自动接着跑 */
    private var pendingStart = false

    private val gradeNames = listOf(
        "一年级", "二年级", "三年级", "四年级", "五年级", "六年级", "七年级", "八年级", "九年级"
    )
    private val intervalValues = listOf(30, 60, 180, 300, 600)
    private val quizTimeValues = listOf(30, 60, 120, 300, 600)
    private val dailyValues = listOf(5, 20, 30, 60)

    private val uiHandler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            updateAll()
            uiHandler.postDelayed(this, 1000)
        }
    }

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
        pwdEt.setText(prefs.getString("pwd", "1234"))
        statusTv = findViewById(R.id.statusTv)
        autoTv = findViewById(R.id.autoTv)
        diagTv = findViewById(R.id.diagTv)
        diagBox = findViewById(R.id.diagBox)
        hintCard = findViewById(R.id.hintCard)
        hintTv = findViewById(R.id.hintTv)
        adminBtn = findViewById(R.id.adminBtn)
        adminCard = findViewById(R.id.adminCard)
        adminStateTv = findViewById(R.id.adminStateTv)
        adminDescTv = findViewById(R.id.adminDescTv)
        startBtn = findViewById(R.id.startBtn)
        startHintTv = findViewById(R.id.startHintTv)
        // 华为的「后台弹窗」是独立于「悬浮窗」的第二个开关，只有这里能直达
        hintCard.setOnClickListener { openAppInfo() }

        gradeSp.adapter = makeAdapter(gradeNames)
        gradeSp.setSelection(prefs.getInt("grade", 3) - 1)
        updateAutoLabel()
        gradeSp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                updateAutoLabel()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        intervalSp.adapter = makeAdapter(listOf("30 秒", "1 分钟", "3 分钟", "5 分钟", "10 分钟"))
        intervalSp.setSelection(intervalValues.indexOf(prefs.getInt("intervalSec", 60)).coerceAtLeast(0))

        quizTimeSp.adapter = makeAdapter(listOf("30 秒", "60 秒", "2 分钟", "5 分钟", "10 分钟"))
        quizTimeSp.setSelection(quizTimeValues.indexOf(prefs.getInt("quizTimeSec", 60)).coerceAtLeast(0))

        penaltySp.adapter = makeAdapter(listOf("加 1 题", "加 2 题", "加 3 题"))
        penaltySp.setSelection(prefs.getInt("penalty", 2) - 1)

        dailySp.adapter = makeAdapter(listOf("5 分钟", "20 分钟", "30 分钟", "60 分钟"))
        dailySp.setSelection(dailyValues.indexOf(prefs.getInt("dailyMin", 30)).coerceAtLeast(0))

        val subs = prefs.getString("subjects", "math,chinese,english")!!
        chkMath.isChecked = subs.contains("math")
        chkChinese.isChecked = subs.contains("chinese")
        chkEnglish.isChecked = subs.contains("english")

        // 「开始守护」：必须先完成第 ① 步「防止被一键清理」，否则按钮是灰色的，点了只弹提示
        startBtn.setOnClickListener {
            if (!isAdminActive()) {
                Toast.makeText(
                    this,
                    "⚠️ 请先点上面第 ① 步「开启防止一键清理」，按钮变蓝后才能开始守护",
                    Toast.LENGTH_LONG
                ).show()
                flashAdminCard()
                return@setOnClickListener
            }
            saveConfig()
            pendingStart = true
            runStartChain()
        }
        findViewById<Button>(R.id.stopBtn).setOnClickListener {
            pendingStart = false
            QuizService.stop(this)
            updateAll()
            Toast.makeText(this, "已停止守护", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.testBtn).setOnClickListener {
            saveConfig()
            QuizService.test(this)
            Toast.makeText(this, "测试弹窗已弹出，答对即可关闭（不影响计时）", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.fixBtn).setOnClickListener { fixNext() }
        findViewById<Button>(R.id.huaweiBtn).setOnClickListener { openHuaweiStartup() }
        adminBtn.setOnClickListener {
            if (isAdminActive()) deactivateAdmin() else activateAdmin()
        }
        findViewById<Button>(R.id.test10Btn).setOnClickListener {
            saveConfig()
            prefs.edit().putInt("intervalSec", 10).apply()
            pendingStart = true
            runStartChain()
            Toast.makeText(this, "10 秒后会自动弹题，请马上切到抖音等着", Toast.LENGTH_LONG).show()
        }
        findViewById<TextView>(R.id.diagToggle).setOnClickListener {
            diagBox.visibility = if (diagBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
    }

    override fun onResume() {
        super.onResume()
        // 守护开着但服务死了（被系统杀/冻结）→ 打开 App 时立刻自动拉起
        if (prefs.getBoolean("guardOn", false) && !QuizService.running) {
            QuizService.resume(this)
        }
        // 一键启动链：授权回来后自动继续下一项
        runStartChain()
        updateAll()
        uiHandler.post(refresher)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(refresher)
        // 设置项现在都是直接可见的，改完不用再找按钮：离开页面就记住
        try { saveConfig() } catch (e: Exception) { /* 控件未就绪时忽略 */ }
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                runStartChain()
            } else {
                pendingStart = false
                Toast.makeText(this, "没开通知权限也能运行，只是后台通知不显示", Toast.LENGTH_LONG).show()
                runStartChain()
            }
        }
    }

    private fun makeAdapter(data: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, data)

    private fun updateAutoLabel() {
        val g = gradeSp.selectedItemPosition + 1
        val sem = QuestionBank.currentSemester()
        val stage = if (g <= 6) "小学" else "初中"
        autoTv.text = "📚 已自动匹配：${stage}${g}年级${if (sem == 1) "上" else "下"}学期 · 人教版"
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

    /* ================= 一键启动链（家长只点一次） ================= */

    /**
     * 缺哪项权限就自动去要哪项；授权回来 onResume 会自动再进来接着跑。
     * 四项全齐后启动服务，2.5 秒后页面自动收起（从最近任务里移除，孩子划不到）。
     */
    private fun runStartChain() {
        if (!pendingStart) return

        // ① 悬浮窗：核心权限，没有它弹不了题
        if (!Settings.canDrawOverlays(this)) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
                Toast.makeText(this, "打开「显示在其他应用上层」，返回后自动继续", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                pendingStart = false
            }
            return
        }

        // ② 通知权限（安卓 13+）
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            return
        }

        // ③ 省电豁免：防止后台被冻结
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            var asked = false
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
                asked = true
            } catch (e: Exception) { /* 有的系统不给弹，跳过 */ }
            if (asked) {
                Toast.makeText(this, "弹出的对话框点「允许」，之后自动继续", Toast.LENGTH_LONG).show()
                return
            }
        }

        // ④ 精确闹钟（安卓 12+）
        if (Build.VERSION.SDK_INT >= 31) {
            val am = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
            if (!am.canScheduleExactAlarms()) {
                var asked = false
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:$packageName")
                        )
                    )
                    asked = true
                } catch (e: Exception) { /* 没这个页面就跳过 */ }
                if (asked) {
                    Toast.makeText(this, "打开「闹钟和提醒」开关，返回后自动继续", Toast.LENGTH_LONG).show()
                    return
                }
            }
        }

        // ⑤ 华为/荣耀专属：系统把「悬浮窗」拆成了两个开关，「后台弹窗」不开就弹不出来。
        //    这一步只在第一次出现，把家长带到能打开它的那个页面（应用信息）。
        if (isHuawei() && !prefs.getBoolean("bgPopupOk", false) &&
            !prefs.getBoolean("bgPopupAsked", false)
        ) {
            prefs.edit().putBoolean("bgPopupAsked", true).apply()
            openAppInfo()
            return
        }

        // 权限齐了 → 启动守护
        pendingStart = false
        QuizService.start(this)
        updateAll()
        Toast.makeText(
            this,
            "✅ 已启动！页面马上自动收起（防止孩子从上滑列表划掉），切到孩子的 App 等弹题即可",
            Toast.LENGTH_LONG
        ).show()
        uiHandler.postDelayed({ removeFromRecents() }, 2500)
    }

    /** 把自己从「最近任务」移除：孩子上滑看不到这张卡片，划不掉也清理不掉 */
    private fun removeFromRecents() {
        try {
            finishAndRemoveTask()
        } catch (e: Throwable) {
            finish()
        }
    }

    /* ================= 状态显示 ================= */

    private fun updateAll() {
        val on = prefs.getBoolean("guardOn", false)
        val remain = GuardCore.remaining(this)
        statusTv.text = if (on) {
            "🟢 守护运行中 · 今日剩余 %d 分 %02d 秒 · %d 秒后弹题"
                .format(remain / 60, remain % 60, GuardCore.nextInSec(this))
        } else {
            "⚪ 未启动 · 先开第 ① 步「防止被一键清理」，再点「开始守护」"
        }

        val total = prefs.getInt("total", 0)
        val ok = prefs.getInt("ok", 0)
        val escape = prefs.getInt("escapes", 0)
        val stats = if (total == 0) "答题统计：还没有答过题"
        else "答题统计：$total 题 · 答对 $ok · 正确率 ${ok * 100 / total}%"
        val escTxt = if (escape > 0) "\n被强行结束时追加惩罚：$escape 次" else ""
        diagTv.text = "v2.4\n${selfCheck()}\n${beatText()}\n$stats$escTxt\n" +
            "上次动作：${prefs.getString("diag", "还没启动过")}"
        updateHint()
        refreshAdminUi()
    }

    /**
     * 第 ① 步「防止被一键清理」与第 ② 步「开始守护」的联动配色：
     * 未开启防清理 → 开始守护是灰的（点了只提示）；开启后 → 变蓝，可以真正启动。
     */
    private fun refreshAdminUi() {
        val on = isAdminActive()
        if (lastAdminOn == on) return   // 状态没变就别每秒钟重建一次背景
        lastAdminOn = on

        if (on) {
            adminStateTv.text = "✅ 已开启"
            adminStateTv.setTextColor(Color.parseColor("#0B7A4B"))
            adminDescTv.text =
                "已开启：孩子上滑清理、一键清理都结束不了守护。以后要卸载本 App，先点下面的按钮取消。"
            adminBtn.text = "取消防止一键清理"
            adminBtn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E6F7EE"))
            adminBtn.setTextColor(Color.parseColor("#0B7A4B"))
        } else {
            adminStateTv.text = "未开启"
            adminStateTv.setTextColor(Color.parseColor("#C0392B"))
            adminDescTv.text = "开启后，孩子上滑清理、一键清理都无法结束守护。开启后才能启动守护。"
            adminBtn.text = "🛡️ 开启防止一键清理"
            adminBtn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EDEBFF"))
            adminBtn.setTextColor(Color.parseColor("#4B3BB3"))
        }

        startBtn.backgroundTintList =
            ColorStateList.valueOf(Color.parseColor(if (on) "#2F6BFF" else "#C9CEDA"))
        startBtn.setTextColor(Color.WHITE)
        startHintTv.visibility = if (on) View.GONE else View.VISIBLE
    }

    /** 灰按钮被点了：把第 ① 步卡片闪两下，家长一眼就知道该先点哪里 */
    private fun flashAdminCard() {
        val blink = android.animation.ObjectAnimator.ofFloat(
            adminCard, "alpha", 1f, 0.25f, 1f, 0.25f, 1f
        )
        blink.duration = 700L
        blink.start()
    }

    /** 顶部提示条：只处理「弹不出来」这一个硬问题（防清理已单独做成第 ① 步卡片） */
    private fun updateHint() {
        if (isHuawei()) {
            val popupOk = prefs.getBoolean("bgPopupOk", false)
            val blocked = prefs.getBoolean("bgPopupBlocked", false)
            if (!popupOk || blocked) {
                hintTv.text = if (blocked)
                    "⚠️ 刚才的弹题被系统拦下了：请在应用信息里把「后台弹窗」打开（悬浮窗也要开）"
                else
                    "⚠️ 华为手机请把「悬浮窗」和「后台弹窗」两个开关都打开，否则离开本页弹不出来"
                hintCard.tag = "popup"
                hintCard.visibility = View.VISIBLE
                return
            }
        }
        hintCard.visibility = View.GONE
    }

    /* ================= 设备管理器（防上滑清理） ================= */

    private fun adminComponent() = ComponentName(this, GuardAdminReceiver::class.java)

    private fun isAdminActive(): Boolean = try {
        (getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager)
            .isAdminActive(adminComponent())
    } catch (e: Exception) {
        false
    }

    private fun activateAdmin() {
        if (isAdminActive()) {
            Toast.makeText(this, "✅ 已开启：孩子无法强行停止或清理掉守护", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent())
            i.putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "开启后：孩子上滑清理、一键清理都无法结束答题守护（系统不再允许强行停止本应用）。\n\n" +
                    "「开始守护」按钮会从灰色变成蓝色，就可以启动了。\n\n" +
                    "以后要卸载本 App，请先回到首页第 ① 步点「取消防止一键清理」。"
            )
            startActivity(i)
            Toast.makeText(this, "请点「激活」——这是防止孩子把守护清掉的关键一步", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "这台手机不支持该设置，请改用系统「家长模式」限制", Toast.LENGTH_LONG).show()
        }
    }

    private fun deactivateAdmin() {
        try {
            (getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager)
                .removeActiveAdmin(adminComponent())
            Toast.makeText(this, "已取消防清理，现在可以正常卸载本 App", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "取消失败，请到 设置→安全→设备管理器 里手动取消", Toast.LENGTH_LONG).show()
        }
        updateAll()
    }

    private fun isHuawei(): Boolean {
        val m = (Build.MANUFACTURER + Build.BRAND).lowercase()
        return m.contains("huawei") || m.contains("honor")
    }

    /** 直达「应用信息」页——华为的「悬浮窗」「后台弹窗」两个开关都在这里 */
    private fun openAppInfo() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
            Toast.makeText(
                this, "把「悬浮窗」和「后台弹窗」两个开关都打开，然后返回本页", Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, "请手动到 设置→应用→答题守护→权限 里打开两个开关", Toast.LENGTH_LONG).show()
        }
    }

    private fun selfCheck(): String {
        val overlay = if (Settings.canDrawOverlays(this)) "✅" else "❌"
        val notif = if (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) "✅" else "❌"
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val battery = if (pm.isIgnoringBatteryOptimizations(packageName)) "✅" else "❌"
        val exact = if (Build.VERSION.SDK_INT < 31 ||
            (getSystemService(ALARM_SERVICE) as android.app.AlarmManager).canScheduleExactAlarms()
        ) "✅" else "❌"
        return "权限 悬浮窗 $overlay 通知 $notif 省电 $battery 闹钟 $exact"
    }

    private fun beatText(): String {
        val srv = if (QuizService.running) "✅" else "❌"
        val keep = if (QuizService.keepAlive) "✅" else "❌"
        val maxGap = prefs.getLong("maxGapSec", 0L)
        val gap = if (maxGap < 8) "后台连续运行 ✅"
        else "后台最长中断 ${maxGap} 秒 ⚠️（做「华为防冻结设置」）"
        return "服务 $srv 静音保活 $keep · $gap"
    }

    /* ================= 诊断工具（收进折叠区） ================= */

    private fun fixNext() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            Toast.makeText(this, "① 打开「显示在其他应用上层」", Toast.LENGTH_LONG).show()
            return
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
                Toast.makeText(this, "② 点「允许」", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "请到 设置→电池→应用耗电管理 选「不受限制」", Toast.LENGTH_LONG).show()
            }
            return
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val am = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
            if (!am.canScheduleExactAlarms()) {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:$packageName")
                        )
                    )
                    Toast.makeText(this, "③ 打开「闹钟和提醒」开关", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "③ 到 设置→应用→答题守护→闹钟和提醒 里允许", Toast.LENGTH_LONG).show()
                }
                return
            }
        }
        updateAll()
        Toast.makeText(this, "✅ 权限都齐了", Toast.LENGTH_SHORT).show()
    }

    private fun openHuaweiStartup() {
        AlertDialog.Builder(this)
            .setTitle("📱 华为防冻结（2 步）")
            .setMessage(
                "第 1 步：设置 → 电池 → 应用耗电管理 → 答题守护 → 选「允许后台活动 / 不受限制」\n\n" +
                    "第 2 步：设置 → 应用和服务 → 应用启动管理 → 答题守护 → 关掉「自动管理」，" +
                    "「自启动 / 关联启动 / 后台活动」三项全部打开\n\n" +
                    "做完后回到本页，点「⚡ 10 秒后弹题」验证。"
            )
            .setPositiveButton("去启动管理") { _, _ -> jumpHuaweiStartup() }
            .setNegativeButton("知道了", null)
            .show()
    }

    private fun jumpHuaweiStartup() {
        val candidates = listOf(
            android.content.ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ),
            android.content.ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
            ),
            android.content.ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity"
            )
        )
        for (cn in candidates) {
            try {
                startActivity(Intent().setComponent(cn))
                return
            } catch (e: Exception) { /* 试下一个入口 */ }
        }
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            Toast.makeText(this, "请手动到 设置→应用启动管理→答题守护 打开三项", Toast.LENGTH_LONG).show()
        }
    }
}
