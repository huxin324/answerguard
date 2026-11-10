package com.family.answerlock

import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout

/**
 * 电视版 26 键（QWERTY）软键盘。
 *
 * 为什么需要它：电视基本没有输入法，遥控器只能上下左右 + OK，
 * 输家长密码时必须有能点的字母键（密码允许数字 + 字母）。
 *
 * 用法：QwertyPad.attach(container) { activeEdit }
 * 多个输入框（原密码 / 新密码 / 确认密码 / 解锁框）共用一个键盘，
 * 由调用方通过焦点决定键帽写进哪个框。
 *
 * 键宽用 weight 均分，容器多宽都能塞下（620dp 最舒服，360dp 也能用）。
 */
object QwertyPad {

    private val ROWS = listOf(
        listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"),
        listOf("A", "S", "D", "F", "G", "H", "J", "K", "L"),
        listOf("Z", "X", "C", "V", "B", "N", "M", "⌫"),
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "清空")
    )

    fun attach(container: LinearLayout, target: () -> EditText?) {
        val ctx = container.context
        container.removeAllViews()

        ROWS.forEach { row ->
            val line = LinearLayout(ctx)
            line.orientation = LinearLayout.HORIZONTAL
            line.gravity = Gravity.CENTER_HORIZONTAL
            container.addView(
                line,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(ctx, 6f) }
            )

            row.forEachIndexed { idx, key ->
                val b = Button(ctx)
                b.text = key
                b.textSize = 17f
                b.isAllCaps = false
                b.setPadding(0, 0, 0, 0)
                b.setTextColor(if (key == "清空") 0xFFFF8A8E.toInt() else 0xFFD6DEEC.toInt())
                b.backgroundTintList = ColorStateList.valueOf(
                    if (key == "⌫" || key == "清空") 0xFF232C42.toInt() else 0xFF1C2740.toInt()
                )
                b.setOnClickListener {
                    val et = target() ?: return@setOnClickListener
                    when (key) {
                        "⌫" -> {
                            val s = et.text.toString()
                            if (s.isNotEmpty()) et.setText(s.substring(0, s.length - 1))
                        }
                        "清空" -> et.setText("")
                        else -> if (et.text.length < 8) et.setText(et.text.toString() + key)
                    }
                    et.setSelection(et.text.length)
                }
                val lp = LinearLayout.LayoutParams(0, dp(ctx, 50f), 1f)
                if (idx != row.lastIndex) lp.marginEnd = dp(ctx, 5f)
                line.addView(b, lp)
            }
        }
    }

    /** 把遥控器焦点给键盘左上角第一个键（弹层刚打开时用） */
    fun focusFirst(container: LinearLayout) {
        val firstRow = container.getChildAt(0) as? LinearLayout ?: return
        (firstRow.getChildAt(0) as? Button)?.requestFocus()
    }

    private fun dp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()
}
