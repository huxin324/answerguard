package com.family.answerlock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * 后台守护服务：
 * - 前台服务常驻，家长点「启动守护」后开始计时
 * - 每到弹题间隔，全局弹出答题窗口（QuizActivity，盖在任意 App 上）
 * - 孩子作答期间不消耗玩耍时间；答错由 QuizActivity 负责加题
 * - 今日时长用完 → 弹出锁屏页，需家长密码解锁
 */
class QuizService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences

    private val tick = object : Runnable {
        override fun run() {
            val e = prefs.edit()
            var remaining = prefs.getInt("remaining", 0)
            var next = prefs.getInt("nextQuizIn", 0)
            val quizTime = prefs.getInt("quizTimeSec", 60)

            if (remaining > 0) remaining--
            next--

            when {
                // 今日时间用完 → 锁屏
                remaining <= 0 -> {
                    e.putInt("remaining", 0).putBoolean("locked", true).apply()
                    open("lock")
                }
                // 弹窗被晾在后台太久没人答 → 记一次答错并重新弹到最前
                next <= -(quizTime + 15) -> {
                    e.putInt("pending", prefs.getInt("pending", 0) + prefs.getInt("penalty", 2)).apply()
                    next = quizTime
                    open("quiz")
                }
                // 正常到点 → 弹题（作答期间不重复弹）
                next <= 0 && !quizOpen -> open("quiz")
            }

            e.putInt("remaining", remaining).putInt("nextQuizIn", next).apply()
            handler.postDelayed(this, 1000)
        }
    }

    private fun open(mode: String) {
        if (quizOpen) return
        val i = Intent(this, QuizActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("mode", mode)
        startActivity(i)
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("guard", MODE_PRIVATE)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) {
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 每次启动都重置为全新的一天
        prefs.edit()
            .putInt("remaining", prefs.getInt("dailyMin", 30) * 60)
            .putInt("nextQuizIn", prefs.getInt("intervalSec", 60))
            .putBoolean("locked", false)
            .putInt("pending", 0)
            .putBoolean("guardOn", true)
            .apply()
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        prefs.edit().putBoolean("guardOn", false).apply()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "guard"

        @Volatile
        var quizOpen = false

        fun start(context: android.content.Context) {
            context.startForegroundService(Intent(context, QuizService::class.java))
        }

        fun stop(context: android.content.Context) {
            context.getSharedPreferences("guard", android.content.Context.MODE_PRIVATE)
                .edit().putInt("remaining", 0).putBoolean("locked", false).apply()
            context.stopService(Intent(context, QuizService::class.java))
        }
    }
}
