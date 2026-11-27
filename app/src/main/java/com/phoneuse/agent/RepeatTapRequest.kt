package com.phoneuse.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** One fixed point in display pixels. Rectangles include their left/top and exclude their right/bottom. */
internal data class RepeatTapRequest(
    val x: Int,
    val y: Int,
    val count: Int,
    val intervalMs: Long,
    val checkEvery: Int,
    val stopOnPageChange: Boolean = false,
) {
    companion object {
        const val NAME = "repeat_tap"
        const val DEFAULT_COUNT = 10
        const val DEFAULT_INTERVAL_MS = 1000L
        const val DEFAULT_CHECK_EVERY = 10
        const val MAX_COUNT = 60
        const val MAX_GROUP = 10

        private val edges = setOf("left", "top", "right", "bottom")
        private val keys = setOf("mode", "x", "y", "count", "interval_ms", "check_every", "user_authorized", "stop_on_page_change") +
            edges + RiskJudge.PROPS.keys
        private val integer = Regex("-?(0|[1-9][0-9]*)")

        fun parse(args: JsonObject, width: Int, height: Int): RepeatTapRequest {
            if (width <= 0 || height <= 0) throw ArgException("display dimensions are unavailable")
            val unknown = args.keys.firstOrNull { it !in keys }
            if (unknown != null) throw ArgException("unknown repeat_tap argument: $unknown")
            validateRiskFields(args)

            val (x, y) = when (string(args, "mode")) {
                "point" -> {
                    if (args.keys.any { it in edges }) throw ArgException("rectangle bounds require mode=rect")
                    bounded(args, "x", 0 until width) to bounded(args, "y", 0 until height)
                }
                "rect" -> {
                    val left = bounded(args, "left", 0 until width)
                    val top = bounded(args, "top", 0 until height)
                    val right = bounded(args, "right", 1..width)
                    val bottom = bounded(args, "bottom", 1..height)
                    if (left >= right || top >= bottom) throw ArgException("rectangle requires left < right and top < bottom")
                    if (("x" in args) != ("y" in args)) throw ArgException("supply both x and y inside the rectangle or omit both")
                    // Subtract before adding: even a valid pair of display coordinates can sum past Int.MAX_VALUE.
                    bounded(args, "x", left until right, left + (right - left) / 2) to
                        bounded(args, "y", top until bottom, top + (bottom - top) / 2)
                }
                else -> throw ArgException("mode must be point or rect")
            }

            val count = bounded(args, "count", 1..MAX_COUNT, DEFAULT_COUNT)
            val interval = whole(args, "interval_ms", DEFAULT_INTERVAL_MS)
            if (interval < 1L) throw ArgException("interval_ms must be positive")
            if (count > 1 && interval > Long.MAX_VALUE / (count - 1)) {
                throw ArgException("the requested intervals exceed the supported duration")
            }
            val checkEvery = bounded(args, "check_every", 1..MAX_COUNT, DEFAULT_CHECK_EVERY)
            val stop = if ("stop_on_page_change" !in args) false else {
                val value = args["stop_on_page_change"] as? JsonPrimitive
                if (value == null || value.isString || value.booleanOrNull == null) throw ArgException("stop_on_page_change must be a boolean")
                value.booleanOrNull!!
            }
            return RepeatTapRequest(x, y, count, interval, checkEvery, stop)
        }

        private fun string(args: JsonObject, key: String): String {
            val value = args[key] ?: throw ArgException("$key is required")
            return (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ArgException("$key must be a string")
        }

        private fun whole(args: JsonObject, key: String, default: Long? = null): Long {
            val value = args[key] ?: return default ?: throw ArgException("$key is required")
            val raw = (value as? JsonPrimitive)?.takeUnless { it.isString }?.content
            return raw?.takeIf { integer.matches(it) }?.toLongOrNull() ?: throw ArgException("$key must be a JSON integer in the 64-bit range")
        }

        private fun bounded(args: JsonObject, key: String, range: IntRange, default: Int? = null): Int {
            val n = whole(args, key, default?.toLong())
            if (n < range.first || n > range.last) throw ArgException("$key must be in ${range.first}..${range.last}")
            return n.toInt()
        }

        private fun validateRiskFields(args: JsonObject) {
            for ((key, spec) in RiskJudge.PROPS) {
                if (key !in args) continue
                val value = string(args, key)
                val choices = spec["enum"] as? JsonArray
                if (choices != null && JsonPrimitive(value) !in choices) throw ArgException("invalid $key")
            }
            if ("user_authorized" in args) {
                val value = args["user_authorized"] as? JsonPrimitive
                if (value == null || value.isString || value.booleanOrNull == null) throw ArgException("user_authorized must be a boolean")
            }
        }
    }
}
