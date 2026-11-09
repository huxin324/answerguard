package com.family.answerlock

import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/**
 * 出题库 v2.9：1~9 年级，自动匹配学期进度，每题附带语音讲解文案
 *
 * - 数学：严格按人教版教材各年级「上学期 / 下学期」知识点出题，
 *   每个年级每学期准备了 5~6 种题型随机轮换，数值全部随机生成，题量无限
 * - 学期自动判断：每年 9 月~次年 1 月 = 上学期；2 月~8 月 = 下学期
 *   （例：三年级上学期 → 只出 3 上的内容，绝不出 3 下和没教到的）
 * - 语文：古诗文、文学常识、成语、基础知识，按学段分档（每档 16+ 道固定题）
 * - 英语：词汇与语法，按学段分档（每档 15+ 道固定题）
 * - Q.explain：答错时展示并用语音朗读的讲解
 */
object QuestionBank {

    data class Q(
        val subject: String,      // 科目名（显示用，含年级学期信息）
        val text: String,         // 题干
        val answer: String,       // 正确答案
        val explain: String,      // 错题讲解（答错时显示 + 语音朗读）
        val options: List<String> // 选项（已打乱，含正确答案）
    )

    /* ---------------- 小工具 ---------------- */

    /** 闭区间随机整数 [a, b] */
    private fun rng(a: Int, b: Int): Int = Random.nextInt(b - a + 1) + a

    /** 格式化小数（固定 Locale.US，避免部分地区出逗号小数点） */
    private fun fmtD(x: Double, d: Int): String {
        var s = String.format(Locale.US, "%.${d}f", x)
        if (s.contains('.')) s = s.trimEnd('0').trimEnd('.')   // 去掉无意义的 0
        return s
    }

    /* ---------------- 学期自动匹配 ---------------- */

    /** 1 = 上学期（9月~1月），2 = 下学期（2月~8月） */
    fun currentSemester(): Int {
        val m = Calendar.getInstance().get(Calendar.MONTH) // 0~11
        return if (m >= Calendar.SEPTEMBER || m == Calendar.JANUARY) 1 else 2
    }

    fun semesterLabel(): String = if (currentSemester() == 1) "上学期" else "下学期"

    /* ---------------- 主入口 ---------------- */

    fun make(grade: Int, subjects: List<String>): Q {
        val subs = if (subjects.isEmpty()) listOf("math") else subjects
        return when (subs[Random.nextInt(subs.size)]) {
            "chinese" -> cn(grade)
            "english" -> en(grade)
            else -> math(grade, currentSemester())
        }
    }

    /* ================= 数学：严格按人教版年级+学期 ================= */

    private fun math(grade: Int, sem: Int): Q {
        val label = "数学 · ${grade}年级${if (sem == 1) "上" else "下"}学期"
        val gens: List<(String) -> Q> = when (grade.coerceIn(1, 9)) {
            1 -> if (sem == 1) g1a else g1b
            2 -> if (sem == 1) g2a else g2b
            3 -> if (sem == 1) g3a else g3b
            4 -> if (sem == 1) g4a else g4b
            5 -> if (sem == 1) g5a else g5b
            6 -> if (sem == 1) g6a else g6b
            7 -> if (sem == 1) g7a else g7b
            8 -> if (sem == 1) g8a else g8b
            else -> if (sem == 1) g9a else g9b
        }
        return gens[Random.nextInt(gens.size)](label)
    }

    /* ---- 1 上：10/20 以内加减法、比大小、数的组成、找规律、认识钟表 ---- */
    private val g1a: List<(String) -> Q> = listOf(
        { label ->                                       // 10 以内加法
            val a = rng(1, 8); val b = rng(1, 10 - a)
            Q(label, "$a + $b = ?", (a + b).toString(),
                "从 $a 往后再数 $b 个，正好数到 ${a + b}。加法就是越加越多。",
                numOptions(a + b))
        },
        { label ->                                       // 10 以内减法
            val x = rng(3, 10); val y = rng(1, x - 1)
            Q(label, "$x − $y = ?", (x - y).toString(),
                "从 $x 里面拿走 $y 个，还剩 ${x - y} 个。减法就是从总数里去掉一部分。",
                numOptions(x - y))
        },
        { label ->                                       // 20 以内进位加（凑十法）
            val a = rng(5, 9); val b = rng(3, 9)
            Q(label, "$a + $b = ?", (a + b).toString(),
                "凑十法：把 $b 分成 ${10 - a} 和 ${b - (10 - a)}，先凑成 10，再加 ${b - (10 - a)}，得 ${a + b}。",
                numOptions(a + b))
        },
        { label ->                                       // 比大小
            val x = rng(1, 9); val y = rng(1, 9)
            val sym = if (x > y) ">" else if (x < y) "<" else "="
            val hint = if (x > y) "$x 比 $y 大" else if (x < y) "$x 比 $y 小" else "两个数一样大"
            Q(label, "在圆圈里填上 >、< 或 =：$x ○ $y", sym,
                "$hint，所以填 $sym。", listOf(">", "<", "=").shuffled())
        },
        { label ->                                       // 数的组成
            val u = rng(2, 9)
            Q(label, "1 个十和 $u 个一合起来是？", (10 + u).toString(),
                "1 个十是 10，再加 $u 个一，就是 10 + $u = ${10 + u}。",
                numOptions(10 + u))
        },
        { label ->                                       // 找规律（每次 +2）
            val s = rng(1, 3)
            Q(label, "找规律填数：$s、${s + 2}、${s + 4}、${s + 6}、？", (s + 8).toString(),
                "每个数都比前一个多 2，所以 ${s + 6} + 2 = ${s + 8}。",
                numOptions(s + 8))
        },
        { label ->                                       // 认识整时
            val h = rng(1, 12)
            Q(label, "钟面上分针指向 12，时针指向 $h，这是几时？", h.toString(),
                "分针指向 12 是整时，时针指向几就是几时，所以是 $h 时。",
                numOptions(h))
        }
    )

    /* ---- 1 下：100 以内加减、人民币、找规律、比较大小 ---- */
    private val g1b: List<(String) -> Q> = listOf(
        { label ->                                       // 100 以内加法
            val a = rng(11, 79); val b = rng(11, 20)
            Q(label, "$a + $b = ?", (a + b).toString(),
                "个位加个位、十位加十位，满十向十位进一。$a + $b = ${a + b}。",
                numOptions(a + b))
        },
        { label ->                                       // 100 以内减法
            val x = rng(35, 98); val y = rng(12, 30)
            Q(label, "$x − $y = ?", (x - y).toString(),
                "个位不够减就向十位借一当十再减。$x − $y = ${x - y}。",
                numOptions(x - y))
        },
        { label ->                                       // 人民币换算
            if (Random.nextBoolean()) {
                Q(label, "1 元 = ？角", "10",
                    "元、角、分之间的关系：1 元 = 10 角，1 角 = 10 分。",
                    numOptions(10))
            } else {
                val j = rng(2, 9)
                Q(label, "$j 角 = ？分", (j * 10).toString(),
                    "1 角 = 10 分，所以 $j 角 = $j × 10 = ${j * 10} 分。",
                    numOptions(j * 10))
            }
        },
        { label ->                                       // 找规律（每次 +5）
            val s = rng(2, 5)
            Q(label, "找规律填数：$s、${s + 5}、${s + 10}、${s + 15}、？", (s + 20).toString(),
                "每个数都比前一个多 5，所以 ${s + 15} + 5 = ${s + 20}。",
                numOptions(s + 20))
        },
        { label ->                                       // 比较大小
            var x = rng(20, 90); var y = rng(20, 90)
            while (x == y) y = rng(20, 90)
            val sym = if (x > y) ">" else "<"
            Q(label, "在圆圈里填上 > 或 <：$x ○ $y", sym,
                "先比十位：${x / 10} 和 ${y / 10}，${if (x > y) "$x 的十位大" else "$y 的十位大"}，所以填 $sym。",
                listOf(">", "<", "=").shuffled())
        },
        { label ->                                       // 数的拆分（整十数）
            val t = rng(2, 8)
            Q(label, "$t 个十是多少？", (t * 10).toString(),
                "1 个十是 10，$t 个十就是 $t × 10 = ${t * 10}。",
                numOptions(t * 10))
        }
    )

    /* ---- 2 上：乘法口诀、进位加、长度单位、认识时间、连加 ---- */
    private val g2a: List<(String) -> Q> = listOf(
        { label ->                                       // 乘法口诀
            val a = rng(2, 9); val b = rng(2, 9)
            Q(label, "$a × $b = ?", (a * b).toString(),
                "想乘法口诀：$a 乘 $b 等于 ${a * b}。乘法就是相同的数连加，$a × $b 就是 $b 个 $a 相加。",
                numOptions(a * b))
        },
        { label ->                                       // 100 以内进位加
            val a = rng(28, 75); val b = rng(16, 24)
            Q(label, "$a + $b = ?", (a + b).toString(),
                "个位满十向十位进一。$a + $b = ${a + b}。",
                numOptions(a + b))
        },
        { label ->                                       // 长度单位
            if (Random.nextBoolean()) {
                Q(label, "1 米 = ？厘米", "100",
                    "米和厘米都是长度单位，1 米 = 100 厘米。",
                    numOptions(100))
            } else {
                val cm = rng(2, 9) * 10
                Q(label, "$cm 厘米 = ？分米", (cm / 10).toString(),
                    "1 分米 = 10 厘米，所以 $cm 厘米 = $cm ÷ 10 = ${cm / 10} 分米。",
                    numOptions(cm / 10))
            }
        },
        { label ->                                       // 认识时间
            if (Random.nextBoolean()) {
                Q(label, "1 时 = ？分", "60",
                    "钟面上有 12 个大格、60 个小格，时针走 1 大格，分针正好走一圈，所以 1 时 = 60 分。",
                    numOptions(60))
            } else {
                val m = rng(1, 3) * 5
                Q(label, "分针从 12 走到 ${m / 5}，走了几分？", m.toString(),
                    "分针走 1 大格是 5 分，走到 ${m / 5} 就是走了 ${m / 5} 大格，$m ÷ 5 = ${m / 5}…即 ${m / 5} × 5 = $m 分。",
                    numOptions(m))
            }
        },
        { label ->                                       // 连加
            val a = rng(10, 40); val b = rng(10, 30); val c = rng(5, 20)
            Q(label, "$a + $b + $c = ?", (a + b + c).toString(),
                "从左往右依次算：$a + $b = ${a + b}，再加 $c 得 ${a + b + c}。",
                numOptions(a + b + c))
        }
    )

    /* ---- 2 下：表内除法、有余数除法、平均分、克与千克、万以内数 ---- */
    private val g2b: List<(String) -> Q> = listOf(
        { label ->                                       // 表内除法
            val b = rng(2, 9); val q = rng(2, 9)
            Q(label, "${b * q} ÷ $b = ?", q.toString(),
                "想乘法口诀：$b 乘几等于 ${b * q}？$b × $q = ${b * q}，所以商是 $q。",
                numOptions(q))
        },
        { label ->                                       // 有余数除法（余数）
            val b = rng(4, 7); val q = rng(1, 8); val r = rng(1, b - 1)
            val a = b * q + r
            val opts = mutableSetOf(r.toString())
            for (d in listOf(r + 1, r - 1, r + 2, r - 2, r + 3, r - 3, r + 4, r - 4)) {
                if (opts.size >= 4) break
                if (d in 0 until b) opts.add(d.toString())
            }
            Q(label, "$a ÷ $b 的余数是？", r.toString(),
                "$a ÷ $b = $q 余 $r（因为 $b × $q = ${b * q}，$a − ${b * q} = $r）。注意余数一定要比除数小。",
                opts.shuffled())
        },
        { label ->                                       // 平均分
            val b = rng(3, 6); val q = rng(3, 9)
            Q(label, "把 ${b * q} 个橘子平均分给 $b 个小朋友，每人分几个？", q.toString(),
                "平均分就是每人分得一样多：${b * q} ÷ $b = $q，每人分 $q 个。",
                numOptions(q))
        },
        { label ->                                       // 克与千克
            if (Random.nextBoolean()) {
                Q(label, "1 千克 = ？克", "1000",
                    "1 千克 = 1000 克。千克用来称较重的物体，克用来称较轻的物体。",
                    numOptions(1000))
            } else {
                Q(label, "一个苹果大约重 200（　），括号里该填什么单位？", "克",
                    "克是较轻物体的单位。一个苹果大约 200 克；如果是 200 千克就比大人还重了，不合理。",
                    listOf("克", "千克", "米", "厘米").shuffled())
            }
        },
        { label ->                                       // 万以内数
            if (Random.nextBoolean()) {
                Q(label, "比 999 大 1 的数是多少？", "1000",
                    "999 再添 1 就要向前进位，变成 1000，它是四位数中最小的数。",
                    numOptions(1000))
            } else {
                val h = rng(2, 9)
                Q(label, "$h 个一百是多少？", (h * 100).toString(),
                    "$h 个一百就是 $h × 100 = ${h * 100}。",
                    numOptions(h * 100))
            }
        }
    )

    /* ---- 3 上：多位数乘一位数、时分秒、万以内加减、分数初步、周长、倍的认识 ---- */
    private val g3a: List<(String) -> Q> = listOf(
        { label ->                                       // 多位数乘一位数
            val a = rng(102, 399); val b = rng(2, 9)
            Q(label, "$a × $b = ?", (a * b).toString(),
                "用 $b 依次去乘 $a 的个位、十位、百位，满几十就向前一位进几，结果是 ${a * b}。",
                numOptions(a * b))
        },
        { label ->                                       // 时分秒
            if (Random.nextBoolean()) {
                Q(label, "1 分 = ？秒", "60",
                    "秒针走一小格是 1 秒，走一圈是 60 秒，正好分针走一小格，所以 1 分 = 60 秒。",
                    numOptions(60))
            } else {
                Q(label, "1 时 = ？分", "60",
                    "时针走一大格是 1 时，分针同时走一圈是 60 分，所以 1 时 = 60 分。",
                    numOptions(60))
            }
        },
        { label ->                                       // 万以内加减
            val x = rng(32, 88) * 10; val y = rng(21, 79) * 10
            if (Random.nextBoolean()) {
                Q(label, "$x + $y = ?", (x + y).toString(),
                    "相同数位对齐，从个位算起，满十进一。$x + $y = ${x + y}。",
                    numOptions(x + y))
            } else {
                val (a, b) = if (x >= y) x to y else y to x
                Q(label, "$a − $b = ?", (a - b).toString(),
                    "相同数位对齐，不够减就向前一位借一。$a − $b = ${a - b}。",
                    numOptions(a - b))
            }
        },
        { label ->                                       // 分数的初步认识
            val d = rng(4, 8); val n = rng(1, d - 1)
            Q(label, "把一个西瓜平均分成 $d 份，吃了其中 $n 份，吃了这个西瓜的几分之几？", "$n/$d",
                "平均分成 $d 份，每份就是这个西瓜的 $d 分之一；吃了 $n 份，就是 $d 分之 $n，写作 $n/$d。",
                fractionOptions(n, d))
        },
        { label ->                                       // 长方形周长
            val l = rng(5, 12); val w = rng(3, l - 1)
            val p = 2 * (l + w)
            Q(label, "长方形长 $l 厘米、宽 $w 厘米，周长是多少厘米？", p.toString(),
                "长方形周长 =（长 + 宽）× 2 =（$l + $w）× 2 = ${l + w} × 2 = $p 厘米。",
                numOptions(p))
        },
        { label ->                                       // 倍的认识
            val s = rng(2, 6); val k = rng(2, 4)
            Q(label, "苹果有 ${s * k} 个，是梨的 $k 倍，梨有多少个？", s.toString(),
                "苹果是梨的 $k 倍，说明 苹果 ÷ $k = 梨：${s * k} ÷ $k = $s，梨有 $s 个。",
                numOptions(s))
        }
    )

    /* ---- 3 下：两位数乘两位数、除数一位数、面积、年月日、小数初步、方向 ---- */
    private val g3b: List<(String) -> Q> = listOf(
        { label ->                                       // 两位数乘两位数
            val a = rng(20, 79); val b = rng(12, 49)
            Q(label, "$a × $b = ?", (a * b).toString(),
                "先算 $a × ${b % 10}（个位），再算 $a × ${b / 10}（十位），错开一位相加，得 ${a * b}。",
                numOptions(a * b))
        },
        { label ->                                       // 除数是一位数的除法
            val b = rng(2, 9); val q = rng(12, 40)
            Q(label, "${b * q} ÷ $b = ?", q.toString(),
                "从最高位除起：${b * q} ÷ $b，每次除得的余数要比除数小，商是 $q。",
                numOptions(q))
        },
        { label ->                                       // 面积
            if (Random.nextBoolean()) {
                val l = rng(4, 9); val w = rng(2, l - 1)
                Q(label, "长方形长 $l 米、宽 $w 米，面积是多少平方米？", (l * w).toString(),
                    "长方形面积 = 长 × 宽 = $l × $w = ${l * w} 平方米。注意面积和周长是两个不同的量。",
                    numOptions(l * w))
            } else {
                Q(label, "1 平方米 = ？平方分米", "100",
                    "面积单位之间的进率是 100：1 平方米 = 100 平方分米，1 平方分米 = 100 平方厘米。",
                    numOptions(100))
            }
        },
        { label ->                                       // 年月日
            val pool = listOf(
                listOf("一年有几个月？", "12", listOf("12", "10", "11", "13"),
                    "一年有 12 个月：1、3、5、7、8、10、12 月是大月 31 天；4、6、9、11 月是小月 30 天。"),
                listOf("平年的二月有多少天？", "28", listOf("28", "29", "30", "31"),
                    "平年二月 28 天、全年 365 天；闰年二月 29 天、全年 366 天。四年一闰。"),
                listOf("一天有多少小时？", "24", listOf("24", "12", "20", "30"),
                    "钟面上时针走两圈正好是一天，所以一天 = 24 小时。"),
                listOf("闰年的二月有多少天？", "29", listOf("29", "28", "30", "31"),
                    "闰年的二月有 29 天，全年 366 天。年份是 4 的倍数一般是闰年（整百年要能被 400 整除）。")
            )
            val t = pool[Random.nextInt(pool.size)]
            Q(label, t[0] as String, t[1] as String, t[3] as String, (t[2] as List<String>).shuffled())
        },
        { label ->                                       // 小数的初步认识（以元为单位）
            val j = rng(1, 9); val f = rng(1, 9)
            val ansS = "$j.$f"
            val opts = mutableSetOf(ansS)
            for (c in listOf("$j.${if (f < 9) f + 1 else f - 1}", "${j + 1}.$f", "${max(1, j - 1)}.$f", "$j.5")) {
                if (opts.size >= 4) break
                opts.add(c)
            }
            Q(label, "$j 元 $f 角 = ？元", ansS,
                "1 角 = 0.1 元，$f 角就是 0.$f 元，所以 $j 元 $f 角 = $ansS 元。",
                opts.shuffled())
        },
        { label ->                                       // 位置与方向
            val pool = listOf(
                listOf("早晨面对太阳升起的方向，你的前面是哪个方向？", "东",
                    listOf("东", "西", "南", "北"), "太阳从东边升起，早晨面向太阳就是面向东，后面是西。"),
                listOf("面向北时，你的左边是哪个方向？", "西",
                    listOf("西", "东", "南", "北"), "上北下南、左西右东：面向北时，左边是西，右边是东。"),
                listOf("南的相反方向是？", "北",
                    listOf("北", "南", "东", "西"), "方向是两两相对的：东对西、南对北。")
            )
            val t = pool[Random.nextInt(pool.size)]
            Q(label, t[0] as String, t[1] as String, t[3] as String, (t[2] as List<String>).shuffled())
        }
    )

    /* ---- 4 上：三位数乘两位数、除数两位数、角的度量、大数的认识、平行四边形 ---- */
    private val g4a: List<(String) -> Q> = listOf(
        { label ->                                       // 三位数乘两位数
            val a = rng(102, 799); val b = rng(12, 89)
            Q(label, "$a × $b = ?", (a * b).toString(),
                "先算 $a × ${b % 10}（个位），再算 $a × ${b / 10}（十位），错开一位相加得 ${a * b}。",
                numOptions(a * b))
        },
        { label ->                                       // 除数是两位数的除法
            val b = rng(11, 30); val q = rng(4, 25)
            Q(label, "${b * q} ÷ $b = ?", q.toString(),
                "把 $b 看成接近的整十数来试商：${b * q} ÷ $b = $q，除到哪一位商就写在哪一位上。",
                numOptions(q))
        },
        { label ->                                       // 角的度量
            val pool = listOf(
                listOf("1 个直角是多少度？", "90", listOf("90", "180", "60", "45"),
                    "大于 0° 小于 90° 的角是锐角，正好 90° 的是直角。"),
                listOf("1 个平角是多少度？", "180", listOf("180", "90", "360", "120"),
                    "角的两边成一条直线时是平角，1 平角 = 180° = 2 个直角。"),
                listOf("1 个周角是多少度？", "360", listOf("360", "180", "90", "270"),
                    "一条射线绕端点转一整圈回到原处是周角，1 周角 = 360° = 2 个平角 = 4 个直角。")
            )
            val t = pool[Random.nextInt(pool.size)]
            Q(label, t[0] as String, t[1] as String, t[3] as String, (t[2] as List<String>).shuffled())
        },
        { label ->                                       // 大数的认识
            if (Random.nextBoolean()) {
                Q(label, "10 个一万是多少？", "十万",
                    "每相邻两个计数单位之间的进率都是 10：10 个一万是十万，10 个十万是一百万。",
                    listOf("十万", "一百万", "一万", "一千").shuffled())
            } else {
                val w = rng(2, 9); val q = rng(1, 9)
                val n = w * 10000 + q * 1000
                Q(label, "一个数由 $w 个万和 $q 个千组成，这个数是多少？", n.toString(),
                    "$w 个万是 ${w * 10000}，$q 个千是 ${q * 1000}，合起来是 $n。",
                    numOptions(n))
            }
        },
        { label ->                                       // 平行四边形与梯形
            Q(label, "平行四边形有几组对边分别平行？", "2",
                "平行四边形两组对边分别平行且相等；梯形只有一组对边平行。",
                numOptions(2))
        }
    )

    /* ---- 4 下：运算定律、小数加减、三角形内角和、平均数、鸡兔同笼 ---- */
    private val g4b: List<(String) -> Q> = listOf(
        { label ->                                       // 乘法分配律
            val a = rng(2, 9); val b = rng(2, 9); val c = rng(2, 9)
            val rev = Random.nextBoolean()
            val text = if (rev) "$a × $b + $a × $c = ?" else "$a × ($b + $c) = ?"
            Q(label, text, (a * (b + c)).toString(),
                "乘法分配律：a × b + a × c = a × (b + c)。先算 ${b} + $c = ${b + c}，再乘 $a，得 ${a * (b + c)}。",
                numOptions(a * (b + c)))
        },
        { label ->                                       // 小数加法
            val a = rng(12, 95); val b = rng(12, 95)
            val ans = (a + b) / 10.0
            Q(label, "${fmtD(a / 10.0, 1)} + ${fmtD(b / 10.0, 1)} = ?", fmtD(ans, 1),
                "小数加法要小数点对齐（相同数位对齐），再从低位算起。结果是 ${fmtD(ans, 1)}。",
                decOptions(ans, 1))
        },
        { label ->                                       // 小数减法
            var a = rng(25, 95); var b = rng(12, 95)
            if (b > a) { val t = a; a = b; b = t }
            val ans = (a - b) / 10.0
            Q(label, "${fmtD(a / 10.0, 1)} − ${fmtD(b / 10.0, 1)} = ?", fmtD(ans, 1),
                "小数减法同样小数点对齐，不够减向前一位借一。结果是 ${fmtD(ans, 1)}。",
                decOptions(ans, 1))
        },
        { label ->                                       // 三角形内角和
            val a = rng(35, 75); val b = rng(35, minOf(100, 175 - a))
            Q(label, "三角形中两个角分别是 $a° 和 $b°，第三个角是多少度？", (180 - a - b).toString(),
                "三角形三个内角的和是 180°：180 − $a − $b = ${180 - a - b} 度。",
                numOptions(180 - a - b))
        },
        { label ->                                       // 平均数
            val m = rng(75, 95); val d1 = rng(1, 5); val d2 = rng(1, 5)
            val a = m - d1; val b = m + d2; val c = m + d1 - d2
            Q(label, "$a、$b、$c 三个数的平均数是多少？", m.toString(),
                "先求总数：$a + $b + $c = ${a + b + c}，再除以 3：${a + b + c} ÷ 3 = $m。",
                numOptions(m))
        },
        { label ->                                       // 鸡兔同笼
            val h = rng(6, 10); val r = rng(1, h - 1)
            val feet = 2 * (h - r) + 4 * r
            Q(label, "鸡兔同笼，共有 $h 个头、$feet 只脚，兔子有几只？", r.toString(),
                "假设全是鸡，脚应有 ${2 * h} 只，多出 $feet − ${2 * h} = ${feet - 2 * h} 只脚；每把一只鸡换成兔多 2 只脚，所以兔有 ${feet - 2 * h} ÷ 2 = $r 只。",
                numOptions(r))
        }
    )

    /* ---- 5 上：简易方程、小数乘法、小数除法、多边形面积、植树问题 ---- */
    private val g5a: List<(String) -> Q> = listOf(
        { label ->                                       // 简易方程（ax+b=c）
            val k = rng(2, 7); val ans = rng(2, 10); val c = rng(5, 30)
            if (Random.nextBoolean()) {
                Q(label, "解方程：${k}x + $c = ${k * ans + c}，x = ?", ans.toString(),
                    "两边同时减 $c：${k}x = ${k * ans}；再两边同时除以 $k，得 x = $ans。",
                    numOptions(ans))
            } else {
                Q(label, "解方程：${k}x − $c = ${k * ans - c}，x = ?", ans.toString(),
                    "两边同时加 $c：${k}x = ${k * ans}；再两边同时除以 $k，得 x = $ans。",
                    numOptions(ans))
            }
        },
        { label ->                                       // 小数乘法
            val t = rng(11, 95); val m = rng(2, 9)
            val ans = t * m / 10.0
            Q(label, "${fmtD(t / 10.0, 1)} × $m = ?", fmtD(ans, 1),
                "先按整数算 $t × $m = ${t * m}，两个因数里有一位小数，所以积是一位小数：${fmtD(ans, 1)}。",
                decOptions(ans, 1))
        },
        { label ->                                       // 小数除法
            val k = rng(11, 95); val d = rng(2, 9)
            val dividend = fmtD(k * d / 10.0, 1)
            val ans = fmtD(k / 10.0, 1)
            Q(label, "$dividend ÷ $d = ?", ans,
                "除数是整数的小数除法按整数除法算，商的小数点要和被除数的小数点对齐，结果是 $ans。",
                decOptions(k / 10.0, 1))
        },
        { label ->                                       // 多边形面积
            if (Random.nextBoolean()) {
                val b = 2 * rng(3, 12); val h = rng(2, 15)
                Q(label, "三角形底 $b 厘米、高 $h 厘米，面积是多少平方厘米？", (b * h / 2).toString(),
                    "三角形面积 = 底 × 高 ÷ 2 = $b × $h ÷ 2 = ${b * h} ÷ 2 = ${b * h / 2} 平方厘米。",
                    numOptions(b * h / 2))
            } else {
                val b = rng(3, 15); val h = rng(2, 12)
                Q(label, "平行四边形底 $b 米、高 $h 米，面积是多少平方米？", (b * h).toString(),
                    "平行四边形面积 = 底 × 高 = $b × $h = ${b * h} 平方米。",
                    numOptions(b * h))
            }
        },
        { label ->                                       // 植树问题（两端都栽）
            val step = rng(4, 8); val k = rng(5, 20)
            val len = step * k
            Q(label, "一条路长 $len 米，每隔 $step 米栽一棵树（两端都栽），一共要栽多少棵？", (k + 1).toString(),
                "两端都栽时，棵数 = 间隔数 + 1。间隔数 = $len ÷ $step = $k，所以要栽 $k + 1 = ${k + 1} 棵。",
                numOptions(k + 1))
        }
    )

    /* ---- 5 下：同分母/异分母分数、因数与倍数、长方体体积、体积单位 ---- */
    private val g5b: List<(String) -> Q> = listOf(
        { label ->                                       // 同分母分数加减
            val d = rng(4, 8)
            val a = rng(1, d - 1)
            var b = rng(1, d - 1)
            val plus = Random.nextBoolean()
            if (!plus) { while (b == a) b = rng(1, d - 1) }
            val p = if (plus) a + b else abs(a - b)
            val g0 = gcd(p, d)
            Q(label, "$a/$d ${if (plus) "+" else "−"} $b/$d = ?", "${p / g0}/${d / g0}",
                "同分母分数相加减，分母不变，只把分子相加减：${if (plus) "$a + $b = $p" else "$a − $b = $p"}，得 $p/$d，约分后是 ${p / g0}/${d / g0}。",
                fractionOptions(p / g0, d / g0))
        },
        { label ->                                       // 异分母分数加减
            val pool = listOf(
                listOf("1/2 + 1/3 = ?", "5/6", listOf("5/6", "2/5", "1/6", "2/3"),
                    "先通分：1/2 = 3/6，1/3 = 2/6，3/6 + 2/6 = 5/6。异分母分数相加减要先通分。"),
                listOf("1/2 + 1/4 = ?", "3/4", listOf("3/4", "2/6", "1/8", "2/4"),
                    "通分：1/2 = 2/4，2/4 + 1/4 = 3/4。"),
                listOf("1/2 − 1/3 = ?", "1/6", listOf("1/6", "1/5", "2/3", "1/2"),
                    "通分：1/2 = 3/6，1/3 = 2/6，3/6 − 2/6 = 1/6。"),
                listOf("3/4 − 1/2 = ?", "1/4", listOf("1/4", "2/4", "1/2", "1/8"),
                    "通分：1/2 = 2/4，3/4 − 2/4 = 1/4。"),
                listOf("1/3 + 1/6 = ?", "1/2", listOf("1/2", "2/9", "1/9", "2/6"),
                    "通分：1/3 = 2/6，2/6 + 1/6 = 3/6 = 1/2（约分后）。"),
                listOf("5/6 − 1/3 = ?", "1/2", listOf("1/2", "4/6", "2/3", "1/3"),
                    "通分：1/3 = 2/6，5/6 − 2/6 = 3/6 = 1/2。")
            )
            val t = pool[Random.nextInt(pool.size)]
            Q(label, t[0] as String, t[1] as String, t[3] as String, (t[2] as List<String>).shuffled())
        },
        { label ->                                       // 因数与倍数
            val pool = listOf(
                listOf("36 的最大因数是多少？", "36",
                    listOf("36", "18", "12", "9"),
                    "一个数最大的因数是它本身：36 的因数有 1、2、3、4、6、9、12、18、36，最大是 36。"),
                listOf("36 的最小倍数是多少？", "36",
                    listOf("36", "1", "18", "72"),
                    "一个数最小的倍数是它本身，没有最大的倍数。"),
                listOf("20 以内最大的质数是多少？", "19",
                    listOf("19", "17", "13", "11"),
                    "质数只有 1 和它本身两个因数。20 以内的质数：2、3、5、7、11、13、17、19，最大是 19。注意 1 不是质数也不是合数。"),
                listOf("既是 2 的倍数又是 5 的倍数的最小两位数是多少？", "10",
                    listOf("10", "15", "20", "25"),
                    "2 的倍数个位是 0、2、4、6、8；5 的倍数个位是 0 或 5。两者共同的个位只能是 0，最小两位数是 10。")
            )
            val t = pool[Random.nextInt(pool.size)]
            Q(label, t[0] as String, t[1] as String, t[3] as String, (t[2] as List<String>).shuffled())
        },
        { label ->                                       // 长方体/正方体体积
            if (Random.nextBoolean()) {
                val l = rng(3, 8); val w = rng(2, 6); val h = rng(2, 5)
                Q(label, "长方体长 $l 厘米、宽 $w 厘米、高 $h 厘米，体积是多少立方厘米？", (l * w * h).toString(),
                    "长方体体积 = 长 × 宽 × 高 = $l × $w × $h = ${l * w * h} 立方厘米。",
                    numOptions(l * w * h))
            } else {
                val a = rng(2, 6)
                Q(label, "正方体棱长 $a 分米，体积是多少立方分米？", (a * a * a).toString(),
                    "正方体体积 = 棱长 × 棱长 × 棱长 = $a × $a × $a = ${a * a * a} 立方分米。",
                    numOptions(a * a * a))
            }
        },
        { label ->                                       // 体积/容积单位
            if (Random.nextBoolean()) {
                Q(label, "1 立方分米 = ？立方厘米", "1000",
                    "相邻体积单位之间的进率是 1000：1 立方分米 = 1000 立方厘米。",
                    numOptions(1000))
            } else {
                Q(label, "1 升 = ？毫升", "1000",
                    "1 升 = 1000 毫升；1 升 = 1 立方分米，1 毫升 = 1 立方厘米。",
                    numOptions(1000))
            }
        }
    )

    /* ---- 6 上：百分数/比、分数乘法、分数除法、圆 ---- */
    private val g6a: List<(String) -> Q> = listOf(
        { label ->                                       // 百分数
            val base = rng(2, 20) * 10
            val pct = listOf(10, 20, 50)[rng(0, 2)]
            Q(label, "$base 的 $pct% 是多少？", (base * pct / 100).toString(),
                "求一个数的百分之几用乘法：$base × $pct% = $base × ${fmtD(pct / 100.0, 2)} = ${base * pct / 100}。",
                numOptions(base * pct / 100))
        },
        { label ->                                       // 比的应用
            val r1 = rng(1, 3); val r2 = r1 + rng(1, 3); val k = rng(2, 9)
            Q(label, "甲∶乙 = $r1∶$r2，已知乙是 ${r2 * k}，甲是多少？", (r1 * k).toString(),
                "乙占 $r2 份、是 ${r2 * k}，每份是 $k；甲占 $r1 份，所以甲 = $r1 × $k = ${r1 * k}。",
                numOptions(r1 * k))
        },
        { label ->                                       // 分数乘法（结果为整数）
            val d = rng(2, 5); val k = rng(2, 6); val a = rng(1, d - 1)
            Q(label, "$a/$d × ${k * d} = ?", (a * k).toString(),
                "分数乘整数：${k * d} × $a/$d，先用 ${k * d} ÷ $d = $k（约分），再乘 $a 得 ${a * k}。",
                numOptions(a * k))
        },
        { label ->                                       // 分数除法（除以分数）
            val d = rng(2, 5); val n = rng(2, 6)
            Q(label, "$n ÷ 1/$d = ?", (n * d).toString(),
                "除以一个分数等于乘它的倒数：$n ÷ 1/$d = $n × $d = ${n * d}。",
                numOptions(n * d))
        },
        { label ->                                       // 圆的周长/面积
            val r = rng(2, 9)
            if (Random.nextBoolean()) {
                val c = 6.28 * r
                Q(label, "圆的半径是 $r，周长是多少（π 取 3.14）？", fmtD(c, 2),
                    "圆的周长 = 2 × π × r = 2 × 3.14 × $r = ${fmtD(c, 2)}。",
                    decOptions(c, 2))
            } else {
                val s = 3.14 * r * r
                Q(label, "圆的半径是 $r，面积是多少（π 取 3.14）？", fmtD(s, 2),
                    "圆的面积 = π × r² = 3.14 × ${r * r} = ${fmtD(s, 2)}。",
                    decOptions(s, 2))
            }
        }
    )

    /* ---- 6 下：负数、圆柱圆锥、解比例、折扣 ---- */
    private val g6b: List<(String) -> Q> = listOf(
        { label ->                                       // 负数运算
            val a = rng(2, 12); val b = rng(2, 9)
            if (Random.nextBoolean()) {
                Q(label, "(−$a) × $b = ?", (-a * b).toString(),
                    "负数乘正数得负数：$a × $b = ${a * b}，再加负号得 ${-a * b}。口诀：负正得负。",
                    numOptions(-a * b))
            } else {
                Q(label, "(−$a) + $b = ?", (b - a).toString(),
                    "异号两数相加，取绝对值较大的符号：$b − $a = ${b - a}，结果为 ${b - a}。",
                    numOptions(b - a))
            }
        },
        { label ->                                       // 圆柱体积
            val r = rng(1, 4); val h = rng(3, 10)
            val v = 3.14 * r * r * h
            Q(label, "圆柱底面半径 $r、高 $h，体积是多少（π 取 3.14）？", fmtD(v, 2),
                "圆柱体积 = 底面积 × 高 = 3.14 × ${r * r} × $h = ${fmtD(v, 2)}。",
                decOptions(v, 2))
        },
        { label ->                                       // 圆锥与圆柱的关系
            Q(label, "等底等高的圆锥体积是圆柱体积的几分之几？", "1/3",
                "圆锥体积 = 等底等高圆柱体积 × 1/3，所以圆锥体积是圆柱的三分之一。",
                listOf("1/3", "1/2", "2/3", "1/4").shuffled())
        },
        { label ->                                       // 解比例
            val a = rng(2, 5); val b = rng(2, 6); val k = rng(2, 6)
            Q(label, "解比例：$a∶$b = ${a * k}∶x，x = ?", (b * k).toString(),
                "比例的基本性质：两内项之积等于两外项之积。$a × x = $b × ${a * k}，x = ${b * a * k} ÷ $a = ${b * k}。",
                numOptions(b * k))
        },
        { label ->                                       // 折扣
            val base = rng(4, 20) * 10
            val disc = listOf(7, 8, 9)[rng(0, 2)]
            Q(label, "一件衣服原价 $base 元，打${if (disc == 8) "八" else if (disc == 7) "七" else "九"}折出售，现价多少元？", (base * disc / 10).toString(),
                "打${disc}折就是按原价的 ${disc}0% 出售：$base × ${fmtD(disc / 10.0, 1)} = ${base * disc / 10} 元。",
                numOptions(base * disc / 10))
        }
    )

    /* ---- 7 上：有理数、一元一次方程、绝对值、乘方、合并同类项、科学记数法 ---- */
    private val g7a: List<(String) -> Q> = listOf(
        { label ->                                       // 一元一次方程
            val k = rng(2, 7); val ans = rng(2, 10); val c = rng(5, 30)
            Q(label, "解方程：${k}x + $c = ${k * ans + c}，x = ?", ans.toString(),
                "移项要变号：${k}x = ${k * ans + c} − $c = ${k * ans}，两边除以 $k，得 x = $ans。",
                numOptions(ans))
        },
        { label ->                                       // 有理数加减
            val a = rng(3, 15); val b = rng(3, 15)
            Q(label, "(−$a) + $b = ?", (b - a).toString(),
                "异号两数相加，取绝对值较大的数的符号：$b − $a = ${b - a}，结果是 ${b - a}。",
                numOptions(b - a))
        },
        { label ->                                       // 绝对值
            val n = rng(3, 12)
            if (Random.nextBoolean()) {
                Q(label, "|−$n| = ?", n.toString(),
                    "绝对值表示到原点的距离，距离不可能是负数，所以 |−$n| = $n。",
                    numOptions(n))
            } else {
                val a = rng(5, 12); val b = rng(1, a - 1)
                Q(label, "|−$a| − $b = ?", (a - b).toString(),
                    "先算绝对值：|−$a| = $a，再减 $b 得 ${a - b}。",
                    numOptions(a - b))
            }
        },
        { label ->                                       // 乘方
            val a = rng(2, 7)
            if (Random.nextBoolean()) {
                Q(label, "(−$a)² = ?", (a * a).toString(),
                    "负数的偶次幂是正数：(−$a)² = $a × $a = ${a * a}。负负得正。",
                    numOptions(a * a))
            } else {
                Q(label, "(−$a)³ = ?", (-a * a * a).toString(),
                    "负数的奇次幂是负数：(−$a)³ = ${-a * a * a}。负负得正、负负负得负。",
                    numOptions(-a * a * a))
            }
        },
        { label ->                                       // 合并同类项
            val a = rng(2, 9); val b = rng(2, 9)
            Q(label, "合并同类项：${a}x + ${b}x 后，x 的系数是多少？", (a + b).toString(),
                "合并同类项只把系数相加、字母和指数不变：$a + $b = ${a + b}，结果是 ${a + b}x。",
                numOptions(a + b))
        },
        { label ->                                       // 科学记数法
            val pairs = listOf(listOf("2.5 × 10³", "2500"), listOf("3.5 × 10³", "3500"),
                listOf("6.2 × 10³", "6200"), listOf("1.2 × 10⁴", "12000"), listOf("5.0 × 10²", "500"))
            val t = pairs[Random.nextInt(pairs.size)]
            Q(label, "${t[0]} = ?", t[1],
                "科学记数法 a × 10ⁿ：10³ 就是把小数点向右移 3 位，10⁴ 移 4 位，10² 移 2 位。",
                numOptions(t[1].toInt()))
        }
    )

    /* ---- 7 下：平方根、对顶角补角、二元一次方程组、不等式、平行线 ---- */
    private val g7b: List<(String) -> Q> = listOf(
        { label ->                                       // 平方根
            val n = rng(2, 15)
            Q(label, "√${n * n} = ?", n.toString(),
                "因为 $n × $n = ${n * n}，所以 ${n * n} 的算术平方根是 $n。平方和开平方互为逆运算。",
                numOptions(n))
        },
        { label ->                                       // 对顶角/补角/余角
            val pool = listOf(
                listOf("两条直线相交，其中一个角是 40°，它的对顶角是多少度？", "40",
                    listOf("40", "50", "130", "140"),
                    "对顶角相等：两条直线相交产生的相对的两个角相等，所以也是 40°。"),
                listOf("一个角是 130°，它的补角是多少度？", "50",
                    listOf("50", "60", "40", "130"),
                    "两角之和为 180° 互为补角：180 − 130 = 50 度。"),
                listOf("一个角是 65°，它的余角是多少度？", "25",
                    listOf("25", "35", "115", "125"),
                    "两角之和为 90° 互为余角：90 − 65 = 25 度。")
            )
            val t = pool[Random.nextInt(pool.size)]
            Q(label, t[0] as String, t[1] as String, t[3] as String, (t[2] as List<String>).shuffled())
        },
        { label ->                                       // 二元一次方程组
            val x = rng(3, 9); val y = rng(1, x - 1)
            Q(label, "解方程组：x + y = ${x + y}，x − y = ${x - y}，x = ?", x.toString(),
                "两式相加：2x = ${(x + y) + (x - y)}，所以 x = ${((x + y) + (x - y)) / 2}，x = $x。",
                numOptions(x))
        },
        { label ->                                       // 一元一次不等式
            val m = rng(5, 20); val c = rng(2, 9)
            Q(label, "解不等式：x + $c > ${m + c}，解集是 x > ?", m.toString(),
                "两边同时减 $c：x > ${m + c} − $c = $m。注意不等式两边加减同一个数，不等号方向不变。",
                numOptions(m))
        },
        { label ->                                       // 平行线的性质
            val a = rng(35, 80)
            Q(label, "两直线平行，∠1 = $a°，与 ∠1 是同位角的 ∠2 = ?", a.toString(),
                "两直线平行，同位角相等、内错角相等、同旁内角互补。同位角相等，所以 ∠2 = $a°。",
                numOptions(a))
        }
    )

    /* ---- 8 上：幂的运算、平方差、完全平方、三角形内角、分式有意义 ---- */
    private val g8a: List<(String) -> Q> = listOf(
        { label ->                                       // 同底数幂
            val base = listOf(2, 3, 5)[rng(0, 2)]
            if (Random.nextBoolean()) {
                val m = rng(2, 5); val n = rng(2, 5)
                Q(label, "$base 的 $m 次方 × $base 的 $n 次方 = $base 的几次方？", (m + n).toString(),
                    "同底数幂相乘，底数不变、指数相加：$m + $n = ${m + n}，所以是 $base 的 ${m + n} 次方。",
                    numOptions(m + n))
            } else {
                val m = rng(2, 5)
                Q(label, "($base 的 $m 次方) 的 2 次方 = $base 的几次方？", (m * 2).toString(),
                    "幂的乘方，底数不变、指数相乘：$m × 2 = ${m * 2}，所以是 $base 的 ${m * 2} 次方。",
                    numOptions(m * 2))
            }
        },
        { label ->                                       // 平方差公式
            val k = rng(2, 9)
            Q(label, "(x + $k)(x − $k) = x² − ?", (k * k).toString(),
                "平方差公式：(a + b)(a − b) = a² − b²。这里 b = $k，所以减去 $k² = ${k * k}。",
                numOptions(k * k))
        },
        { label ->                                       // 完全平方公式
            val k = rng(2, 9)
            Q(label, "(x + $k)² = x² + ${2 * k}x + ?", (k * k).toString(),
                "完全平方公式：(a + b)² = a² + 2ab + b²。这里 2ab = ${2 * k}x，b² = ${k * k}。",
                numOptions(k * k))
        },
        { label ->                                       // 三角形内角和
            val a = rng(35, 75); val b = rng(35, minOf(100, 175 - a))
            Q(label, "三角形两个内角是 $a° 和 $b°，第三个内角是多少度？", (180 - a - b).toString(),
                "三角形内角和是 180°：180 − $a − $b = ${180 - a - b} 度。",
                numOptions(180 - a - b))
        },
        { label ->                                       // 分式有意义/值为零
            val k = rng(2, 9)
            if (Random.nextBoolean()) {
                Q(label, "分式 1/(x − $k) 有意义时，x 应满足什么条件？", "x ≠ $k",
                    "分母不能为零：x − $k ≠ 0，即 x ≠ $k。",
                    listOf("x ≠ $k", "x ≠ −$k", "x ≠ 0", "x ≠ 1").shuffled())
            } else {
                Q(label, "分式 (x − $k)/(x + 1) 的值为零时，x = ?", k.toString(),
                    "分式值为零要同时满足：分子为零、分母不为零。x − $k = 0 得 x = $k，此时分母 $k + 1 ≠ 0，成立。",
                    numOptions(k))
            }
        }
    )

    /* ---- 8 下：勾股定理、一次函数、二次根式、数据分析、平移 ---- */
    private val g8b: List<(String) -> Q> = listOf(
        { label ->                                       // 勾股定理
            val k = rng(1, 6)
            Q(label, "直角三角形两直角边为 ${3 * k} 和 ${4 * k}，斜边长是多少？", (5 * k).toString(),
                "勾股定理：斜边² = ${3 * k}² + ${4 * k}² = ${9 * k * k} + ${16 * k * k} = ${25 * k * k}，开方得 ${5 * k}。这是勾股数 3、4、5 的倍数。",
                numOptions(5 * k))
        },
        { label ->                                       // 一次函数求值
            val k = rng(2, 6); val b = rng(1, 9); val x = rng(2, 8)
            Q(label, "一次函数 y = ${k}x + $b，当 x = $x 时，y = ?", (k * x + b).toString(),
                "把 x 代进去算：y = $k × $x + $b = ${k * x} + $b = ${k * x + b}。",
                numOptions(k * x + b))
        },
        { label ->                                       // 二次根式化简求值
            val a = rng(2, 6); val b = rng(2, 6)
            Q(label, "√${a * a} + √${b * b} = ?", (a + b).toString(),
                "√${a * a} = $a，√${b * b} = $b，所以 ${a} + $b = ${a + b}。先化简再相加。",
                numOptions(a + b))
        },
        { label ->                                       // 数据分析（中位数/众数）
            val m = rng(20, 60)
            if (Random.nextBoolean()) {
                Q(label, "数据 ${m - 6}、${m - 3}、$m、${m + 4}、${m + 7} 的中位数是多少？", m.toString(),
                    "把数据从小到大排列后，正中间那个数是中位数，这里正好是 $m。",
                    numOptions(m))
            } else {
                val x = rng(1, 9); val z = rng(10, 19)
                Q(label, "数据 $x、$m、$z、$m、${m + 3} 的众数是多少？", m.toString(),
                    "出现次数最多的数叫众数：$m 出现了 2 次，其他数各出现 1 次，所以众数是 $m。",
                    numOptions(m))
            }
        },
        { label ->                                       // 一次函数平移
            val k = rng(2, 5); val b = rng(1, 6)
            Q(label, "直线 y = ${k}x 由 y = ${k}x + $b 怎样平移得到？", "向下平移 $b 个单位",
                "加上 $b 是向上平移 $b 个单位；反过来由 y = ${k}x + $b 变成 y = ${k}x，就是减去 $b，即向下平移 $b 个单位。上加下减。",
                listOf("向下平移 $b 个单位", "向上平移 $b 个单位", "向左平移 $b 个单位", "向右平移 $b 个单位").shuffled())
        }
    )

    /* ---- 9 上：一元二次方程、二次函数、圆、概率 ---- */
    private val g9a: List<(String) -> Q> = listOf(
        { label ->                                       // 一元二次方程（十字相乘）
            val r = rng(2, 8); val s = rng(2, 8)
            val p = -(r + s); val q = r * s
            Q(label, "方程 x² ${term(p, "x")}${term(q, "")} = 0 的较大根是多少？", max(r, s).toString(),
                "十字相乘分解：两个根的积是 $q、和是 ${-p}，正好是 $r 和 $s，所以较大根是 ${max(r, s)}。",
                numOptions(max(r, s)))
        },
        { label ->                                       // 二次函数对称轴
            val b = 2 * rng(1, 6); val c = rng(1, 9)
            Q(label, "抛物线 y = x² − ${b}x + $c 的对称轴是 x = ?", (b / 2).toString(),
                "对称轴 x = −b/2a = −(−$b)/2 = ${b / 2}。",
                numOptions(b / 2))
        },
        { label ->                                       // 二次函数顶点式
            val k = rng(1, 6); val m = rng(1, 8)
            Q(label, "抛物线 y = (x − $k)² + $m 的顶点横坐标是多少？", k.toString(),
                "顶点式 y = (x − h)² + m 的顶点是（$k，$m），横坐标是 $k。",
                numOptions(k))
        },
        { label ->                                       // 圆的周长/弧长
            if (Random.nextBoolean()) {
                val r = rng(2, 9)
                val c = 6.28 * r
                Q(label, "圆的半径为 $r，周长是多少（π 取 3.14）？", fmtD(c, 2),
                    "圆的周长 C = 2πr = 2 × 3.14 × $r = ${fmtD(c, 2)}。",
                    decOptions(c, 2))
            } else {
                val k = rng(1, 5)
                val l = 3.14 * k
                Q(label, "半径为 ${3 * k} 的圆中，60° 圆心角所对的弧长是多少（π 取 3.14）？", fmtD(l, 2),
                    "弧长 = n/360 × 2πr = 60/360 × 2 × 3.14 × ${3 * k} = 3.14 × $k = ${fmtD(l, 2)}。",
                    decOptions(l, 2))
            }
        },
        { label ->                                       // 概率
            val pool = listOf(
                listOf("掷一枚均匀的硬币，正面朝上的概率是多少？", "1/2",
                    listOf("1/2", "1/3", "1/4", "2/3"),
                    "硬币只有正、反两种等可能结果，正面占 1 种，概率 = 1/2。"),
                listOf("掷一枚均匀的骰子，掷出偶数的概率是多少？", "1/2",
                    listOf("1/2", "1/3", "1/6", "2/3"),
                    "骰子有 6 种等可能结果，偶数是 2、4、6 共 3 种，概率 = 3/6 = 1/2。"),
                listOf("掷一枚均匀的骰子，掷出大于 4 的点数的概率是多少？", "1/3",
                    listOf("1/3", "1/2", "1/6", "2/3"),
                    "大于 4 的点数是 5、6 两种，概率 = 2/6 = 1/3。"),
                listOf("袋中有 3 个红球、2 个白球（除颜色外相同），摸到红球的概率是多少？", "3/5",
                    listOf("3/5", "2/5", "1/2", "3/2"),
                    "共有 5 个球，红球 3 个，摸到红球的概率 = 3/5。")
            )
            val t = pool[Random.nextInt(pool.size)]
            Q(label, t[0] as String, t[1] as String, t[3] as String, (t[2] as List<String>).shuffled())
        }
    )

    /* ---- 9 下：反比例函数、锐角三角函数、相似三角形、解直角三角形 ---- */
    private val g9b: List<(String) -> Q> = listOf(
        { label ->                                       // 反比例函数
            val x = rng(2, 7); val y = rng(2, 7)
            Q(label, "反比例函数 y = k/x 的图象经过点 ($x, $y)，k = ?", (x * y).toString(),
                "把点代入：$y = k ÷ $x，所以 k = $x × $y = ${x * y}。反比例函数里 k 恒等于横纵坐标之积。",
                numOptions(x * y))
        },
        { label ->                                       // 特殊角三角函数
            val t = triBank[Random.nextInt(triBank.size)]
            Q(label, t[0] as String, t[1] as String, t[3] as String, (t[2] as List<String>).shuffled())
        },
        { label ->                                       // 相似三角形
            val pool = listOf(
                listOf("两个相似三角形的相似比是 1∶2，它们的周长比是多少？", "1∶2",
                    listOf("1∶2", "1∶4", "2∶1", "1∶3"),
                    "相似三角形的周长比等于相似比，面积比等于相似比的平方。所以周长比是 1∶2。"),
                listOf("两个相似三角形的相似比是 1∶2，它们的面积比是多少？", "1∶4",
                    listOf("1∶4", "1∶2", "1∶8", "2∶4"),
                    "相似三角形的面积比等于相似比的平方：(1/2)² = 1/4，所以面积比是 1∶4。"),
                listOf("两个相似三角形的相似比是 2∶3，它们的周长比是多少？", "2∶3",
                    listOf("2∶3", "4∶9", "3∶2", "2∶9"),
                    "周长比等于相似比，直接就是 2∶3。")
            )
            val t = pool[Random.nextInt(pool.size)]
            Q(label, t[0] as String, t[1] as String, t[3] as String, (t[2] as List<String>).shuffled())
        },
        { label ->                                       // 解直角三角形（特殊角反推）
            val pool = listOf(
                listOf("在直角三角形中，∠A 的正弦 sinA = 1/2，∠A = ?度", "30",
                    listOf("30", "45", "60", "90"),
                    "特殊角：sin 30° = 1/2，所以 ∠A = 30°。"),
                listOf("在直角三角形中，∠A 的正切 tanA = 1，∠A = ?度", "45",
                    listOf("45", "30", "60", "90"),
                    "tan 45° = 1（等腰直角三角形两直角边相等），所以 ∠A = 45°。"),
                listOf("在直角三角形中，∠A 的余弦 cosA = 1/2，∠A = ?度", "60",
                    listOf("60", "30", "45", "90"),
                    "cos 60° = 1/2（互余关系 cos 60° = sin 30°），所以 ∠A = 60°。")
            )
            val t = pool[Random.nextInt(pool.size)]
            Q(label, t[0] as String, t[1] as String, t[3] as String, (t[2] as List<String>).shuffled())
        }
    )

    /** 拼接 " + 5x" / " − 3" 形式的项 */
    private fun term(v: Int, suffix: String): String = when {
        v >= 0 -> " + $v$suffix"
        else -> " − ${-v}$suffix"
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    /** 整数答案的四选一干扰项 */
    private fun numOptions(ans: Int): List<String> {
        val set = mutableSetOf(ans)
        var step = 1
        while (set.size < 4) {
            val delta = (Random.nextInt(maxOf(3, abs(ans) / 3 + 1)) + step) * (if (Random.nextBoolean()) 1 else -1)
            set.add(ans + delta)
            step++
        }
        return set.map { it.toString() }.shuffled()
    }

    /** 小数答案的四选一干扰项 */
    private fun decOptions(ans: Double, decimals: Int): List<String> {
        val set = mutableSetOf(fmtD(ans, decimals))
        val unit = if (decimals == 1) 0.1 else 0.01
        for (mul in listOf(1, -1, 2, -2, 5, -5, 10)) {
            if (set.size >= 4) break
            val cand = ans + unit * mul
            if (cand > 0) set.add(fmtD(cand, decimals))
        }
        return set.shuffled()
    }

    /** 同分母分数答案的四选一干扰项 */
    private fun fractionOptions(p: Int, q: Int): List<String> {
        val set = mutableSetOf("$p/$q")
        val candidates = listOf("${p + 1}/$q", "${p.coerceAtLeast(1) + 2}/$q",
            "$p/${q + 1}", "${p + 1}/${q + 1}", "${p + 2}/$q", "${max(1, p - 1)}/$q")
        for (c in candidates) { if (set.size >= 4) break; set.add(c) }
        return set.shuffled()
    }

    /** 锐角三角函数特殊值：题干 / 答案 / 选项 / 讲解 */
    private val triBank = listOf(
        listOf("sin 30° = ?", "1/2", listOf("1/2", "√3/2", "√2/2", "1"), "这是特殊角的三角函数值：sin 30° 恒等于二分之一，建议直接背下来。"),
        listOf("cos 60° = ?", "1/2", listOf("1/2", "√3/2", "√2/2", "1"), "互余关系：cos 60° = sin 30° = 二分之一。"),
        listOf("sin 45° = ?", "√2/2", listOf("√2/2", "1/2", "√3/2", "1"), "等腰直角三角形中，45° 的正弦值是二分之根号二。"),
        listOf("tan 45° = ?", "1", listOf("1", "1/2", "√3/3", "0"), "45° 的正切值恒等于 1，因为等腰直角三角形两条直角边相等。"),
        listOf("sin 60° = ?", "√3/2", listOf("√3/2", "√2/2", "1/2", "1"), "特殊角：sin 60° 等于二分之根号三。"),
        listOf("tan 30° = ?", "√3/3", listOf("√3/3", "1/2", "√3/2", "1"), "特殊角：tan 30° 等于三分之根号三。")
    )

    /* ================= 语文：按学段（题干/答案/选项/讲解） ================= */

    private val cnBand1 = listOf( // 1~2 年级
        listOf("「床前明月光」的下一句是？", "疑是地上霜", listOf("疑是地上霜", "低头思故乡", "举头望明月", "处处闻啼鸟"), "《静夜思》：床前明月光，疑是地上霜。这句把月光比作了地上的白霜。"),
        listOf("「白毛浮绿水」的下一句是？", "红掌拨清波", listOf("红掌拨清波", "曲项向天歌", "拔清波", "浮绿水"), "《咏鹅》的顺序是：曲项向天歌、白毛浮绿水、红掌拨清波。按鹅的动作顺序记。"),
        listOf("《静夜思》的作者是？", "李白", listOf("李白", "杜甫", "白居易", "王维"), "《静夜思》是唐代诗人李白写的，李白被称为「诗仙」。"),
        listOf("一年四季，排在第一位的是？", "春天", listOf("春天", "夏天", "秋天", "冬天"), "四季的顺序是春、夏、秋、冬，春天是一年的开始，冰雪融化、万物发芽。"),
        listOf("「春眠不觉晓」出自哪首诗？", "《春晓》", listOf("《春晓》", "《咏鹅》", "《悯农》", "《静夜思》"), "「春眠不觉晓，处处闻啼鸟」出自孟浩然的《春晓》，写的是春天早晨的景色。"),
        listOf("「谁知盘中餐」的下一句是？", "粒粒皆辛苦", listOf("粒粒皆辛苦", "汗滴禾下土", "春种一粒粟", "秋收万颗子"), "《悯农》：谁知盘中餐，粒粒皆辛苦。提醒我们要爱惜粮食。"),
        listOf("《咏鹅》的作者是？", "骆宾王", listOf("骆宾王", "李白", "孟浩然", "王之涣"), "《咏鹅》是唐代诗人骆宾王小时候写的，他七岁就会作诗了。"),
        listOf("「白日依山尽」的下一句是？", "黄河入海流", listOf("黄河入海流", "更上一层楼", "欲穷千里目", "万里尽朝晖"), "王之涣《登鹳雀楼》：白日依山尽，黄河入海流。写的是傍晚登楼望远的景色。"),
        listOf("「举头望明月」的下一句是？", "低头思故乡", listOf("低头思故乡", "疑是地上霜", "床前明月光", "明月照我还"), "《静夜思》：举头望明月，低头思故乡。抬头看月、低头想家，一抬头一低头写出了思乡之情。"),
        listOf("「大」的反义词是？", "小", listOf("小", "高", "长", "多"), "反义词意思相反：大对小、高对矮、长对短、多对少。"),
        listOf("《悯农》的作者是？", "李绅", listOf("李绅", "李白", "骆宾王", "孟浩然"), "《悯农》是唐代诗人李绅写的，诗名「悯」就是同情农民的意思。"),
        listOf("「远看山有色」出自哪首诗？", "《画》", listOf("《画》", "《春晓》", "《画鸡》", "《小池》"), "《画》：远看山有色，近听水无声。全诗都在说一幅画的特点：看着有山，却听不到水声。"),
        listOf("下列哪个字是「日」加一笔变成的？", "目", listOf("目", "口", "山", "木"), "「日」加一横变成「目」，目就是眼睛的意思。写字时注意笔画变化。"),
        listOf("《春晓》的作者是？", "孟浩然", listOf("孟浩然", "李白", "王维", "杜甫"), "《春晓》是唐代山水田园诗人孟浩然的作品，他和王维并称「王孟」。"),
        listOf("「小时不识月」的下一句是？", "呼作白玉盘", listOf("呼作白玉盘", "又疑瑶台镜", "飞在青云端", "仙人垂两足"), "李白《古朗月行》：小时不识月，呼作白玉盘。把月亮比作白玉盘，写出了孩子的天真。"),
        listOf("下列哪一组都是表示动物的词？", "猫、狗、鸟", listOf("猫、狗、鸟", "跑、跳、飞", "红、蓝、绿", "一、二、三"), "猫、狗、鸟都是动物；跑跳飞是动作，红蓝绿是颜色，一二三是数字。分类要抓特点。")
    )

    private val cnBand2 = listOf( // 3~4 年级
        listOf("「独在异乡为异客」的下一句是？", "每逢佳节倍思亲", listOf("每逢佳节倍思亲", "遍插茱萸少一人", "遥知兄弟登高处", "冷露无声湿桂花"), "王维《九月九日忆山东兄弟》：独自在外做客，每到过节就加倍思念亲人。"),
        listOf("「不知细叶谁裁出」的下一句是？", "二月春风似剪刀", listOf("二月春风似剪刀", "万条垂下绿丝绦", "碧玉妆成一树高", "小荷才露尖尖角"), "贺知章《咏柳》：不知细叶谁裁出，二月春风似剪刀。把春风比作剪刀。"),
        listOf("《赠汪伦》的作者是？", "李白", listOf("李白", "王维", "孟浩然", "杜牧"), "「桃花潭水深千尺，不及汪伦送我情」出自李白的《赠汪伦》，写的是朋友间的情谊。"),
        listOf("成语「画蛇添足」的意思是？", "多此一举，反而坏事", listOf("多此一举，反而坏事", "画画非常好", "跑得很快", "非常喜欢小动物"), "画蛇添足：蛇画好了又给它添上脚，比喻做多余的事反而弄巧成拙。"),
        listOf("「但愿人长久」的下一句是？", "千里共婵娟", listOf("千里共婵娟", "把酒问青天", "月有阴晴圆缺", "高处不胜寒"), "苏轼《水调歌头》：但愿人长久，千里共婵娟。婵娟指月亮，意思是隔千里也能共赏一轮明月。"),
        listOf("「小荷才露尖尖角」写的是哪个季节？", "夏天", listOf("夏天", "春天", "秋天", "冬天"), "「小荷才露尖尖角，早有蜻蜓立上头」写的是初夏的池塘，荷花刚冒出尖角。"),
        listOf("《望庐山瀑布》的作者是？", "李白", listOf("李白", "杜甫", "白居易", "苏轼"), "《望庐山瀑布》：飞流直下三千尺，疑是银河落九天。李白的诗充满想象力。"),
        listOf("「飞流直下三千尺」的下一句是？", "疑是银河落九天", listOf("疑是银河落九天", "日照香炉生紫烟", "遥看瀑布挂前川", "两岸猿声啼不住"), "李白把瀑布比作从天上落下来的银河，夸张又浪漫。"),
        listOf("「两个黄鹂鸣翠柳」的下一句是？", "一行白鹭上青天", listOf("一行白鹭上青天", "窗含西岭千秋雪", "门泊东吴万里船", "黄梅时节家家雨"), "杜甫《绝句》：两个黄鹂鸣翠柳，一行白鹭上青天。黄对白、翠柳对青天，对仗非常工整。"),
        listOf("「遥知不是雪」的下一句是？", "为有暗香来", listOf("为有暗香来", "凌寒独自开", "墙角数枝梅", "时有疏钟度"), "王安石《梅花》：遥知不是雪，为有暗香来。远远就知道那不是雪，因为闻到了淡淡的梅香。"),
        listOf("「欲穷千里目」的下一句是？", "更上一层楼", listOf("更上一层楼", "黄河入海流", "白日依山尽", "一览众山小"), "《登鹳雀楼》：欲穷千里目，更上一层楼。想看得更远就要站得更高，常用来自我激励。"),
        listOf("「不识庐山真面目」的下一句是？", "只缘身在此山中", listOf("只缘身在此山中", "远近高低各不同", "横看成岭侧成峰", "不废江河万古流"), "苏轼《题西林壁》：不识庐山真面目，只缘身在此山中。身在山中反而看不清全貌，蕴含「当局者迷」的道理。"),
        listOf("成语「守株待兔」讽刺的是哪种人？", "妄想不劳而获的人", listOf("妄想不劳而获的人", "非常勤劳的人", "善于打猎的人", "爱护动物的人"), "守株待兔：农夫偶然捡到撞树的兔子后就不再干活，天天等兔子。讽刺死守经验、妄想不劳而获的人。"),
        listOf("《题西林壁》的作者是？", "苏轼", listOf("苏轼", "李白", "王安石", "陆游"), "《题西林壁》是苏轼游览庐山时写在西林寺墙壁上的诗。"),
        listOf("《望天门山》的作者是？", "李白", listOf("李白", "杜甫", "白居易", "王昌龄"), "《望天门山》：天门中断楚江开，碧水东流至此回。是李白乘船经过天门山时所写。"),
        listOf("「停车坐爱枫林晚」写的是哪个季节？", "秋天", listOf("秋天", "春天", "夏天", "冬天"), "杜牧《山行》：停车坐爱枫林晚，霜叶红于二月花。枫叶经霜变红，写的是深秋。"),
        listOf("「天门中断楚江开」中的「楚江」指的是？", "长江", listOf("长江", "黄河", "淮河", "珠江"), "天门山在今天的安徽，古时属楚国地界，所以流经这里的长江被称为楚江。")
    )

    private val cnBand3 = listOf( // 5~6 年级
        listOf("「春蚕到死丝方尽」的下一句是？", "蜡炬成灰泪始干", listOf("蜡炬成灰泪始干", "一枝红杏出墙来", "病树前头万木春", "化作春泥更护花"), "李商隐《无题》：春蚕到死丝方尽，蜡炬成灰泪始干。常用来赞美无私奉献的人。"),
        listOf("「人生自古谁无死」的下一句是？", "留取丹心照汗青", listOf("留取丹心照汗青", "零落成泥碾作尘", "要留清白在人间", "一片冰心在玉壶"), "文天祥《过零丁洋》：人生自古谁无死，留取丹心照汗青。表达以死报国的决心。"),
        listOf("《西游记》的作者是？", "吴承恩", listOf("吴承恩", "罗贯中", "施耐庵", "曹雪芹"), "《西游记》是明代吴承恩写的，讲唐僧师徒四人西天取经的故事。四大名著要记牢作者。"),
        listOf("成语「卧薪尝胆」的主人公是？", "勾践", listOf("勾践", "刘备", "项羽", "岳飞"), "越王勾践战败后睡柴草、尝苦胆，提醒自己不忘耻辱，最终复国。形容刻苦自励。"),
        listOf("「落霞与孤鹜齐飞」的下一句是？", "秋水共长天一色", listOf("秋水共长天一色", "烟波江上使人愁", "芳草萋萋鹦鹉洲", "晴川历历汉阳树"), "王勃《滕王阁序》名句：晚霞与野鸭一起飞，秋水和长天连成一片。"),
        listOf("「粉骨碎身浑不怕」的下一句是？", "要留清白在人间", listOf("要留清白在人间", "千磨万击还坚劲", "任尔东西南北风", "只留清气满乾坤"), "于谦《石灰吟》：即使粉身碎骨也不害怕，只要把清白留在人间。借石灰言志。"),
        listOf("「王师北定中原日」的下一句是？", "家祭无忘告乃翁", listOf("家祭无忘告乃翁", "但悲不见九州同", "死去元知万事空", "铁马冰河入梦来"), "陆游《示儿》：王师北定中原日，家祭无忘告乃翁。临终还惦记着国家统一，感人至深。"),
        listOf("「落红不是无情物」的下一句是？", "化作春泥更护花", listOf("化作春泥更护花", "蜡炬成灰泪始干", "病树前头万木春", "零落成泥碾作尘"), "龚自珍《己亥杂诗》：落红不是无情物，化作春泥更护花。落花化为春泥仍要护花，表达奉献精神。"),
        listOf("「欲把西湖比西子」的下一句是？", "淡妆浓抹总相宜", listOf("淡妆浓抹总相宜", "水光潋滟晴方好", "山色空蒙雨亦奇", "最爱湖东行不足"), "苏轼《饮湖上初晴后雨》：把西湖比作美女西施，无论淡妆还是浓妆都好看。"),
        listOf("《泊船瓜洲》中「春风又绿江南岸」的下一句是？", "明月何时照我还", listOf("明月何时照我还", "京口瓜洲一水间", "钟山只隔数重山", "明月几时有"), "王安石《泊船瓜洲》：春风又绿江南岸，明月何时照我还？一个「绿」字把春天写活了。"),
        listOf("《枫桥夜泊》的作者是？", "张继", listOf("张继", "杜甫", "孟浩然", "刘禹锡"), "《枫桥夜泊》：月落乌啼霜满天，江枫渔火对愁眠。写的是秋天夜泊枫桥的愁绪。"),
        listOf("「月落乌啼霜满天」的下一句是？", "江枫渔火对愁眠", listOf("江枫渔火对愁眠", "姑苏城外寒山寺", "夜半钟声到客船", "落霞与孤鹜齐飞"), "《枫桥夜泊》首句：月落、乌啼、霜满天，三个景象叠在一起，写出秋夜的清冷。"),
        listOf("成语「负荆请罪」的主人公是？", "廉颇", listOf("廉颇", "蔺相如", "勾践", "韩信"), "廉颇背着荆条向蔺相如请罪，两人从此结为生死之交。形容主动认错、诚恳道歉。"),
        listOf("《三国演义》的作者是？", "罗贯中", listOf("罗贯中", "吴承恩", "施耐庵", "曹雪芹"), "《三国演义》是元末明初罗贯中所写，讲魏蜀吴三国争霸的故事。"),
        listOf("「爆竹声中一岁除」的下一句是？", "春风送暖入屠苏", listOf("春风送暖入屠苏", "千门万户曈曈日", "总把新桃换旧符", "每逢佳节倍思亲"), "王安石《元日》：爆竹声中一岁除，春风送暖入屠苏。写的是春节的热闹景象。"),
        listOf("《示儿》的作者是？", "陆游", listOf("陆游", "苏轼", "辛弃疾", "王安石"), "《示儿》是南宋诗人陆游写给儿子的临终诗，一心盼望朝廷收复中原。"),
        listOf("「谁知盘中餐，粒粒皆辛苦」告诉我们要？", "珍惜粮食", listOf("珍惜粮食", "多种庄稼", "早睡早起", "尊敬老师"), "每一粒粮食都是农民辛辛苦苦种出来的，这句诗提醒我们爱惜粮食、不浪费。")
    )

    private val cnBand4 = listOf( // 7~9 年级（初中）
        listOf("《出师表》的作者是？", "诸葛亮", listOf("诸葛亮", "司马迁", "曹操", "欧阳修"), "《出师表》是三国时期诸葛亮北伐前写给后主刘禅的表文，提出「亲贤臣，远小人」的建议。"),
        listOf("「海内存知己」的下一句是？", "天涯若比邻", listOf("天涯若比邻", "儿女共沾巾", "无为在歧路", "同是宦游人"), "王勃《送杜少府之任蜀州》：四海之内有知己，天涯也像近邻。是送别诗中的旷达名句。"),
        listOf("「会当凌绝顶」的下一句是？", "一览众山小", listOf("一览众山小", "造化钟神秀", "荡胸生曾云", "阴阳割昏晓"), "杜甫《望岳》：一定要登上泰山顶峰，俯瞰群山。表达攀登人生高峰的豪情。"),
        listOf("《岳阳楼记》的作者是？", "范仲淹", listOf("范仲淹", "欧阳修", "苏轼", "柳宗元"), "范仲淹《岳阳楼记》名句：先天下之忧而忧，后天下之乐而乐。"),
        listOf("「长风破浪会有时」的下一句是？", "直挂云帆济沧海", listOf("直挂云帆济沧海", "拔剑四顾心茫然", "多歧路，今安在", "忽复乘舟梦日边"), "李白《行路难》：相信总有一天能乘风破浪，扬起高帆渡过沧海。表达乐观自信。"),
        listOf("「问君能有几多愁」的下一句是？", "恰似一江春水向东流", listOf("恰似一江春水向东流", "故国不堪回首月明中", "雕栏玉砌应犹在", "只是朱颜改"), "李煜《虞美人》：愁绪就像一江春水滚滚东流。把无形的愁写得有形了。"),
        listOf("《陋室铭》的作者是？", "刘禹锡", listOf("刘禹锡", "韩愈", "周敦颐", "陶渊明"), "刘禹锡《陋室铭》名句：斯是陋室，惟吾德馨。屋子虽然简陋，但品德高尚就不觉得简陋。"),
        listOf("「了却君王天下事」的下一句是？", "赢得生前身后名", listOf("赢得生前身后名", "梦回吹角连营", "沙场秋点兵", "可怜白发生"), "辛弃疾《破阵子》：完成君王统一天下的大业，赢得生前死后的美名。"),
        listOf("「忽如一夜春风来」的下一句是？", "千树万树梨花开", listOf("千树万树梨花开", "散入珠帘湿罗幕", "狐裘不暖锦衾薄", "瀚海阑干百丈冰"), "岑参《白雪歌送武判官归京》：把雪压枝头比作一夜开放的梨花，是咏雪的千古名句。"),
        listOf("「沉舟侧畔千帆过」的下一句是？", "病树前头万木春", listOf("病树前头万木春", "到乡翻似烂柯人", "巴山楚水凄凉地", "暂凭杯酒长精神"), "刘禹锡《酬乐天扬州初逢席上见赠》：沉船旁边千帆竞发，病树前头万木争春。蕴含新事物必将取代旧事物的哲理。"),
        listOf("《爱莲说》的作者是？", "周敦颐", listOf("周敦颐", "陶渊明", "刘禹锡", "柳宗元"), "《爱莲说》名句：出淤泥而不染，濯清涟而不妖。赞美莲花高洁的品格。"),
        listOf("「采菊东篱下」的下一句是？", "悠然见南山", listOf("悠然见南山", "心远地自偏", "问君何能尔", "飞鸟相与还"), "陶渊明《饮酒（其五）》：在东篱下采菊，无意中抬头看见南山，写出闲适自得的心境。"),
        listOf("「老骥伏枥」的下一句是？", "志在千里", listOf("志在千里", "壮心不已", "烈士暮年", "以观沧海"), "曹操《龟虽寿》：老骥伏枥，志在千里；烈士暮年，壮心不已。老马虽伏在马槽，仍想驰骋千里。"),
        listOf("《记承天寺夜游》的作者是？", "苏轼", listOf("苏轼", "张怀民", "柳宗元", "欧阳修"), "苏轼夜游承天寺，写下「庭下如积水空明」的名句，把月光比作积水。"),
        listOf("《天净沙·秋思》中「夕阳西下」的下一句是？", "断肠人在天涯", listOf("断肠人在天涯", "小桥流水人家", "古道西风瘦马", "枯藤老树昏鸦"), "马致远《天净沙·秋思》：夕阳西下，断肠人在天涯。被称为「秋思之祖」。"),
        listOf("「潮平两岸阔」的下一句是？", "风正一帆悬", listOf("风正一帆悬", "海日生残夜", "客路青山外", "江春入旧年"), "王湾《次北固山下》：潮水平坦两岸开阔，顺风正好高挂一帆。"),
        listOf("李清照的号是？", "易安居士", listOf("易安居士", "青莲居士", "香山居士", "东坡居士"), "李清照号易安居士，宋代婉约词派代表；李白号青莲居士，白居易号香山居士，苏轼号东坡居士。"),
        listOf("《小石潭记》的作者是？", "柳宗元", listOf("柳宗元", "苏轼", "范仲淹", "欧阳修"), "柳宗元《小石潭记》是「永州八记」之一，写潭水清澈、游鱼灵动。")
    )

    private fun cn(grade: Int): Q {
        val bank = when {
            grade <= 2 -> cnBand1
            grade <= 4 -> cnBand2
            grade <= 6 -> cnBand3
            else -> cnBand4
        }
        val b = bank[Random.nextInt(bank.size)]
        return Q("语文 · ${grade}年级", b[0] as String, b[1] as String, b[3] as String, (b[2] as List<String>).shuffled())
    }

    /* ================= 英语：按学段（题干/答案/选项/讲解） ================= */

    private val enBand1 = listOf( // 1~2 年级
        listOf("\"apple\" 的中文意思是？", "苹果", listOf("苹果", "香蕉", "橘子", "葡萄"), "apple 读作「爱剖」，意思是苹果。水果类单词：apple 苹果、banana 香蕉。"),
        listOf("\"book\" 的中文意思是？", "书", listOf("书", "包", "笔", "桌"), "book 读作「布克」，意思是书。注意区分 bag 是书包。"),
        listOf("\"yellow\" 是什么颜色？", "黄色", listOf("黄色", "红色", "蓝色", "绿色"), "yellow 是黄色。常见颜色：red 红、blue 蓝、green 绿、yellow 黄。"),
        listOf("数字 \"three\" 是几？", "3", listOf("3", "2", "4", "5"), "three 是 3。数一数：one 1、two 2、three 3。"),
        listOf("\"cat\" 是什么动物？", "猫", listOf("猫", "狗", "鸟", "鱼"), "cat 是猫，dog 是狗。两个词都只有三个字母，注意别混。"),
        listOf("\"father\" 的中文意思是？", "爸爸", listOf("爸爸", "妈妈", "爷爷", "哥哥"), "father 是爸爸，mother 是妈妈。er 结尾的多是人的称呼。"),
        listOf("\"red\" 是什么颜色？", "红色", listOf("红色", "绿色", "蓝色", "黑色"), "red 是红色。绿灯是 green，红灯是 red，过马路要看红绿灯。"),
        listOf("\"banana\" 的中文意思是？", "香蕉", listOf("香蕉", "苹果", "梨", "西瓜"), "banana 是香蕉。以 a 开头的水果：apple 苹果、banana 香蕉。"),
        listOf("\"school\" 的中文意思是？", "学校", listOf("学校", "家", "公园", "商店"), "school 是学校。go to school 就是去上学。"),
        listOf("\"mother\" 的中文意思是？", "妈妈", listOf("妈妈", "爸爸", "姐姐", "奶奶"), "mother 是妈妈，口语里常说 mom。father 是爸爸。"),
        listOf("数字 \"eight\" 是几？", "8", listOf("8", "5", "6", "9"), "eight 是 8。four 4、five 5、six 6、seven 7、eight 8。"),
        listOf("\"bird\" 是什么动物？", "鸟", listOf("鸟", "猫", "鱼", "鸭"), "bird 是鸟。鸟会飞：fly。Fish 会游泳：swim。"),
        listOf("\"pen\" 的中文意思是？", "钢笔", listOf("钢笔", "铅笔", "尺子", "橡皮"), "pen 是钢笔，pencil 是铅笔。都是写字的工具。"),
        listOf("\"dog\" 是什么动物？", "狗", listOf("狗", "猫", "鸟", "猪"), "dog 是狗。狗叫：Woof! 猫叫：Meow。"),
        listOf("\"milk\" 的中文意思是？", "牛奶", listOf("牛奶", "水", "果汁", "鸡蛋"), "milk 是牛奶。喝牛奶：drink milk。")
    )

    private val enBand2 = listOf( // 3~4 年级
        listOf("\"teacher\" 的中文意思是？", "老师", listOf("老师", "医生", "警察", "司机"), "teacher 是老师，动词 teach 是「教」的意思，加 er 变成「教的人」= 老师。"),
        listOf("「星期三」的英文是？", "Wednesday", listOf("Wednesday", "Monday", "Friday", "Sunday"), "Wednesday 是星期三。记忆：Monday 周一、Friday 周五、Sunday 周日。"),
        listOf("\"run\" 的中文意思是？", "跑", listOf("跑", "走", "睡", "吃"), "run 是跑。walk 是走、eat 是吃、sleep 是睡。"),
        listOf("\"How are you?\" 的正确回答是？", "I'm fine, thank you.", listOf("I'm fine, thank you.", "My name is Tom.", "Good night.", "See you."), "How are you 问的是「你好吗」，回答自己状态：我很好，谢谢。My name is... 是回答「你叫什么」。"),
        listOf("\"winter\" 是哪个季节？", "冬天", listOf("冬天", "春天", "夏天", "秋天"), "winter 是冬天。四季：spring 春、summer 夏、autumn 秋、winter 冬。"),
        listOf("\"play football\" 的意思是？", "踢足球", listOf("踢足球", "打篮球", "游泳", "跑步"), "play football 是踢足球；basketball 是篮球。play + 球类运动。"),
        listOf("\"hospital\" 的中文意思是？", "医院", listOf("医院", "学校", "银行", "邮局"), "hospital 是医院，看病去医院：go to the hospital。"),
        listOf("\"dance\" 的中文意思是？", "跳舞", listOf("跳舞", "唱歌", "画画", "读书"), "dance 是跳舞，sing 是唱歌，draw 是画画。"),
        listOf("\"eleven\" 是数字几？", "11", listOf("11", "7", "12", "10"), "eleven 是 11，twelve 是 12。从 thirteen（13）开始才有规律：thirteen = three + teen。"),
        listOf("\"hot\" 的反义词是？", "cold", listOf("cold", "warm", "cool", "wet"), "hot 热的，反义词 cold 冷的。warm 温暖的、cool 凉爽的，程度都轻一些。"),
        listOf("\"breakfast\" 的中文意思是？", "早餐", listOf("早餐", "午餐", "晚餐", "零食"), "breakfast 是早餐，lunch 午餐，dinner 晚餐。break + fast 本意是「打破停止进食的状态」。"),
        listOf("\"panda\" 是什么动物？", "熊猫", listOf("熊猫", "老虎", "大象", "猴子"), "panda 是熊猫，是中国的国宝。"),
        listOf("一周的第一天（英文习惯）是？", "Sunday", listOf("Sunday", "Monday", "Saturday", "Friday"), "在英美习惯里，一周从 Sunday（星期日）开始，Monday 是第二天。"),
        listOf("\"What's your name?\" 的正确回答是？", "My name is Li Ming.", listOf("My name is Li Ming.", "I'm fine.", "Good morning.", "Thank you."), "What's your name 问「你叫什么名字」，用 My name is... 回答。"),
        listOf("\"December\" 是几月？", "十二月", listOf("十二月", "十月", "十一月", "九月"), "December 是十二月，一年的最后一个月。November 是十一月。")
    )

    private val enBand3 = listOf( // 5~6 年级
        listOf("I ___ a student.", "am", listOf("am", "is", "are", "be"), "be 动词口诀：我用 am，你用 are，is 跟着他她它；单数 is，复数 are。主语是 I，所以用 am。"),
        listOf("They ___ playing football now.", "are", listOf("are", "is", "am", "be"), "now 提示现在进行时，主语 They 是复数，所以用 are。"),
        listOf("\"go\" 的过去式是？", "went", listOf("went", "goed", "goes", "going"), "go 是不规则动词，过去式是 went，不能加 ed。不规则动词要单独记。"),
        listOf("\"bought\" 是哪个动词的过去式？", "buy", listOf("buy", "bring", "build", "break"), "buy（买）的过去式是 bought，和 bring（带来）的 brought 只差一个字母，注意区分。"),
        listOf("There ___ some milk in the glass.", "is", listOf("is", "are", "have", "has"), "milk 牛奶是不可数名词，不可数名词当单数看，所以用 is。"),
        listOf("\"heavy\" 的反义词是？", "light", listOf("light", "tall", "small", "long"), "heavy 重的，反义词 light 轻的。light 还有「灯、光」的意思，一词多义要记住。"),
        listOf("I ___ to school yesterday.", "went", listOf("went", "go", "goes", "going"), "yesterday（昨天）提示一般过去时，go 的过去式是 went。"),
        listOf("She ___ like dancing.", "doesn't", listOf("doesn't", "don't", "isn't", "aren't"), "主语 She 是第三人称单数，否定用 doesn't + 动词原形。don't 用于 I/you/we/they。"),
        listOf("\"library\" 的中文意思是？", "图书馆", listOf("图书馆", "书店", "教室", "实验室"), "library 是图书馆，借书是 borrow books。注意与 bookshop（书店）区分。"),
        listOf("Tom is ___ than his brother.（用 tall 的正确形式）", "taller", listOf("taller", "tall", "tallest", "more tall"), "than 提示比较级：tall → taller。最高级才是 tallest。"),
        listOf("We will ___ to Beijing next week.", "go", listOf("go", "went", "goes", "going"), "will 后面接动词原形，表示一般将来时：will go。"),
        listOf("\"get up\" 的中文意思是？", "起床", listOf("起床", "睡觉", "上车", "到达"), "get up 是起床。go to bed 是上床睡觉。"),
        listOf("Look! The children ___ swimming.", "are", listOf("are", "is", "am", "be"), "Look! 提示动作正在进行，children 是复数，用 are，构成现在进行时 are swimming。"),
        listOf("\"cheap\" 的反义词是？", "expensive", listOf("expensive", "heavy", "small", "old"), "cheap 便宜的，反义词 expensive 昂贵的。"),
        listOf("\"weather\" 的中文意思是？", "天气", listOf("天气", "whether", "水", "风"), "weather 是天气。What's the weather like? 是问「天气怎么样」。")
    )

    private val enBand4 = listOf( // 7~9 年级（初中）
        listOf("\"big\" 的比较级是？", "bigger", listOf("bigger", "biger", "more big", "biggest"), "重读闭音节词，双写末尾字母再加 er：big → bigger。bigger 是比较级，biggest 是最高级。"),
        listOf("\"environment\" 的中文意思是？", "环境", listOf("环境", "设备", "政府", "社会"), "environment 读作「因外伦门特」，意思是环境，常考 protect the environment 保护环境。"),
        listOf("The room ___ cleaned every day.（被动语态）", "is", listOf("is", "was", "has", "being"), "一般现在时的被动语态：am/is/are + 过去分词。主语 the room 是单数，every day 表明是现在，所以用 is cleaned。"),
        listOf("Neither of them ___ right.", "is", listOf("is", "are", "were", "be"), "neither（两者都不）作主语时按单数处理，谓语用第三人称单数 is。"),
        listOf("I have ___ this book twice.（现在完成时）", "read", listOf("read", "readed", "reading", "was reading"), "现在完成时：have + 过去分词。read 的过去分词还是 read（拼写不变，读作 /red/）。"),
        listOf("\"How long have you ___ here?\"", "lived", listOf("lived", "live", "living", "lives"), "how long 问持续多长时间，用现在完成时 have + 过去分词 lived。"),
        listOf("This is the teacher ___ taught us English.", "who", listOf("who", "which", "what", "whom"), "定语从句：先行词是 teacher（人），在从句中作主语，用 who。which 用于指物。"),
        listOf("It took me two hours ___ my homework.", "to finish", listOf("to finish", "finish", "finishing", "finished"), "固定句型：It takes sb. some time to do sth. 花费某人多长时间做某事，后面接 to do。"),
        listOf("Please stop ___. The baby is sleeping.（用 talk 的正确形式）", "talking", listOf("talking", "to talk", "talked", "talk"), "stop doing sth. 停止正在做的事；stop to do sth. 停下来去做另一件事。这里婴儿在睡觉，要「停止说话」，用 talking。"),
        listOf("\"advice\" 是可数名词还是不可数名词？", "不可数", listOf("不可数", "可数", "两者皆可", "只用于复数"), "advice（建议）是不可数名词，一条建议要说 a piece of advice，不能说 an advice。"),
        listOf("He used to ___ up late, but now he gets up early.", "get", listOf("get", "getting", "gets", "got"), "used to + 动词原形，表示「过去常常做某事（现在不做了）」。"),
        listOf("There are ___ books in our library than in theirs.（用 few 的正确形式）", "fewer", listOf("fewer", "few", "less", "least"), "books 是可数名词复数，比较级用 fewer；不可数名词才用 less。"),
        listOf("\"so do I\" 的意思是？", "我也是", listOf("我也是", "我也不是", "我确实如此", "我也是这样想的吗"), "So do I = 我也一样。上文是肯定句时用 So + 助动词 + 主语 表示「我也……」。"),
        listOf("I have lived here ___ 2015.", "since", listOf("since", "for", "from", "at"), "since + 时间点（2015 年），for + 一段时间。现在完成时里 since 从过去某点持续到现在。"),
        listOf("\"population\" 常搭配的 be 动词是？", "is", listOf("is", "are", "am", "were"), "population 表示「人口」这一整体概念时是不可数名词，谓语用单数 is。")
    )

    private fun en(grade: Int): Q {
        val bank = when {
            grade <= 2 -> enBand1
            grade <= 4 -> enBand2
            grade <= 6 -> enBand3
            else -> enBand4
        }
        val b = bank[Random.nextInt(bank.size)]
        return Q("英语 · ${grade}年级", b[0] as String, b[1] as String, b[3] as String, (b[2] as List<String>).shuffled())
    }
}
