package com.family.answerlock

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
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
        /** 选项落盘用的分隔符（题干里不会出现） */
        private const val SEP_OPT = "\u0001"

        /** 「测试语音讲解」读的那句话 */
        private const val TEST_SENTENCE =
            "语音讲解已开启。答错的时候，我会把这道题的解题思路读给你听。"

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
    private var quizPanel: View? = null
    private var emergencyBtn: TextView? = null
    private var emgBox: View? = null
    private var emgPwd: EditText? = null
    private var emgErrTv: TextView? = null
    private var subjectTv: TextView? = null
    private var penaltyTv: TextView? = null
    private var timerTv: TextView? = null
    private var questionTv: TextView? = null
    private var optionsBox: LinearLayout? = null
    private var feedbackTv: TextView? = null
    private var explainTv: TextView? = null
    private var replayBtn: Button? = null
    private var pwdEt: EditText? = null

    /** 当前题目的选项按钮（电视版：答完要恢复焦点 / 紧急解锁面板关掉后焦点回选项） */
    private val optBtns = ArrayList<Button>()

    /* ---------- 紧急解锁状态 ---------- */
    /** 打开密码面板时暂停倒计时，取消后从原处继续 */
    private var emgOpen = false
    private var emgLeftSec = 0
    /** 这一题还剩多少秒（每次 onTick 更新） */
    private var leftSec = 0
    /** 连续输错密码的次数 / 暂时禁止再试的时间点（防孩子穷举） */
    private var emgWrong = 0
    private var emgBlockUntil = 0L

    /* ---------- 音效 / 语音 ---------- */
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsInitTried = false
    /** 语音引擎诊断信息（家长页「测试语音」直接显示，用来判断是没引擎还是没中文数据） */
    private var ttsInfo = "未初始化"
    /** 点了「测试语音」但引擎还在初始化 → 就绪后立刻朗读 */
    private var testPending = false
    /** 引擎探测已经出结论（就绪 / 全部没中文包），不会再有异步回调了 */
    private var ttsSettled = false
    private var soundPool: SoundPool? = null
    private var sndOk = 0
    private var sndNo = 0

    /* ---------- 答题状态 ---------- */
    private var pending = 0
    private var cur: QuestionBank.Q? = null
    /** 下一次 show 时是否接着上次那道题（被划掉/被杀后重弹用） */
    private var resumeNext = false
    private var answerTimer: CountDownTimer? = null
    /** v3.4 息屏冻结：true = 题目流程被冻住，亮屏后由 onScreenOn 接上 */
    private var screenPaused = false
    /** 息屏那一刻是「正在看讲解/等跳下一题」还是「正在作答」 */
    private var screenWasExplain = false
    private var lastExplain = ""
    private var speakRunnable: Runnable? = null
    private val nextRunnable = Runnable { nextQuestion() }
    private val UTTER_EXPLAIN = "explain"
    private val WRONG_SOUND_MS = 500L

    /** 错题解答的停留时长（v3.5 家长定）：10 秒看完再跳下一题 */
    private val EXPLAIN_STAY_MS = 10_000L

    /** 弹窗关闭回调（GuardCore 用它重启计时） */
    var onClosed: (() -> Unit)? = null

    /* ================= 预热 ================= */

    /** 启动守护时就调用：把语音引擎和音效准备好，弹题时才不会「没声音」 */
    fun warmup() {
        initSound()
        // v3.5 语音播报取消：不再初始化 TTS 引擎（省电省内存，tts 相关代码保留备用）
    }

    /* ================= 弹窗 ================= */

    fun showQuiz() = show("quiz")
    fun showLock() = show("lock")

    /**
     * 没答完的题被划掉 / 进程被杀之后重新弹出来，并且沿用原来那道题（划掉换不来新题）。
     */
    fun resumePending(): Boolean {
        if (!prefs.getBoolean("overlayOpen", false)) return false
        if (isShowing) return true
        resumeNext = true
        val mode = prefs.getString("overlayMode", "quiz") ?: "quiz"
        show(if (mode == "lock") "lock" else "quiz")
        return true
    }

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
        quizPanel = v.findViewById(R.id.quizPanel)
        emergencyBtn = v.findViewById(R.id.emergencyBtn)
        emgBox = v.findViewById(R.id.emgBox)
        emgPwd = v.findViewById(R.id.emgPwd)
        emgErrTv = v.findViewById(R.id.emgErrTv)
        subjectTv = v.findViewById(R.id.subjectTv)
        penaltyTv = v.findViewById(R.id.penaltyTv)
        timerTv = v.findViewById(R.id.timerTv)
        questionTv = v.findViewById(R.id.questionTv)
        optionsBox = v.findViewById(R.id.optionsBox)
        feedbackTv = v.findViewById(R.id.feedbackTv)
        explainTv = v.findViewById(R.id.explainTv)
        replayBtn = v.findViewById(R.id.replayBtn)
        pwdEt = v.findViewById(R.id.lockPwd)

        // 每次重新弹窗都从「干净状态」开始（上一轮的密码面板不能留着）
        emgOpen = false
        emgWrong = 0
        emgBox?.visibility = View.GONE
        quizPanel?.visibility = View.VISIBLE

        emergencyBtn?.setOnClickListener { openEmergencyPanel() }
        v.findViewById<Button>(R.id.emgCancelBtn)?.setOnClickListener { closeEmergencyPanel() }
        v.findViewById<Button>(R.id.emgOkBtn)?.setOnClickListener { tryEmergencyUnlock() }

        // 吃掉返回键 + 吃掉所有触摸（不让点击漏到底下的 App）
        v.isFocusable = true
        v.isFocusableInTouchMode = true
        v.isClickable = true
        v.setOnClickListener { }
        v.setOnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_BACK }
        // v3.5 语音播报取消：「再听一遍讲解」按钮永久隐藏（讲解只看文字）

        try {
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.CENTER }
            // 铺满刘海区域，避免顶部留出缺口让孩子点到状态栏
            if (Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            wm.addView(v, lp)
        } catch (e: Exception) {
            root = null
            // 华为把「悬浮窗」拆成了「悬浮窗」+「后台弹窗」两个开关：
            // 只开前者时，前台能弹、离开本应用就被系统拦住（BadTokenException / permission denied）。
            // 这里把结果落盘，家长页顶部的红条就会亮起来，直接带他去开第二个开关。
            val blocked = e is WindowManager.BadTokenException ||
                (e.message ?: "").contains("permission", true) ||
                (e.message ?: "").contains("denied", true)
            if (blocked) {
                prefs.edit()
                    .putBoolean("bgPopupBlocked", true)
                    .putBoolean("bgPopupOk", false)
                    .apply()
                GuardCore.diag(ctx, "弹窗被系统拦住 → 华为「后台弹窗」权限没开")
            } else {
                GuardCore.diag(ctx, "弹窗失败：${e.javaClass.simpleName} ${e.message}")
            }
            return
        }

        // 能加进 WindowManager ＝ 两个开关都齐了，顶部红条可以收起
        prefs.edit()
            .putBoolean("bgPopupOk", true)
            .putBoolean("bgPopupBlocked", false)
            .apply()

        // 尽量屏蔽「上滑回桌面/最近任务」手势（系统只允许排除边缘 200dp 内的区域，尽力而为）
        blockSystemGestures(v)

        // 窗口被系统以外的方式移除时（部分机型在切换最近任务时会这样），
        // 立刻把「显示中」标记清掉，让 GuardCore 的心跳在 1 秒内把题目重新画出来。
        v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {}

            override fun onViewDetachedFromWindow(view: View) {
                if (isShowing) {
                    isShowing = false
                    GuardCore.diag(ctx, "答题窗被系统移除 → 1 秒内自动重弹")
                }
            }
        })

        isShowing = true
        // 落盘「有一题没答完」：进程被杀 / 窗口被系统移除后，服务会立刻把它重新弹出来
        prefs.edit()
            .putBoolean("overlayOpen", true)
            .putString("overlayMode", mode)
            .apply()

        if (mode == "lock") showLockUi()
        else if (resumeNext) {
            resumeNext = false
            restoreQuestion()
            GuardCore.diag(ctx, "答题窗被划掉 → 已重新弹出（必须答对才能离开）")
        } else nextQuestion()
    }

    /**
     * 屏蔽「上滑回桌面 / 上滑进最近任务」手势。
     *
     * 安卓 10（API 29）对排除区域有 200dp 上限，只能挡屏幕四边；
     * 安卓 11（API 30）起取消了这个上限，直接把整块窗口排除掉，
     * 手势就从窗口上"滑不动"了（华为 Mate 60 属于这一档）。
     */
    private fun blockSystemGestures(v: View) {
        if (Build.VERSION.SDK_INT < 29) return
        v.post(object : Runnable {
            override fun run() {
                try {
                    val w = v.width
                    val h = v.height
                    if (w <= 0 || h <= 0) return
                    val rects = ArrayList<Rect>(4)
                    if (Build.VERSION.SDK_INT >= 30) {
                        // 整屏排除：底部上滑、侧边返回全都落在窗口内，不算系统手势
                        rects.add(Rect(0, 0, w, h))
                    } else {
                        val edge = (200 * ctx.resources.displayMetrics.density).toInt()
                        rects.add(Rect(0, 0, w, minOf(edge, h)))
                        rects.add(Rect(0, maxOf(h - edge, 0), w, h))
                        rects.add(Rect(0, 0, minOf(edge, w), h))
                        rects.add(Rect(maxOf(w - edge, 0), 0, w, h))
                    }
                    v.setSystemGestureExclusionRects(rects)
                } catch (e: Exception) { /* 某些系统不支持，忽略 */ }
            }
        })
    }

    /* ================= 紧急解锁（家长按，孩子不知道密码就过不去） ================= */

    /**
     * 弹题窗右上角的「🆘 紧急解锁」。
     *
     * 场景：家长正在用手机办事、要接电话、要扫码付钱，这时候被弹题卡住很难受。
     * 点一下 → 让家长输家长密码 → 输对就立刻关掉弹题窗、本轮题目作废，
     * 并且接下来 10 分钟不弹题、也不扣今日时长（这段时间是家长在用手机）。
     */
    private fun openEmergencyPanel() {
        if (emgOpen) return
        emgOpen = true
        emgLeftSec = leftSec.coerceAtLeast(5)
        answerTimer?.cancel()            // 输密码期间倒计时先停住，避免「输密码输到超时算答错」
        answerTimer = null
        ui.removeCallbacks(nextRunnable) // 也别在这时候自动跳下一题
        emgErrTv?.visibility = View.GONE
        emgPwd?.setText("")
        quizPanel?.visibility = View.GONE
        emgBox?.visibility = View.VISIBLE
        // 电视版：紧急解锁的密码也走屏幕键盘（遥控器上下左右 + OK）
        root?.findViewById<LinearLayout>(R.id.emgPadBox)?.let { pad ->
            emgPwd?.let { et -> QwertyPad.attach(pad) { et } }
            QwertyPad.focusFirst(pad)
        }
    }

    /** 取消：回到题目，倒计时从刚才暂停的地方继续 */
    private fun closeEmergencyPanel() {
        if (!emgOpen) return
        emgOpen = false
        emgBox?.visibility = View.GONE
        quizPanel?.visibility = View.VISIBLE
        val answered = !feedbackTv?.text.isNullOrBlank()
        if (answered) {
            // 刚才已经答错、正在看讲解 → 重新排一次「跳下一题」
            ui.postDelayed(nextRunnable, 3000)
        } else if (cur != null) {
            startTimer(emgLeftSec.coerceAtLeast(5))
            // 不再把焦点塞回某个选项：答案位置是随机的，让用户自己选
            optionsBox?.post { hintFocus() }
        }
    }

    /* ================= 息屏冻结（v3.4：锁屏不计时、不超时、不加题） ================= */

    /**
     * 息屏/锁屏：把弹题窗里的一切冻住。
     * · 正在作答 → 倒计时停住（精确秒数落盘），不会走到 timeUp() 加题；
     * · 正在看讲解 → 暂停「自动跳下一题」，语音读没读完都不再推进；
     * · 亮屏后由 onScreenOn() 从停住的地方原样接上。
     * 这样「锁屏休息」和「锁屏逃逸」是同一件事：题没消失，但也不会被偷偷超时。
     */
    fun onScreenOff() {
        ui.post {
            if (!isShowing || emgOpen) return@post
            val answered = !feedbackTv?.text.isNullOrBlank()
            ui.removeCallbacks(nextRunnable)
            speakRunnable?.let { ui.removeCallbacks(it) }
            speakRunnable = null
            screenPaused = true
            screenWasExplain = answered
            if (!answered && cur != null) {
                answerTimer?.cancel()
                answerTimer = null
                // 精确落盘当前剩余秒数（平时只在整 5 秒时落盘）
                prefs.edit().putInt("qLeftSec", leftSec.coerceAtLeast(5)).apply()
            }
        }
    }

    /** 亮屏：从冻住的地方接上（作答→倒计时继续；讲解→马上安排跳下一题） */
    fun onScreenOn() {
        ui.post {
            if (!screenPaused) return@post
            screenPaused = false
            if (!isShowing || emgOpen) return@post
            val answered = !feedbackTv?.text.isNullOrBlank()
            if (answered || screenWasExplain) {
                // 讲解还在屏幕上 → 稍等片刻跳下一题（让亮屏的一瞬先看清画面）
                ui.postDelayed(nextRunnable, 1500)
            } else if (cur != null) {
                startTimer(prefs.getInt("qLeftSec", prefs.getInt("quizTimeSec", 60)).coerceAtLeast(5))
            }
        }
    }

    private fun tryEmergencyUnlock() {
        val now = System.currentTimeMillis()
        if (now < emgBlockUntil) {
            val s = ((emgBlockUntil - now) / 1000L + 1L).toInt()
            emgErrTv?.text = "错误次数太多，请 $s 秒后再试"
            emgErrTv?.visibility = View.VISIBLE
            return
        }
        val pwd = prefs.getString("pwd", "1234") ?: "1234"
        if (emgPwd?.text?.toString() == pwd) {
            emgOpen = false
            GuardCore.startEmergency(ctx, GuardCore.EMERGENCY_MIN, "弹题窗 ↗ 紧急解锁")
            Toast.makeText(
                ctx,
                "✅ 已紧急解锁：本轮题目作废，${GuardCore.EMERGENCY_MIN} 分钟内不弹题",
                Toast.LENGTH_LONG
            ).show()
            close()
            return
        }
        // 输错：连错 3 次锁 60 秒，防止孩子在这儿穷举密码
        emgWrong++
        if (emgWrong >= 3) {
            emgWrong = 0
            emgBlockUntil = now + 60_000L
            emgErrTv?.text = "密码错误 3 次，请 60 秒后再试"
        } else {
            emgErrTv?.text = "密码不对，还可以再试 ${3 - emgWrong} 次"
        }
        emgErrTv?.visibility = View.VISIBLE
        emgPwd?.setText("")
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
        // 先清标记：这样 removeView 触发的「窗口被移除」回调不会误报成「被系统移除」
        isShowing = false
        root?.let {
            try { wm.removeView(it) } catch (e: Exception) { /* 已移除 */ }
        }
        root = null
        // 正常关闭 = 这一题结束了，清除「没答完」标记（否则 watchdog 会把它又弹出来）
        prefs.edit().putBoolean("overlayOpen", false).putString("overlayMode", "").apply()
        if (was) onClosed?.invoke()
    }

    /* ================= 答题 ================= */

    private fun nextQuestion() {
        if (root == null) return
        pending = prefs.getInt("pending", 0)
        val grade = prefs.getInt("grade", 3)
        val subjects = prefs.getString("subjects", "math")!!
            .split(",").filter { it.isNotBlank() }

        val q = QuestionBank.make(grade, subjects)
        cur = q

        // 落盘当前这道题：孩子上滑杀掉进程后，重新弹出来的还是同一道题（换不来一道新题），
        // 剩余倒计时也接着走，删掉重来毫无收益。
        prefs.edit()
            .putString("qSubject", q.subject)
            .putString("qText", q.text)
            .putString("qAnswer", q.answer)
            .putString("qExplain", q.explain)
            .putString("qOptions", q.options.joinToString(SEP_OPT))
            .putInt("qLeftSec", prefs.getInt("quizTimeSec", 60))
            .apply()

        render(q, prefs.getInt("quizTimeSec", 60))
    }

    /** 重新弹窗时恢复上次没答完的那道题（含剩余时间） */
    private fun restoreQuestion() {
        if (root == null) return
        val text = prefs.getString("qText", null)
        val opts = prefs.getString("qOptions", null)
        if (text.isNullOrBlank() || opts.isNullOrBlank()) {
            nextQuestion()
            return
        }
        val q = QuestionBank.Q(
            subject = prefs.getString("qSubject", "数学") ?: "数学",
            text = text,
            answer = prefs.getString("qAnswer", "") ?: "",
            explain = prefs.getString("qExplain", "") ?: "",
            options = opts.split(SEP_OPT).filter { it.isNotBlank() }
        )
        cur = q
        val left = prefs.getInt("qLeftSec", prefs.getInt("quizTimeSec", 60)).coerceAtLeast(5)
        render(q, left)
    }

    /** 画一道题 + 起倒计时。startSec = 这题还剩多少秒可答 */
    private fun render(q: QuestionBank.Q, startSec: Int) {
        subjectTv?.text = "📘 ${q.subject} · 答对才能继续看"
        if (pending > 0) {
            penaltyTv?.visibility = View.VISIBLE
            penaltyTv?.text = "⚠️ 答错加题 · 还需连答 $pending 题"
        } else penaltyTv?.visibility = View.GONE

        questionTv?.text = q.text
        feedbackTv?.text = ""
        explainTv?.visibility = View.GONE
        replayBtn?.visibility = View.GONE
        optionsBox?.removeAllViews()
        optBtns.clear()

        // 电视版：选项每行 2 个（横屏下遥控器左右移动最顺手）、大字号 + 聚焦高亮样式，
        // 同时把按钮登记进 optBtns（answer() 答完禁点、紧急解锁关面板后焦点回收都靠它）。
        val d = ctx.resources.displayMetrics.density
        var row: LinearLayout? = null
        q.options.forEachIndexed { i, opt ->
            if (i % 2 == 0) {
                val r = LinearLayout(ctx)
                r.orientation = LinearLayout.HORIZONTAL
                val rlp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                rlp.topMargin = if (i == 0) 0 else (16 * d).toInt()
                optionsBox?.addView(r, rlp)
                row = r
            }
            val b = Button(ctx)
            b.text = opt
            b.textSize = 24f
            b.isAllCaps = false
            b.setBackgroundResource(R.drawable.bg_opt)   // 聚焦 = 亮蓝底 + 白描边
            b.setTextColor(0xFFF2F5FA.toInt())
            b.setPadding((10 * d).toInt(), (18 * d).toInt(), (10 * d).toInt(), (18 * d).toInt())
            b.setOnClickListener { answer(opt, b) }
            val blp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            blp.setMargins((6 * d).toInt(), 0, (6 * d).toInt(), 0)
            row?.addView(b, blp)
            optBtns.add(b)
        }

        answerTimer?.cancel()
        startTimer(startSec)
        // 不再自动把焦点放在第一项上：答案位置随机，自动聚焦等于帮孩子选了一个，
        // 交给孩子自己按方向键移过去（父容器 focusable，第一下方向键就能进选项区）
        optionsBox?.post { hintFocus() }
    }

    /** 让焦点停在题目区而不是某个选项上：方向键一下就能进选项，回车不会直接作答 */
    private fun hintFocus() {
        if (optBtns.isEmpty()) return
        questionTv?.requestFocus()
    }

    /** 倒计时（抽出来是因为「紧急解锁 → 取消」要从暂停的地方接着走） */
    private fun startTimer(startSec: Int) {
        leftSec = startSec
        answerTimer?.cancel()
        answerTimer = object : CountDownTimer(startSec * 1000L, 500) {
            override fun onTick(ms: Long) {
                val s = ms / 1000 + 1
                leftSec = s.toInt()
                val txt = if (s >= 60) "${s / 60} 分 ${s % 60} 秒" else "$s 秒"
                timerTv?.text = "⏳ 剩余作答时间：$txt（超时算答错）"
                // 每 5 秒把剩余秒数落盘，被划掉重弹时接着倒计时
                val secInt = s.toInt()
                if (secInt % 5 == 0 && prefs.getInt("qLeftSec", -1) != secInt) {
                    prefs.edit().putInt("qLeftSec", secInt).apply()
                }
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
        // 答完这题就不许再改：全部禁点，且把对/错颜色画上（保留圆角，不突兀）
        for (b in optBtns) {
            b.isEnabled = false
            b.isFocusable = false
        }

        if (opt == q.answer) {
            playTone(true)
            btn.setBackgroundResource(R.drawable.bg_opt_ok)
            btn.setTextColor(0xFFFFFFFF.toInt())
            addStat("ok")
            if (pending > 0) {
                pending--
                prefs.edit().putInt("pending", pending).apply()
                feedbackTv?.text = if (pending > 0)
                    "✅ 答对了！但因之前答错，还需再答 $pending 题"
                else "✅ 答对了！加题已还清，继续看吧～"
                ui.postDelayed({ nextQuestion() }, 1600)
            } else {
                feedbackTv?.text = "✅ 答对了！可以继续看～"
                ui.postDelayed({ close() }, 1000)
            }
        } else {
            playTone(false)
            btn.setBackgroundResource(R.drawable.bg_opt_no)
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

    /**
     * 错题/超时讲解（v3.5 取消语音播报，家长要求）：
     * 只显示文字解答，停留 10 秒后自动跳下一题——太短看不完，太长孩子干等。
     * 答错音效仍保留（在调用方先响一声）。
     */
    private fun showExplain(feedback: String, explain: String, speakDelayMs: Long = 0L) {
        feedbackTv?.text = feedback
        lastExplain = explain
        explainTv?.text = "📖 错题讲解：$explain"
        explainTv?.visibility = View.VISIBLE
        replayBtn?.visibility = View.GONE      // 语音取消，「再听一遍」按钮不再出现

        speakRunnable?.let { ui.removeCallbacks(it) }
        speakRunnable = null
        scheduleNext(EXPLAIN_STAY_MS)
    }

    private fun onSpeakFinished() {
        ui.removeCallbacks(nextRunnable)
        // 息屏冻结中（或家长密码面板开着）：别自动跳题，亮屏/关面板后由对应入口接上
        if (screenPaused || emgOpen) return
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
        // 电视版：锁屏页的密码用屏幕键盘输（遥控器上下左右 + OK）
        root?.findViewById<LinearLayout>(R.id.lockPadBox)?.let { pad ->
            QwertyPad.attach(pad) { et }
            QwertyPad.focusFirst(pad)
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

    /**
     * 初始化语音引擎。
     *
     * v3.2 修的「语音讲解一直没声音」：manifest 缺 `<queries><intent action=TTS_SERVICE>`，
     * 安卓 11 起 App 看不到语音引擎，TextToSpeech 初始化静默失败。
     *
     * v3.3 修的「提示没有中文语音包」：queries 生效后能找到引擎了，但手机里常装了
     * 好几个引擎（谷歌 TTS / 华为引擎 / 讯飞…），**系统默认选中的那个不一定带中文语音数据**。
     * 现在的流程：先用默认引擎试中文 → 不行就按优先级逐个探测系统里其他引擎
     * （华为引擎 → 讯飞 → 百度 → 其他 → 谷歌），谁有中文就用谁，并永久记住这个选择；
     * 全都没有中文包 → 自动打开系统的语音设置页，让家长装一次语音数据（这一步没有 API 可替代）。
     */
    private fun initTts() {
        if (tts != null || probing) return
        ttsInitTried = true
        ttsSettled = false
        val saved = prefs.getString("ttsEngine", "")
        try {
            tts = if (!saved.isNullOrBlank())
                TextToSpeech(ctx, { s -> onTtsInit(s) }, saved)
            else
                TextToSpeech(ctx) { s -> onTtsInit(s) }
        } catch (e: Throwable) {
            ttsReady = false
            ttsInitTried = false
            ttsInfo = "引擎创建失败：${e.javaClass.simpleName}"
            GuardCore.diag(ctx, "语音引擎创建失败：${e.javaClass.simpleName}")
        }
    }

    /** 对一个已初始化成功的引擎设置音频通道并试中文；返回是否可用 */
    private fun tryChinese(t: TextToSpeech): Boolean {
        try {
            t.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        } catch (e: Throwable) { /* 老引擎不支持就跳过 */ }

        var r = t.setLanguage(Locale.SIMPLIFIED_CHINESE)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED)
            r = t.setLanguage(Locale.CHINESE)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED)
            r = t.setLanguage(Locale.PRC)
        return r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
    }

    private fun engineName(t: TextToSpeech): String =
        try { t.defaultEngine ?: "未知引擎" } catch (e: Throwable) { "未知引擎" }

    /** 引擎包名 → 家长看得懂的短名 */
    private fun engineLabel(pkg: String): String = when {
        pkg.contains("huawei") -> "华为语音引擎"
        pkg.contains("iflytek") || pkg.contains("vflytone") -> "讯飞语音"
        pkg.contains("baidu") -> "百度语音"
        pkg.contains("google") -> "谷歌语音"
        pkg.contains("samsung") -> "三星语音"
        else -> pkg.substringAfterLast('.').ifBlank { pkg }
    }

    private fun attachUtterListener(t: TextToSpeech) {
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}

            override fun onDone(id: String?) {
                if (id == UTTER_EXPLAIN) ui.post { onSpeakFinished() }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                if (id == UTTER_EXPLAIN) ui.post { onSpeakFinished() }
            }

            override fun onError(id: String?, errorCode: Int) {
                GuardCore.diag(ctx, "语音朗读出错 code=$errorCode")
                if (id == UTTER_EXPLAIN) ui.post { onSpeakFinished() }
            }
        })
    }

    private fun onTtsInit(status: Int) {
        val t = tts
        if (status != TextToSpeech.SUCCESS || t == null) {
            ttsReady = false
            ttsInitTried = false
            // 记住的引擎可能被卸载了，清掉下次走默认
            if (!prefs.getString("ttsEngine", "").isNullOrBlank())
                prefs.edit().remove("ttsEngine").apply()
            ttsInfo = "初始化失败（code=$status）"
            GuardCore.diag(ctx, "语音引擎初始化失败 code=$status，只能用文字讲解")
            ttsSettled = true
            if (testPending) { testPending = false; openVoiceSettings() }
            return
        }

        if (tryChinese(t)) {
            ttsReady = true
            ttsSettled = true
            ttsInfo = "引擎 ${engineLabel(engineName(t))} · 中文可用"
            attachUtterListener(t)
            GuardCore.diag(ctx, "语音引擎就绪：$ttsInfo")
            if (testPending) { testPending = false; speak(TEST_SENTENCE) }
        } else {
            ttsReady = false
            val cur = engineName(t)
            if (!prefs.getString("ttsEngine", "").isNullOrBlank()) {
                // 之前记住的引擎现在没中文了（比如语音数据被清理），清掉重新探测
                prefs.edit().remove("ttsEngine").apply()
            }
            ttsInfo = "引擎 ${engineLabel(cur)} · 没有中文语音包，正在找其他引擎…"
            GuardCore.diag(ctx, "默认引擎无中文 → 自动探测其他引擎")
            probeNextEngine()
        }
    }

    /** 系统引擎按「最可能带中文」排序：华为 → 讯飞 → 百度 → 其他 → 谷歌（谷歌常缺数据，放最后） */
    private val enginePriority = listOf(
        "com.huawei.ttsengine",
        "com.iflytek.speechsuite",
        "com.iflytek.vflytone3.tts",
        "com.baidu.speechsynthesis",
        "com.samsung.SMT",
        "com.google.android.tts"
    )

    /** 还没试过的引擎包名队列 */
    private var probeQueue: MutableList<String>? = null
    /** 正在初始化一个探测引擎（防止并发创建一堆 TTS） */
    private var probing = false
    private var probe: TextToSpeech? = null

    /** 从队列里取下一个引擎，创建并试中文；队列空了 → 打开系统语音设置兜底 */
    private fun probeNextEngine() {
        if (probing) return
        val t = tts ?: return
        if (probeQueue == null) {
            val cur = engineName(t)
            val all = try { t.engines.map { it.name } } catch (e: Throwable) { emptyList<String>() }
            val known = enginePriority.filter { it != cur && it in all }
            val rest = all.filter { it != cur && it !in enginePriority }
            probeQueue = (known + rest).toMutableList()
        }
        val next = probeQueue?.removeFirstOrNull()
        if (next == null) {
            ttsInfo = "所有语音引擎都没有中文语音包"
            GuardCore.diag(ctx, "全部引擎无中文 → 打开系统语音设置")
            ttsSettled = true
            if (testPending) { testPending = false; openVoiceSettings() }
            return
        }
        probing = true
        GuardCore.diag(ctx, "尝试语音引擎：${engineLabel(next)}")
        val p = try {
            TextToSpeech(ctx, { s -> onProbeInit(next, s) }, next)
        } catch (e: Throwable) {
            probing = false
            GuardCore.diag(ctx, "引擎 ${engineLabel(next)} 无法创建：${e.javaClass.simpleName}")
            probeNextEngine()
            return
        }
        probe = p
    }

    private fun onProbeInit(enginePkg: String, status: Int) {
        val p = probe
        probing = false
        if (status != TextToSpeech.SUCCESS || p == null) {
            GuardCore.diag(ctx, "引擎 ${engineLabel(enginePkg)} 初始化失败")
            probeNextEngine()
            return
        }
        if (!tryChinese(p)) {
            try { p.shutdown() } catch (e: Throwable) {}
            GuardCore.diag(ctx, "引擎 ${engineLabel(enginePkg)} 没有中文语音包")
            probeNextEngine()
            return
        }
        // 这个引擎有中文 → 换用它并永久记住，下次不再探测
        try { tts?.shutdown() } catch (e: Throwable) {}
        tts = p
        ttsReady = true
        ttsInitTried = true
        ttsSettled = true
        prefs.edit().putString("ttsEngine", enginePkg).apply()
        ttsInfo = "引擎 ${engineLabel(enginePkg)} · 中文可用"
        attachUtterListener(p)
        GuardCore.diag(ctx, "语音引擎就绪：$ttsInfo（已记住）")
        if (testPending) { testPending = false; speak(TEST_SENTENCE) }
    }

    /** 没有任何引擎带中文包：唯一剩下的办法是去系统设置装语音数据（这一步没有 API 可替代） */
    private fun openVoiceSettings() {
        try {
            ctx.startActivity(
                Intent("com.android.settings.TTS_SETTINGS")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Toast.makeText(
                ctx,
                "这台手机还没装中文语音包：在打开的页面里选一个引擎 → 点「安装语音数据」，装好回来再点一次测试",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Throwable) {
            Toast.makeText(
                ctx,
                "这台手机没有中文语音引擎，建议安装「讯飞语音引擎+」等免费 TTS 应用后再试",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun speak(text: String) {
        if (!ttsReady) return            // 没引擎/没中文数据：文字讲解照常显示
        try {
            val r = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), UTTER_EXPLAIN)
            if (r == TextToSpeech.ERROR) GuardCore.diag(ctx, "朗读被引擎拒绝（ERROR）")
        } catch (e: Exception) { /* 朗读失败不阻塞跳题 */ }
    }

    /** 家长页自检用：现在到底能不能出声、用的是什么引擎 */
    fun voiceStatus(): String =
        if (ttsReady) "✅ 语音正常（$ttsInfo）" else "❌ $ttsInfo（讲解只能看文字）"

    /** 家长页「测试语音讲解」：初始化/探测是异步的，好了自动读；全失败自动打开系统语音设置 */
    fun testVoice() {
        initTts()
        if (ttsReady) {
            speak(TEST_SENTENCE)
            return
        }
        if (ttsSettled) {
            // 探测已经有结论还是不行 → 直接带家长去装语音包
            openVoiceSettings()
            return
        }
        testPending = true
    }

    private fun playTone(ok: Boolean) {
        val id = if (ok) sndOk else sndNo
        if (id != 0) try {
            soundPool?.play(id, 1f, 1f, 1, 0, 1f)
        } catch (e: Exception) {}
    }
}
