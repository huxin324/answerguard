package com.family.answerlock

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 后台守护服务（悬浮窗方案）：
 * - 前台服务常驻计时，答题界面用 WindowManager 悬浮窗直接绘制
 *   → 不需要「后台启动 Activity」权限（华为/鸿蒙对此有严格限制），只要有悬浮窗权限就能弹
 *   → 悬浮窗不属于任何任务列表，上划滑不掉，按返回键也没用
 * - 答对继续玩；答错/超时 → 追加题目 + 语音错题讲解，讲解播完才进下一题
 * - 今日时长用完 → 锁屏悬浮窗，家长密码解锁
 */
class QuizService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences

    /* ---------------- 悬浮窗与控件 ---------------- */

    private var overlayRoot: FrameLayout? = null
    private var overlayMode: String? = null      // "quiz" / "lock"
    private lateinit var wm: WindowManager

    private var quizBox: View? = null
    private var lockBox: View? = null
    private var subjectTv: TextView? = null
    private var penaltyTv: TextView? = null
    private var timerTv: TextView? = null
    private var questionTv: TextView? = null
    private var optionsBox: LinearLayout? = null
    private var feedbackTv: TextView? = null
    private var explainTv: TextView? = null
    private var replayBtn: Button? = null
    private var pwdEt: EditText? = null

    /* ---------------- 音效与语音 ---------------- */

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var soundPool: SoundPool? = null
    private var sndOk = 0
    private var sndNo = 0

    /* ---------------- 答题状态 ---------------- */

    private var pending = 0
    private var cur: QuestionBank.Q? = null
    private var answerTimer: CountDownTimer? = null
    private var lastExplain = ""
    private val nextRunnable = Runnable { nextQuestion() }
    private var speakRunnable: Runnable? = null
    private val UTTER_EXPLAIN = "explain"
    private val WRONG_SOUND_MS = 500L

    /* ---------------- 计时（系统闹钟驱动） ----------------
     * 关键设计：不靠进程内 Handler 每秒数秒——华为会把后台进程冻结，Handler 一停计时就中断。
     * 改用 AlarmManager 精确闹钟：即使进程被冻结/杀掉，系统也会准时唤醒服务弹题。
     * remaining（今日剩余秒数）用「时间戳差值」结算（时间戳持久化，进程死了也不丢），
     * 闹钟隔多久响都不影响准确性。
     */

    /* ================= 生命周期 ================= */

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("guard", MODE_PRIVATE)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        running = true

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "守护运行中", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("答题守护运行中")
            .setContentText("到时间会自动弹题，请勿在设置里关闭本应用")
            .setOngoing(true)
            .build()
        startForeground(1, n)

        initSound()
        initTts()   // 启动守护时就初始化语音引擎，等弹题时早已就绪
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            // 家长点「测试弹题效果」：只弹窗，不动计时
            ACTION_TEST -> showOverlay("quiz")
            // 系统闹钟到点：结算时间、弹题或锁屏
            ACTION_TICK -> handleTick()
            // 正式启动守护：家长首次启动 → 重置为全新的一天；进程被杀后的自动恢复 → 继续计时
            else -> {
                val now = SystemClock.elapsedRealtime()
                if (!prefs.getBoolean("guardOn", false)) {
                    // 全新启动
                    prefs.edit()
                        .putInt("remaining", prefs.getInt("dailyMin", 30) * 60)
                        .putBoolean("locked", false)
                        .putInt("pending", 0)
                        .putLong("lastTickAt", now)
                        .putBoolean("guardOn", true)
                        .apply()
                    scheduleNextQuiz(prefs.getInt("intervalSec", 60))
                    diag("守护已启动")
                } else {
                    // 服务被系统杀掉后自动恢复：把「死掉期间」的时间照常扣掉，防止孩子强停 App 绕过
                    val last = prefs.getLong("lastTickAt", now)
                    val gone = ((now - last) / 1000L).toInt().coerceAtLeast(0)
                    val remaining = (prefs.getInt("remaining", 0) - gone).coerceAtLeast(0)
                    prefs.edit()
                        .putInt("remaining", remaining)
                        .putLong("lastTickAt", now)
                        .putBoolean("guardOn", true)
                        .apply()
                    val interval = prefs.getInt("intervalSec", 60)
                    scheduleNextQuiz(minOf(interval, remaining).coerceAtLeast(5))
                    diag("守护自动恢复（已扣 $gone 秒）")
                }
            }
        }
        return START_STICKY
    }

    /** 闹钟到点：结算这一段玩了多少秒 → 时间用完锁屏，否则弹题 */
    private fun handleTick() {
        if (!prefs.getBoolean("guardOn", false)) return

        // 正在答题/锁屏：时间暂停（作答不白扣玩耍时间），过会儿再检查
        if (overlayRoot != null) {
            scheduleNextQuiz(15)
            return
        }

        val now = SystemClock.elapsedRealtime()
        val last = prefs.getLong("lastTickAt", now)
        val elapsed = ((now - last) / 1000L).toInt().coerceAtLeast(0)
        prefs.edit().putLong("lastTickAt", now).apply()

        val remaining = prefs.getInt("remaining", 0) - elapsed
        if (remaining <= 0) {
            // 今日时间用完 → 锁屏
            prefs.edit().putInt("remaining", 0).putBoolean("locked", true).apply()
            showOverlay("lock")
        } else {
            prefs.edit().putInt("remaining", remaining).apply()
            showOverlay("quiz")
        }
        // 弹窗失败（如悬浮窗权限被关）→ 30 秒后自动重试，守护不会悄悄死掉
        if (overlayRoot == null) scheduleNextQuiz(30)
    }

    /**
     * 用系统精确闹钟排下一次弹题。
     * 就算 App 进程此刻已被华为冻结甚至杀掉，闹钟到点系统也会唤醒服务——这就是
     * 「离开 App 计时不再中断」的核心。
     */
    private fun scheduleNextQuiz(delaySec: Int) {
        val at = SystemClock.elapsedRealtime() + delaySec * 1000L
        prefs.edit().putLong("nextQuizAt", at).apply()
        val intent = Intent(this, QuizService::class.java).setAction(ACTION_TICK)
        val pi = if (Build.VERSION.SDK_INT >= 26)
            PendingIntent.getForegroundService(
                this, 2001, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        else
            PendingIntent.getService(
                this, 2001, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        val am = getSystemService(ALARM_SERVICE) as AlarmManager
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        } catch (e: Exception) {
            // 部分系统对精确闹钟有权限限制，退化为允许休眠的非精确闹钟（仍然能唤醒）
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        }
    }

    private fun cancelQuizAlarm() {
        val intent = Intent(this, QuizService::class.java).setAction(ACTION_TICK)
        val pi = if (Build.VERSION.SDK_INT >= 26)
            PendingIntent.getForegroundService(
                this, 2001, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        else
            PendingIntent.getService(
                this, 2001, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        (getSystemService(ALARM_SERVICE) as AlarmManager).cancel(pi)
    }

    /** 答题窗关闭（答对/解锁）后：重新开始计时并排下一次弹题 */
    private fun resumeAfterQuiz() {
        if (!prefs.getBoolean("guardOn", false)) return
        prefs.edit().putLong("lastTickAt", SystemClock.elapsedRealtime()).apply()
        val interval = prefs.getInt("intervalSec", 60)
        val remaining = prefs.getInt("remaining", 0)
        // 剩余时间不足一个间隔时，到点直接锁屏
        scheduleNextQuiz(minOf(interval, remaining).coerceAtLeast(5))
    }

    /** 孩子上划划掉 App 后，自动拉起服务（配合系统的「允许自启动」设置） */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (prefs.getBoolean("guardOn", false)) {
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(this, RestartReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            am.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 1500, pi
            )
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        val wasGuarding = prefs.getBoolean("guardOn", false)
        answerTimer?.cancel()
        ui.removeCallbacks(nextRunnable)
        speakRunnable?.let { ui.removeCallbacks(it) }
        closeOverlay()
        running = false
        soundPool?.release()
        tts?.stop()
        tts?.shutdown()
        // 只有家长主动「停止守护」才清除 guardOn；
        // 如果是系统杀进程（wasGuarding=true），保持 guardOn，
        // 之前排下的系统闹钟到点会重新唤醒服务，守护自动恢复
        if (!wasGuarding) prefs.edit().putBoolean("guardOn", false).apply()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private val ui = handler

    /* ================= 悬浮窗 ================= */

    private fun showOverlay(mode: String) {
        if (!Settings.canDrawOverlays(this)) {
            diag("失败：没有悬浮窗权限")
            return
        }
        // 已经开着就不再叠加
        if (overlayRoot != null) {
            diag("窗口已存在，跳过")
            return
        }

        val root = LayoutInflater.from(this)
            .inflate(R.layout.activity_quiz, null) as FrameLayout
        overlayRoot = root
        overlayMode = mode

        quizBox = root.findViewById(R.id.quizBox)
        lockBox = root.findViewById(R.id.lockBox)
        subjectTv = root.findViewById(R.id.subjectTv)
        penaltyTv = root.findViewById(R.id.penaltyTv)
        timerTv = root.findViewById(R.id.timerTv)
        questionTv = root.findViewById(R.id.questionTv)
        optionsBox = root.findViewById(R.id.optionsBox)
        feedbackTv = root.findViewById(R.id.feedbackTv)
        explainTv = root.findViewById(R.id.explainTv)
        replayBtn = root.findViewById(R.id.replayBtn)
        pwdEt = root.findViewById(R.id.lockPwd)

        // 吃掉返回键：孩子按返回/上划都无法关掉答题窗
        root.isFocusable = true
        root.isFocusableInTouchMode = true
        root.setOnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_BACK }

        replayBtn?.setOnClickListener {
            speak(lastExplain)
            // 重听时刷新兜底计时，保证听完讲解才跳下一题
            if (prefs.getBoolean("voice", true) && ttsReady)
                scheduleNext((lastExplain.length * 300L + 3000L).coerceAtMost(60_000L))
        }

        try {
            wm.addView(root, WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.CENTER })
        } catch (e: Exception) {
            overlayRoot = null
            diag("失败：${e.javaClass.simpleName} ${e.message}")
            return
        }

        diag("已弹出（$mode）")
        if (mode == "lock") showLock() else nextQuestion()
    }

    /** 记录最近一次弹窗结果，家长页可见，方便定位「为什么不弹」 */
    private fun diag(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())
        prefs.edit().putString("diag", "$t $msg").apply()
        // 同步写到通知栏：孩子玩别的 App 时下拉通知栏也能看到弹没弹
        updateNotification("$t $msg")
    }

    private fun updateNotification(txt: String) {
        try {
            val n = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle("答题守护运行中")
                .setContentText(txt)
                .setOngoing(true)
                .build()
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(1, n)
        } catch (e: Exception) { /* 通知失败不影响弹窗 */ }
    }

    private fun closeOverlay() {
        answerTimer?.cancel()
        ui.removeCallbacks(nextRunnable)
        speakRunnable?.let { ui.removeCallbacks(it) }
        overlayRoot?.let {
            try { wm.removeView(it) } catch (e: Exception) { /* 已移除 */ }
        }
        val wasShowing = overlayRoot != null
        overlayRoot = null
        overlayMode = null
        // 答题/锁屏窗关闭 → 恢复计时并排下一次闹钟（即使此刻服务正被系统销毁，
        // 闹钟也已交给系统保管，到点会重新唤醒服务，守护不会因为进程被杀而中断）
        if (wasShowing) resumeAfterQuiz()
    }

    /* ================= 答题逻辑 ================= */

    private fun nextQuestion() {
        val root = overlayRoot ?: return
        pending = prefs.getInt("pending", 0)
        val grade = prefs.getInt("grade", 3)
        val subjects = prefs.getString("subjects", "math")!!
            .split(",").filter { it.isNotBlank() }

        cur = QuestionBank.make(grade, subjects)
        val q = cur!!

        subjectTv?.text = "📘 ${q.subject} · 答对才能继续玩"
        if (pending > 0) {
            penaltyTv?.visibility = View.VISIBLE
            penaltyTv?.text = "⚠️ 答错加题 · 还需连答 $pending 题"
        } else {
            penaltyTv?.visibility = View.GONE
        }
        questionTv?.text = q.text
        feedbackTv?.text = ""
        explainTv?.visibility = View.GONE
        replayBtn?.visibility = View.GONE
        optionsBox?.removeAllViews()

        q.options.forEach { opt ->
            val b = Button(this)
            b.text = opt
            b.textSize = 16f
            b.setAllCaps(false)
            b.setPadding(0, 28, 0, 28)
            b.setOnClickListener { answer(opt, b) }
            optionsBox?.addView(b)
        }

        answerTimer?.cancel()
        val total = prefs.getInt("quizTimeSec", 60) * 1000L
        answerTimer = object : CountDownTimer(total, 500) {
            override fun onTick(ms: Long) {
                val s = ms / 1000 + 1
                val txt = if (s >= 60) "${s / 60} 分 ${s % 60} 秒" else "$s 秒"
                timerTv?.text = "⏳ 剩余作答时间：$txt（超时算答错）"
            }
            override fun onFinish() {
                timerTv?.text = "⏳ 剩余作答时间：0 秒"
                timeUp()
            }
        }.start()
    }

    private fun answer(opt: String, btn: Button) {
        val q = cur ?: return
        answerTimer?.cancel()
        val box = optionsBox ?: return
        for (i in 0 until box.childCount) box.getChildAt(i).isEnabled = false

        if (opt == q.answer) {
            playTone(true)
            btn.setBackgroundColor(0xFF18A058.toInt())
            btn.setTextColor(0xFFFFFFFF.toInt())
            addStat("ok")
            if (pending > 0) {
                pending--
                prefs.edit().putInt("pending", pending).apply()
                feedbackTv?.text = if (pending > 0)
                    "✅ 答对了！但因之前答错，还需再答 $pending 题"
                else
                    "✅ 答对了！加题已还清，继续玩吧～"
                ui.postDelayed({ nextQuestion() }, 1600)
            } else {
                feedbackTv?.text = "✅ 答对了！可以继续玩～"
                ui.postDelayed({ closeOverlay() }, 1000)
            }
        } else {
            playTone(false)
            btn.setBackgroundColor(0xFFE5484D.toInt())
            btn.setTextColor(0xFFFFFFFF.toInt())
            val penalty = prefs.getInt("penalty", 2)
            pending += penalty
            prefs.edit().putInt("pending", pending).apply()
            addStat("no")
            // 先播答错音效，等它响完再开始语音讲解（wrong.wav 约 0.5 秒）
            showExplain("❌ 答错了！正确答案：${q.answer}\n⚠️ 惩罚：追加 $penalty 题", q.explain, WRONG_SOUND_MS)
        }
    }

    private fun timeUp() {
        val q = cur ?: return
        playTone(false)
        val penalty = prefs.getInt("penalty", 2)
        pending += penalty
        prefs.edit().putInt("pending", pending).apply()
        addStat("no")
        showExplain("⏰ 超时未作答，视为答错！\n正确答案：${q.answer}\n⚠️ 惩罚：追加 $penalty 题", q.explain, WRONG_SOUND_MS)
    }

    /** 展示错题讲解 + 语音朗读（可关），朗读结束后才进入下一题 */
    private fun showExplain(feedback: String, explain: String, speakDelayMs: Long = 0L) {
        feedbackTv?.text = feedback
        lastExplain = explain
        explainTv?.text = "📖 错题讲解：$explain"
        explainTv?.visibility = View.VISIBLE
        replayBtn?.visibility = View.VISIBLE
        speakRunnable?.let { ui.removeCallbacks(it) }
        if (prefs.getBoolean("voice", true) && ttsReady) {
            // 延迟启动朗读，避开答错音效
            speakRunnable = Runnable { speak(explain) }
            ui.postDelayed(speakRunnable!!, speakDelayMs)
            // 兜底：按「音效延迟 + 朗读预估时长 + 3 秒」保底跳题（上限 60 秒）
            val fallback = speakDelayMs + (explain.length * 300L + 3000L).coerceAtMost(60_000L)
            scheduleNext(fallback)
        } else {
            // 语音未就绪/已关闭 → 固定 2.6 秒快速跳题，绝不卡住
            ui.postDelayed(nextRunnable, 2600)
        }
    }

    /** 语音朗读完成 → 稍作停顿进入下一题 */
    private fun onSpeakFinished() {
        ui.removeCallbacks(nextRunnable)
        ui.postDelayed(nextRunnable, 600)
    }

    private fun scheduleNext(fallbackMs: Long) {
        ui.removeCallbacks(nextRunnable)
        ui.postDelayed(nextRunnable, fallbackMs)
    }

    private fun speak(text: String) {
        if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), UTTER_EXPLAIN)
        }
    }

    /** 答题音效：答对 = 金币声「叮铃~」，答错/超时 = 下坠嗡鸣「哐~」 */
    private fun playTone(ok: Boolean) {
        val id = if (ok) sndOk else sndNo
        if (id != 0) soundPool?.play(id, 1f, 1f, 1, 0, 1f)
    }

    private fun addStat(key: String) {
        prefs.edit()
            .putInt("total", prefs.getInt("total", 0) + 1)
            .putInt(key, prefs.getInt(key, 0) + 1)
            .apply()
    }

    /* ================= 锁屏 ================= */

    private fun showLock() {
        val root = overlayRoot ?: return
        quizBox?.visibility = View.GONE
        lockBox?.visibility = View.VISIBLE
        val et = pwdEt ?: return
        root.findViewById<Button>(R.id.unlockBtn)?.setOnClickListener {
            if (et.text.toString() == prefs.getString("pwd", "1234")) {
                prefs.edit()
                    .putBoolean("locked", false)
                    .putInt("remaining", prefs.getInt("dailyMin", 30) * 60)
                    .putInt("pending", 0)
                    .apply()
                Toast.makeText(this, "✅ 已解锁，重新开始计时", Toast.LENGTH_SHORT).show()
                closeOverlay()
            } else {
                Toast.makeText(this, "❌ 密码错误", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /* ================= 引擎初始化 ================= */

    private fun initSound() {
        soundPool = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        sndOk = soundPool?.load(this, R.raw.correct, 1) ?: 0
        sndNo = soundPool?.load(this, R.raw.wrong, 1) ?: 0
    }

    private fun initTts() {
        tts = TextToSpeech(applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    // 有的引擎只认 Locale.CHINA，再试一次
                    val r2 = tts?.setLanguage(Locale.CHINA)
                    ttsReady = r2 != TextToSpeech.LANG_MISSING_DATA && r2 != TextToSpeech.LANG_NOT_SUPPORTED
                } else {
                    ttsReady = true
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) {
                        if (id == UTTER_EXPLAIN) ui.post { onSpeakFinished() }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) {
                        if (id == UTTER_EXPLAIN) ui.post { onSpeakFinished() }
                    }
                })
            } else {
                ttsReady = false
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "guard"
        const val ACTION_TEST = "com.family.answerlock.TEST"
        /** 系统闹钟到点的动作：结算时间并弹题/锁屏 */
        const val ACTION_TICK = "com.family.answerlock.TICK"

        /**
         * 兼容字段：旧版的 QuizActivity 引用过它。
         * 新版答题界面已改为悬浮窗，该界面类已废弃；保留此字段是为了让还留在工程里的
         * 旧文件仍能编译通过（不用手动删文件）。
         */
        @Volatile
        var quizOpen = false

        /** 服务是否活着（家长页自检用） */
        @Volatile
        var running = false

        fun start(context: android.content.Context) {
            context.startForegroundService(
                Intent(context, QuizService::class.java).setAction("start")
            )
        }

        fun test(context: android.content.Context) {
            context.startForegroundService(
                Intent(context, QuizService::class.java).setAction(ACTION_TEST)
            )
        }

        fun stop(context: android.content.Context) {
            val intent = Intent(context, QuizService::class.java).setAction(ACTION_TICK)
            val pi = if (android.os.Build.VERSION.SDK_INT >= 26)
                PendingIntent.getForegroundService(
                    context, 2001, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            else
                PendingIntent.getService(
                    context, 2001, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            (context.getSystemService(android.content.Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
            context.getSharedPreferences("guard", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("guardOn", false).putBoolean("locked", false).apply()
            context.stopService(Intent(context, QuizService::class.java))
        }
    }
}
