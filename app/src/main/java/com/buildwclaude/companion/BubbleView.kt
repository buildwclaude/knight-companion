package com.buildwclaude.companion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import kotlin.math.ceil
import kotlin.math.min

/**
 * The knight's speech bubble: a parchment card with a bold title and up to three lines of body,
 * or a compact dark pill for the focus countdown.
 */
class BubbleView(context: Context) : View(context) {
    private val dp = resources.displayMetrics.density
    private val sp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics)

    private val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F6F2E9") }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * dp
        color = Color.parseColor("#C9A470")
    }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E61A1C26") }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13.5f * sp
        color = Color.parseColor("#23283A")
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * sp
        color = Color.parseColor("#4A4F60")
    }
    private val pillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * sp
        color = Color.WHITE
        typeface = Typeface.MONOSPACE
    }

    private var title: StaticLayout? = null
    private var body: StaticLayout? = null
    private var isPill = false
    private val padH = 12 * dp
    private val padV = 9 * dp
    private val gap = 3 * dp
    private val rect = RectF()

    /** Lays out the text and returns the size (px) the window should be. */
    fun setMessage(titleText: String, bodyText: String, maxWidthPx: Int): Pair<Int, Int> {
        isPill = false
        val inner = (maxWidthPx - 2 * padH).toInt().coerceAtLeast(40)
        title = fit(titleText, titlePaint, inner, 2)
        body = if (bodyText.isBlank()) null else fit(bodyText, bodyPaint, inner, 3)
        val w = maxOf(title?.let(::widest) ?: 0, body?.let(::widest) ?: 0)
        val h = (title?.height ?: 0) + (body?.let { it.height + gap.toInt() } ?: 0)
        invalidate()
        return Pair(ceil(w + 2 * padH).toInt(), ceil(h + 2 * padV).toInt())
    }

    fun setPill(text: String): Pair<Int, Int> {
        isPill = true
        title = fit(text, pillPaint, 400 * dp.toInt(), 1)
        body = null
        val w = title?.let(::widest) ?: 0
        invalidate()
        return Pair(ceil(w + 2 * padH).toInt(), ceil((title?.height ?: 0) + 1.2f * padV).toInt())
    }

    private fun fit(text: String, paint: TextPaint, width: Int, lines: Int): StaticLayout {
        // measure at max width first, then shrink the box to the longest line actually used
        val full = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        val used = min(width, ceil((0 until full.lineCount).maxOf { full.getLineWidth(it) }).toInt() + 1)
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, used)
            .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
    }

    private fun widest(l: StaticLayout) = ceil((0 until l.lineCount).maxOf { l.getLineWidth(it) }).toInt()

    override fun onDraw(canvas: Canvas) {
        val inset = edge.strokeWidth / 2
        rect.set(inset, inset, width - inset, height - inset)
        val r = if (isPill) height / 2f else 14 * dp
        if (isPill) {
            canvas.drawRoundRect(rect, r, r, pill)
        } else {
            canvas.drawRoundRect(rect, r, r, card)
            canvas.drawRoundRect(rect, r, r, edge)
        }
        canvas.save()
        canvas.translate(padH, if (isPill) 0.6f * padV else padV)
        title?.draw(canvas)
        body?.let {
            canvas.translate(0f, (title?.height ?: 0) + gap)
            it.draw(canvas)
        }
        canvas.restore()
    }

    override fun performClick(): Boolean = super.performClick()
}
