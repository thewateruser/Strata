package app.strata.psd.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.BitmapShader
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min

// ---------------------------------------------------------------- layout helpers

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun frameLp(w: Int, h: Int, gravity: Int = Gravity.NO_GRAVITY, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
    FrameLayout.LayoutParams(w, h, gravity).apply { setMargins(l, t, r, b) }

fun linLp(w: Int, h: Int, weight: Float = 0f, gravity: Int = -1, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
    LinearLayout.LayoutParams(w, h, weight).apply { setMargins(l, t, r, b); if (gravity != -1) this.gravity = gravity }

fun Context.text(s: CharSequence, style: TextStyle, color: Int = Theme.c.onSurface, lines: Int = 0): TextView =
    TextView(this).apply {
        text = s
        Theme.style(this, style, color)
        if (lines > 0) { maxLines = lines; ellipsize = TextUtils.TruncateAt.END }
    }

fun Context.iconDrawable(id: Int, tint: Int): Drawable = getDrawable(id)!!.mutate().apply { setTint(tint) }

fun Context.iconView(id: Int, tint: Int, size: Int = 24.dp): ImageView = ImageView(this).apply {
    setImageDrawable(iconDrawable(id, tint))
    scaleType = ImageView.ScaleType.FIT_CENTER
    layoutParams = LinearLayout.LayoutParams(size, size)
}

fun View.haptic() = performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)

// ---------------------------------------------------------------- shapes

/** Rounded rectangle whose corner radius and colors can be animated. Radius is clamped to a pill. */
class MorphRect(var color: Int, var radius: Float, var strokeColor: Int = 0, var strokeWidth: Float = 0f) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    var topOnly = false

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        val r = min(radius, min(rect.width(), rect.height()) / 2f)
        if ((color ushr 24) > 0) {
            paint.style = Paint.Style.FILL; paint.color = color
            if (topOnly) {
                canvas.drawRoundRect(rect.left, rect.top, rect.right, rect.bottom + r, r, r, paint)
            } else canvas.drawRoundRect(rect, r, r, paint)
        }
        if (strokeWidth > 0f && (strokeColor ushr 24) > 0) {
            paint.style = Paint.Style.STROKE; paint.color = strokeColor; paint.strokeWidth = strokeWidth
            rect.inset(strokeWidth / 2, strokeWidth / 2)
            canvas.drawRoundRect(rect, r, r, paint)
        }
    }

    override fun getOutline(outline: Outline) {
        val b = bounds
        val r = min(radius, min(b.width(), b.height()) / 2f)
        if (topOnly) outline.setRoundRect(b.left, b.top, b.right, b.bottom + r.toInt(), r) else outline.setRoundRect(b, r)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}


/** Morphing container: on press the corners spring from [radius] to [pressedRadius]. */
open class MorphLayout(ctx: Context, color: Int, var radius: Float, var pressedRadius: Float = radius * 0.55f) : LinearLayout(ctx) {
    val shape = MorphRect(color, radius)
    private val spring = Spring(Motion.fastSpatial, radius) { shape.radius = it; invalidateOutline(); invalidate(); background?.invalidateSelf() }
    var pressScale = 0.97f
    private val scaleSpring = Spring(Motion.fastSpatial, 1f) { scaleX = it; scaleY = it }
    var selectedRadius: Float? = null

    init {
        val ripple = RippleDrawable(ColorStateList.valueOf(withAlpha(Theme.c.onSurface, 0.12f)), shape, null)
        background = ripple
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = shape.getOutline(outline)
        }
        clipToOutline = true
    }

    fun setColor(c: Int) { shape.color = c; invalidate(); background?.invalidateSelf() }

    fun animateColor(to: Int) {
        val from = shape.color
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            addUpdateListener { setColor(lerpColor(from, to, it.animatedValue as Float)) }
            start()
        }
    }

    private fun restingRadius() = if (isSelected) (selectedRadius ?: radius) else radius

    override fun setPressed(pressed: Boolean) {
        val was = isPressed
        super.setPressed(pressed)
        if (was == pressed || !isClickable) return
        spring.animateTo(if (pressed) pressedRadius else restingRadius())
        scaleSpring.animateTo(if (pressed) pressScale else 1f)
    }

    override fun setSelected(selected: Boolean) {
        val was = isSelected
        super.setSelected(selected)
        if (was != selected) spring.animateTo(restingRadius())
    }

    fun snapRadius(r: Float) { radius = r; spring.snapTo(restingRadius()) }
}

// ---------------------------------------------------------------- buttons

enum class ButtonStyle { FILLED, TONAL, OUTLINED, TEXT, ELEVATED }

@SuppressLint("ViewConstructor")
class ExpressiveButton(
    ctx: Context, label: CharSequence, icon: Int? = null, style: ButtonStyle = ButtonStyle.FILLED, height: Int = 48.dp,
) : MorphLayout(ctx, bgFor(style), height / 2f, height * 0.22f) {
    val label: TextView
    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        val fg = fgFor(style)
        val pad = if (height >= 56.dp) 28.dp else 20.dp
        setPadding(if (icon != null) pad - 4.dp else pad, 0, pad, 0)
        minimumHeight = height
        isClickable = true; isFocusable = true
        if (style == ButtonStyle.OUTLINED) { shape.strokeColor = Theme.c.outlineVariant; shape.strokeWidth = 1.dpf }
        if (style == ButtonStyle.ELEVATED) elevation = 2.dpf
        if (icon != null) addView(ctx.iconView(icon, fg, if (height >= 56.dp) 24.dp else 20.dp), linLp(WRAP, WRAP, r = 8.dp))
        this.label = ctx.text(label, if (height >= 56.dp) Type.titleMedium else Type.labelLarge, fg, 1)
        addView(this.label)
        layoutParams = LayoutParams(WRAP, height)
    }

    companion object {
        fun bgFor(s: ButtonStyle) = when (s) {
            ButtonStyle.FILLED -> Theme.c.primary
            ButtonStyle.TONAL -> Theme.c.secondaryContainer
            ButtonStyle.ELEVATED -> Theme.c.surfaceContainerLow
            else -> 0
        }
        fun fgFor(s: ButtonStyle) = when (s) {
            ButtonStyle.FILLED -> Theme.c.onPrimary
            ButtonStyle.TONAL -> Theme.c.onSecondaryContainer
            ButtonStyle.OUTLINED -> Theme.c.onSurfaceVariant
            else -> Theme.c.primary
        }
    }
}

/**
 * Icon button. When [toggle] is true, selection morphs it from a circle into a rounded square and
 * swaps to the filled color pair, like M3 Expressive toggle buttons.
 */
@SuppressLint("ViewConstructor")
class IconButton(
    ctx: Context, icon: Int, private val restColor: Int = 0, private val iconColor: Int = Theme.c.onSurfaceVariant,
    size: Int = 48.dp, private val selColor: Int = Theme.c.primary, private val selIconColor: Int = Theme.c.onPrimary,
    desc: String? = null,
) : MorphLayout(ctx, restColor, size / 2f, size * 0.3f) {
    private val image = ImageView(ctx)
    private var iconId = icon
    init {
        gravity = Gravity.CENTER
        isClickable = true; isFocusable = true
        selectedRadius = size * 0.3f
        contentDescription = desc
        image.setImageDrawable(ctx.iconDrawable(icon, iconColor))
        addView(image, linLp(24.dp, 24.dp))
        layoutParams = LayoutParams(size, size)
        if (desc != null && android.os.Build.VERSION.SDK_INT >= 26) tooltipText = desc
    }

    fun setIcon(id: Int) { iconId = id; image.setImageDrawable(context.iconDrawable(id, if (isSelected) selIconColor else iconColor)) }

    override fun setSelected(selected: Boolean) {
        if (selected == isSelected) return
        super.setSelected(selected)
        animateColor(if (selected) selColor else restColor)
        image.setImageDrawable(context.iconDrawable(iconId, if (selected) selIconColor else iconColor))
    }
}

/** Small rounded label used for metadata (blend mode, kind...). */
fun Context.chip(label: String, icon: Int? = null, bg: Int = Theme.c.surfaceContainerHighest, fg: Int = Theme.c.onSurfaceVariant): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = MorphRect(bg, 8.dpf)
        setPadding(if (icon != null) 6.dp else 10.dp, 0, 10.dp, 0)
        minimumHeight = 28.dp
        if (icon != null) addView(iconView(icon, fg, 16.dp), linLp(16.dp, 16.dp, r = 4.dp))
        addView(text(label, Type.labelMedium, fg, 1))
    }

// ---------------------------------------------------------------- checkerboard

/** Transparency checkerboard; cells stay a constant on-screen size. */
class Checker(cell: Int = 8.dp, light: Int, dark: Int) {
    val paint: Paint
    init {
        val bmp = Bitmap.createBitmap(cell * 2, cell * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(light)
        val p = Paint().apply { color = dark }
        c.drawRect(0f, 0f, cell.toFloat(), cell.toFloat(), p)
        c.drawRect(cell.toFloat(), cell.toFloat(), cell * 2f, cell * 2f, p)
        paint = Paint().apply { shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
    }

    companion object {
        fun themed(cell: Int = 8.dp) = if (Theme.c.dark) Checker(cell, 0xFF3A3A3E.toInt(), 0xFF2C2C30.toInt())
        else Checker(cell, 0xFFFFFFFF.toInt(), 0xFFE6E6EA.toInt())
    }
}

class CheckerDrawable(private val radius: Float, cell: Int = 6.dp) : Drawable() {
    private val checker = Checker.themed(cell)
    private val r = RectF()
    override fun draw(canvas: Canvas) { r.set(bounds); canvas.drawRoundRect(r, radius, radius, checker.paint) }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

// ---------------------------------------------------------------- loading indicator

/**
 * M3 Expressive loading indicator: a shape that continuously morphs through a sequence of
 * polygons (burst, cookies, pentagon, pill, oval) while rotating, optionally inside a container.
 */
class LoadingIndicator(ctx: Context, private val contained: Boolean = true) : View(ctx) {
    private val shapes = PolarShape.loadingSequence
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val containerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var progress = 0f
    var indicatorColor = if (contained) Theme.c.onPrimaryContainer else Theme.c.primary
    var containerColor = Theme.c.primaryContainer
    private val anim = ValueAnimator.ofFloat(0f, shapes.size.toFloat()).apply {
        duration = 650L * shapes.size
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { progress = it.animatedValue as Float; invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (!isAttachedToWindow) return
        if (visibility == VISIBLE && isShown) { if (!anim.isStarted) anim.start() } else anim.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val size = min(width, height).toFloat()
        if (contained) {
            containerPaint.color = containerColor
            canvas.drawCircle(cx, cy, size / 2f, containerPaint)
        }
        val i = progress.toInt() % shapes.size
        val local = progress - progress.toInt()
        // Hold each shape briefly, then morph with an overshooting ease (expressive spring feel).
        val t = easeMorph(((local - 0.25f) / 0.75f).coerceIn(0f, 1f))
        val shape = shapes[i].lerp(shapes[(i + 1) % shapes.size], t)
        val rotation = (progress * 140f + t * 90f) * (Math.PI / 180f).toFloat()
        paint.color = indicatorColor
        shape.toPath(path, cx, cy, size * (if (contained) 0.32f else 0.46f), rotation)
        canvas.drawPath(path, paint)
    }

    private fun easeMorph(x: Float): Float {
        // Damped spring step response approximation (slight overshoot).
        val c = 1.70158f * 0.6f
        val y = x - 1
        return 1 + (c + 1) * y * y * y + c * y * y
    }
}

/** Decorative static expressive shape (used for empty states and headers). */
class ShapeView(ctx: Context, private val shape: PolarShape, color: Int, private val rotationDeg: Float = 0f) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val path = Path()
    var spin = 0f
        set(v) { field = v; invalidate() }
    override fun onDraw(canvas: Canvas) {
        shape.toPath(path, width / 2f, height / 2f, min(width, height) / 2f, ((rotationDeg + spin) * Math.PI / 180).toFloat())
        canvas.drawPath(path, paint)
    }
}

/** Circle/shape-clipped icon badge (e.g. leading icons in the info sheet). */
fun Context.iconBadge(icon: Int, bg: Int, fg: Int, size: Int = 40.dp, shape: PolarShape? = null): View {
    val f = FrameLayout(this)
    if (shape != null) f.addView(ShapeView(this, shape, bg), FrameLayout.LayoutParams(size, size))
    else f.background = MorphRect(bg, size / 2f)
    f.addView(iconView(icon, fg, 20.dp), frameLp(20.dp, 20.dp, Gravity.CENTER))
    f.layoutParams = LinearLayout.LayoutParams(size, size)
    return f
}

// ---------------------------------------------------------------- snackbar

class SnackbarHost(ctx: Context) : FrameLayout(ctx) {
    private var current: View? = null
    private val hide = Runnable { dismiss() }

    fun show(message: String, action: String? = null, onAction: (() -> Unit)? = null) {
        current?.let { removeView(it) }
        removeCallbacks(hide)
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = MorphRect(Theme.c.inverseSurface, 16.dpf)
            elevation = 6.dpf
            setPadding(20.dp, 6.dp, 8.dp, 6.dp)
            minimumHeight = 56.dp
            addView(context.text(message, Type.bodyMedium, Theme.c.inverseOnSurface, 3), linLp(0, WRAP, 1f, r = 8.dp))
            if (action != null) {
                val b = ExpressiveButton(context, action, style = ButtonStyle.TEXT, height = 40.dp)
                b.label.setTextColor(Theme.c.inversePrimary)
                b.setOnClickListener { onAction?.invoke(); dismiss() }
                addView(b)
            }
        }
        addView(bar, frameLp(MATCH, WRAP, Gravity.BOTTOM))
        current = bar
        bar.translationY = 120.dpf; bar.alpha = 0f
        val s = Spring(Motion.defaultSpatial, 120.dpf) { bar.translationY = it }
        s.animateTo(0f)
        bar.animate().alpha(1f).setDuration(150).start()
        postDelayed(hide, if (action != null) 6000 else 3200)
    }

    fun dismiss() {
        val bar = current ?: return
        current = null
        bar.animate().alpha(0f).translationY(40.dpf).setDuration(180).withEndAction { removeView(bar) }.start()
    }
}

fun Rect.isEmptyRect() = width() <= 0 || height() <= 0
