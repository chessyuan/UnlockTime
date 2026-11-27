package com.phoneuse.agent

import com.phoneuse.screen.CompressedScreen
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

/** Verifies unambiguous values in the user's order against a proposed tool call. Never supplies device input. */
internal class RepeatTapOrder(private val text: String, simple: Boolean) {
    private val parameters = quoted.replace(text, " ")
    private val defaults = simple && !contextual.containsMatchIn(parameters)
    private val checks = checkPattern.findAll(parameters).toList()
    private val total = totalPattern.find(parameters)
    private val frequency = ratePattern.find(parameters)
    private val durations = intervalPattern.findAll(parameters).toList()
    private val countText = listOf(checkPattern, ratePattern, intervalPattern).fold(parameters) { s, p -> p.replace(s, " ") }
    private val counts = countPattern.findAll(countText).toList()

    private val expectedCount = when {
        total != null -> number(total.groupValues[1])
        counts.isNotEmpty() -> agreed(counts.map { number(it.groupValues[1]) })
        twice.containsMatchIn(countText) -> BigDecimal(2)
        thrice.containsMatchIn(countText) -> BigDecimal(3)
        defaults && !unknownCount.containsMatchIn(countText) -> BigDecimal(RepeatTapRequest.DEFAULT_COUNT)
        else -> null
    }
    private val expectedInterval = when {
        keyedInterval.containsMatchIn(parameters) -> keyedInterval.find(parameters)?.groupValues?.get(1)?.let(::number)
        durations.isNotEmpty() -> agreed(durations.map { duration(it.groupValues[1], it.groupValues[2]) })
        frequency != null -> frequencyInterval(frequency)
        oncePerSecond.containsMatchIn(parameters) -> BigDecimal(1000)
        defaults && !unknownInterval.containsMatchIn(parameters) -> BigDecimal(RepeatTapRequest.DEFAULT_INTERVAL_MS)
        else -> null
    }
    private val expectedCheck = when {
        checks.isNotEmpty() -> agreed(checks.map { number(it.groupValues[1].ifEmpty { it.groupValues[2] }) })
        defaults && !checkCue.containsMatchIn(parameters) -> BigDecimal(RepeatTapRequest.DEFAULT_CHECK_EVERY)
        else -> null
    }

    private data class Position(val x: BigDecimal, val y: BigDecimal)
    private data class Region(val left: BigDecimal, val top: BigDecimal, val right: BigDecimal, val bottom: BigDecimal)

    private val points = pair.findAll(text).map { Position(it.groupValues[1].toBigDecimal(), it.groupValues[2].toBigDecimal()) }.toList()
    private val isRect = rectWord.containsMatchIn(text) || labelled("left", "左边界") != null && labelled("right", "右边界") != null
    private val region: Region? = if (isRect) readRegion() else null
    private val xy = readXY()
    private val position = when {
        isRect -> xy ?: if (four.containsMatchIn(text) || labelled("left", "左边界") != null) points.singleOrNull() else points.getOrNull(2)
        points.size == 1 -> points.single()
        else -> xy ?: barePoint.find(text)?.let { Position(it.groupValues[1].toBigDecimal(), it.groupValues[2].toBigDecimal()) }
    }
    private val quotedLabels = quoted.findAll(text).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.distinct().toList()

    val hasExplicitGeometry: Boolean get() = if (isRect) region != null else position != null

    fun mismatch(args: JsonObject, screen: CompressedScreen?, clarification: String, refinement: RepeatTapOrder?, standalone: Boolean): String? {
        fun actual(key: String, fallback: Long? = null): BigDecimal? {
            if (key !in args) return fallback?.toBigDecimal()
            return (args[key] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toBigDecimalOrNull()
        }
        fun check(key: String, expected: BigDecimal?, fallback: Long? = null): String? =
            expected?.takeUnless { actual(key, fallback)?.compareTo(it) == 0 }?.let { "use the user's $key=${it.toPlainString()}" }

        val mode = (args["mode"] as? JsonPrimitive)?.content
        val wantedRegion = refinement?.region ?: region
        val wantedPosition = refinement?.position ?: position
        val rectangle = isRect || refinement?.isRect == true
        if (rectangle && mode != "rect") return "the user specified a rectangle; use mode=rect"
        if (!rectangle && wantedPosition != null && mode != "point") return "the user specified a point; use mode=point"
        wantedRegion?.let { r ->
            check("left", r.left)?.let { return it }
            check("top", r.top)?.let { return it }
            check("right", r.right)?.let { return it }
            check("bottom", r.bottom)?.let { return it }
            if (wantedPosition == null && ("x" in args || "y" in args)) {
                val cx = (r.left + r.right).divideToIntegralValue(BigDecimal(2))
                val cy = (r.top + r.bottom).divideToIntegralValue(BigDecimal(2))
                if (actual("x")?.compareTo(cx) != 0 || actual("y")?.compareTo(cy) != 0) return "use the requested rectangle's center"
            }
        }
        wantedPosition?.let { p ->
            check("x", p.x)?.let { return it }
            check("y", p.y)?.let { return it }
        }
        check("count", refinement?.expectedCount ?: expectedCount, RepeatTapRequest.DEFAULT_COUNT.toLong())?.let { return it }
        check("interval_ms", refinement?.expectedInterval ?: expectedInterval, RepeatTapRequest.DEFAULT_INTERVAL_MS)?.let { return it }
        check("check_every", refinement?.expectedCheck ?: expectedCheck, RepeatTapRequest.DEFAULT_CHECK_EVERY.toLong())?.let { return it }
        if (standalone && clarification.isBlank() && text.length <= 240 && bareWords.replace(text, "").all { it in bareCharacters }) {
            return "the user has not identified a target; ask which point, region or control to tap"
        }
        if (screen == null || wantedPosition != null || rectangle) return null

        // The compressor exposes a center for every live control. Two buttons with the same name
        // require a user-provided qualifier even when the model picks plausible coordinates.
        val label = quotedLabels.singleOrNull() ?: return null
        val matches = screen.elements.filter { (i, n) ->
            n.isActionable && n.enabled && n.visibleToUser && !n.covered && screen.fullLabels[i].equals(label, ignoreCase = true)
        }
        if (matches.isEmpty()) return null // A drawn control can still be identified in the screenshot.
        val options = matches.keys.toList()
        val qualifier = quoted.replace(text, "") + " " + clarification
        val target = when {
            options.size == 1 -> options.single()
            else -> {
                val horizontal = options.groupBy { screen.tapPoint(it).first }
                val column = when {
                    leftSide.containsMatchIn(qualifier) -> horizontal.minBy { it.key }.value
                    rightSide.containsMatchIn(qualifier) -> horizontal.maxBy { it.key }.value
                    else -> options
                }
                val vertical = column.groupBy { screen.tapPoint(it).second }
                val ranked = when {
                    topSide.containsMatchIn(qualifier) -> vertical.minBy { it.key }.value
                    bottomSide.containsMatchIn(qualifier) -> vertical.maxBy { it.key }.value
                    else -> column
                }
                val ordinal = ordinalPattern.find(qualifier)?.groupValues?.get(1)?.let(::number)?.toInt()
                ranked.singleOrNull() ?: ranked.singleOrNull { "[$it]" in qualifier } ?: ordinal?.let { ranked.getOrNull(it - 1) }
            }
        } ?: return "several visible controls have the requested label; ask the user which one"
        val request = try { RepeatTapRequest.parse(args, screen.displayWidth, screen.displayHeight) } catch (_: IllegalArgumentException) { return null }
        val node = matches.getValue(target)
        return if ((node.uncovered ?: node.bounds).containsPoint(request.x, request.y)) null else "use the location of the specified visible control"
    }

    private fun labelled(english: String, chinese: String): BigDecimal? = Regex(
        """(?:(?<![a-z0-9_])$english(?![a-z0-9_])|$chinese)\s*[:=为]?\s*(-?\d+)""", RegexOption.IGNORE_CASE,
    ).find(text)?.groupValues?.get(1)?.toBigDecimal()

    private fun readXY(): Position? {
        val x = labelled("x", "横坐标") ?: return null
        val y = labelled("y", "纵坐标") ?: return null
        return Position(x, y)
    }

    private fun readRegion(): Region? {
        val l = labelled("left", "左边界")
        val t = labelled("top", "上边界")
        val r = labelled("right", "右边界")
        val b = labelled("bottom", "下边界")
        if (l != null && t != null && r != null && b != null) return Region(l, t, r, b)
        four.find(text)?.let { m ->
            val v = m.groupValues.drop(1).map { it.toBigDecimal() }
            return Region(v[0], v[1], v[2], v[3])
        }
        if (points.size !in 2..3) return null
        return Region(points[0].x, points[0].y, points[1].x, points[1].y)
    }

    private companion object {
        const val NUM = """(?:-?\d+(?:\.\d+)?|半|[一二两三四五六七八九十百零〇]+|\bone\b|\btwo\b|\bthree\b|\bfour\b|\bfive\b|\bsix\b|\bseven\b|\beight\b|\bnine\b|\bten\b)"""
        const val UNIT = """(?:毫秒|秒钟?|分钟|milliseconds?\b|msecs?\b|ms\b|seconds?\b|secs?\b|s\b|minutes?\b)"""
        fun regex(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)
        val totalPattern = regex("""(?:一共|总共|总计|合计|共计|共|次数|总计次数|\bcount\b)\s*[:=为]?\s*($NUM)""")
        val checkPattern = regex("""(?:每(?:隔)?|every)\s*($NUM)\s*(?:次|下|taps?)\s*(?:点击)?(?:后)?(?:检查|复查|校验|检测)|(?:check_every\s*[:=]|\bcheck every)\s*($NUM)""")
        val ratePattern = regex("""每(?:一|1)?秒\s*(?:钟)?\s*(?:自动)?(?:点按|点击|点)\s*($NUM)\s*(?:次|下)|($NUM)\s*(?:taps?|clicks?|times)\s*(?:per|a)\s*second""")
        val oncePerSecond = regex("""每(?:一|1)?秒\s*(?:自动)?(?:点按|点击|点)|\b(?:every|per|once a)\s+second\b""")
        val intervalPattern = regex("""(?:每隔|相隔|间隔(?:时间)?|每|every|interval(?:_ms)?\s*[:=]?)\s*[:=为]?\s*($NUM)\s*($UNIT)(?:\s*(?:点击|点按|点)?\s*(?:一|1)\s*(?:次|下))?""")
        val keyedInterval = regex("""\binterval_ms\s*[:=]\s*($NUM)""")
        val countPattern = regex("""($NUM)\s*(?:次|下|times\b|taps?\b|clicks?\b)""")
        val twice = regex("""\b(?:twice|double[ -](?:tap|click))\b""")
        val thrice = regex("""\b(?:thrice|triple[ -](?:tap|click))\b""")
        val contextual = regex("""直到|直至|持续|一直|剩余|剩下|上次|之前|刚才|前面|照旧|照常|预设|若干|不定|按原来|相同|一样|\b(?:until|duration|remaining|as before|last time|as above|same as|preset|for\s+\w+\s+(?:seconds?|minutes?))\b""")
        val unknownCount = regex("""[几多若数]下|次|一直|一会儿|不停|循环|\b(?:times|count|few|several|dozen|couple|until)\b""")
        val unknownInterval = regex("""间隔|每隔|速度|频率|节奏|每(?:秒|分钟|帧)|毫秒|秒钟?|分钟|hz|\b(?:interval(?:_ms)?|speed|pace|rate|every|seconds?|milliseconds?|ms|as fast)\b""")
        val checkCue = regex("""检查|复查|校验|检测|\bcheck(?:_every)?\b""")
        val pair = Regex("""[\[(]\s*(-?\d+)\s*,\s*(-?\d+)\s*[\])]""")
        val four = Regex("""[\[(]\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(-?\d+)\s*[\])]""")
        val barePoint = regex("""(?:坐标|\bcoordinates?\b|\bpoint\b)\s*[:=为]?\s*(-?\d+)\s*,\s*(-?\d+)""")
        val rectWord = regex("""矩形|长方形|\b(?:rect|rectangle|bounding box)\b""")
        val quoted = Regex("""["“「『']([^"”」』'\n]+)["”」』']""")
        val bareWords = regex(
            """请|帮我|给我|麻烦你|现在|(?:执行|开始)(?=自动|连续|重复|连点)|自动|快速|连续|重复|批量|点击|点按|连点|点|共|每隔|每|间隔|毫秒|秒|次|下|吧|""" +
                """please\b|auto[ -]?(?:click|tap)|repeat(?:edly)?\b|(?:click|tap)(?:ping|ing)?\b|times\b|every\b|ms\b|seconds?\b""",
        )
        const val bareCharacters = "0123456789一二两三四五六七八九十百零 \t\r\n,.!，。！"
        val ordinalPattern = regex("""第\s*($NUM)\s*个""")
        val leftSide = regex("""左侧|左边|左面|最左|左方|左上|左下|\s左$|\bleft(?:most)?\b""")
        val rightSide = regex("""右侧|右边|右面|最右|右方|右上|右下|\s右$|\bright(?:most)?\b""")
        val topSide = regex("""上侧|上边|上面|最上|上方|左上|右上|\b(?:top|upper)(?:most)?\b""")
        val bottomSide = regex("""下侧|下边|下面|最下|下方|左下|右下|\b(?:bottom|lower)(?:most)?\b""")
        val units = mapOf('十' to 10, '百' to 100)
        val cnDigits = "零一二三四五六七八九"
        val enNumbers = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")

        fun number(raw: String): BigDecimal? {
            raw.toBigDecimalOrNull()?.let { return it }
            if (raw == "半") return BigDecimal("0.5")
            val english = enNumbers.indexOf(raw.lowercase())
            if (english >= 0) return english.toBigDecimal()
            val cn = raw.replace('两', '二').replace('〇', '零')
            if (cn.length == 1 && cn[0] in cnDigits) return cnDigits.indexOf(cn[0]).toBigDecimal()
            if (cn.none { it in units }) return null
            var result = 0
            var digit: Int? = null
            var lastUnit = Int.MAX_VALUE
            for (c in cn) {
                val unit = units[c]
                if (unit != null) {
                    if (unit >= lastUnit) return null
                    result += (digit ?: 1) * unit
                    lastUnit = unit
                    digit = null
                } else {
                    if (c !in cnDigits || (digit != null && digit != 0)) return null
                    digit = cnDigits.indexOf(c)
                }
            }
            return (result + (digit ?: 0)).toBigDecimal()
        }

        fun duration(raw: String, unit: String): BigDecimal? {
            val n = number(raw) ?: return null
            val scale = when (unit.lowercase()) {
                "毫秒", "ms", "msec", "msecs", "millisecond", "milliseconds" -> 1
                "分钟", "minute", "minutes" -> 60_000
                else -> 1000
            }
            return n * scale.toBigDecimal()
        }

        fun frequencyInterval(match: MatchResult): BigDecimal? {
            val n = number(match.groupValues[1].ifEmpty { match.groupValues[2] }) ?: return null
            if (n <= BigDecimal.ZERO) return null
            val (ms, remainder) = BigDecimal(1000).divideAndRemainder(n)
            return ms.takeIf { remainder.compareTo(BigDecimal.ZERO) == 0 }
        }

        fun agreed(values: List<BigDecimal?>): BigDecimal? {
            val first = values.firstOrNull() ?: return null
            return first.takeIf { values.all { v -> v != null && v.compareTo(first) == 0 } }
        }
    }
}
