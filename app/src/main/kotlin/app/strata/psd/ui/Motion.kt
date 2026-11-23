package app.strata.psd.ui

import android.graphics.Path
import android.view.Choreographer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** M3 Expressive motion scheme (spring stiffness / damping ratio). */
class SpringSpec(val stiffness: Float, val damping: Float)

object Motion {
    val fastSpatial = SpringSpec(800f, 0.6f)
    val defaultSpatial = SpringSpec(380f, 0.8f)
    val slowSpatial = SpringSpec(200f, 0.8f)
    val fastEffects = SpringSpec(3800f, 1f)
    val defaultEffects = SpringSpec(1600f, 1f)
    val slowEffects = SpringSpec(800f, 1f)
}

/**
 * A physically based spring driving a single float (mass = 1). Runs on the Choreographer,
 * retargets smoothly mid-flight and keeps velocity, which is what makes motion feel "expressive".
 */
class Spring(
    var spec: SpringSpec,
    initial: Float = 0f,
    private val onUpdate: (Float) -> Unit,
) : Choreographer.FrameCallback {
    var value = initial
        private set
    var velocity = 0f
    var target = initial
        private set
    var running = false
        private set
    private var lastFrame = 0L
    var onEnd: (() -> Unit)? = null

    fun animateTo(t: Float, spec: SpringSpec = this.spec, startVelocity: Float? = null) {
        this.spec = spec
        target = t
        if (startVelocity != null) velocity = startVelocity
        if (!running) {
            running = true
            lastFrame = 0L
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun snapTo(v: Float) {
        cancel()
        value = v; target = v; velocity = 0f
        onUpdate(v)
    }

    fun cancel() {
        if (running) Choreographer.getInstance().removeFrameCallback(this)
        running = false
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        var dt = if (lastFrame == 0L) 1f / 60f else (frameTimeNanos - lastFrame) / 1e9f
        lastFrame = frameTimeNanos
        dt = dt.coerceIn(0f, 1f / 20f)
        val k = spec.stiffness
        val c = 2f * spec.damping * sqrt(k)
        // Semi-implicit Euler in small sub-steps for stability at high stiffness.
        var remaining = dt
        while (remaining > 0f) {
            val h = minOf(remaining, 1f / 480f)
            val a = -k * (value - target) - c * velocity
            velocity += a * h
            value += velocity * h
            remaining -= h
        }
        val range = max(1f, abs(target))
        if (abs(velocity) < 0.002f * range * 10 && abs(value - target) < 0.0005f * range) {
            value = target; velocity = 0f; running = false
            onUpdate(value)
            onEnd?.invoke()
            return
        }
        onUpdate(value)
        Choreographer.getInstance().postFrameCallback(this)
    }
}

/**
 * Star-shaped outline described by its radius at [N] evenly spaced angles. Every expressive shape
 * used here (cookies, bursts, pills, ovals) is star-shaped around its center, so two shapes morph
 * simply by interpolating radii.
 */
class PolarShape(val radii: FloatArray) {
    fun lerp(other: PolarShape, t: Float): PolarShape =
        PolarShape(FloatArray(N) { radii[it] + (other.radii[it] - radii[it]) * t })

    /** Writes the outline into [path], centered at (cx, cy) with outer radius [r] and [rotation] in radians. */
    fun toPath(path: Path, cx: Float, cy: Float, r: Float, rotation: Float = 0f) {
        path.reset()
        // Catmull-Rom through the samples for silky curves.
        fun px(i: Int): Float { val j = Math.floorMod(i, N); val a = j * STEP + rotation; return cx + cos(a) * radii[j] * r }
        fun py(i: Int): Float { val j = Math.floorMod(i, N); val a = j * STEP + rotation; return cy + sin(a) * radii[j] * r }
        path.moveTo(px(0), py(0))
        for (i in 0 until N) {
            val c1x = px(i) + (px(i + 1) - px(i - 1)) / 6f; val c1y = py(i) + (py(i + 1) - py(i - 1)) / 6f
            val c2x = px(i + 1) - (px(i + 2) - px(i)) / 6f; val c2y = py(i + 1) - (py(i + 2) - py(i)) / 6f
            path.cubicTo(c1x, c1y, c2x, c2y, px(i + 1), py(i + 1))
        }
        path.close()
    }

    companion object {
        const val N = 96
        private const val STEP = (2 * PI / N).toFloat()

        private fun of(f: (Float) -> Float): PolarShape {
            val r = FloatArray(N) { f(it * STEP) }
            val m = r.max()
            for (i in r.indices) r[i] /= m
            return PolarShape(r)
        }

        /** Scalloped "cookie" with [k] lobes; [depth] controls how deep the scallops go. */
        fun cookie(k: Int, depth: Float) = of { a -> 1f - depth + depth * smoothLobe(cos(k * a)) }

        /** Spiky "burst" with [k] points. */
        fun burst(k: Int, depth: Float) = of { a -> 1f - depth + depth * sharpLobe(cos(k * a)) }

        fun ellipse(ratio: Float) = of { a -> ratio / hypot(ratio * cos(a), sin(a)) }

        fun circle() = of { 1f }

        /** Rounded regular polygon with [k] sides. */
        fun polygon(k: Int, roundness: Float) = of { a ->
            val sector = (2 * PI / k).toFloat()
            val local = ((a % sector) + sector) % sector - sector / 2
            val flat = (cos(PI / k) / cos(local.toDouble())).toFloat()
            flat + (1f - flat) * roundness
        }

        private fun smoothLobe(c: Float) = (c + 1f) / 2f
        private fun sharpLobe(c: Float): Float { val t = (c + 1f) / 2f; return t * t * t }

        /** The seven-shape sequence of the M3 Expressive loading indicator. */
        val loadingSequence: List<PolarShape> by lazy {
            listOf(burst(10, 0.18f), cookie(9, 0.12f), polygon(5, 0.45f), ellipse(0.62f), cookie(8, 0.08f), cookie(4, 0.16f), ellipse(0.8f))
        }

        val cookie12 by lazy { burst(12, 0.10f).lerp(cookie(12, 0.10f), 0.5f) }
        val clover by lazy { cookie(4, 0.34f) }
        val flower by lazy { cookie(8, 0.16f) }
        val sunny by lazy { burst(8, 0.12f) }
    }
}
