package com.family.answerlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 孩子上划划掉 App 后由 AlarmManager 触发，自动把守护服务重新拉起。
 * 仅在守护处于开启状态（guardOn = true）时才拉起。
 */
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("guard", Context.MODE_PRIVATE)
        if (prefs.getBoolean("guardOn", false) &&
            !prefs.getBoolean("locked", false)
        ) {
            QuizService.start(context)
        }
    }
}
