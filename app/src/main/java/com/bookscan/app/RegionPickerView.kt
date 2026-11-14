package com.bookscan.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Hiển thị ảnh mẫu và cho người dùng kéo khoanh các vùng chữ nhật theo 3 loại: 0 = Nền, 1 = Giấy, 2 = Chữ. */
class RegionPickerView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    companion object {
        val COLORS = intArrayOf(Color.rgb(33, 150, 243), Color.rgb(76, 175, 80), Color.rgb(244, 67, 54))
        const val MAX_PER_TYPE = 4
    }

    private var bmp: Bitmap? = null
    private val toView = Matrix()
    private val toImg = Matrix()
    private val regions = Array(3) { ArrayList<RectF>() }   // toạ độ trong ảnh
    private var cur: RectF? = null                           // đang kéo (toạ độ màn hình)
    private var sx = 0f
    private var sy = 0f
    var type = 1
    var onChange: (() -> Unit)? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun setImage(b: Bitmap) {
        bmp = b
        for (r in regions) r.clear()
        updateMatrix()
        invalidate()
        onChange?.invoke()
    }

    fun image(): Bitmap? = bmp
    fun count(t: Int): Int = regions[t].size
    fun rects(t: Int): List<RectF> = regions[t]

    fun clear(t: Int) { regions[t].clear(); invalidate(); onChange?.invoke() }
    fun clearAll() { for (r in regions) r.clear(); invalidate(); onChange?.invoke() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        updateMatrix()
    }

    private fun updateMatrix() {
        val b = bmp ?: return
        if (width == 0 || height == 0) return
        val s = min(width.toFloat() / b.width, height.toFloat() / b.height)
        val dx = (width - b.width * s) / 2f
        val dy = (height - b.height * s) / 2f
        toView.reset()
        toView.postScale(s, s)
        toView.postTranslate(dx, dy)
        toView.invert(toImg)
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val b = bmp ?: return
        c.drawBitmap(b, toView, bmpPaint)
        for (t in 0 until 3) {
            fill.color = (COLORS[t] and 0x00FFFFFF) or 0x44000000
            stroke.color = COLORS[t]
            for (r in regions[t]) {
                val v = RectF(r)
                toView.mapRect(v)
                c.drawRect(v, fill)
                c.drawRect(v, stroke)
            }
        }
        cur?.let {
            fill.color = (COLORS[type] and 0x00FFFFFF) or 0x44000000
            stroke.color = COLORS[type]
            c.drawRect(it, fill)
            c.drawRect(it, stroke)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bmp == null) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                sx = e.x; sy = e.y
                cur = RectF(sx, sy, sx, sy)
            }
            MotionEvent.ACTION_MOVE -> {
                cur = RectF(min(sx, e.x), min(sy, e.y), max(sx, e.x), max(sy, e.y))
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val r = cur
                cur = null
                if (r != null && r.width() > 24f && r.height() > 24f) {
                    val img = RectF(r)
                    toImg.mapRect(img)
                    val b = bmp!!
                    img.intersect(0f, 0f, b.width.toFloat(), b.height.toFloat())
                    if (img.width() > 4f && img.height() > 4f) {
                        val list = regions[type]
                        if (list.size >= MAX_PER_TYPE) list.removeAt(0)
                        list.add(img)
                        onChange?.invoke()
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { cur = null; invalidate() }
        }
        return true
    }
}
