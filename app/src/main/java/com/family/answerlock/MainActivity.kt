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
            Toast.makeText(this, "测试弹窗已弹出，答对即可关闭（测试不会开始计时）", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.fixBtn).setOnClickListener { fixNext() }
        findViewById<Button>(R.id.a11yBtn).setOnClickListener { openA11y() }
        findViewById<Button>(R.id.huaweiBtn).setOnClickListener { openHuaweiStartup() }
        findViewById<Button>(R.id.test10Btn).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先打开「显示在其他应用上层」权限", Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
                return@setOnClickListener
            }
            saveConfig()
            prefs.edit().putInt("intervalSec", 10).apply()
            QuizService.start(this)
            updateStatus()
            Toast.makeText(
                this, "已启动守护，10 秒后自动弹题——请立刻切到抖音等着", Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // 守护已开启但服务死了（被系统杀/冻结后闹钟还没到）→ 打开 App 时立刻自动拉起，
        // 同时把闹钟重新排上，保证回到后台后还能继续弹题
        if (prefs.getBoolean("guardOn", false) && !QuizService.running) {
            QuizService.resume(this)
        }
        updateStatus()
        uiHandler.post(refresher)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(refresher)
        super.onPause()
    }

    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /** 每秒刷新状态，让家长看到「下次弹题 X 秒」真的在倒数 */
    private val refresher = object : Runnable {
        override fun run() {
            updateStatus()
            uiHandler.postDelayed(this, 1000)
        }
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

        // 3. 无障碍只是加分项：华为/鸿蒙禁止「侧载安装」的 App 开启无障碍
        //    （会提示"应用暂不支持获取系统无障碍权限"），所以不再当必要条件。
        //    主保活手段已换成服务里的「静音媒体保活 + 每秒心跳」，不依赖无障碍。
        Toast.makeText(
            this,
            "✅ 守护已启动：静音媒体保活已开启，切到别的 App 也会准时弹题",
            Toast.LENGTH_LONG
        ).show()

        // 4. 省电豁免（第二重保险）
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
                Toast.makeText(this, "请点「允许」——不让系统冻结后台，离开 App 才能正常弹题", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "提示：请点「🔧 一键修复权限」把「省电豁免」补上", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 无障碍（华为对侧载 App 会拦截，仅作可选加分项） */
    private fun openA11y() {
        if (GuardAccessibilityService.isEnabled(this)) {
            Toast.makeText(this, "✅ 无障碍已开启，会额外多一层保障", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(
            this,
            "华为不允许侧载应用开无障碍（会提示「暂不支持」）。跳过没关系——已用静音保活替代，直接点「启动守护」即可。",
            Toast.LENGTH_LONG
        ).show()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("关于「无障碍」")
            .setMessage(
                "华为/鸿蒙出于隐私策略，禁止「非华为应用市场安装」的 App 开启无障碍服务，" +
                    "所以系统会提示「应用暂不支持获取系统无障碍权限」，这不是 App 的问题，也不是你没点对。\n\n" +
                    "v1.7 已经不再依赖无障碍：改用「静音媒体保活 + 每秒心跳 + 系统闹钟」三重机制，" +
                    "让守护进程像音乐 App 一样常驻，不会被系统冻结。\n\n" +
                    "所以这一步可以跳过，直接点「▶️ 启动守护」。"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    /**
     * 直达华为「应用启动管理」页面：把自启动/关联启动/后台活动三项打开，
     * 这是华为手机防止 App 后台被冻结、闹钟被压住的官方开关。
     */
    private fun openHuaweiStartup() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("📱 华为防冻结：这 3 步一定要做")
            .setMessage(
                "第 1 步：设置 → 电池 → 应用耗电管理（或「更多电池设置」）→ 答题守护 → " +
                    "选「允许后台活动 / 不受限制」\n\n" +
                    "第 2 步：设置 → 应用和服务 → 应用启动管理 → 答题守护 → " +
                    "关掉「自动管理」，弹出的「自启动 / 关联启动 / 后台活动」三项全部打开\n\n" +
                    "第 3 步：打开最近任务（从屏幕底部上滑并停住），找到「答题守护」卡片，" +
                    "按住往下拉，出现小锁 🔒 就说明锁定了，系统清理不掉它\n\n" +
                    "做完这 3 步，再点「▶️ 启动守护」，然后切到抖音等它弹题。"
            )
            .setPositiveButton("去启动管理") { _, _ -> jumpHuaweiStartup() }
            .setNeutralButton("去应用信息页") { _, _ -> jumpAppDetail() }
            .setNegativeButton("知道了", null)
            .show()
    }

    /** 尝试直达华为「应用启动管理」页面 */
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
                Toast.makeText(
                    this,
                    "找到「答题守护」→ 关掉「自动管理」→ 三项全开",
                    Toast.LENGTH_LONG
                ).show()
                return
            } catch (e: Exception) { /* 试下一个入口 */ }
        }
        jumpAppDetail()
    }

    private fun jumpAppDetail() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
            Toast.makeText(
                this, "在应用信息里找「耗电管理」，选「允许后台活动」", Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, "请手动到 设置→应用→应用启动管理→答题守护 打开三项", Toast.LENGTH_LONG).show()
        }
    }

    /** 一键修复：每次点修第一个还缺的权限，修完一项再点一次 */
    private fun fixNext() {
        // ① 悬浮窗（全局弹窗的核心）
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            Toast.makeText(this, "① 打开「允许显示在其他应用上层」，返回后再点一次", Toast.LENGTH_LONG).show()
            return
        }
        // ② 通知（前台服务常驻通知）
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            try {
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                )
                Toast.makeText(this, "② 把「通知」开关打开，返回后再点一次", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            return
        }
        // ③ 省电豁免（防止后台服务被杀）
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
                Toast.makeText(this, "③ 点「允许」别限制后台，返回后再点一次", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    Toast.makeText(this, "③ 找到「答题守护」→ 选「不受限制」", Toast.LENGTH_LONG).show()
                } catch (e2: Exception) {
                    Toast.makeText(this, "请手动到 设置→电池→应用耗电管理 选「不受限制」", Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        // ④ 无障碍：华为禁止侧载应用开启，仅当已经开了才提示，不作为必需项
        if (GuardAccessibilityService.isEnabled(this)) {
            Toast.makeText(this, "④ 无障碍已开（额外保障）", Toast.LENGTH_SHORT).show()
        }
        updateStatus()
        Toast.makeText(
            this,
            "✅ 权限齐了：点「▶️ 启动守护」，再按「📱 华为防冻结」把 3 步做完，然后切到抖音等弹题",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun updateStatus() {
        val on = prefs.getBoolean("guardOn", false)
        val remain = GuardCore.remaining(this)
        statusTv.text = if (on) {
            "v1.8 · 🟢 守护运行中 · 今日剩余 %d 分 %02d 秒".format(remain / 60, remain % 60)
        } else "v1.8 · ⚪ 未启动（点上面蓝色按钮才开始计时）"

        // 倒计时按「时间戳账本」算，进程被冻结也不会错
        val diagTxt = prefs.getString("diag", "还没弹过") ?: "还没弹过"
        val cd = if (on) "距离下次弹题 ${GuardCore.nextInSec(this)} 秒（${GuardCore.modeText(this)}）" else "未计时"
        statusTv.text = statusTv.text.toString() + "\n" + selfCheck() + "\n" + beatText() + "\n" + cd + "\n上次：" + diagTxt

        val total = prefs.getInt("total", 0)
        val ok = prefs.getInt("ok", 0)
        val no = prefs.getInt("no", 0)
        statsTv.text = if (total == 0) "累计答题 0 · 正确率 --"
        else "累计答题 $total · 答对 $ok · 答错 $no · 正确率 ${ok * 100 / total}%"
    }

    /** 保活自检：告诉家长「进程在后台有没有被冻结过」 */
    private fun beatText(): String {
        val srv = if (QuizService.running) "✅" else "❌"
        val keep = if (QuizService.keepAlive) "✅" else "❌"
        val maxGap = prefs.getLong("maxGapSec", 0L)
        val gap = if (maxGap < 8) "后台连续运行 ✅"
        else "后台最长中断 ${maxGap} 秒 ⚠️（做「华为防冻结」3 步）"
        return "服务 $srv 静音保活 $keep · $gap"
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
        return "自检 弹窗 $overlay 通知 $notif 省电 $battery"
    }
}
