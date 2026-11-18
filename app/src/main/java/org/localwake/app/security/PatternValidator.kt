// Portions adapted from OpenClaw. Copyright (c) 2026 OpenClaw Foundation. MIT.
package org.localwake.app.security

import kotlin.math.abs

object PatternValidator {
    fun append(selected: IntArray, point: Int): IntArray {
        require(point in 1..9)
        if (point in selected) return selected.copyOf()
        val result = selected.toMutableList()
        selected.lastOrNull()?.let { previous ->
            val pr = (previous - 1) / 3
            val pc = (previous - 1) % 3
            val r = (point - 1) / 3
            val c = (point - 1) % 3
            if ((abs(r - pr) == 2 || abs(c - pc) == 2) && (r + pr) % 2 == 0 && (c + pc) % 2 == 0) {
                val middle = (r + pr) / 2 * 3 + (c + pc) / 2 + 1
                if (middle !in result) result.add(middle)
            }
        }
        result.add(point)
        return result.toIntArray()
    }

    fun valid(pattern: IntArray): Boolean {
        if (pattern.size !in 4..9 || pattern.any { it !in 1..9 } || pattern.distinct().size != pattern.size) return false
        var normalized = intArrayOf()
        try {
            for (point in pattern) {
                val next = append(normalized, point)
                normalized.fill(0)
                normalized = next
            }
            return normalized.contentEquals(pattern)
        } finally { normalized.fill(0) }
    }
}
