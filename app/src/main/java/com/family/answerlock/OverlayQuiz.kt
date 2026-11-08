package com.family.answerlock

import android.content.Context
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
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
import java.util.Locale

/**
 * 答题 / 锁屏悬浮窗（单例）。
 *
 * 为什么是悬浮窗而不是 Activity：
 * - 华为/鸿蒙限制「后台启动界面」，Activity 弹不出来；悬浮窗只要「显示在其他应用上层」权限就能弹。
 * - 悬浮窗不属于任何任务，孩子上划任务列表、按返回键都关不掉。
 *
 * 单例的原因：QuizService 和无障碍服务都可能在弹窗，共用同一个实例才不会弹出两个窗口，
 * 也保证 TTS 引擎只初始化一次（华为引擎初始化慢，提前预热避免答错时没声音）。
 */
class OverlayQuiz private constructor(private val ctx: Context) {

    companion object {
        @Volatile private var inst: OverlayQuiz? = null

        /** 是否正在显示答题/锁屏窗（计时会暂停） */
        @Volatile var isShowing = false
            private set

        fun get(c: Context): OverlayQuiz {
            val app = c.applicationContext
            return inst ?: synchronized(this) {
                inst ?: OverlayQuiz(app).also { inst = it }
            }
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private val prefs = GuardCore.prefs(ctx)
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var root: FrameLayout? = null
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

    /* ---------- 音效 / 语音 ---------- */
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsInitTried = false
    private var soundPool: SoundPool? = null
    private var sndOk = 0
    private var sndNo = 0

    /* ---------- 答题状态 ---------- */
    private var pending = 0
    private var cur: QuestionBank.Q? = null
    private var answerTimer: CountDownTimer? = null
    private var lastExplain = ""
    private var speakRunnable: Runnable? = null
    private val nextRunnable = Runnable { nextQuestion() }
    private val UTTER_EXPLAIN = "explain"
    private val WRONG_SOUND_MS = 500L

    /** 弹窗关闭回调（GuardCore 用它重启计时） */
    var onClosed: (() -> Unit)? = null

    /* ================= 预热 ================= */

    /** 启动守护时就调用：把语音引擎和音效准备好，弹题时才不会「没声音」 */
    fun warmup() {
        initSound()
        initTts()
    }

    /* ================= 弹窗 ================= */

    fun showQuiz() = show("quiz")
    fun showLock() = show("lock")

    private fun show(mode: String) {
        if (isShowing) {
            GuardCore.diag(ctx, "窗口已存在，跳过")
            return
        }
        if (!Settings.canDrawOverlays(ctx)) {
            GuardCore.diag(ctx, "失败：没有「显示在其他应用上层」权限")
            return
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            ui.post { show(mode) }
            return
        }

        val v = LayoutInflater.from(ctx).inflate(R.layout.activity_quiz, null) as FrameLayout
        root = v
        quizBox = v.findViewById(R.id.quizBox)
        lockBox = v.findViewById(R.id.lockBox)
        subjectTv = v.findViewById(R.id.subjectTv)
        penaltyTv = v.findViewById(R.id.penaltyTv)
        timerTv = v.findViewById(R.id.timerTv)
        questionTv = v.findViewById(R.id.questionTv)
        optionsBox = v.findViewById(R.id.optionsBox)
        feedbackTv = v.findViewById(R.id.feedbackTv)
        explainTv = v.findViewById(R.id.explainTv)
        replayBtn = v.findViewById(R.id.replayBtn)
        pwdEt = v.findViewById(R.id.lockPwd)

        // 吃掉返回键
        v.isFocusable = true
        v.isFocusableInTouchMode = true
        v.setOnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_BACK }

        replayBtn?.setOnClickListener {
            speak(lastExplain)
            if (prefs.getBoolean("voice", true) && ttsReady)
                scheduleNext((lastExplain.length * 300L + 3000L).coerceAtMost(60_000L))
        }

        try {
            wm.addView(
                v, WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply { gravity = Gravity.CENTER }
            )
        } catch (e: Exception) {
            root = null
            GuardCore.diag(ctx, "失败：${e.javaClass.simpleName} ${e.message}")
            return
        }

        isShowing = true
        if (mode == "lock") showLockUi() else nextQuestion()
    }

    /** 关闭弹窗：亲子答对 / 家长解锁 / 服务销毁 */
    fun close() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            ui.post { close() }
            return
        }
        answerTimer?.cancel()
        answerTimer = null
        ui.removeCallbacks(nextRunnable)
        speakRunnable?.let { ui.removeCallbacks(it) }
        speakRunnable = null
        try { tts?.stop() } catch (e: Exception) {}

        val was = isShowing
        root?.let {
            try { wm.removeView(it) } catch (e: Exception) { /* 已移除 */ }
        }
        root = null
        isShowing = false
        if (was) onClosed?.invoke()
    }

    /* ================= 答题 ================= */

    private fun nextQuestion() {
        if (root == null) return
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
        } else penaltyTv?.visibility = View.GONE

        questionTv?.text = q.text
        feedbackTv?.text = ""
        explainTv?.visibility = View.GONE
        replayBtn?.visibility = View.GONE
        optionsBox?.removeAllViews()

        q.options.forEach { opt ->
            val b = Button(ctx)
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
                else "✅ 答对了！加题已还清，继续玩吧～"
                ui.postDelayed({ nextQuestion() }, 1600)
            } else {
                feedbackTv?.text = "✅ 答对了！可以继续玩～"
                ui.postDelayed({ close() }, 1000)
            }
        } else {
            playTone(false)
            btn.setBackgroundColor(0xFFE5484D.toInt())
            btn.setTextColor(0xFFFFFFFF.toInt())
            val penalty = prefs.getInt("penalty", 2)
            pending += penalty
            prefs.edit().putInt("pending", pending).apply()
            addStat("no")
            // 先放答错音效，响完再开始语音讲解
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
        showExplain(
            "⏰ 超时未作答，视为答错！\n正确答案：${q.answer}\n⚠️ 惩罚：追加 $penalty 题",
            q.explain, WRONG_SOUND_MS
        )
    }

    /** 错题讲解：文字 + 语音，语音读完才跳下一题 */
    private fun showExplain(feedback: String, explain: String, speakDelayMs: Long = 0L) {
        feedbackTv?.text = feedback
        lastExplain = explain
        explainTv?.text = "📖 错题讲解：$explain"
        explainTv?.visibility = View.VISIBLE
        replayBtn?.visibility = View.VISIBLE

        speakRunnable?.let { ui.removeCallbacks(it) }
        if (prefs.getBoolean("voice", true) && ttsReady) {
            speakRunnable = Runnable { speak(explain) }
            ui.postDelayed(speakRunnable!!, speakDelayMs)
            // 兜底：万一系统没回调 onDone，按字数估时长跳题（上限 60 秒）
            val fallback = speakDelayMs + (explain.length * 300L + 3000L).coerceAtMost(60_000L)
            scheduleNext(fallback)
        } else {
            ui.postDelayed(nextRunnable, 2600)
        }
    }

    private fun onSpeakFinished() {
        ui.removeCallbacks(nextRunnable)
        ui.postDelayed(nextRunnable, 600)
    }

    private fun scheduleNext(ms: Long) {
        ui.removeCallbacks(nextRunnable)
        ui.postDelayed(nextRunnable, ms)
    }

    private fun addStat(key: String) {
        prefs.edit()
            .putInt("total", prefs.getInt("total", 0) + 1)
            .putInt(key, prefs.getInt(key, 0) + 1)
            .apply()
    }

    /* ================= 锁屏 ================= */

    private fun showLockUi() {
        quizBox?.visibility = View.GONE
        lockBox?.visibility = View.VISIBLE
        val et = pwdEt ?: return
        root?.findViewById<Button>(R.id.unlockBtn)?.setOnClickListener {
            if (et.text.toString() == prefs.getString("pwd", "1234")) {
                prefs.edit()
                    .putBoolean("locked", false)
                    .putInt("remaining", prefs.getInt("dailyMin", 30) * 60)
                    .putInt("pending", 0)
                    .apply()
                GuardCore.diag(ctx, "家长已解锁，重新开始计时")
                Toast.makeText(ctx, "✅ 已解锁，重新开始计时", Toast.LENGTH_SHORT).show()
                close()
            } else {
                Toast.makeText(ctx, "❌ 密码错误", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /* ================= 引擎 ================= */

    private fun initSound() {
        if (soundPool != null) return
        try {
            soundPool = SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .build()
            sndOk = soundPool?.load(ctx, R.raw.correct, 1) ?: 0
            sndNo = soundPool?.load(ctx, R.raw.wrong, 1) ?: 0
        } catch (e: Exception) { /* 没音效也能答题 */ }
    }

    private fun initTts() {
        if (tts != null || ttsInitTried) return
        ttsInitTried = true
        try {
            tts = TextToSpeech(ctx) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val r = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                    ttsReady = if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                        val r2 = tts?.setLanguage(Locale.CHINA)
                        r2 != TextToSpeech.LANG_MISSING_DATA && r2 != TextToSpeech.LANG_NOT_SUPPORTED
                    } else true

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
                    if (!ttsReady) GuardCore.diag(ctx, "语音引擎不支持中文，将只用文字讲解")
                } else {
                    ttsReady = false
                    ttsInitTried = false   // 允许下次弹题时重试
                    GuardCore.diag(ctx, "语音引擎初始化失败，将只用文字讲解")
                }
            }
        } catch (e: Exception) {
            ttsReady = false
        }
    }

    private fun speak(text: String) {
        if (ttsReady) {
            try {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), UTTER_EXPLAIN)
            } catch (e: Exception) { /* 朗读失败不阻塞跳题 */ }
        }
    }

    private fun playTone(ok: Boolean) {
        val id = if (ok) sndOk else sndNo
        if (id != 0) try {
            soundPool?.play(id, 1f, 1f, 1, 0, 1f)
        } catch (e: Exception) {}
    }
}
