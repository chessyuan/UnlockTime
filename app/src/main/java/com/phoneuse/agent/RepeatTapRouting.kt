package com.phoneuse.agent

import com.phoneuse.model.InputItem
import com.phoneuse.screen.CompressedScreen
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.text.Normalizer

/**
 * Guards against a model translating a clear repeated-tap order into ordinary input.
 * It can only decline a proposed tool call. Targets, parameters and permission to touch still go
 * through the model's real repeat_tap call and the executor's live-screen checks.
 */
internal class RepeatTapRouting(message: String, private val standalone: Boolean = true) {
    private val text = Normalizer.normalize(message, Normalizer.Form.NFKC)
    private val clauses = text.split(clauseBreak)
    private val actionClauses = clauses.filter { repeats(it) && !discussion.containsMatchIn(it) }.ifEmpty {
        if (splitCadence.containsMatchIn(text) && !discussion.containsMatchIn(text)) listOf(text) else emptyList()
    }
    private val discussesOnly = discussion.containsMatchIn(text) && repeats(text) &&
        (actionClauses.isEmpty() || actionClauses.none { explicitOrder.containsMatchIn(it) })
    private val repeatClauses = if (discussesOnly) emptyList() else actionClauses
    private val repeatOnly = repeatClauses.isNotEmpty() && !otherAction.containsMatchIn(text)
    private val singleTap = !repeats(text) && once.containsMatchIn(text)
    private val order = if (repeatClauses.isEmpty()) null else RepeatTapOrder(if (repeatOnly) text else repeatClauses.joinToString(" "), repeatOnly)
    private var clarification = ""
    private var refinement: RepeatTapOrder? = null
    private var resultLine: String? = null
    private var stoppedPoint: Pair<Int, Int>? = null

    /** A failed sequence cannot be restarted by a vague continue instruction. */
    fun steered(message: String): RepeatTapRouting =
        if (resultLine?.endsWith("stop_reason=COMPLETED") == false) this
        else RepeatTapRouting(message, standalone = false)

    fun record(call: InputItem.FunctionCall, result: ToolResult) = record(call, result.output)

    val hasUserGeometry: Boolean get() = order?.hasExplicitGeometry == true || refinement?.hasExplicitGeometry == true

    fun rejection(call: InputItem.FunctionCall, screen: CompressedScreen?): String? {
        if (call.name in substitutes) {
            if (discussesOnly && !otherAction.containsMatchIn(text)) return "NOT RUN: answer the question in chat."
            resultLine?.takeIf { (repeatOnly || replaysStoppedTarget(call)) && !it.endsWith("stop_reason=COMPLETED") }?.let {
                return "NOT RUN: the sequence stopped. Report its result: $it"
            }
        }
        if (call.name == RepeatTapRequest.NAME) {
            if (discussesOnly) return "NOT RUN: answer the user's question about tapping in chat."
            if (singleTap) return "NOT RUN: the user requested one ordinary tap. Use tap."
            if (clarification.trim().lowercase() in cancellations) return "NOT RUN: the user cancelled the tapping request."
            resultLine?.let { return "NOT RUN: this sequence already ended. Report its result: $it" }
            val args = parse(call.argumentsJson) ?: return null
            return order?.mismatch(args, screen, clarification, refinement, standalone)?.let { "NOT RUN: $it. Correct the arguments or clarify before acting." }
        }
        if (repeatClauses.isEmpty() || call.name !in substitutes) return null
        val args = parse(call.argumentsJson) ?: return null
        val substitutesRepeat = when (call.name) {
            "tap" -> repeatOnly || namedTarget(args, screen)
            "batch" -> (args["actions"] as? JsonArray).orEmpty().any {
                val action = it as? JsonObject ?: return@any false
                val name = (action["tool"] as? JsonPrimitive)?.content
                name == RepeatTapRequest.NAME || name == "tap" &&
                    (repeatOnly || (action["args"] as? JsonObject)?.let { step -> namedTarget(step, screen) } == true)
            }
            "delegate" -> repeatOnly || repeats((args["task"] as? JsonPrimitive)?.content.orEmpty())
            else -> false
        }
        return if (substitutesRepeat) {
            resultLine?.let { "NOT RUN: this sequence already ended. Report its result: $it" }
                ?: "NOT RUN: use one repeat_tap call for the repeated target. Inspect the current screen or ask which target if needed."
        } else null
    }

    fun record(call: InputItem.FunctionCall, output: String) {
        if (order != null && call.name == "ask_user" && output.startsWith("User answered: ")) {
            clarification = Normalizer.normalize(output.removePrefix("User answered: "), Normalizer.Form.NFKC)
            refinement = RepeatTapOrder(clarification, simple = false)
        }
        // A first call can return the current screen and ask the model to locate its target there.
        // Once the executor reports an outcome, a fresh order from the user is required to start again.
        if (call.name != RepeatTapRequest.NAME) return
        val first = output.lineSequence().firstOrNull().orEmpty()
        if (!first.startsWith("repeat_tap requested=") || first.endsWith("stop_reason=SCREEN_READ_REQUIRED")) return
        if (repeatOnly || !first.endsWith("stop_reason=COMPLETED")) resultLine = first
        if (!first.endsWith("stop_reason=COMPLETED")) {
            parse(call.argumentsJson)?.let { args ->
                fun number(key: String) = (args[key] as? JsonPrimitive)?.intOrNull
                val x = number("x") ?: number("left")?.let { l -> number("right")?.let { r -> l + (r - l) / 2 } }
                val y = number("y") ?: number("top")?.let { t -> number("bottom")?.let { b -> t + (b - t) / 2 } }
                if (x != null && y != null) stoppedPoint = x to y
            }
        }
    }

    private fun replaysStoppedTarget(call: InputItem.FunctionCall): Boolean {
        if (call.name == "delegate") return true
        val point = stoppedPoint ?: return false
        val args = parse(call.argumentsJson) ?: return false
        fun same(a: JsonObject) = (a["x"] as? JsonPrimitive)?.intOrNull == point.first &&
            (a["y"] as? JsonPrimitive)?.intOrNull == point.second
        return when (call.name) {
            "tap" -> same(args)
            "batch" -> (args["actions"] as? JsonArray).orEmpty().any {
                val a = it as? JsonObject
                (a?.get("tool") as? JsonPrimitive)?.content == "tap" && (a["args"] as? JsonObject)?.let(::same) == true
            }
            else -> false
        }
    }

    /** In a compound task ordinary navigation remains available; protect the repeated target it identifies. */
    private fun namedTarget(args: JsonObject, screen: CompressedScreen?): Boolean {
        val index = (args["index"] as? JsonPrimitive)?.intOrNull
        val x = (args["x"] as? JsonPrimitive)?.intOrNull
        val y = (args["y"] as? JsonPrimitive)?.intOrNull
        val labels = buildList {
            (args["target"] as? JsonPrimitive)?.content?.let { add(it) }
            if (screen != null && index != null) screen.fullLabels[index]?.let { add(it) }
            if (screen != null && x != null && y != null) {
                for (i in screen.elements.keys) {
                    if (screen.tapPoint(i) == (x to y)) screen.fullLabels[i]?.let { add(it) }
                }
            }
        }
        return repeatClauses.any { clause ->
            labels.any { it.isNotBlank() && mentionsLabel(clause, it) } || (index != null && "[$index]" in clause) ||
                (x != null && y != null && point.findAll(clause).any {
                    it.groupValues[1].toIntOrNull() == x && it.groupValues[2].toIntOrNull() == y
                })
        }
    }

    private companion object {
        val substitutes = setOf("tap", "batch", "delegate")
        val cancellations = setOf("取消", "算了", "不点了", "cancel", "stop", "취소")
        fun phrases(vararg patterns: String) = Regex(patterns.joinToString("|"), RegexOption.IGNORE_CASE)

        val clauseBreak = phrases(
            """[。；;\n]|(?<!\d)[,，]|然后|接着|随后|之后|再(?=点|按)|并且?再?(?=点|按)""",
            """\b(?:and then|then)\b|\band\s+(?=(?:repeatedly|tap|click|open)\b)""", "그런 다음",
        )
        val otherAction = phrases(
            """然后|接着|随后|之后|分别|依次|各.{0,6}次|再(?:点|按|打开)|并且?(?:点|按|打开)""",
            """打开|进入|前往|切换|返回|滑动|滚动|输入|搜索""",
            """\b(?:then|before|after|open|navigate|go to|scroll|type|search|select|both|each|and (?:tap|click|close|the))\b""", "먼저|열고|그런 다음",
        )
        val discussion = phrases(
            "怎么|怎样|如何|为什么|什么是|是什么意思|原理|示例|举例|代码|脚本|教程|解释|讨论|介绍|不要\\s*(?:实际|自动|连续|重复|快速)*\\s*(?:连点|点按|点击|点|操作|执行)|不用执行|不需要(?:操作|执行)|别(?:点|操作)|是否支持",
            """\b(?:how (?:do|does|can|to|would)|explain|example|code|script|implementation|implement|discuss|what is|(?:do not|don't)\s+(?:tap|click|operate|execute|run)|without (?:tapping|clicking))\b""",
            "설명|예시|코드|하지 마",
        )
        val explicitOrder = phrases(
            """(?:请|帮我|给我|麻烦你|现在|实际|直接).{0,12}(?:连点|点按|点击)""",
            """^\s*(?:连点|连续点击|重复点击|自动点击|批量点击)""",
            """\b(?:please|actually|now|can you)\s+(?:repeatedly\s+)?(?:tap|click|auto[ -]?click)\b""", "지금|해 줘",
        )
        val repeatVerb = phrases(
            """(?:自动|快速连续|连续|重复|批量)(?:点按|点击|连点|点)|连点|(?:每秒|每隔[^。；;\n]{1,18})\s*(?:自动)?点""",
            """\b(?:auto[ -]?(?:tap(?:ping)?|click(?:ing)?)|(?:repeated(?:ly)?|continuously|keep)\s+(?:tapping|clicking|tap|click)|(?:double|triple)[ -](?:tap|click))\b""",
            """\b(?:tap|click)\b[^.;\n]{0,80}\b(?:repeatedly|twice|thrice|every|per second|at intervals)\b""",
            """(?:자동|연속|반복)\s*(?:탭|클릭)|(?:탭|클릭).{0,12}반복""",
        )
        val counted = listOf(
            Regex("""(?:点击|点按|轻点|按下|点)[^。；;\n]{0,70}?(-?\d+|[一二两三四五六七八九十百零]+)\s*(?:次|下)"""),
            Regex("""\b(?:tap|click)\b[^.;\n]{0,70}?\b(\d+|two|three|four|five|six|seven|eight|nine|ten)\s*(?:times|x)\b""", RegexOption.IGNORE_CASE),
            Regex("""(\d+|두|세|네|열)\s*번.{0,10}(?:눌러|탭|클릭)"""),
        )
        val once = phrases("""(?:点击|点按|轻点|点)[^。；;\n]{0,60}?(?:一|1)(?:次|下)""", """\b(?:tap|click)\b[^.;\n]{0,60}\bonce\b""")
        val splitCadence = phrases(
            """(?:点击|点按|轻点|点)[^。；;\n]{0,65}(?:每隔|间隔|每)\s*[:=为]?\s*[-\d.半一二两三四五六七八九十]+\s*(?:毫秒|秒|分钟|ms)""",
            """\b(?:tap|click)\b[^.;\n]{0,65}\b(?:interval|every)\s*[:=]?\s*\d""",
        )
        val point = Regex("""\(\s*(-?\d+)\s*,\s*(-?\d+)\s*\)""")

        fun repeats(s: String): Boolean = repeatVerb.containsMatchIn(s) ||
            counted.any { pattern ->
                pattern.findAll(s).any { match ->
                    val n = match.groupValues[1]
                    n != "一" && n.toIntOrNull() != 1
                }
            }

        fun mentionsLabel(clause: String, label: String): Boolean {
            if (label.any { it !in 'A'..'Z' && it !in 'a'..'z' && !it.isDigit() }) return clause.contains(label, ignoreCase = true)
            return Regex("(?<![a-zA-Z0-9])" + Regex.escape(label) + "(?![a-zA-Z0-9])", RegexOption.IGNORE_CASE).containsMatchIn(clause)
        }

        fun parse(raw: String): JsonObject? = try {
            Json.parseToJsonElement(raw) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
