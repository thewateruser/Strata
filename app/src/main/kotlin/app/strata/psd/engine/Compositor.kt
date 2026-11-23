package app.strata.psd.engine

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Software compositor that reproduces Photoshop's layer model:
 * blend modes, opacity x fill, layer & group masks, clipping groups, pass-through and isolated groups.
 *
 * Adjustment layers and layer effects are not rendered (they need Photoshop's engine); the
 * caller can detect that via [unsupported].
 */
class Compositor(
    private val doc: PsdDocument,
    private val scale: Int,
    /** Supplies decoded layer pixels (cached by the caller). */
    private val pixels: (Layer) -> Raster?,
    /** Supplies decoded masks (cached by the caller). */
    private val masks: (Layer) -> Plane?,
    private val cancelled: () -> Boolean = { false },
) {
    val width = PsdDecoder.ceilDiv(doc.width, scale)
    val height = PsdDecoder.ceilDiv(doc.height, scale)

    /** Layers that influenced the document but could not be reproduced. */
    val unsupported = LinkedHashSet<Layer>()

    fun render(): IntArray {
        val canvas = IntArray(width * height)
        compositeList(doc.roots.asReversed(), canvas)
        return canvas
    }

    private class CancelledException : RuntimeException()

    private fun checkCancel() { if (cancelled()) throw CancelledException() }

    fun renderOrNull(): IntArray? = try { render() } catch (_: CancelledException) { null }

    /** [layers] are bottom-most first. */
    private fun compositeList(layers: List<Layer>, dst: IntArray) {
        var i = 0
        while (i < layers.size) {
            checkCancel()
            val base = layers[i]
            var j = i + 1
            while (j < layers.size && layers[j].clipping && !base.clipping) j++
            val clipped = layers.subList(i + 1, j)
            if (base.visible) {
                val visibleClipped = clipped.filter { it.visible }
                if (visibleClipped.isEmpty()) {
                    renderNode(base, dst, base.blend, opacityOf(base), atop = false)
                } else {
                    // Clipping group: base + clipped layers rendered in isolation, then blended
                    // with the base layer's mode and opacity.
                    val buf = IntArray(width * height)
                    renderNode(base, buf, Blend.NORMAL, if (base.isGroup) opacityOf(base) else fillOf(base), atop = false)
                    for (c in visibleClipped) renderNode(c, buf, c.blend, opacityOf(c), atop = true)
                    blend(dst, Raster(0, 0, width, height, buf), if (base.blend == Blend.PASS_THROUGH) Blend.NORMAL else base.blend,
                        if (base.isGroup) 1f else base.opacity / 255f, atop = false)
                }
            }
            i = j
        }
    }

    private fun opacityOf(l: Layer) = l.opacity / 255f * if (l.isGroup) 1f else l.fillOpacity / 255f
    private fun fillOf(l: Layer) = l.fillOpacity / 255f

    private fun renderNode(layer: Layer, dst: IntArray, mode: Blend, opacity: Float, atop: Boolean) {
        if (opacity <= 0f) return
        when (layer.kind) {
            LayerKind.GROUP -> {
                if (layer.children.isEmpty()) return
                val mask = masks(layer)
                if (mode == Blend.PASS_THROUGH && !atop) {
                    if (opacity >= 1f && mask == null) {
                        compositeList(layer.children.asReversed(), dst)
                    } else {
                        val copy = dst.copyOf()
                        compositeList(layer.children.asReversed(), copy)
                        lerpInto(dst, copy, opacity, mask)
                    }
                } else {
                    val buf = IntArray(width * height)
                    compositeList(layer.children.asReversed(), buf)
                    if (mask != null) applyPlane(buf, mask)
                    blend(dst, Raster(0, 0, width, height, buf), if (mode == Blend.PASS_THROUGH) Blend.NORMAL else mode, opacity, atop)
                }
            }
            LayerKind.ADJUSTMENT -> unsupported.add(layer)
            else -> {
                val raster = pixels(layer)
                if (raster != null) {
                    if (layer.hasEffects) unsupported.add(layer)
                    blend(dst, raster, mode, opacity, atop)
                } else if (layer.solidColor != null) {
                    // Solid color fill without stored pixels: fill the canvas through the mask.
                    val fill = IntArray(width * height) { layer.solidColor }
                    masks(layer)?.let { applyPlane(fill, it) }
                    blend(dst, Raster(0, 0, width, height, fill), mode, opacity, atop)
                } else if (layer.kind == LayerKind.FILL || layer.kind == LayerKind.SHAPE) {
                    unsupported.add(layer)
                }
            }
        }
    }

    private fun applyPlane(buf: IntArray, plane: Plane) {
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            val p = buf[i]
            val a = p ushr 24
            if (a == 0) continue
            val m = plane.at(x, y)
            if (m != 255) buf[i] = (div255(a * m) shl 24) or (p and 0xFFFFFF)
        }
    }

    /** dst = lerp(dst, src, opacity * mask) per channel (pass-through group with opacity/mask). */
    private fun lerpInto(dst: IntArray, src: IntArray, opacity: Float, mask: Plane?) {
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            var t = opacity
            if (mask != null) t *= mask.at(x, y) / 255f
            if (t <= 0f) continue
            val a = dst[i]; val b = src[i]
            if (t >= 1f || a == b) { dst[i] = b; continue }
            val aa = (a ushr 24); val ba = (b ushr 24)
            val oa = aa + (ba - aa) * t
            if (oa <= 0f) { dst[i] = 0; continue }
            fun ch(s: Int): Int {
                val ca = ((a shr s) and 0xFF) * aa; val cb = ((b shr s) and 0xFF) * ba
                return ((ca + (cb - ca) * t) / oa + 0.5f).toInt().coerceIn(0, 255)
            }
            dst[i] = ((oa + 0.5f).toInt() shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }

    /**
     * Composites [src] onto [dst] with blend [mode]. With [atop] the destination alpha is preserved
     * (used for layers clipped to a base layer).
     */
    private fun blend(dst: IntArray, src: Raster, mode: Blend, opacity: Float, atop: Boolean) {
        val x0 = max(0, src.left); val y0 = max(0, src.top)
        val x1 = min(width, src.right); val y1 = min(height, src.bottom)
        if (x0 >= x1 || y0 >= y1) return
        val op = (opacity * 255f + 0.5f).toInt().coerceIn(0, 255)
        val sp = src.pixels
        for (y in y0 until y1) {
            if ((y and 63) == 0) checkCancel()
            var si = (y - src.top) * src.width + (x0 - src.left)
            var di = y * width + x0
            for (x in x0 until x1) {
                val s = sp[si++]
                val d = dst[di]
                var sa = div255((s ushr 24) * op)
                if (sa != 0) {
                    if (mode == Blend.DISSOLVE) sa = if (noise(x, y) < sa) 255 else 0
                    if (sa != 0) dst[di] = blendPixel(d, s, sa, mode, atop)
                }
                di++
            }
        }
    }

    private fun noise(x: Int, y: Int): Int {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        return (h xor (h ushr 16)) and 0xFF
    }

    private fun blendPixel(d: Int, s: Int, sa: Int, mode: Blend, atop: Boolean): Int {
        val da = d ushr 24
        if (atop && da == 0) return d
        if ((mode == Blend.NORMAL || mode == Blend.DISSOLVE || da == 0) && !atop) {
            // Fast path: plain "over" (any mode over transparent pixels is normal).
            if (sa == 255) return (s and 0xFFFFFF) or (0xFF shl 24)
            val oa = sa + div255(da * (255 - sa))
            if (oa == 0) return 0
            val wd = div255(da * (255 - sa))
            fun ch(sh: Int) = (((s shr sh) and 0xFF) * sa + ((d shr sh) and 0xFF) * wd + oa / 2) / oa
            return (oa shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        val fa = sa / 255f; val fb = da / 255f
        val sr = ((s shr 16) and 0xFF) / 255f; val sg = ((s shr 8) and 0xFF) / 255f; val sb = (s and 0xFF) / 255f
        val br = ((d shr 16) and 0xFF) / 255f; val bg = ((d shr 8) and 0xFF) / 255f; val bb = (d and 0xFF) / 255f

        var rr: Float; var rg: Float; var rb: Float
        when (mode) {
            Blend.HUE, Blend.SATURATION, Blend.COLOR, Blend.LUMINOSITY, Blend.DARKER_COLOR, Blend.LIGHTER_COLOR -> {
                val out = FloatArray(3)
                nonSeparable(mode, br, bg, bb, sr, sg, sb, out)
                rr = out[0]; rg = out[1]; rb = out[2]
            }
            else -> { rr = sep(mode, br, sr); rg = sep(mode, bg, sg); rb = sep(mode, bb, sb) }
        }
        if (atop) {
            // Result keeps backdrop alpha; mix source (already blended with backdrop) by source alpha.
            val r = br + (rr - br) * fa; val g = bg + (rg - bg) * fa; val b = bb + (rb - bb) * fa
            return (da shl 24) or (to8(r) shl 16) or (to8(g) shl 8) or to8(b)
        }
        val oa = fa + fb * (1 - fa)
        // W3C compositing: Cs' = (1 - ab) * Cs + ab * B(Cb, Cs)
        fun ch(cs: Float, cb: Float, bl: Float) = (fa * ((1 - fb) * cs + fb * bl) + fb * cb * (1 - fa)) / oa
        return (to8(oa) shl 24) or (to8(ch(sr, br, rr)) shl 16) or (to8(ch(sg, bg, rg)) shl 8) or to8(ch(sb, bb, rb))
    }

    private fun to8(f: Float) = (f * 255f + 0.5f).toInt().coerceIn(0, 255)

    private fun sep(mode: Blend, b: Float, s: Float): Float = when (mode) {
        Blend.MULTIPLY -> b * s
        Blend.SCREEN -> b + s - b * s
        Blend.OVERLAY -> if (b <= 0.5f) 2 * b * s else 1 - 2 * (1 - b) * (1 - s)
        Blend.HARD_LIGHT -> if (s <= 0.5f) 2 * b * s else 1 - 2 * (1 - b) * (1 - s)
        Blend.SOFT_LIGHT -> if (s <= 0.5f) 2 * b * s + b * b * (1 - 2 * s) else 2 * b * (1 - s) + sqrt(b) * (2 * s - 1)
        Blend.DARKEN -> min(b, s)
        Blend.LIGHTEN -> max(b, s)
        Blend.COLOR_DODGE -> dodge(b, s)
        Blend.COLOR_BURN -> burn(b, s)
        Blend.LINEAR_BURN -> max(0f, b + s - 1)
        Blend.LINEAR_DODGE -> min(1f, b + s)
        Blend.VIVID_LIGHT -> if (s <= 0.5f) burn(b, 2 * s) else dodge(b, 2 * s - 1)
        Blend.LINEAR_LIGHT -> (b + 2 * s - 1).coerceIn(0f, 1f)
        Blend.PIN_LIGHT -> if (s <= 0.5f) min(b, 2 * s) else max(b, 2 * s - 1)
        Blend.HARD_MIX -> if (b + s >= 1f) 1f else 0f
        Blend.DIFFERENCE -> kotlin.math.abs(b - s)
        Blend.EXCLUSION -> b + s - 2 * b * s
        Blend.SUBTRACT -> max(0f, b - s)
        Blend.DIVIDE -> if (s <= 0f) (if (b <= 0f) 0f else 1f) else min(1f, b / s)
        else -> s
    }

    private fun dodge(b: Float, s: Float) = if (b <= 0f) 0f else if (s >= 1f) 1f else min(1f, b / (1 - s))
    private fun burn(b: Float, s: Float) = if (b >= 1f) 1f else if (s <= 0f) 0f else 1 - min(1f, (1 - b) / s)

    private fun lum(r: Float, g: Float, b: Float) = 0.3f * r + 0.59f * g + 0.11f * b

    private fun nonSeparable(mode: Blend, br: Float, bg: Float, bb: Float, sr: Float, sg: Float, sb: Float, out: FloatArray) {
        when (mode) {
            Blend.DARKER_COLOR -> if (lum(sr, sg, sb) < lum(br, bg, bb)) set(out, sr, sg, sb) else set(out, br, bg, bb)
            Blend.LIGHTER_COLOR -> if (lum(sr, sg, sb) > lum(br, bg, bb)) set(out, sr, sg, sb) else set(out, br, bg, bb)
            Blend.HUE -> { setSat(sr, sg, sb, sat(br, bg, bb), out); setLum(out[0], out[1], out[2], lum(br, bg, bb), out) }
            Blend.SATURATION -> { setSat(br, bg, bb, sat(sr, sg, sb), out); setLum(out[0], out[1], out[2], lum(br, bg, bb), out) }
            Blend.COLOR -> setLum(sr, sg, sb, lum(br, bg, bb), out)
            else -> setLum(br, bg, bb, lum(sr, sg, sb), out) // LUMINOSITY
        }
    }

    private fun set(o: FloatArray, r: Float, g: Float, b: Float) { o[0] = r; o[1] = g; o[2] = b }
    private fun sat(r: Float, g: Float, b: Float) = max(r, max(g, b)) - min(r, min(g, b))

    private fun setLum(r: Float, g: Float, b: Float, l: Float, o: FloatArray) {
        val d = l - lum(r, g, b)
        var cr = r + d; var cg = g + d; var cb = b + d
        val ll = lum(cr, cg, cb)
        val n = min(cr, min(cg, cb)); val x = max(cr, max(cg, cb))
        if (n < 0f) { cr = ll + (cr - ll) * ll / (ll - n); cg = ll + (cg - ll) * ll / (ll - n); cb = ll + (cb - ll) * ll / (ll - n) }
        if (x > 1f) { cr = ll + (cr - ll) * (1 - ll) / (x - ll); cg = ll + (cg - ll) * (1 - ll) / (x - ll); cb = ll + (cb - ll) * (1 - ll) / (x - ll) }
        set(o, cr, cg, cb)
    }

    private fun setSat(r: Float, g: Float, b: Float, s: Float, o: FloatArray) {
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        if (mx <= mn) { set(o, 0f, 0f, 0f); return }
        fun f(c: Float) = when (c) { mx -> s; mn -> 0f; else -> (c - mn) * s / (mx - mn) }
        set(o, f(r), f(g), f(b))
    }
}
