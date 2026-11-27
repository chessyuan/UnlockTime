package com.phoneuse.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RepeatTapRequestTest {
    private val point = """{"mode":"point","x":0,"y":79}"""
    private val rect = """{"mode":"rect","left":8,"top":13,"right":19,"bottom":24}"""

    private fun parse(raw: String, width: Int = 100, height: Int = 80) =
        RepeatTapRequest.parse(Json.parseToJsonElement(raw).jsonObject, width, height)

    private fun withArgs(base: String, extra: String) = base.dropLast(1) + "," + extra + "}"

    @Test fun schemaAndExecutorAgreeOnDefaultsAndLimits() {
        val parameters = ScreenToolSpecs.all.single { it.name == "repeat_tap" }.parameters
        assertEquals(listOf(JsonPrimitive("mode")), parameters.getValue("required").jsonArray)
        assertEquals(JsonPrimitive(false), parameters["additionalProperties"])
        val props = parameters.getValue("properties").jsonObject
        assertEquals(setOf("mode", "x", "y", "left", "top", "right", "bottom", "count", "interval_ms", "check_every",
            "risk", "effect", "delete_scope", "user_authorized", "stop_on_page_change"), props.keys)
        val request = parse(point)
        assertEquals(RepeatTapRequest(0, 79, 10, 1000, 10), request)
        assertEquals(false, request.stopOnPageChange)
        assertEquals(true, parse(withArgs(point, "\"stop_on_page_change\":true")).stopOnPageChange)
        for (invalid in listOf("\"true\"", "null", "1")) {
            assertThrows(IllegalArgumentException::class.java) { parse(withArgs(point, "\"stop_on_page_change\":$invalid")) }
        }
        for ((key, value) in mapOf("count" to request.count.toLong(), "interval_ms" to request.intervalMs, "check_every" to request.checkEvery.toLong())) {
            assertEquals(JsonPrimitive(value), props.getValue(key).jsonObject["default"])
            assertEquals(JsonPrimitive(1), props.getValue(key).jsonObject["minimum"])
        }
        assertEquals(JsonPrimitive(60), props.getValue("count").jsonObject["maximum"])
        assertEquals(JsonPrimitive(60), props.getValue("check_every").jsonObject["maximum"])
    }

    @Test fun rectUsesOneSuppliedPointOrItsIntegerCenter() {
        assertEquals(RepeatTapRequest(13, 18, 10, 1000, 10), parse(rect))
        assertEquals(RepeatTapRequest(8, 23, 60, 1, 60), parse(withArgs(rect, """"x":8,"y":23,"count":60,"interval_ms":1,"check_every":60""")))
        assertEquals(RepeatTapRequest(99, 79, 1, 24_000_000_000, 1),
            parse("""{"mode":"rect","left":99,"right":100,"top":79,"bottom":80,"count":1,"interval_ms":24000000000,"check_every":1}"""))
        assertEquals(Int.MAX_VALUE - 1, parse("""{"mode":"rect","left":2147483645,"top":0,"right":2147483647,"bottom":1}""", Int.MAX_VALUE, 1).x)
    }

    @Test fun invalidPositionsAreRejectedBeforeTouchingAnything() {
        val invalid = listOf(
            """{"x":0,"y":0}""", """{"mode":"POINT","x":0,"y":0}""", """{"mode":null,"x":1,"y":2}""",
            """{"mode":"point","x":0}""", """{"mode":"point","x":100,"y":0}""", """{"mode":"point","x":0,"y":80}""",
            """{"mode":"point","x":-1,"y":0}""", withArgs(point, """"left":0"""), withArgs(point, """"index":1"""),
            withArgs(rect, """"x":9"""), withArgs(rect, """"y":20"""), withArgs(rect, """"x":19,"y":20"""),
            withArgs(rect, """"x":8,"y":24"""), rect.replace("\"left\":8", "\"left\":-1"),
            rect.replace("\"bottom\":24", "\"bottom\":81"), rect.replace("\"right\":19", "\"right\":8"),
            rect.replace("\"top\":13", "\"top\":25"), rect.replace(",\"right\":19", ""),
        )
        invalid.forEach { assertThrows(it, IllegalArgumentException::class.java) { parse(it) } }
        assertThrows(IllegalArgumentException::class.java) { parse(point, 0, 80) }
    }

    @Test fun numericParametersAreStrictAndNeverRoundedOrClamped() {
        for (value in listOf(""" "2" """, "2.0", "2e0", "null", "false", "[]", "{}")) {
            for (key in listOf("count", "interval_ms", "check_every")) {
                assertThrows("$key=$value", IllegalArgumentException::class.java) { parse(withArgs(point, """"$key":$value""")) }
            }
            assertThrows("x=$value", IllegalArgumentException::class.java) { parse("""{"mode":"point","x":$value,"y":4}""") }
        }
        for ((key, value) in listOf("count" to "0", "count" to "61", "interval_ms" to "0", "interval_ms" to "-20",
            "check_every" to "0", "check_every" to "61", "interval_ms" to "9223372036854775808")) {
            assertThrows("$key=$value", IllegalArgumentException::class.java) { parse(withArgs(point, """"$key":$value""")) }
        }
        assertEquals(Long.MAX_VALUE, parse(withArgs(point, """"count":2,"interval_ms":9223372036854775807""")).intervalMs)
        assertThrows(IllegalArgumentException::class.java) { parse(withArgs(point, """"count":3,"interval_ms":9223372036854775807""")) }
    }

    @Test fun riskFieldsUseTheDeclaredTypes() {
        val request = parse(withArgs(point, """"risk":"delete","effect":"Remove one item","delete_scope":"requested_one","user_authorized":false"""))
        assertEquals(parse(point), request)
        for (invalid in listOf(""""risk":null""", """"risk":"other"""", """"effect":2""", """"delete_scope":false""",
            """"user_authorized":"true"""", """"user_authorized":0""", """"interval":5""")) {
            val error = assertThrows(IllegalArgumentException::class.java) { parse(withArgs(point, invalid)) }
            assertTrue(invalid, !error.message.isNullOrBlank())
        }
    }
}
