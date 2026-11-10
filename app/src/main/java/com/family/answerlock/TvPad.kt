package com.family.answerlock

import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout

/**
 * 电视遥控器数字键盘。
 *
 * 电视不一定装了输入法（很多电视按 EditText 压根弹不出键盘），
 * 所以所有密码输入框都配一个屏幕键盘：遥控器上下左右移焦点、OK 键按数字。
 * 键位布局和手机九宫格一致：1-9 三行 + 退格 / 0 / 清空。
 *
 * 按键外观用 bg_opt（聚焦 = 亮蓝 + 白描边），焦点在哪儿一目了然。
 */
object TvPad {

    /** xml 里的 maxLength 在代码里拿不到，密码统一按 8 位处理 */
    private const val MAX_LEN = 8

    /**
     * 在 container 里生成键盘，按下的数字追加到 target 输入框。
     * 每次打开面板都重新 attach（同时清掉上次输剩的内容焦点残留）。
     */
    fun attach(container: LinearLayout, target: EditText) {
        container.removeAllViews()
        val ctx = container.context
        val d = ctx.resources.displayMetrics.density
        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("⌫ 删除", "0", "清空")
        )
        for (row in rows) {
            val rl = LinearLayout(ctx)
            rl.orientation = LinearLayout.HORIZONTAL
            for (label in row) {
                val b = Button(ctx)
                b.text = label
                b.textSize = 20f
                b.isAllCaps = false
                b.setBackgroundResource(R.drawable.bg_opt)
                b.setTextColor(0xFFF2F5FA.toInt())
                b.setPadding(0, (10 * d).toInt(), 0, (10 * d).toInt())
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.setMargins((5 * d).toInt(), (5 * d).toInt(), (5 * d).toInt(), (5 * d).toInt())
                b.layoutParams = lp
                b.setOnClickListener {
                    when (label) {
                        "⌫ 删除" -> {
                            val s = target.text.toString()
                            if (s.isNotEmpty()) target.setText(s.dropLast(1))
                        }
                        "清空" -> target.setText("")
                        else -> {
                            if (target.length() < MAX_LEN)
                                target.setText(target.text.toString() + label)
                        }
                    }
                }
                rl.addView(b)
            }
            container.addView(rl)
        }
    }

    /** 把遥控器焦点交给键盘（默认落在最后一行中间的「0」上） */
    fun focusPad(container: LinearLayout) {
        container.post {
            val lastRow = container.getChildAt(container.childCount - 1) as? LinearLayout
            lastRow?.getChildAt(1)?.requestFocus()
        }
    }
}
