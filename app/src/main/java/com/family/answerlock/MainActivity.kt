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
 * 家长页（v3.2）：只留一个主按钮 + 一张使用须知。
 *
 * 页面顺序：年级/间隔 → 出题设置 → 【大按钮】→ 使用须知（红色，教锁后台卡片）→ 状态 → 诊断
 *
 * 主按钮三态（v3.2 起点一下就生效，不再要第二次确认）：
 *   灰/蓝「▶ 开始守护」 ← 点一下就开始（缺悬浮窗会先带你开权限，回来自动继续）
 *   绿「🟢 守护中 · 点此停止」 ← 点一下立刻停
 *   停止后回到蓝，状态行提示「未启动」。
 *   不用二次确认：守护中整页被家长密码锁层盖住，只有家长本人能点到这个按钮。
 *
 * 防清理的真实分工：
 *   · 防上划/一键清理 = 最近任务卡片「下拉上锁」（唯一实测有效；App 无 API 可代设，
 *     所以第一次启动后自动弹一次 4 步图文，点「我锁好了」永久记住）。
 *   · 防卸载/防设置里强行停止 = 设备管理器，降级成使用须知里的一行可选开关，不再挡启动。
 *   · 后台不被冻结 = 媒体播放态 + 隐形 1px 窗 + 省电豁免（启动链里自动要）。
 *
 * 启动链（悬浮窗 → 通知 → 省电 → 闹钟 → 华为启动管理 → 后台弹窗）全自动跳转，
 * 家长每授一项回来自动继续，不需要自己找设置项。
 *
 * 守护运行时用家长密码把设置页整体锁住：孩子从桌面图标点进来，只能看到
 * 「守护运行中 + 输密码」那一屏。
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
    private lateinit var adminLine: TextView
    private lateinit var noticeCard: View
    private lateinit var startBtn: Button
    private lateinit var startHintTv: TextView
    private lateinit var lockLayer: View
    private lateinit var unlockEt: EditText
    private lateinit var unlockBtn: Button
    private lateinit var lockErrTv: TextView

    /** 主按钮状态的缓存 key：只在状态真的变了才重刷配色，避免每秒重建 drawable */
    private var lastStartKey: String? = null

    /** 本次进程内是否已用家长密码解锁设置页（守护运行时页面默认锁住） */
    private var unlocked = false

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
        adminLine = findViewById(R.id.adminLine)
        noticeCard = findViewById(R.id.noticeCard)
        startBtn = findViewById(R.id.startBtn)
        startHintTv = findViewById(R.id.startHintTv)
        lockLayer = findViewById(R.id.lockLayer)
        unlockEt = findViewById(R.id.unlockEt)
        unlockBtn = findViewById(R.id.unlockBtn)
        lockErrTv = findViewById(R.id.lockErrTv)
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

        // 唯一主按钮（v3.0）：蓝＝开始 → 点一下变绿（守护中）→ 再点变红（确认停止）→ 点后变灰
        startBtn.setOnClickListener { onMainButton() }

        // 守护运行时设置页是锁着的，家长在这里输密码解锁
        unlockBtn.setOnClickListener {
            val pwd = prefs.getString("pwd", "1234") ?: "1234"
            if (unlockEt.text.toString() == pwd) {
                unlocked = true
                unlockEt.setText("")
                lockErrTv.visibility = View.GONE
                refreshLockLayer()
                Toast.makeText(this, "已解锁，可以修改设置了", Toast.LENGTH_SHORT).show()
            } else {
                lockErrTv.visibility = View.VISIBLE
                unlockEt.setText("")
            }
        }
        // 使用须知卡：点卡片重看锁卡片图文；点底部那行切换「防卸载保护」（设备管理器）
        noticeCard.setOnClickListener { showTaskLockGuide(null) }
        adminLine.setOnClickListener {
            if (isAdminActive()) deactivateAdmin() else activateAdmin()
        }
        findViewById<Button>(R.id.fixBtn).setOnClickListener { fixNext() }
        findViewById<Button>(R.id.huaweiBtn).setOnClickListener { openHuaweiStartup() }
        // 语音自检：直接读一句话出来，并把「用的是什么引擎 / 中文能不能用」显示出来
        findViewById<Button>(R.id.voiceTestBtn).setOnClickListener {
            val oq = OverlayQuiz.get(this)
            oq.testVoice()
            // 引擎探测是异步的（可能要逐个试好几个引擎），多等一会儿再报状态
            uiHandler.postDelayed({
                Toast.makeText(this, oq.voiceStatus(), Toast.LENGTH_LONG).show()
            }, 3000)
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
        // 跨天先重置今日额度（否则昨天用完了，今天一打开就是「今日时长已用完」）
        GuardCore.rolloverIfNeeded(this)
        // 守护开着但服务死了（被系统杀/冻结）→ 打开 App 时立刻自动拉起
        if (prefs.getBoolean("guardOn", false) && !QuizService.running) {
            QuizService.resume(this)
        }
        // 上次守护是被强行结束的？先把账算清（扣时间 + 追加题），再显示状态
        checkGuardGap()
        // 一键启动链：授权回来后自动继续下一项
        runStartChain()
        updateAll()
        refreshLockLayer()
        uiHandler.post(refresher)
    }

    /**
     * 守护中断检测：进程被 force-stop 后没有任何办法自己活过来，
     * 但"被杀"这件事本身要留账 —— 下次打开 App 时把中断时长扣掉并追加惩罚题。
     * 阈值 3 分钟：短时间的系统抖动、息屏、授权跳转都不会误判。
     *
     * v3.2 关键修复：**跨天的中断一律不罚**。
     * 原来昨天 22 点手机被杀、今天 8 点打开 App，中断时长按 10 小时算：
     * 今日 30 分钟额度被一次性扣光 → 孩子还没玩就显示「今日时长已用完」（OPPO 上同一个问题）。
     */
    private fun checkGuardGap() {
        if (!prefs.getBoolean("guardOn", false)) return
        val last = prefs.getLong("lastBeatWall", 0L)
        if (last <= 0L) return
        val now = System.currentTimeMillis()
        if (!GuardCore.sameDay(last, now)) return       // 隔夜不算逃课
        val gapMin = (now - last) / 60000L
        if (gapMin < 3L) return
        if (gapMin >= 120L) {
            // 两小时以上没有心跳：多半是手机关机 / 一整天没碰，不该算成孩子逃课。
            // 只把基准往前挪，不扣时长、不罚题（否则「今天刚打开就说时长用完」还会再来一次）。
            prefs.edit().putLong("lastBeatWall", now).putLong("lastAtWall", now).apply()
            lastStartKey = null
            return
        }
        GuardCore.onGapKilled(this, gapMin)
        lastStartKey = null
        Toast.makeText(
            this,
            "⚠️ 守护刚才被结束了 $gapMin 分钟：已扣除这段时间，并追加惩罚题",
            Toast.LENGTH_LONG
        ).show()
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

        // ⑤ 华为/荣耀：应用启动管理（自启动 / 关联启动 / 后台活动）。
        //    这一项不开，后台会被冻结 —— 到点根本不弹题，是所有「离开 App 就不弹」的头号原因。
        //    以前这步藏在「诊断与修复 → 华为防冻结设置」里，家长不知道要点，所以现在自动跳一次。
        if (isHuawei() && !prefs.getBoolean("hwStartupAsked", false)) {
            prefs.edit().putBoolean("hwStartupAsked", true).apply()
            if (jumpHuaweiStartup()) {
                Toast.makeText(
                    this,
                    "把「答题守护」的自动管理关掉，自启动 / 关联启动 / 后台活动 三项全开，然后返回本页",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        }

        // ⑥ 华为/荣耀：系统把「悬浮窗」拆成了两个开关，「后台弹窗」不开就弹不出来。
        //    这一步只在第一次出现，先弹图文说明，点「去打开」再跳到应用信息页。
        if (isHuawei() && !prefs.getBoolean("bgPopupOk", false) &&
            !prefs.getBoolean("bgPopupAsked", false)
        ) {
            prefs.edit().putBoolean("bgPopupAsked", true).apply()
            showHuaweiPopupGuide()
            return
        }

        // 权限齐了 → 启动守护
        pendingStart = false
        QuizService.start(this)
        // 服务是异步启动的，先落一个标记，让按钮「立刻」变绿（服务里 bootstrap 会再写一次）
        prefs.edit().putBoolean("guardOn", true).apply()
        lastStartKey = null
        updateAll()
        val firstStart = !prefs.getBoolean("taskLocked", false)
        Toast.makeText(
            this,
            if (firstStart) "✅ 已启动！马上教你花 10 秒锁住后台卡片（防上划，只需这一次）"
            else "✅ 已启动！切到孩子的 App 等弹题即可",
            Toast.LENGTH_LONG
        ).show()
        uiHandler.postDelayed({
            if (firstStart) {
                // 第一次：自动弹出上锁引导，确认后自动退到后台
                showTaskLockGuide { leaveToBackground() }
            } else {
                leaveToBackground()
            }
        }, 1800)
    }

    /**
     * 退到后台但**保留最近任务卡片**（v2.8 恢复，推翻 v2.7）。
     * 实测：华为最近任务里把卡片「下拉上锁」后，上划/一键清理都杀不掉它 ——
     * 锁标记虽然不能由 App 直接设置（华为桌面私有功能），但可以引导家长一次完成。
     * 孩子点开卡片也没用：设置页被家长密码锁层挡住，只能看到「守护运行中 + 输密码」。
     */
    private fun leaveToBackground() {
        unlocked = false
        refreshLockLayer()
        try {
            moveTaskToBack(true)
        } catch (e: Throwable) { /* 个别 ROM 不支持就留在前台，锁层已经挡住了 */ }
    }

    /**
     * 给最近任务卡片「下拉上锁」的图文引导。
     * 这个 🔒 是华为桌面（SystemUI）的私有功能，App 没有任何公开 API 可以替用户打上；
     * 无障碍代点也走不通（华为禁止侧载 App 获取无障碍权限）。
     * 所以把流程压缩到极限：只在第一次启动守护后自动弹一次，4 步图文，点「我锁好了」
     * 记入 prefs.taskLocked，之后永远不再打扰。
     */
    private fun showTaskLockGuide(onDone: (() -> Unit)?) {
        AlertDialog.Builder(this)
            .setTitle("🔒 锁住后台卡片（防上划，只需一次）")
            .setMessage(
                "开启守护后，请按下面 4 步给「答题守护」的卡片上锁：\n\n" +
                    "1️⃣ 从屏幕底部上滑并停顿，进入「最近任务」\n" +
                    "2️⃣ 找到「答题守护」的卡片\n" +
                    "3️⃣ 按住卡片往下拉（或长按卡片）\n" +
                    "4️⃣ 点出现的 🔒 锁图标，卡片右上角出现小锁 = 成功\n\n" +
                    "上锁之后：上划划不掉、一键清理清不掉（你已实测验证）。\n" +
                    "以后不用再做第二次，只有重新安装才需要重锁。"
            )
            .setPositiveButton("✅ 我锁好了") { _, _ ->
                prefs.edit().putBoolean("taskLocked", true).apply()
                lastStartKey = null
                updateAll()
                Toast.makeText(this, "已记住。守护从现在起划不掉也清不掉", Toast.LENGTH_LONG).show()
                onDone?.invoke()
            }
            .setNegativeButton("稍后再锁") { _, _ ->
                onDone?.invoke()
            }
            .setCancelable(false)
            .show()
    }

    /** 守护运行时：设置页整体锁住，只有输对家长密码才放行 */
    private fun refreshLockLayer() {
        val guardOn = prefs.getBoolean("guardOn", false)
        lockLayer.visibility = if (guardOn && !unlocked) View.VISIBLE else View.GONE
        if (lockLayer.visibility != View.VISIBLE) {
            lockErrTv.visibility = View.GONE
            unlockEt.setText("")
        }
    }

    /* ================= 状态显示 ================= */

    private fun updateAll() {
        val on = prefs.getBoolean("guardOn", false)
        val remain = GuardCore.remaining(this)
        val emg = GuardCore.emergencyLeftSec(this)
        statusTv.text = when {
            !on -> "⚪ 未启动 · 选好年级和间隔，点上面的「开始守护」即可"
            emg > 0 -> "🆘 紧急解锁中 · 还有 %d 分 %02d 秒恢复弹题 · 今日剩余 %d 分"
                .format(emg / 60, emg % 60, remain / 60)
            else -> "🟢 守护运行中 · 今日剩余 %d 分 %02d 秒 · %d 秒后弹题"
                .format(remain / 60, remain % 60, GuardCore.nextInSec(this))
        }

        val total = prefs.getInt("total", 0)
        val ok = prefs.getInt("ok", 0)
        val escape = prefs.getInt("escapes", 0)
        val emgCount = prefs.getInt("emgCount", 0)
        val stats = if (total == 0) "答题统计：还没有答过题"
        else "答题统计：$total 题 · 答对 $ok · 正确率 ${ok * 100 / total}%"
        val escTxt = if (escape > 0) "\n被强行结束时追加惩罚：$escape 次" else ""
        val emgTxt = if (emgCount > 0) "\n家长紧急解锁：$emgCount 次" else ""
        diagTv.text = "v3.4\n${selfCheck()}\n${beatText()}\n$stats$escTxt$emgTxt\n" +
            "语音：${OverlayQuiz.get(this).voiceStatus()}\n" +
            "上次动作：${prefs.getString("diag", "还没启动过")}"
        updateHint()
        refreshStartBtn()
        refreshLockLayer()
    }

    /**
     * 唯一主按钮（v3.2 简化为「点一下开始 / 点一下停止」）：
     *   灰＝还缺悬浮窗权限（点它会先带你去开）· 蓝＝就绪 · 绿＝守护中。
     *
     * 为什么可以去掉二次确认：守护运行时整页被家长密码锁层盖住，
     * 孩子根本点不到这个按钮；能点到的人一定已经输过家长密码。
     */
    private fun refreshStartBtn() {
        val on = prefs.getBoolean("guardOn", false)
        val overlayOk = Settings.canDrawOverlays(this)
        val remain = GuardCore.remaining(this)
        val emg = GuardCore.emergencyLeftSec(this)

        val key = "$on|$overlayOk|${remain / 60}|${emg / 10}"
        if (lastStartKey == key) return   // 状态没变就别每秒钟重建一次背景
        lastStartKey = key

        when {
            on && emg > 0 -> {
                startBtn.text = "🟢 守护中 · 点此停止"
                startBtn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#0FA968"))
                startHintTv.text = "🆘 紧急解锁中：还有 ${emg / 60} 分 ${emg % 60} 秒恢复弹题"
                startHintTv.visibility = View.VISIBLE
            }
            on -> {
                startBtn.text = "🟢 守护中 · 点此停止"
                startBtn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#0FA968"))
                startHintTv.visibility = View.GONE
            }
            overlayOk -> {
                startBtn.text = "▶ 开始守护"
                startBtn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2F6BFF"))
                startHintTv.text = "点一下就开始，缺的权限会自动带你逐项打开"
                startHintTv.visibility = View.VISIBLE
            }
            else -> {
                startBtn.text = "▶ 开始守护"
                startBtn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#C9CEDA"))
                startHintTv.text = "还差「显示在其他应用上层」权限：点这个按钮会带你打开"
                startHintTv.visibility = View.VISIBLE
            }
        }
        startBtn.setTextColor(Color.WHITE)

        // 使用须知底部那行：防卸载保护（设备管理器），可选，不挡启动
        val adminOn = isAdminActive()
        adminLine.text = if (adminOn)
            "🛡️ 防卸载保护：已开启 · 点此关闭（关闭后才能卸载本 App）"
        else
            "🛡️ 防卸载保护：未开启 · 点此开启（可选，挡住孩子在设置里强行停止和卸载）"
        adminLine.setTextColor(Color.parseColor(if (adminOn) "#0B7A4B" else "#B26A00"))
    }

    /** 主按钮点击：点一下开始，再点一下就停（v3.2 去掉二次确认） */
    private fun onMainButton() {
        if (prefs.getBoolean("guardOn", false)) {
            pendingStart = false
            QuizService.stop(this)
            lastStartKey = null
            updateAll()
            Toast.makeText(this, "已停止守护（孩子现在可以正常玩了）", Toast.LENGTH_SHORT).show()
            return
        }

        // 未守护：没悬浮窗权限先去开，回来 onResume 自动接着启动
        if (!Settings.canDrawOverlays(this)) {
            pendingStart = true
            saveConfig()
            Toast.makeText(this, "先把「显示在其他应用上层」打开，回来会自动开始", Toast.LENGTH_LONG).show()
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                pendingStart = false
            }
            return
        }

        saveConfig()
        pendingStart = true
        runStartChain()
    }

    /**
     * 顶部提示条：只在「真的被系统拦过」时才亮。
     * 以前只要没成功弹过一次（bgPopupOk=false）就常驻红条，家长会误以为必须去点它、
     * 每次都得往返「华为图解」那一趟 —— 现在改成按实际拦截结果提示。
     */
    private fun updateHint() {
        if (isHuawei() && prefs.getBoolean("bgPopupBlocked", false)) {
            hintTv.text = "⚠️ 刚才弹题被系统拦下了：点这里确认「悬浮窗」和「后台弹窗」都已打开"
            hintCard.visibility = View.VISIBLE
        } else {
            hintCard.visibility = View.GONE
        }
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
            Toast.makeText(this, "✅ 已开启：孩子不能在设置里强行停止，也不能直接卸载", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent())
            i.putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "开启后，孩子不能在「设置 → 应用 → 答题守护」里点「强行停止」，也不能直接卸载" +
                    "（要先取消这项激活）。\n\n" +
                    "这项是可选的，只影响防卸载，不影响正常守护。\n\n" +
                    "以后要卸载本 App，回到本页点这行「防卸载保护」取消即可。"
            )
            startActivity(i)
            Toast.makeText(this, "请点「激活」：这是防孩子自己卸载的唯一办法", Toast.LENGTH_LONG).show()
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
                this, "在「权限」里把「悬浮窗」和「后台弹窗」都打开，然后返回本页", Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, "请手动到 设置→应用→答题守护→权限 里打开两个开关", Toast.LENGTH_LONG).show()
        }
    }

    /** 华为「两个开关」的图文说明：只开悬浮窗 = 在 App 里能弹、切走就不弹 */
    private fun showHuaweiPopupGuide() {
        AlertDialog.Builder(this)
            .setTitle("📱 华为要开两个开关")
            .setMessage(
                "点「去打开」后，在「答题守护 → 权限」里把这两个都打开：\n\n" +
                    "① 悬浮窗（显示在其他应用上层）\n" +
                    "② 后台弹窗（华为单独拆出来的第二个开关）\n\n" +
                    "⚠️ 只开「悬浮窗」时：待在本 App 里能弹，一切到抖音就弹不出来——这是之前那个毛病的原因。\n\n" +
                    "顺带确认「应用启动管理」里本 App 允许后台活动，否则后台会被冻结。"
            )
            .setPositiveButton("去打开") { _, _ -> openAppInfo() }
            .setNegativeButton("知道了", null)
            .show()
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
        val media = if (QuizService.mediaReady) "✅" else "❌"
        val maxGap = prefs.getLong("maxGapSec", 0L)
        val gap = if (maxGap < 8) "后台连续运行 ✅"
        else "后台最长中断 ${maxGap} 秒 ⚠️（做「华为防冻结设置」）"
        return "服务 $srv 静音保活 $keep 媒体播放 $media · $gap"
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

    /** 跳到华为「应用启动管理」；返回 true 表示已经跳出去了（调用方应 return 等家长回来） */
    private fun jumpHuaweiStartup(): Boolean {
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
            ),
            // 鸿蒙 4 / HarmonyOS NEXT 的部分机型换了入口
            android.content.ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupAppListActivity"
            )
        )
        for (cn in candidates) {
            try {
                startActivity(Intent().setComponent(cn))
                return true
            } catch (e: Exception) { /* 试下一个入口 */ }
        }
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
            return true
        } catch (e: Exception) {
            Toast.makeText(this, "请手动到 设置→应用启动管理→答题守护 打开三项", Toast.LENGTH_LONG).show()
            return false
        }
    }
}
