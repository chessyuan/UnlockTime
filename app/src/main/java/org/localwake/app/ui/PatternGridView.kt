// Adapted from OpenClaw LocalPinSetupActivity. Copyright (c) 2026 OpenClaw Foundation. MIT.
package org.localwake.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import com.phoneuse.R
import org.localwake.app.security.PatternValidator
import kotlin.math.hypot
import kotlin.math.min

internal class PatternGridView(context: Context, private val accent: Int, private val inactive: Int) : View(context) {
    private var selected = intArrayOf()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        strokeWidth = resources.displayMetrics.density * 3
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pointerX = 0f
    private var pointerY = 0f
    private var drawing = false
    var onCountChanged: ((Int) -> Unit)? = null
    init {
        filterTouchesWhenObscured = true
        isSaveEnabled = false
        isSaveFromParentEnabled = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        importantForContentCapture = IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        contentDescription = context.getString(R.string.credential_pattern_grid)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centers = centers()
        if (selected.isNotEmpty()) {
            val path = Path()
            selected.forEachIndexed { i, p ->
                val center = centers[p - 1]
                if (i == 0) path.moveTo(center.first, center.second) else path.lineTo(center.first, center.second)
            }
            if (drawing) path.lineTo(pointerX, pointerY)
            canvas.drawPath(path, linePaint)
            path.reset()
        }
        centers.forEachIndexed { i, center ->
            dotPaint.color = if (i + 1 in selected) accent else inactive
            canvas.drawCircle(center.first, center.second, min(width, height) * 0.035f, dotPaint)
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        if ((event.flags and (MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)) != 0) { reset(); return false }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { reset(); drawing = true; parent?.requestDisallowInterceptTouchEvent(true); addNearest(event.x, event.y) }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.historySize) addNearest(event.getHistoricalX(i), event.getHistoricalY(i))
                addNearest(event.x, event.y)
            }
            MotionEvent.ACTION_UP -> { addNearest(event.x, event.y); drawing = false; parent?.requestDisallowInterceptTouchEvent(false); performClick() }
            MotionEvent.ACTION_CANCEL -> { reset(); parent?.requestDisallowInterceptTouchEvent(false) }
            else -> return true
        }
        pointerX = event.x; pointerY = event.y
        onCountChanged?.invoke(selected.size); invalidate(); return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDetachedFromWindow() { reset(); super.onDetachedFromWindow() }
    fun pattern() = selected.copyOf()
    fun reset() { selected.fill(0); selected = intArrayOf(); drawing = false; onCountChanged?.invoke(0); invalidate() }
    private fun centers(): List<Pair<Float, Float>> {
        val side = min(width, height) * 0.78f
        val left = (width - side) / 2f; val top = (height - side) / 2f; val cell = side / 3f
        return List(9) { Pair(left + (it % 3 + 0.5f) * cell, top + (it / 3 + 0.5f) * cell) }
    }
    private fun addNearest(x: Float, y: Float) {
        val centers = centers()
        val index = centers.indices.minByOrNull { hypot((centers[it].first - x).toDouble(), (centers[it].second - y).toDouble()) } ?: return
        if (hypot((centers[index].first - x).toDouble(), (centers[index].second - y).toDouble()) > min(width, height) * 0.78f / 5f) return
        val next = PatternValidator.append(selected, index + 1)
        selected.fill(0); selected = next
    }
}
