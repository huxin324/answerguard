package com.family.answerlock

import java.util.Calendar
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/**
 * 出题库：1~9 年级，自动匹配学期进度，每题附带语音讲解文案
 *
 * - 数学：按人教版教材各年级「上学期 / 下学期」知识点生成
 * - 学期自动判断：每年 9 月~次年 1 月 = 上学期；2 月~8 月 = 下学期
 * - 语文：古诗文、文学常识，按学段分档
 * - 英语：词汇与语法，按学段分档
 * - Q.explain：答错时展示并用语音朗读的讲解
 */
object QuestionBank {

    data class Q(
        val subject: String,      // 科目名（显示用，含年级学期信息）
        val text: String,         // 题干
        val answer: String,       // 正确答案
        val explain: String,      // 错题讲解（答错时显示 + 语音朗读）
        val options: List<String> // 四个选项（已打乱，含正确答案）
    )

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

    /* ================= 数学：按年级+学期 ================= */

    private fun math(grade: Int, sem: Int): Q {
        val label = "数学 · ${grade}年级${if (sem == 1) "上" else "下"}学期"
        var text: String
        var ans: Int
        var explain: String

        when (grade.coerceIn(1, 9)) {
            1 -> if (sem == 1) { // 1上：10/20以内加减法
                val a = Random.nextInt(9) + 1
                val b = Random.nextInt(9) + 1
                if (Random.nextBoolean()) {
                    text = "$a + $b = ?"; ans = a + b
                    explain = "$a 加 $b，就是从 $a 往后再数 $b 个，数到 $ans。记住：加法是越加越多。"
                } else {
                    val (x, y) = hiLo(a, b)
                    text = "$x − $y = ?"; ans = x - y
                    explain = "从 $x 里面拿走 $y 个，还剩下 $ans 个。减法是从总数里去掉一部分。"
                }
            } else { // 1下：20以内退位减、100以内加减
                val a = Random.nextInt(80) + 15
                val b = Random.nextInt(20) + 10
                if (Random.nextBoolean()) {
                    text = "$a + $b = ?"; ans = a + b
                    explain = "先算个位：个位相加后满十要向十位进一。$a 加 $b 等于 $ans。"
                } else {
                    val (x, y) = hiLo(a, b)
                    text = "$x − $y = ?"; ans = x - y
                    explain = "两位数减法从个位算起，个位不够减要向十位借一。$x 减 $y 等于 $ans。"
                }
            }

            2 -> if (sem == 1) { // 2上：乘法口诀 / 100以内加减
                if (Random.nextBoolean()) {
                    val a = Random.nextInt(9) + 1
                    val b = Random.nextInt(9) + 1
                    text = "$a × $b = ?"; ans = a * b
                    explain = "乘法口诀：$a$b 得多少？记住 $a 乘 $b 等于 $ans。乘法是相同的数连加。"
                } else {
                    val a = Random.nextInt(70) + 25
                    val b = Random.nextInt(20) + 5
                    if (Random.nextBoolean()) {
                        text = "$a + $b = ?"; ans = a + b
                        explain = "个位加个位、十位加十位，满十进一。$a 加 $b 等于 $ans。"
                    } else {
                        val (x, y) = hiLo(a, b)
                        text = "$x − $y = ?"; ans = x - y
                        explain = "个位不够减就向十位借一当十。$x 减 $y 等于 $ans。"
                    }
                }
            } else { // 2下：表内除法
                val b = Random.nextInt(8) + 2
                ans = Random.nextInt(8) + 2
                text = "${b * ans} ÷ $b = ?"
                explain = "除法用乘法口诀来想：$b 乘几等于 ${b * ans}？口诀里 $b 乘 $ans 得 ${b * ans}，所以商是 $ans。"
            }

            3 -> if (sem == 1) { // 3上：多位数乘一位数
                val a = Random.nextInt(300) + 100
                val b = Random.nextInt(8) + 2
                text = "$a × $b = ?"; ans = a * b
                explain = "多位数乘一位数：用 $b 依次去乘 $a 的个位、十位、百位，满几十就向前一位进几。结果是 $ans。"
            } else { // 3下：两位数乘两位数
                val a = Random.nextInt(60) + 20
                val b = Random.nextInt(50) + 12
                text = "$a × $b = ?"; ans = a * b
                explain = "两位数乘两位数：先算 $a 乘 $b 的个位，再算 $a 乘 $b 的十位，最后两步结果相加，得 $ans。"
            }

            4 -> if (sem == 1) { // 4上：三位数乘两位数
                val a = Random.nextInt(700) + 100
                val b = Random.nextInt(80) + 12
                text = "$a × $b = ?"; ans = a * b
                explain = "先算 $a 乘 $b 的个位，再算 $a 乘 $b 的十位并错开一位，两次结果相加等于 $ans。"
            } else { // 4下：运算定律（乘法分配律）
                val a = Random.nextInt(9) + 2
                val b = Random.nextInt(9) + 2
                val c = Random.nextInt(9) + 2
                text = if (Random.nextBoolean()) "$a × ($b + $c) = ?" else "$a × $b + $a × $c = ?"
                ans = a * (b + c)
                explain = "这是乘法分配律：$a × ($b + $c) = $a × $b + $a × $c。先算括号里的 ${b} + $c = ${b + c}，再乘 $a，得 $ans。"
            }

            5 -> if (sem == 1) { // 5上：简易方程
                val k = Random.nextInt(6) + 2
                ans = Random.nextInt(9) + 2
                val c = Random.nextInt(30) + 5
                if (Random.nextBoolean()) {
                    text = "解方程：${k}x + $c = ${k * ans + c}，x = ?"
                    explain = "解方程：两边同时减 $c，得到 ${k}x = ${k * ans}；再两边同时除以 $k，所以 x = $ans。"
                } else {
                    text = "解方程：${k}x − $c = ${k * ans - c}，x = ?"
                    explain = "解方程：两边同时加 $c，得到 ${k}x = ${k * ans}；再两边同时除以 $k，所以 x = $ans。"
                }
            } else { // 5下：同分母分数加减
                val d = Random.nextInt(4) + 3
                val a = Random.nextInt(d - 1) + 1
                var b = Random.nextInt(d - 1) + 1
                val plus = Random.nextBoolean()
                if (!plus) { while (b == a) b = Random.nextInt(d - 1) + 1 }
                val p = if (plus) a + b else abs(a - b)
                val g = gcd(p, d)
                val rp = p / g; val rq = d / g
                return Q(label, "$a/$d ${if (plus) "+" else "−"} $b/$d = ?",
                    "${rp}/${rq}",
                    "同分母分数相加减，分母不变，只把分子相加减：分子 ${if (plus) "$a + $b = $p" else "$a − $b = $p"}，得 $p/$d，再约分就是 ${rp}/$rq。",
                    fractionOptions(rp, rq))
            }

            6 -> if (sem == 1) { // 6上：百分数 / 比
                if (Random.nextBoolean()) {
                    val base = (Random.nextInt(20) + 2) * 10
                    val pct = listOf(10, 20, 50)[Random.nextInt(3)]
                    text = "$base 的 $pct% 是？"; ans = base * pct / 100
                    explain = "求一个数的百分之几用乘法：$base × $pct% = $base × 0.${pct / 10} = $ans。"
                } else {
                    val r1 = Random.nextInt(3) + 1
                    val r2 = r1 + Random.nextInt(3) + 1
                    val k = Random.nextInt(8) + 2
                    text = "甲∶乙 = $r1∶$r2，乙 = ${r2 * k}，甲 = ?"
                    ans = r1 * k
                    explain = "按比例分配：乙占 $r2 份、是 ${r2 * k}，说明每份是 $k；甲占 $r1 份，所以甲是 $r1 × $k = $ans。"
                }
            } else { // 6下：负数
                val a = Random.nextInt(12) + 2
                val b = Random.nextInt(9) + 2
                if (Random.nextBoolean()) {
                    text = "(${-a}) × $b = ?"; ans = -a * b
                    explain = "负数乘正数，符号取负，数字相乘：$a × $b = ${a * b}，再加负号，得 $ans。口诀：负负得正，负正得负。"
                } else {
                    text = "(${-a}) + $b = ?"; ans = b - a
                    explain = "异号两数相加，取绝对值较大的数的符号，再用大的减小的：$b − $a = ${b - a}，$b 比 $a ${if (b >= a) "大" else "小"}，所以结果是 $ans。"
                }
            }

            7 -> if (sem == 1) { // 7上：有理数运算 / 一元一次方程
                if (Random.nextBoolean()) {
                    val k = Random.nextInt(6) + 2
                    ans = Random.nextInt(9) + 2
                    val c = Random.nextInt(30) + 5
                    text = "解方程：${k}x + $c = ${k * ans + c}，x = ?"
                    explain = "移项要变号：${k}x = ${k * ans + c} − $c = ${k * ans}，两边除以 $k，得 x = $ans。"
                } else {
                    val a = Random.nextInt(15) + 3
                    val b = Random.nextInt(15) + 3
                    if (Random.nextBoolean()) {
                        text = "(${-a}) + $b = ?"; ans = b - a
                        explain = "有理数加法：异号相加取绝对值大的符号，$b − $a = ${b - a}，结果是 $ans。"
                    } else {
                        text = "(${-a}) × $b = ?"; ans = -a * b
                        explain = "有理数乘法：负正得负，$a × $b = ${a * b}，结果带负号是 $ans。"
                    }
                }
            } else { // 7下：实数与平方根
                val n = Random.nextInt(12) + 2
                text = "√${n * n} = ?"; ans = n
                explain = "因为 $n × $n = ${n * n}，所以 ${n * n} 的算术平方根就是 $n。平方和开平方互为逆运算。"
            }

            8 -> if (sem == 1) { // 8上：整式乘除 · 幂的运算
                val base = listOf(2, 3, 5)[Random.nextInt(3)]
                if (Random.nextBoolean()) {
                    val m = Random.nextInt(4) + 2
                    val n = Random.nextInt(4) + 2
                    text = "$base 的 ${m} 次方 × $base 的 ${n} 次方 = $base 的几次方？"
                    ans = m + n
                    explain = "同底数幂相乘，底数不变、指数相加：$m + $n = ${m + n}，所以是 $base 的 ${m + n} 次方。"
                } else {
                    val m = Random.nextInt(3) + 2
                    text = "($base 的 ${m} 次方) 的 2 次方 = $base 的几次方？"
                    ans = m * 2
                    explain = "幂的乘方，底数不变、指数相乘：$m × 2 = ${m * 2}，所以是 $base 的 ${m * 2} 次方。"
                }
            } else { // 8下：勾股定理 / 一次函数
                if (Random.nextBoolean()) {
                    val k = Random.nextInt(5) + 1
                    text = "直角三角形两直角边为 ${3 * k} 和 ${4 * k}，斜边长 = ?"
                    ans = 5 * k
                    explain = "勾股定理：斜边的平方 = ${3 * k}² + ${4 * k}² = ${9 * k * k} + ${16 * k * k} = ${25 * k * k}，开方得斜边 = ${5 * k}。这是最常见的勾股数 3、4、5 的倍数。"
                } else {
                    val k = Random.nextInt(4) + 2
                    val b = Random.nextInt(9) + 1
                    val x = Random.nextInt(6) + 2
                    text = "一次函数 y = ${k}x + $b，当 x = $x 时，y = ?"
                    ans = k * x + b
                    explain = "求函数值就是把 x 代进去算：y = $k × $x + $b = ${k * x} + $b = $ans。"
                }
            }

            else -> if (sem == 1) { // 9上：一元二次方程
                if (Random.nextBoolean()) {
                    val r = Random.nextInt(7) + 2
                    val s = Random.nextInt(7) + 2
                    val p = -(r + s)
                    val q = r * s
                    text = "方程 x² ${term(p, "x")}${term(q, "")} = 0 的较大根 = ?"
                    ans = max(r, s)
                    explain = "用十字相乘分解：两个根的积是 $q、和是 ${-p}，能凑出 $r 和 $s。所以较大根是 $ans。也可以用求根公式验证。"
                } else {
                    val n = Random.nextInt(9) + 3
                    text = "若 x² = ${n * n}，则 x 的正根 = ?"
                    ans = n
                    explain = "直接开平方：x² = ${n * n}，所以 x = ±$n，其中正根是 $n。"
                }
            } else { // 9下：反比例函数 / 锐角三角函数
                if (Random.nextBoolean()) {
                    val x = Random.nextInt(6) + 2
                    val y = Random.nextInt(6) + 2
                    text = "反比例函数 y = k/x 的图象经过点 ($x, $y)，k = ?"
                    ans = x * y
                    explain = "把点代入解析式：$y = k ÷ $x，所以 k = $x × $y = ${x * y}。反比例函数里 k 永远等于横纵坐标之积。"
                } else {
                    val t = triBank[Random.nextInt(triBank.size)]
                    return Q(label, t[0] as String, t[1] as String, t[3] as String,
                        (t[2] as List<String>).shuffled())
                }
            }
        }
        return Q(label, text, ans.toString(), explain, numOptions(ans))
    }

    /** 拼接 " + 5x" / " − 3" 形式的项 */
    private fun term(v: Int, suffix: String): String = when {
        v >= 0 -> " + $v$suffix"
        else -> " − ${-v}$suffix"
    }

    private fun hiLo(a: Int, b: Int): Pair<Int, Int> = if (a >= b) a to b else b to a

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

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
        listOf("sin 45° = ?", "√2/2", listOf("√2/2", "1/2", "√3/2", "1"), "等腰直角三角形中，45° 的正弦值是根号二分之二，也就是二分之根号二。"),
        listOf("tan 45° = ?", "1", listOf("1", "1/2", "√3/3", "0"), "45° 的正切值恒等于 1，因为等腰直角三角形两条直角边相等。"),
        listOf("sin 60° = ?", "√3/2", listOf("√3/2", "√2/2", "1/2", "1"), "特殊角：sin 60° 等于根号三分之二，即二分之根号三。"),
        listOf("tan 30° = ?", "√3/3", listOf("√3/3", "1/2", "√3/2", "1"), "特殊角：tan 30° 等于根号三分之一，即三分之根号三。")
    )

    /* ================= 语文：按学段（题干/答案/选项/讲解） ================= */

    private val cnBand1 = listOf( // 1~2 年级
        listOf("「床前明月光」的下一句是？", "疑是地上霜", listOf("疑是地上霜", "低头思故乡", "举头望明月", "处处闻啼鸟"), "《静夜思》：床前明月光，疑是地上霜。这句把月光比作了地上的白霜。"),
        listOf("「白毛浮绿水」的下一句是？", "红掌拨清波", listOf("红掌拨清波", "曲项向天歌", "拔清波", "浮绿水"), "《咏鹅》的顺序是：曲项向天歌、白毛浮绿水、红掌拨清波。按鹅的动作顺序记。"),
        listOf("《静夜思》的作者是？", "李白", listOf("李白", "杜甫", "白居易", "王维"), "《静夜思》是唐代诗人李白写的，李白被称为「诗仙」。"),
        listOf("一年四季，排在第一位的是？", "春天", listOf("春天", "夏天", "秋天", "冬天"), "四季的顺序是春、夏、秋、冬，春天是一年的开始，冰雪融化、万物发芽。"),
        listOf("「春眠不觉晓」出自哪首诗？", "《春晓》", listOf("《春晓》", "《咏鹅》", "《悯农》", "《静夜思》"), "「春眠不觉晓，处处闻啼鸟」出自孟浩然的《春晓》，写的是春天早晨的景色。"),
        listOf("「谁知盘中餐」的下一句是？", "粒粒皆辛苦", listOf("粒粒皆辛苦", "汗滴禾下土", "春种一粒粟", "秋收万颗子"), "《悯农》：谁知盘中餐，粒粒皆辛苦。提醒我们要爱惜粮食。")
    )

    private val cnBand2 = listOf( // 3~4 年级
        listOf("「独在异乡为异客」的下一句是？", "每逢佳节倍思亲", listOf("每逢佳节倍思亲", "遍插茱萸少一人", "遥知兄弟登高处", "冷露无声湿桂花"), "王维《九月九日忆山东兄弟》：独自在外做客，每到过节就加倍思念亲人。"),
        listOf("「不知细叶谁裁出」的下一句是？", "二月春风似剪刀", listOf("二月春风似剪刀", "万条垂下绿丝绦", "碧玉妆成一树高", "小荷才露尖尖角"), "贺知章《咏柳》：不知细叶谁裁出，二月春风似剪刀。把春风比作剪刀。"),
        listOf("《赠汪伦》的作者是？", "李白", listOf("李白", "王维", "孟浩然", "杜牧"), "「桃花潭水深千尺，不及汪伦送我情」出自李白的《赠汪伦》，写的是朋友间的情谊。"),
        listOf("成语「画蛇添足」的意思是？", "多此一举，反而坏事", listOf("多此一举，反而坏事", "画画非常好", "跑得很快", "非常喜欢小动物"), "画蛇添足：蛇画好了又给它添上脚，比喻做多余的事反而弄巧成拙。"),
        listOf("「但愿人长久」的下一句是？", "千里共婵娟", listOf("千里共婵娟", "把酒问青天", "月有阴晴圆缺", "高处不胜寒"), "苏轼《水调歌头》：但愿人长久，千里共婵娟。婵娟指月亮，意思是隔千里也能共赏一轮明月。"),
        listOf("「小荷才露尖尖角」写的是哪个季节？", "夏天", listOf("夏天", "春天", "秋天", "冬天"), "「小荷才露尖尖角，早有蜻蜓立上头」写的是初夏的池塘，荷花刚冒出尖角。")
    )

    private val cnBand3 = listOf( // 5~6 年级
        listOf("「春蚕到死丝方尽」的下一句是？", "蜡炬成灰泪始干", listOf("蜡炬成灰泪始干", "一枝红杏出墙来", "病树前头万木春", "化作春泥更护花"), "李商隐《无题》：春蚕到死丝方尽，蜡炬成灰泪始干。常用来赞美无私奉献的人。"),
        listOf("「人生自古谁无死」的下一句是？", "留取丹心照汗青", listOf("留取丹心照汗青", "零落成泥碾作尘", "要留清白在人间", "一片冰心在玉壶"), "文天祥《过零丁洋》：人生自古谁无死，留取丹心照汗青。表达以死报国的决心。"),
        listOf("《西游记》的作者是？", "吴承恩", listOf("吴承恩", "罗贯中", "施耐庵", "曹雪芹"), "《西游记》是明代吴承恩写的，讲唐僧师徒四人西天取经的故事。四大名著要记牢作者。"),
        listOf("成语「卧薪尝胆」的主人公是？", "勾践", listOf("勾践", "刘备", "项羽", "岳飞"), "越王勾践战败后睡柴草、尝苦胆，提醒自己不忘耻辱，最终复国。形容刻苦自励。"),
        listOf("「落霞与孤鹜齐飞」的下一句是？", "秋水共长天一色", listOf("秋水共长天一色", "烟波江上使人愁", "芳草萋萋鹦鹉洲", "晴川历历汉阳树"), "王勃《滕王阁序》名句：晚霞与野鸭一起飞，秋水和长天连成一片。"),
        listOf("「粉骨碎身浑不怕」的下一句是？", "要留清白在人间", listOf("要留清白在人间", "千磨万击还坚劲", "任尔东西南北风", "只留清气满乾坤"), "于谦《石灰吟》：即使粉身碎骨也不害怕，只要把清白留在人间。借石灰言志。")
    )

    private val cnBand4 = listOf( // 7~9 年级（初中）
        listOf("《出师表》的作者是？", "诸葛亮", listOf("诸葛亮", "司马迁", "曹操", "欧阳修"), "《出师表》是三国时期诸葛亮北伐前写给后主刘禅的表文，提出「亲贤臣，远小人」的建议。"),
        listOf("「海内存知己」的下一句是？", "天涯若比邻", listOf("天涯若比邻", "儿女共沾巾", "无为在歧路", "同是宦游人"), "王勃《送杜少府之任蜀州》：四海之内有知己，天涯也像近邻。是送别诗中的旷达名句。"),
        listOf("「会当凌绝顶」的下一句是？", "一览众山小", listOf("一览众山小", "造化钟神秀", "荡胸生曾云", "阴阳割昏晓"), "杜甫《望岳》：一定要登上泰山顶峰，俯瞰群山。表达攀登人生高峰的豪情。"),
        listOf("《岳阳楼记》的作者是？", "范仲淹", listOf("范仲淹", "欧阳修", "苏轼", "柳宗元"), "范仲淹《岳阳楼记》名句：先天下之忧而忧，后天下之乐而乐。"),
        listOf("「长风破浪会有时」的下一句是？", "直挂云帆济沧海", listOf("直挂云帆济沧海", "拔剑四顾心茫然", "多歧路，今安在", "忽复乘舟梦日边"), "李白《行路难》：相信总有一天能乘风破浪，扬起高帆渡过沧海。表达乐观自信。"),
        listOf("「问君能有几多愁」的下一句是？", "恰似一江春水向东流", listOf("恰似一江春水向东流", "故国不堪回首月明中", "雕栏玉砌应犹在", "只是朱颜改"), "李煜《虞美人》：愁绪就像一江春水滚滚东流。把无形的愁写得有形了。"),
        listOf("《陋室铭》的作者是？", "刘禹锡", listOf("刘禹锡", "韩愈", "周敦颐", "陶渊明"), "刘禹锡《陋室铭》名句：斯是陋室，惟吾德馨。屋子虽然简陋，但品德高尚就不觉得简陋。"),
        listOf("「了却君王天下事」的下一句是？", "赢得生前身后名", listOf("赢得生前身后名", "梦回吹角连营", "沙场秋点兵", "可怜白发生"), "辛弃疾《破阵子》：完成君王统一天下的大业，赢得生前死后的美名。")
    )

    private fun cn(grade: Int): Q {
        val bank = when {
            grade <= 2 -> cnBand1
            grade <= 4 -> cnBand2
            grade <= 6 -> cnBand3
            else -> cnBand4
        }
        val b = bank[Random.nextInt(bank.size)]
        return Q("语文", b[0] as String, b[1] as String, b[3] as String, (b[2] as List<String>).shuffled())
    }

    /* ================= 英语：按学段（题干/答案/选项/讲解） ================= */

    private val enBand1 = listOf( // 1~2 年级
        listOf("\"apple\" 的中文意思是？", "苹果", listOf("苹果", "香蕉", "橘子", "葡萄"), "apple 读作「爱剖」，意思是苹果。水果类单词：apple 苹果、banana 香蕉。"),
        listOf("\"book\" 的中文意思是？", "书", listOf("书", "包", "笔", "桌"), "book 读作「布克」，意思是书。注意区分 bag 是书包。"),
        listOf("\"yellow\" 是什么颜色？", "黄色", listOf("黄色", "红色", "蓝色", "绿色"), "yellow 是黄色。常见颜色：red 红、blue 蓝、green 绿、yellow 黄。"),
        listOf("数字 \"three\" 是几？", "3", listOf("3", "2", "4", "5"), "three 是 3。数一数：one 1、two 2、three 3。"),
        listOf("\"cat\" 是什么动物？", "猫", listOf("猫", "狗", "鸟", "鱼"), "cat 是猫，dog 是狗。两个词都只有三个字母，注意别混。"),
        listOf("\"father\" 的中文意思是？", "爸爸", listOf("爸爸", "妈妈", "爷爷", "哥哥"), "father 是爸爸，mother 是妈妈。er 结尾的多是人的称呼。")
    )

    private val enBand2 = listOf( // 3~4 年级
        listOf("\"teacher\" 的中文意思是？", "老师", listOf("老师", "医生", "警察", "司机"), "teacher 是老师，动词 teach 是「教」的意思，加 er 变成「教的人」= 老师。"),
        listOf("「星期三」的英文是？", "Wednesday", listOf("Wednesday", "Monday", "Friday", "Sunday"), "Wednesday 是星期三。记忆：Monday 周一、Friday 周五、Sunday 周日。"),
        listOf("\"run\" 的中文意思是？", "跑", listOf("跑", "走", "睡", "吃"), "run 是跑。walk 是走、eat 是吃、sleep 是睡。"),
        listOf("\"How are you?\" 的正确回答是？", "I'm fine, thank you.", listOf("I'm fine, thank you.", "My name is Tom.", "Good night.", "See you."), "How are you 问的是「你好吗」，回答自己状态：我很好，谢谢。My name is... 是回答「你叫什么」。"),
        listOf("\"winter\" 是哪个季节？", "冬天", listOf("冬天", "春天", "夏天", "秋天"), "winter 是冬天。四季：spring 春、summer 夏、autumn 秋、winter 冬。"),
        listOf("\"play football\" 的意思是？", "踢足球", listOf("踢足球", "打篮球", "游泳", "跑步"), "play football 是踢足球；basketball 是篮球。play + 球类运动。")
    )

    private val enBand3 = listOf( // 5~6 年级
        listOf("I ___ a student.", "am", listOf("am", "is", "are", "be"), "be 动词口诀：我用 am，你用 are，is 跟着他她它；单数 is，复数 are。主语是 I，所以用 am。"),
        listOf("They ___ playing football now.", "are", listOf("are", "is", "am", "be"), "now 提示现在进行时，主语 They 是复数，所以用 are。"),
        listOf("\"go\" 的过去式是？", "went", listOf("went", "goed", "goes", "going"), "go 是不规则动词，过去式是 went，不能加 ed。不规则动词要单独记。"),
        listOf("\"bought\" 是哪个动词的过去式？", "buy", listOf("buy", "bring", "build", "break"), "buy（买）的过去式是 bought，和 bring（带来）的 brought 只差一个字母，注意区分。"),
        listOf("There ___ some milk in the glass.", "is", listOf("is", "are", "have", "has"), "milk 牛奶是不可数名词，不可数名词当单数看，所以用 is。"),
        listOf("\"heavy\" 的反义词是？", "light", listOf("light", "tall", "small", "long"), "heavy 重的，反义词 light 轻的。light 还有「灯、光」的意思，一词多义要记住。")
    )

    private val enBand4 = listOf( // 7~9 年级（初中）
        listOf("\"big\" 的比较级是？", "bigger", listOf("bigger", "biger", "more big", "biggest"), "重读闭音节词，双写末尾字母再加 er：big → bigger。bigger 是比较级，biggest 是最高级。"),
        listOf("\"environment\" 的中文意思是？", "环境", listOf("环境", "设备", "政府", "社会"), "environment 读作「因外伦门特」，意思是环境，常考 protect the environment 保护环境。"),
        listOf("\"population\" 的中文意思是？", "人口", listOf("人口", "污染", "经济", "文化"), "population 是人口，注意：population 做主语时谓语动词用单数 is。"),
        listOf("The room ___ cleaned every day.（被动语态）", "is", listOf("is", "was", "has", "being"), "一般现在时的被动语态：am/is/are + 过去分词。主语 the room 是单数，every day 表明是现在，所以用 is cleaned。"),
        listOf("Neither of them ___ right.", "is", listOf("is", "are", "were", "be"), "neither（两者都不）作主语时按单数处理，谓语用第三人称单数 is。"),
        listOf("I have ___ this book twice.（现在完成时）", "read", listOf("read", "readed", "reading", "was reading"), "现在完成时：have + 过去分词。read 的过去分词还是 read（拼写不变，读作 /red/）。"),
        listOf("\"How long have you ___ here?\"", "lived", listOf("lived", "live", "living", "lives"), "how long 问持续多长时间，用现在完成时 have + 过去分词 lived。"),
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
        return Q("英语", b[0] as String, b[1] as String, b[3] as String, (b[2] as List<String>).shuffled())
    }
}
