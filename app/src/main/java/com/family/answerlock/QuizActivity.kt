package com.family.answerlock

import android.app.Activity
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.SoundPool
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * 全局答题弹窗：
 * - mode = "quiz"：答题。答对继续玩；答错/超时 → 按设置追加题目，连答还清后才能关闭
 * - 答错时展示「错题讲解」并用系统语音（TTS）朗读，加深印象，可点「再听一遍」重听
 * - mode = "lock"：今日时长用完的锁屏页，家长输入密码解锁
 */
class QuizActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private var mode = "quiz"
    private var pending = 0
    private var timer: CountDownTimer? = null
    private var cur: QuestionBank.Q? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var soundPool: SoundPool? = null
    private var sndOk = 0
    private var sndNo = 0

    private lateinit var quizBox: View
    private lateinit var lockBox: View
    private lateinit var subjectTv: TextView
    private lateinit var penaltyTv: TextView
    private lateinit var timerTv: TextView
    private lateinit var questionTv: TextView
    private lateinit var optionsBox: LinearLayout
    private lateinit var feedbackTv: TextView
    private lateinit var explainTv: TextView
    private lateinit var replayBtn: Button

    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        QuizService.quizOpen = true
        prefs = getSharedPreferences("guard", MODE_PRIVATE)
        setContentView(R.layout.activity_quiz)

        quizBox = findViewById(R.id.quizBox)
        lockBox = findViewById(R.id.lockBox)
        subjectTv = findViewById(R.id.subjectTv)
        penaltyTv = findViewById(R.id.penaltyTv)
        timerTv = findViewById(R.id.timerTv)
        questionTv = findViewById(R.id.questionTv)
        optionsBox = findViewById(R.id.optionsBox)
        feedbackTv = findViewById(R.id.feedbackTv)
        explainTv = findViewById(R.id.explainTv)
        replayBtn = findViewById(R.id.replayBtn)

        // 游戏音效：8-bit 复古风（金币声/下坠嗡鸣），打包在 res/raw，离线可用
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

        // 系统语音引擎（离线免费），初始化后设为中文
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = tts?.setLanguage(Locale.CHINA) != TextToSpeech.LANG_MISSING_DATA &&
                        tts?.setLanguage(Locale.CHINA) != TextToSpeech.LANG_NOT_SUPPORTED
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
            }
        }
        replayBtn.setOnClickListener {
            speak(lastExplain)
            // 重听时刷新兜底计时，保证听完讲解才跳下一题
            if (prefs.getBoolean("voice", true))
                scheduleNext((lastExplain.length * 400L + 4000L).coerceAtMost(90_000L))
        }

        mode = intent?.getStringExtra("mode") ?: "quiz"
        if (mode == "lock") showLock() else nextQuestion()
    }

    override fun onDestroy() {
        timer?.cancel()
        ui.removeCallbacks(nextRunnable)
        speakRunnable?.let { ui.removeCallbacks(it) }
        soundPool?.release()
        tts?.stop()
        tts?.shutdown()
        QuizService.quizOpen = false
        super.onDestroy()
    }

    /* ---------------- 答题 ---------------- */

    private fun nextQuestion() {
        pending = prefs.getInt("pending", 0)
        val grade = prefs.getInt("grade", 3)
        val subjects = prefs.getString("subjects", "math")!!
            .split(",").filter { it.isNotBlank() }

        cur = QuestionBank.make(grade, subjects)
        val q = cur!!

        subjectTv.text = "📘 ${q.subject} · 答对才能继续玩"
        if (pending > 0) {
            penaltyTv.visibility = View.VISIBLE
            penaltyTv.text = "⚠️ 答错加题 · 还需连答 $pending 题"
        } else {
            penaltyTv.visibility = View.GONE
        }
        questionTv.text = q.text
        feedbackTv.text = ""
        explainTv.visibility = View.GONE
        replayBtn.visibility = View.GONE
        optionsBox.removeAllViews()

        q.options.forEach { opt ->
            val b = Button(this)
            b.text = opt
            b.textSize = 16f
            b.setAllCaps(false)
            b.setPadding(0, 28, 0, 28)
            b.setOnClickListener { answer(opt, b) }
            optionsBox.addView(b)
        }

        timer?.cancel()
        val total = prefs.getInt("quizTimeSec", 60) * 1000L
        timer = object : CountDownTimer(total, 500) {
            override fun onTick(ms: Long) {
                val s = ms / 1000 + 1
                val txt = if (s >= 60) "${s / 60} 分 ${s % 60} 秒" else "$s 秒"
                timerTv.text = "⏳ 剩余作答时间：$txt（超时算答错）"
            }
            override fun onFinish() {
                timerTv.text = "⏳ 剩余作答时间：0 秒"
                timeUp()
            }
        }.start()
    }

    private fun answer(opt: String, btn: Button) {
        val q = cur ?: return
        timer?.cancel()
        for (i in 0 until optionsBox.childCount) optionsBox.getChildAt(i).isEnabled = false

        if (opt == q.answer) {
            playTone(true)
            btn.setBackgroundColor(0xFF18A058.toInt())
            btn.setTextColor(0xFFFFFFFF.toInt())
            addStat("ok")
            if (pending > 0) {
                pending--
                prefs.edit().putInt("pending", pending).apply()
                feedbackTv.text = if (pending > 0)
                    "✅ 答对了！但因之前答错，还需再答 $pending 题"
                else
                    "✅ 答对了！加题已还清，继续玩吧～"
                ui.postDelayed({ nextQuestion() }, 1600)
            } else {
                feedbackTv.text = "✅ 答对了！可以继续玩～"
                resetQuizTimer()
                ui.postDelayed({ finish() }, 1000)
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
        // 先播答错音效，等它响完再开始语音讲解
        showExplain("⏰ 超时未作答，视为答错！\n正确答案：${q.answer}\n⚠️ 惩罚：追加 $penalty 题", q.explain, WRONG_SOUND_MS)
    }

    /** 展示错题讲解 + 语音朗读（可关），朗读结束后才进入下一题 */
    private var lastExplain = ""
    private val nextRunnable = Runnable { nextQuestion() }
    private val UTTER_EXPLAIN = "explain"
    /** 答错音效时长（wrong.wav 约 0.47 秒），音效响完再开始讲解朗读 */
    private val WRONG_SOUND_MS = 500L

    private fun showExplain(feedback: String, explain: String, speakDelayMs: Long = 0L) {
        feedbackTv.text = feedback
        lastExplain = explain
        explainTv.text = "📖 错题讲解：$explain"
        explainTv.visibility = View.VISIBLE
        replayBtn.visibility = View.VISIBLE
        speakRunnable?.let { ui.removeCallbacks(it) }
        if (prefs.getBoolean("voice", true)) {
            // 延迟启动朗读，避开答错音效
            speakRunnable = Runnable { speak(explain) }
            ui.postDelayed(speakRunnable!!, speakDelayMs)
            // 兜底：即使语音引擎异常/过慢，也按「音效延迟 + 朗读预估时长 + 4 秒」保底跳题
            val fallback = speakDelayMs + (explain.length * 400L + 4000L).coerceAtMost(90_000L)
            scheduleNext(fallback)
        } else {
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

    private var speakRunnable: Runnable? = null

    private fun speak(text: String) {
        if (ttsReady) {
            val params = Bundle()
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, UTTER_EXPLAIN)
        }
    }

    /** 答题音效：答对 = 金币声「叮铃~」，答错/超时 = 下坠嗡鸣「哐~」 */
    private fun playTone(ok: Boolean) {
        val id = if (ok) sndOk else sndNo
        if (id != 0) soundPool?.play(id, 1f, 1f, 1, 0, 1f)
    }

    /** 答题结束，重新开始下一轮弹题倒计时 */
    private fun resetQuizTimer() {
        prefs.edit().putInt("nextQuizIn", prefs.getInt("intervalSec", 60)).apply()
    }

    private fun addStat(key: String) {
        prefs.edit()
            .putInt("total", prefs.getInt("total", 0) + 1)
            .putInt(key, prefs.getInt(key, 0) + 1)
            .apply()
    }

    /* ---------------- 锁屏 ---------------- */

    private fun showLock() {
        quizBox.visibility = View.GONE
        lockBox.visibility = View.VISIBLE
        val pwdEt = findViewById<EditText>(R.id.lockPwd)
        findViewById<Button>(R.id.unlockBtn).setOnClickListener {
            if (pwdEt.text.toString() == prefs.getString("pwd", "1234")) {
                prefs.edit()
                    .putBoolean("locked", false)
                    .putInt("remaining", prefs.getInt("dailyMin", 30) * 60)
                    .putInt("nextQuizIn", prefs.getInt("intervalSec", 60))
                    .putInt("pending", 0)
                    .apply()
                Toast.makeText(this, "✅ 已解锁，重新开始计时", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this, "❌ 密码错误", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
