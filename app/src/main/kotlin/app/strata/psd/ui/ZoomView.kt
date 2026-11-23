package app.strata.psd.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Document canvas: pinch-zoom, pan with rubber-banding, fling, animated double-tap zoom,
 * crisp pixels + pixel grid at high zoom, and a transparency background.
 *
 * Works in document coordinates so the displayed bitmap can have any resolution
 * (e.g. a downscaled composite) without the viewport jumping.
 */
class ZoomView(ctx: Context) : View(ctx) {

    enum class Backdrop { CHECKER, LIGHT, DARK }

    private var bitmap: Bitmap? = null
    private var previous: Bitmap? = null
    private var crossfade = 1f
    private var docW = 1f
    private var docH = 1f

    private var scale = 1f        // screen px per document px
    private var tx = 0f           // screen x of document origin
    private var ty = 0f
    private var fitScale = 1f
    private var hasLayout = false

    /** Extra padding (system bars, toolbars) the fitted image should avoid. */
    val safe = Rect()

    var backdrop = Backdrop.CHECKER
        set(v) { field = v; invalidate() }
    var onSingleTap: (() -> Unit)? = null
    var onZoomChanged: ((Float) -> Unit)? = null

    private val checker = Checker.themed(10.dp)
    private val solid = Paint()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fadePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val gridPaint = Paint().apply { strokeWidth = 1f }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    private val scroller = OverScroller(ctx)
    private var appear = 1f

    // One spring drives viewport transitions; we interpolate scale and translation along it.
    private var fromS = 0f; private var fromX = 0f; private var fromY = 0f
    private var toS = 0f; private var toX = 0f; private var toY = 0f
    private val viewSpring = Spring(Motion.defaultSpatial, 1f) { t ->
        scale = fromS + (toS - fromS) * t
        tx = fromX + (toX - fromX) * t
        ty = fromY + (toY - fromY) * t
        changed()
    }
    private val appearSpring = Spring(Motion.slowSpatial, 1f) { appear = it; invalidate() }
    private val fadeSpring = Spring(Motion.defaultEffects, 1f) { crossfade = it; if (it >= 1f) previous = null; invalidate() }

    /**
     * Shows [bmp] which depicts a document of [documentWidth] x [documentHeight].
     * Keeps the current viewport when the document size is unchanged (e.g. re-rendered layers).
     */
    fun setImage(bmp: Bitmap, documentWidth: Int, documentHeight: Int) {
        val first = bitmap == null
        val sameDoc = !first && documentWidth.toFloat() == docW && documentHeight.toFloat() == docH
        previous = if (first) null else bitmap
        bitmap = bmp
        docW = documentWidth.toFloat(); docH = documentHeight.toFloat()
        if (!sameDoc) { computeFit(); resetToFit(animate = false) }
        if (first) {
            appearSpring.snapTo(0f); appearSpring.animateTo(1f)
        } else {
            fadeSpring.snapTo(0f); fadeSpring.animateTo(1f)
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val wasFit = !hasLayout || abs(scale - fitScale) < 0.001f
        hasLayout = true
        computeFit()
        if (wasFit) resetToFit(false) else settle(false)
    }

    fun setSafeInsets(l: Int, t: Int, r: Int, b: Int) {
        val atFit = abs(scale - fitScale) < 0.001f
        safe.set(l, t, r, b)
        computeFit()
        if (atFit) resetToFit(animate = bitmap != null && isLaidOut)
    }

    private fun computeFit() {
        val aw = max(1, width - safe.left - safe.right - 32.dp)
        val ah = max(1, height - safe.top - safe.bottom - 32.dp)
        fitScale = min(aw / docW, ah / docH)
    }

    private val maxScale get() = max(fitScale * 4f, 48f)
    private val minScale get() = fitScale * 0.5f

    fun resetToFit(animate: Boolean = true) {
        val s = fitScale
        val x = safe.left + (width - safe.left - safe.right - docW * s) / 2f
        val y = safe.top + (height - safe.top - safe.bottom - docH * s) / 2f
        if (animate) animateTo(s, x, y) else { viewSpring.cancel(); scale = s; tx = x; ty = y; changed() }
    }

    private fun animateTo(s: Float, x: Float, y: Float, spec: SpringSpec = Motion.defaultSpatial) {
        scroller.forceFinished(true)
        fromS = scale; fromX = tx; fromY = ty
        toS = s; toX = x; toY = y
        viewSpring.snapTo(0f)
        // snapTo applied t=0 (no change); now animate.
        viewSpring.animateTo(1f, spec)
    }

    private fun changed() {
        onZoomChanged?.invoke(scale)
        postInvalidateOnAnimation()
    }

    /** Clamped translation for scale [s]: centered when smaller than the viewport, else edge-limited. */
    private fun clampX(x: Float, s: Float): Float {
        val w = docW * s
        val avail = width - safe.left - safe.right
        return if (w <= avail) safe.left + (avail - w) / 2f else x.coerceIn(width - safe.right - w, safe.left.toFloat())
    }

    private fun clampY(y: Float, s: Float): Float {
        val h = docH * s
        val avail = height - safe.top - safe.bottom
        return if (h <= avail) safe.top + (avail - h) / 2f else y.coerceIn(height - safe.bottom - h, safe.top.toFloat())
    }

    /** Springs back into bounds after a gesture. */
    private fun settle(animate: Boolean = true) {
        val s = scale.coerceIn(fitScale, maxScale)
        val fx = width / 2f; val fy = height / 2f
        var x = fx - (fx - tx) * (s / scale)
        var y = fy - (fy - ty) * (s / scale)
        x = clampX(x, s); y = clampY(y, s)
        if (abs(s - scale) < 0.0001f && abs(x - tx) < 0.5f && abs(y - ty) < 0.5f) return
        if (animate) animateTo(s, x, y, Motion.fastSpatial) else { scale = s; tx = x; ty = y; changed() }
    }

    // ------------------------------------------------------------------ gestures

    private var scaling = false
    private var flinging = false

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { scaling = true; viewSpring.cancel(); return true }
        override fun onScale(d: ScaleGestureDetector): Boolean {
            var f = d.scaleFactor
            val target = scale * f
            // Resistance beyond limits.
            if (target > maxScale || target < minScale) f = 1f + (f - 1f) * 0.3f
            val ns = (scale * f).coerceIn(fitScale * 0.25f, maxScale * 2f)
            tx = d.focusX - (d.focusX - tx) * (ns / scale)
            ty = d.focusY - (d.focusY - ty) * (ns / scale)
            scale = ns
            changed()
            return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true); flinging = false; viewSpring.cancel()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaling) return true
            tx -= resist(dx, tx, clampX(tx, scale))
            ty -= resist(dy, ty, clampY(ty, scale))
            changed()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scaling || scale < fitScale) return false
            val minX = clampX(-1e9f, scale); val maxX = clampX(1e9f, scale)
            val minY = clampY(-1e9f, scale); val maxY = clampY(1e9f, scale)
            if (tx !in minX..maxX || ty !in minY..maxY) return false
            scroller.fling(tx.toInt(), ty.toInt(), vx.toInt(), vy.toInt(), minX.toInt(), maxX.toInt(), minY.toInt(), maxY.toInt(), 40.dp, 40.dp)
            flinging = true
            postInvalidateOnAnimation()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (scale > fitScale * 1.1f) resetToFit()
            else {
                val s = min(maxScale, max(fitScale * 3f, 1f))
                val x = clampX(e.x - (e.x - tx) * (s / scale), s)
                val y = clampY(e.y - (e.y - ty) * (s / scale), s)
                animateTo(s, x, y)
            }
            haptic()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onSingleTap?.invoke(); return true }
    })

    /** Rubber-band: full movement inside bounds, diminishing movement when overscrolled. */
    private fun resist(d: Float, pos: Float, clamped: Float): Float {
        val over = abs(pos - clamped)
        if (over < 0.5f) return d
        val movingOut = (pos > clamped && d < 0) || (pos < clamped && d > 0)
        return if (movingOut) d * (1f / (1f + over / 60.dpf)) * 0.5f else d
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            scaling = false
            if (!flinging) settle()
        }
        if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.pointerCount <= 2) {
            // Finished pinching; settle if over-zoomed.
            post { if (!scaling && (scale > maxScale || scale < fitScale)) settle() }
        }
        parent?.requestDisallowInterceptTouchEvent(scale > fitScale * 1.01f)
        return true
    }

    override fun computeScroll() {
        if (flinging && scroller.computeScrollOffset()) {
            tx = scroller.currX.toFloat(); ty = scroller.currY.toFloat()
            changed()
        } else if (flinging) {
            flinging = false
            settle()
        }
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        val a = appear
        val s = scale * (0.92f + 0.08f * a)
        val cx = width / 2f; val cy = height / 2f
        val ox = cx - (cx - tx) * (s / scale); val oy = cy - (cy - ty) * (s / scale)
        dst.set(ox, oy, ox + docW * s, oy + docH * s)

        // Soft drop shadow so the document floats above the surface.
        if (a > 0f) {
            shadowPaint.color = withAlpha(0xFF000000.toInt(), (if (Theme.c.dark) 0.5f else 0.16f) * a)
            shadowPaint.setShadowLayer(18.dpf, 0f, 6.dpf, shadowPaint.color)
            canvas.drawRect(dst, shadowPaint)
        }
        val alpha = (a.coerceIn(0f, 1f) * 255).toInt()
        when (backdrop) {
            Backdrop.CHECKER -> { checker.paint.alpha = alpha; canvas.drawRect(dst, checker.paint) }
            Backdrop.LIGHT -> { solid.color = 0xFFFFFFFF.toInt(); solid.alpha = alpha; canvas.drawRect(dst, solid) }
            Backdrop.DARK -> { solid.color = 0xFF111114.toInt(); solid.alpha = alpha; canvas.drawRect(dst, solid) }
        }

        val pxPerBitmapPx = s * docW / bmp.width
        val smooth = pxPerBitmapPx < 3f
        bmpPaint.isFilterBitmap = smooth
        bmpPaint.alpha = alpha
        val prev = previous
        if (prev != null && crossfade < 1f) {
            fadePaint.isFilterBitmap = smooth
            fadePaint.alpha = ((1f - crossfade) * alpha).toInt()
            canvas.drawBitmap(prev, null, dst, fadePaint)
            bmpPaint.alpha = (crossfade * alpha).toInt()
        }
        canvas.drawBitmap(bmp, null, dst, bmpPaint)

        if (pxPerBitmapPx >= 14f) drawPixelGrid(canvas, bmp, pxPerBitmapPx)
    }

    private fun drawPixelGrid(canvas: Canvas, bmp: Bitmap, step: Float) {
        val alphaRamp = ((step - 14f) / 10f).coerceIn(0f, 1f)
        gridPaint.color = withAlpha(if (Theme.c.dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt(), 0.14f * alphaRamp)
        val left = max(dst.left, 0f); val right = min(dst.right, width.toFloat())
        val top = max(dst.top, 0f); val bottom = min(dst.bottom, height.toFloat())
        var i = ceil((left - dst.left) / step)
        val iEnd = floor((right - dst.left) / step)
        while (i <= iEnd) { val x = dst.left + i * step; canvas.drawLine(x, top, x, bottom, gridPaint); i++ }
        var j = ceil((top - dst.top) / step)
        val jEnd = floor((bottom - dst.top) / step)
        while (j <= jEnd) { val y = dst.top + j * step; canvas.drawLine(left, y, right, y, gridPaint); j++ }
    }

    val fitScaleValue: Float get() = fitScale

    /** Current zoom relative to 100% document pixels. */
    val zoom: Float get() = scale
}
