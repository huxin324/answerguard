package com.family.answerlock

import android.Manifest
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
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 电视版家长首页（v1.2，基于手机版 v3.5 的守护内核重新设计）。
 *
 * 页面结构（横屏双栏）：左 = 年级/间隔 + 出题设置 + 大按钮；右 = 使用须知 + 状态 + 诊断。
 *
 * 主按钮两态（沿用手机版 v3.2 的一键启停）：
 *   蓝「▶ 开始守护」 ← 点一下就开始（缺悬浮窗权限会先带你去开，回来自动继续）
 *   绿「🟢 守护中 · 点此停止」 ← 点一下立刻停
 *
 * 和手机版的差异（电视版特意删掉的东西）：
 *   · 华为「应用启动管理 / 后台弹窗」引导 —— 电视没有这套管控；
 *   · 最近任务「锁卡片」引导 —— 电视不会杀后台，没有最近任务清理这回事；
 *   · 设备管理器开关 —— 电视大多没有这个页面，留着只会让家长困惑；
 *   · 加了「26 键软键盘」—— 电视不一定有输入法，家长密码用遥控器点键输入，
 *     改密码走独立弹层（平时只显示圆点，不占地方）。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var gradeSp: Spinner
    private lateinit var intervalSp: Spinner
    private lateinit var quizTimeSp: Spinner
    private lateinit var penaltySp: Spinner
    private lateinit var dailySp: Spinner
    private lateinit var pwdMaskTv: TextView
    private lateinit var pwdModal: View
    private lateinit var pwdOldEt: EditText
    private lateinit var pwdNewEt: EditText
    private lateinit var pwdNew2Et: EditText
    private lateinit var pwdErrTv: TextView
    private lateinit var chkMath: CheckBox
    private lateinit var chkChinese: CheckBox
    private lateinit var chkEnglish: CheckBox
    private lateinit var statusTv: TextView
    private lateinit var autoTv: TextView
    private lateinit var diagTv: TextView
    private lateinit var diagBox: View
    private lateinit var startBtn: Button
    private lateinit var startHintTv: TextView
    private lateinit var lockLayer: View
    private lateinit var unlockEt: EditText
    private lateinit var unlockBtn: Button
    private lateinit var lockErrTv: TextView
    private lateinit var padBoxL: LinearLayout

    /** 主按钮状态的缓存 key：只在状态真的变了才重刷配色 */
    private var lastStartKey: String? = null

    /** 本次进程内是否已用家长密码解锁设置页（守护运行时页面默认锁住） */
    private var unlocked = false

    /** 锁层上一次的显示状态（updateAll 每秒刷一次，键盘只在「刚出现」时挂，别每秒重建打断焦点） */
    private var lockShown = false

    /** 「开始守护」的一键链式授权标记：授权回来 onResume 自动接着跑 */
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
        pwdMaskTv = findViewById(R.id.pwdMaskTv)
        pwdModal = findViewById(R.id.pwdModal)
        pwdOldEt = findViewById(R.id.pwdOldEt)
        pwdNewEt = findViewById(R.id.pwdNewEt)
        pwdNew2Et = findViewById(R.id.pwdNew2Et)
        pwdErrTv = findViewById(R.id.pwdErrTv)
        chkMath = findViewById(R.id.chkMath)
        chkChinese = findViewById(R.id.chkChinese)
        chkEnglish = findViewById(R.id.chkEnglish)
        refreshPwdMask()
        statusTv = findViewById(R.id.statusTv)
        autoTv = findViewById(R.id.autoTv)
        diagTv = findViewById(R.id.diagTv)
        diagBox = findViewById(R.id.diagBox)
        startBtn = findViewById(R.id.startBtn)
        startHintTv = findViewById(R.id.startHintTv)
        lockLayer = findViewById(R.id.lockLayer)
        unlockEt = findViewById(R.id.unlockEt)
        unlockBtn = findViewById(R.id.unlockBtn)
        lockErrTv = findViewById(R.id.lockErrTv)
        padBoxL = findViewById(R.id.padBoxL)

        // 改密码走弹层 + 26 键软键盘（电视不一定有输入法，遥控器直接点）
        setupPwdModal()

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

        // 唯一主按钮：蓝＝开始 → 绿（守护中）→ 点一下停止 → 蓝
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

        findViewById<Button>(R.id.fixBtn).setOnClickListener { fixNext() }
        findViewById<Button>(R.id.test10Btn).setOnClickListener {
            saveConfig()
            prefs.edit().putInt("intervalSec", 10).apply()
            pendingStart = true
            runStartChain()
            Toast.makeText(this, "10 秒后会自动弹题，请马上切到孩子正在看的视频等着", Toast.LENGTH_LONG).show()
        }
        findViewById<TextView>(R.id.diagToggle).setOnClickListener {
            diagBox.visibility = if (diagBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
    }

    override fun onResume() {
        super.onResume()
        // 跨天先重置今日额度（否则昨天用完了，今天一打开就是「今日时长已用完」）
        GuardCore.rolloverIfNeeded(this)
        // 守护开着但服务死了 → 打开 App 时立刻自动拉起
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
     * 守护中断检测：进程被系统杀掉后自己回不来，但「被杀」要留账 ——
     * 下次打开 App 时把中断时长扣掉并追加惩罚题。
     * 阈值 3 分钟：短时间的系统抖动、息屏、授权跳转不会误判。
     * 跨天的中断一律不罚（隔夜不是逃课）；两小时以上视为关机，只挪基准不扣不罚。
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

    /** 深色主题的 Spinner 条目（收起态白字），展开列表仍用系统白底样式 */
    private fun makeAdapter(data: List<String>): ArrayAdapter<String> {
        val a = ArrayAdapter(this, R.layout.sp_item, data)
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        return a
    }

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
            .apply()
    }

    /* ================= 修改家长密码（26 键软键盘） ================= */

    /** 当前键盘要写入的输入框 */
    private var activePwdEt: EditText? = null

    /** 密码框只显示圆点，不给直接编辑：统一走弹层，孩子也没法顺手改 */
    private fun refreshPwdMask() {
        val p = prefs.getString("pwd", "1234") ?: "1234"
        if (p == "1234") {
            // 还是初始密码：直接写出来，家长不用去别处找
            pwdMaskTv.text = "初始密码 1234"
            pwdMaskTv.letterSpacing = 0.02f
        } else {
            pwdMaskTv.text = "●".repeat(p.length)
            pwdMaskTv.letterSpacing = 0.3f
        }
    }

    private fun setupPwdModal() {
        findViewById<Button>(R.id.pwdEditBtn).setOnClickListener { showPwdModal() }
        findViewById<Button>(R.id.pwdCancelBtn).setOnClickListener { hidePwdModal() }
        findViewById<Button>(R.id.pwdOkBtn).setOnClickListener { submitPwd() }

        val ets = listOf(pwdOldEt, pwdNewEt, pwdNew2Et)
        ets.forEach { et ->
            // 电视没有输入法：禁掉系统软键盘，只用屏幕 26 键
            et.setShowSoftInputOnFocus(false)
            et.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) activePwdEt = et
                ets.forEach { other -> other.alpha = if (other === et) 1f else 0.6f }
            }
        }
        QwertyPad.attach(findViewById(R.id.qwertyBox)) { activePwdEt }
    }

    private fun showPwdModal() {
        pwdOldEt.setText("")
        pwdNewEt.setText("")
        pwdNew2Et.setText("")
        pwdErrTv.visibility = View.GONE
        pwdModal.visibility = View.VISIBLE
        activePwdEt = pwdOldEt
        listOf(pwdOldEt, pwdNewEt, pwdNew2Et).forEach {
            it.alpha = if (it === pwdOldEt) 1f else 0.6f
        }
        pwdOldEt.requestFocus()
    }

    private fun hidePwdModal() {
        pwdModal.visibility = View.GONE
        activePwdEt = null
        startBtn.requestFocus()
    }

    private fun submitPwd() {
        val cur = prefs.getString("pwd", "1234") ?: "1234"
        val a = pwdOldEt.text.toString()
        val b = pwdNewEt.text.toString()
        val c = pwdNew2Et.text.toString()
        val err = when {
            a != cur -> "❌ 原始密码不正确"
            b.length < 4 -> "❌ 新密码至少 4 位"
            b != c -> "❌ 两次输入的新密码不一致"
            else -> null
        }
        if (err != null) {
            pwdErrTv.text = err
            pwdErrTv.visibility = View.VISIBLE
            return
        }
        prefs.edit().putString("pwd", b).apply()
        refreshPwdMask()
        hidePwdModal()
        Toast.makeText(this, "✅ 家长密码已修改", Toast.LENGTH_SHORT).show()
    }

    /* ================= 一键启动链（家长只点一次） ================= */

    /**
     * 缺哪项权限就自动去要哪项；授权回来 onResume 会自动再进来接着跑。
     * 电视上通常只有 ①（悬浮窗）会真的跳设置页，②③④ 在很多电视上没有对应
     * 系统页 / 系统默认放行，都包了 try-catch 静默跳过，不会卡住启动。
     */
    private fun runStartChain() {
        if (!pendingStart) return

        // ① 悬浮窗：核心权限，没有它弹不了题（电视设置里叫「显示在其他应用上层 / 悬浮窗」）
        if (!Settings.canDrawOverlays(this)) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
                Toast.makeText(this, "打开「显示在其他应用上层」，按返回键后自动继续", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                // 个别电视没有这个设置页：提示家长手动开，但不再卡流程
                Toast.makeText(this, "这台电视没找到权限页，请在 设置→应用→答题守护TV 里开启「悬浮窗」", Toast.LENGTH_LONG).show()
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

        // ③ 省电豁免：电视盒子上多数没有电池优化页，跳过失败不影响启动
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
                Toast.makeText(this, "弹出的对话框点「允许」，之后自动继续", Toast.LENGTH_LONG).show()
                return
            } catch (e: Exception) { /* 没这个页面就跳过 */ }
        }

        // ④ 精确闹钟（安卓 12+）
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
                    Toast.makeText(this, "打开「闹钟和提醒」开关，返回后自动继续", Toast.LENGTH_LONG).show()
                    return
                } catch (e: Exception) { /* 没这个页面就跳过 */ }
            }
        }

        // 权限齐了 → 启动守护
        pendingStart = false
        // 沿用手机版 v3.5 修复：必须先 bootstrap 再 start 服务，
        // 否则服务以为「守护早就在跑」跳过计时重置 → 一开启就立刻弹题。
        GuardCore.bootstrap(this)
        QuizService.start(this)
        lastStartKey = null
        updateAll()
        Toast.makeText(this, "✅ 已启动！按 Home 键退出本页，切到孩子看的视频等弹题即可", Toast.LENGTH_LONG).show()
        // 电视不用做任何防清理设置，1.5 秒后自动退到后台
        uiHandler.postDelayed({ leaveToBackground() }, 1500)
    }

    /** 退到后台但保留最近任务；电视桌面按 Home 就能回来 */
    private fun leaveToBackground() {
        unlocked = false
        refreshLockLayer()
        try {
            moveTaskToBack(true)
        } catch (e: Throwable) { /* 个别 ROM 不支持就留在前台，锁层已经挡住了 */ }
    }

    /** 守护运行时：设置页整体锁住，只有输对家长密码才放行 */
    private fun refreshLockLayer() {
        val guardOn = prefs.getBoolean("guardOn", false)
        val show = guardOn && !unlocked
        lockLayer.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            // 键盘只在「锁层刚出现」时挂一次（updateAll 每秒进来，别重复重建打断遥控器焦点）
            if (!lockShown) {
                QwertyPad.attach(padBoxL) { unlockEt }
                QwertyPad.focusFirst(padBoxL)
            }
            lockErrTv.visibility = View.GONE
        } else {
            lockErrTv.visibility = View.GONE
            unlockEt.setText("")
        }
        lockShown = show
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
        diagTv.text = "答题守护TV v1.2\n${selfCheck()}\n${beatText()}\n$stats$escTxt$emgTxt\n" +
            "上次动作：${prefs.getString("diag", "还没启动过")}"
        refreshStartBtn()
        refreshLockLayer()
    }

    /**
     * 唯一主按钮：点一下开始 / 守护中点一下停止。
     * 守护运行时整页被家长密码锁层盖住，孩子按遥控器也点不到按钮。
     */
    private fun refreshStartBtn() {
        val on = prefs.getBoolean("guardOn", false)
        val overlayOk = Settings.canDrawOverlays(this)
        val emg = GuardCore.emergencyLeftSec(this)

        val key = "$on|$overlayOk|${emg / 10}"
        if (lastStartKey == key) return
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
                startHintTv.text = "按 Home 键退出本页即可，弹题会自动盖在孩子看的视频上"
                startHintTv.visibility = View.VISIBLE
            }
            overlayOk -> {
                startBtn.text = "▶ 开始守护"
                startBtn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2F6BFF"))
                startHintTv.text = "点一下就开始，缺的权限会自动带你逐项打开"
                startHintTv.visibility = View.VISIBLE
            }
            else -> {
                startBtn.text = "▶ 开始守护"
                startBtn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2F6BFF"))
                startHintTv.text = "点这个按钮会带你打开「显示在其他应用上层」权限"
                startHintTv.visibility = View.VISIBLE
            }
        }
        startBtn.setTextColor(Color.WHITE)
    }

    /** 主按钮点击：点一下开始，再点一下就停 */
    private fun onMainButton() {
        if (prefs.getBoolean("guardOn", false)) {
            pendingStart = false
            QuizService.stop(this)
            lastStartKey = null
            updateAll()
            Toast.makeText(this, "已停止守护（孩子现在可以正常看了）", Toast.LENGTH_SHORT).show()
            return
        }

        // 未守护：没悬浮窗权限先去开，回来 onResume 自动接着启动
        if (!Settings.canDrawOverlays(this)) {
            pendingStart = true
            saveConfig()
            Toast.makeText(this, "先把「显示在其他应用上层」打开，按返回键会自动开始", Toast.LENGTH_LONG).show()
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                pendingStart = false
                Toast.makeText(this, "这台电视没找到权限页，请在 设置→应用→答题守护TV 里开启「悬浮窗」", Toast.LENGTH_LONG).show()
            }
            return
        }

        saveConfig()
        pendingStart = true
        runStartChain()
    }

    /* ================= 诊断 ================= */

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
        else "后台最长中断 ${maxGap} 秒 ⚠️（电视被系统清理过，试试重启电视后重新开始守护）"
        return "服务 $srv 静音保活 $keep 媒体播放 $media · $gap"
    }

    private fun fixNext() {
        if (!Settings.canDrawOverlays(this)) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
                Toast.makeText(this, "① 打开「显示在其他应用上层」", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "请到 设置→应用→答题守护TV→权限 里打开「悬浮窗」", Toast.LENGTH_LONG).show()
            }
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
                Toast.makeText(this, "这台电视没有电池优化页，跳过即可", Toast.LENGTH_LONG).show()
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
                    Toast.makeText(this, "这台电视没有闹钟权限页，跳过即可", Toast.LENGTH_LONG).show()
                }
                return
            }
        }
        updateAll()
        Toast.makeText(this, "✅ 权限都齐了", Toast.LENGTH_SHORT).show()
    }
}
