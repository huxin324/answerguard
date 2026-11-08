package com.family.answerlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 孩子上划划掉 App 后由闹钟触发，自动把守护重新拉起（不重置当天时间）。
 */
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (GuardCore.prefs(context).getBoolean("guardOn", false)) {
            QuizService.resume(context)
        }
    }
}
