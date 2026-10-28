package com.buildwclaude.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/** Draws one sprite frame, mirrored when he faces left. Feet sit on the bottom edge. */
class KnightView(context: Context) : View(context) {
    private var frame: Bitmap? = null
    private var mirrored = false
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun show(bitmap: Bitmap, facingLeft: Boolean) {
        if (bitmap === frame && facingLeft == mirrored) return
        frame = bitmap
        mirrored = facingLeft
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val b = frame ?: return
        if (b.isRecycled) return
        if (mirrored) canvas.scale(-1f, 1f, width / 2f, 0f)
        canvas.drawBitmap(b, (width - b.width) / 2f, (height - b.height).toFloat(), paint)
    }

    override fun performClick(): Boolean = super.performClick()
}
