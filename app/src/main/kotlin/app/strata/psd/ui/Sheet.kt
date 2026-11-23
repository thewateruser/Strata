package app.strata.psd.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Bottom sheet host. Sheets slide in on a spring, can be dragged down by their header to dismiss,
 * and follow predictive back gestures ([backProgress]).
 *
 * Modal sheets dim the content behind them; non-modal sheets (the layers panel) leave the
 * document interactive so changes can be watched live.
 */
@SuppressLint("ViewConstructor")
class Sheet(ctx: Context, private val modal: Boolean, var maxHeightFraction: Float = 0.9f, private val fill: Boolean = false) : FrameLayout(ctx) {

    private val scrim = View(ctx).apply { setBackgroundColor(0x52000000); alpha = 0f }
    val panel = PanelLayout(ctx)
    val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val handle = View(ctx).apply { background = MorphRect(withAlpha(Theme.c.onSurfaceVariant, 0.4f), 2.dpf) }
    var bottomInset = 0
        set(v) { field = v; panel.setPadding(0, 0, 0, v) }
    var onDismissed: (() -> Unit)? = null
    var isShowing = false
        private set
    /** Called with the visible height of the panel whenever it moves. */
    var onOffset: ((Float) -> Unit)? = null
    private val ySpring = Spring(Motion.defaultSpatial, 0f) { panel.translationY = it; updateScrim(); onOffset?.invoke(max(0f, panel.height - it)) }

    init {
        if (modal) {
            addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
            scrim.setOnClickListener { dismiss() }
        }
        panel.orientation = LinearLayout.VERTICAL
        panel.background = MorphRect(Theme.c.surfaceContainerLow, 28.dpf).apply { topOnly = true }
        panel.elevation = 3.dpf
        panel.clipToOutline = true
        panel.isClickable = true
        val handleBox = FrameLayout(ctx)
        handleBox.addView(handle, frameLp(32.dp, 4.dp, Gravity.CENTER))
        panel.addView(handleBox, linLp(MATCH, 24.dp))
        panel.addView(body, if (fill) linLp(MATCH, 0, 1f) else linLp(MATCH, WRAP))
        addView(panel, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        visibility = GONE
        if (!modal) isClickable = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val lp = panel.layoutParams as FrameLayout.LayoutParams
        lp.width = min(w, 640.dp)
        panel.maxH = (h * maxHeightFraction).toInt()
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    // Non-modal sheets must let touches through outside the panel.
    override fun onTouchEvent(event: MotionEvent): Boolean = modal && super.onTouchEvent(event)

    private fun updateScrim() {
        if (!modal) return
        val h = max(1, panel.height).toFloat()
        scrim.alpha = (1f - panel.translationY / h).coerceIn(0f, 1f)
    }

    fun show() {
        if (isShowing) return
        isShowing = true
        if (!ySpring.running) ySpring.snapTo(resources.displayMetrics.heightPixels.toFloat())
        visibility = VISIBLE
        panel.scaleX = 1f; panel.scaleY = 1f
        panel.post {
            if (panel.translationY > panel.height) ySpring.snapTo(panel.height.toFloat())
            ySpring.onEnd = null
            ySpring.animateTo(0f, Motion.defaultSpatial)
        }
    }

    fun dismiss(velocity: Float = 0f) {
        if (!isShowing) return
        isShowing = false
        ySpring.onEnd = {
            if (!isShowing) { visibility = GONE; onDismissed?.invoke() }
        }
        ySpring.animateTo(panel.height.toFloat() + 24.dpf, Motion.defaultEffects, startVelocity = velocity)
        panel.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
    }

    fun toggle() = if (isShowing) dismiss() else show()

    /** Height the sheet occupies when shown (for insetting content behind non-modal sheets). */
    val shownHeight: Int get() = if (panel.height > 0) panel.height else panel.maxH

    /** Predictive back: 0..1 while the user drags back; shrinks and nudges the sheet. */
    fun backProgress(p: Float) {
        val s = 1f - 0.08f * p
        panel.scaleX = s; panel.scaleY = s
        panel.pivotY = panel.height.toFloat()
        ySpring.snapTo(p * 24.dpf)
    }

    fun backCancelled() {
        panel.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
        ySpring.animateTo(0f)
    }

    /** Panel that limits its height and lets the header area drag the sheet. */
    inner class PanelLayout(ctx: Context) : LinearLayout(ctx) {
        var maxH = Int.MAX_VALUE
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private var downY = 0f
        private var dragging = false
        private var downInRegion = false
        private var startTrans = 0f
        private var tracker: VelocityTracker? = null
        /** Height (from the panel top) of the region that starts a drag. */
        var dragRegion = 96.dp

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxH, if (fill) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST))
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = ev.rawY; dragging = false; startTrans = translationY }
                MotionEvent.ACTION_MOVE -> {
                    if (ev.y - (ev.rawY - downY) < dragRegion && abs(ev.rawY - downY) > slop) {
                        dragging = true
                        tracker = VelocityTracker.obtain()
                        downY = ev.rawY
                        return true
                    }
                }
            }
            return false
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (!dragging) {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downY = ev.rawY; startTrans = translationY; downInRegion = ev.y < dragRegion }
                    MotionEvent.ACTION_MOVE -> if (downInRegion && abs(ev.rawY - downY) > slop) {
                        dragging = true; tracker = VelocityTracker.obtain(); downY = ev.rawY
                    }
                }
                if (!dragging) { super.onTouchEvent(ev); return true }
            }
            tracker?.addMovement(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val dy = ev.rawY - downY
                    // Rubber-band when pulled up past the resting position.
                    val y = if (dy < 0) dy * 0.15f else dy
                    ySpring.snapTo(startTrans + y)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    tracker?.computeCurrentVelocity(1000)
                    val vy = tracker?.yVelocity ?: 0f
                    tracker?.recycle(); tracker = null
                    dragging = false
                    if (vy > 1200f || translationY > height * 0.35f) dismiss(vy) else ySpring.animateTo(0f, Motion.fastSpatial, vy)
                }
            }
            return true
        }
    }
}
