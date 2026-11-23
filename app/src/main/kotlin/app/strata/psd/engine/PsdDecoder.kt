package app.strata.psd.engine

import java.util.zip.Inflater
import kotlin.math.pow

/** ARGB (non-premultiplied) pixels positioned on the (scaled) canvas. */
class Raster(val left: Int, val top: Int, val width: Int, val height: Int, val pixels: IntArray) {
    val right get() = left + width
    val bottom get() = top + height
}

/** 8-bit coverage plane positioned on the (scaled) canvas; [outside] applies beyond its bounds. */
class Plane(val left: Int, val top: Int, val width: Int, val height: Int, val data: ByteArray, val outside: Int) {
    fun at(x: Int, y: Int): Int {
        val lx = x - left; val ly = y - top
        if (lx < 0 || ly < 0 || lx >= width || ly >= height) return outside
        return data[ly * width + lx].toInt() and 0xFF
    }
}

/**
 * Decodes channel data. Every decode works at an integer downscale factor [n] (1 = full resolution)
 * using an area-average filter so huge documents can be previewed cheaply.
 */
class PsdDecoder(private val doc: PsdDocument, private val r: PsdReader) {

    private val bps = if (doc.depth == 1) 0 else doc.depth / 8

    fun scaledSize(n: Int) = Pair(ceilDiv(doc.width, n), ceilDiv(doc.height, n))

    // ---------------------------------------------------------------- layers

    /** Decodes a layer's pixels (with its layer mask applied) at downscale [n]. Null if no pixels. */
    fun decodeLayer(layer: Layer, n: Int): Raster? {
        val rect = layer.rect
        if (rect.isEmpty) return null
        val w = rect.width; val h = rect.height
        val colorCount = colorChannels()
        val planes = arrayOfNulls<ByteArray>(colorCount)
        var alpha: ByteArray? = null
        for (c in layer.channels) {
            if (c.id in 0 until colorCount) planes[c.id] = decodeChannel(c, w, h, isAlpha = false)
            else if (c.id == -1) alpha = decodeChannel(c, w, h, isAlpha = true)
        }
        if (planes.all { it == null } && alpha == null) return null
        val full = IntArray(w * h)
        toArgb(planes, alpha, full, w * h)
        var raster = Raster(rect.left, rect.top, w, h, full)
        if (n > 1) raster = downscale(raster, n)
        val mask = decodeMask(layer, n)
        if (mask != null) applyMask(raster, mask)
        return raster
    }

    /** User mask (channel -2) as a coverage plane at downscale [n], honoring disabled/invert flags. */
    fun decodeMask(layer: Layer, n: Int): Plane? {
        val m = layer.mask ?: return null
        if (m.disabled) return null
        val ch = layer.channels.firstOrNull { it.id == -2 } ?: return null
        val rect = m.rect
        var plane = if (rect.isEmpty) Plane(0, 0, 0, 0, ByteArray(0), m.defaultColor) else {
            val data = decodeChannel(ch, rect.width, rect.height, isAlpha = true)
            Plane(rect.left, rect.top, rect.width, rect.height, data, m.defaultColor)
        }
        if (n > 1) plane = downscalePlane(plane, n)
        if (m.invert) {
            val d = plane.data
            for (i in d.indices) d[i] = (255 - (d[i].toInt() and 0xFF)).toByte()
            plane = Plane(plane.left, plane.top, plane.width, plane.height, d, 255 - plane.outside)
        }
        return plane
    }

    private fun applyMask(raster: Raster, mask: Plane) {
        val px = raster.pixels
        for (y in 0 until raster.height) {
            val cy = raster.top + y
            for (x in 0 until raster.width) {
                val i = y * raster.width + x
                val p = px[i]
                val a = p ushr 24
                if (a == 0) continue
                val m = mask.at(raster.left + x, cy)
                if (m == 255) continue
                px[i] = (div255(a * m) shl 24) or (p and 0xFFFFFF)
            }
        }
    }

    // ---------------------------------------------------------------- merged image

    /** Decodes the flattened composite Photoshop stored in the file. */
    fun decodeMerged(n: Int): Raster {
        if (doc.mergedOffset < 0) throw PsdException("File has no composite image")
        val w = doc.width; val h = doc.height
        r.seek(doc.mergedOffset)
        val compression = r.u16()
        val colorCount = colorChannels()
        val wantAlpha = doc.mergedHasAlpha && doc.channels > colorCount && doc.mode != ColorMode.INDEXED && doc.mode != ColorMode.BITMAP
        val total = minOf(doc.channels, colorCount + if (wantAlpha) 1 else 0)
        val rowBytes = rowBytes(w)
        val planeBytes = rowBytes.toLong() * h
        val planes = arrayOfNulls<ByteArray>(colorCount)
        var alpha: ByteArray? = null

        fun store(ch: Int, raw: ByteArray) {
            val p = toPlane8(raw, w, h, isAlpha = ch >= colorCount)
            if (ch < colorCount) planes[ch] = p else alpha = p
        }

        when (compression) {
            0 -> {
                // Raw composite always runs to the end of the file; trust that over section lengths.
                val tail = r.length - planeBytes * doc.channels
                if (tail - 2 >= 0 && tail != r.position) r.seek(tail)
                for (ch in 0 until total) store(ch, r.bytes(checkedSize(planeBytes)))
            }
            1 -> {
                val counts = IntArray(doc.channels * h) { if (doc.isPsb) r.i32() else r.u16() }
                for (ch in 0 until total) {
                    val out = ByteArray(checkedSize(planeBytes))
                    for (y in 0 until h) unpackBits(counts[ch * h + y], out, y * rowBytes, rowBytes)
                    store(ch, out)
                }
            }
            2, 3 -> {
                val all = inflate(r.bytes(checkedSize(r.length - r.position)), checkedSize(planeBytes * total))
                for (ch in 0 until total) {
                    val raw = all.copyOfRange((ch * planeBytes).toInt(), ((ch + 1) * planeBytes).toInt())
                    if (compression == 3) unpredict(raw, w, h)
                    store(ch, raw)
                }
            }
            else -> throw PsdException("Unknown compression $compression")
        }
        val full = IntArray(w * h)
        toArgb(planes, alpha, full, w * h)
        val raster = Raster(0, 0, w, h, full)
        return if (n > 1) downscale(raster, n) else raster
    }

    // ---------------------------------------------------------------- channel decoding

    private fun colorChannels() = when (doc.mode) {
        ColorMode.RGB, ColorMode.LAB -> 3
        ColorMode.CMYK -> 4
        else -> 1
    }

    private fun rowBytes(w: Int) = if (doc.depth == 1) (w + 7) / 8 else w * bps

    private fun checkedSize(n: Long): Int {
        if (n < 0 || n > Int.MAX_VALUE - 16) throw PsdException("Image too large to decode")
        return n.toInt()
    }

    /** Decodes one channel to an 8-bit plane of w*h. */
    private fun decodeChannel(c: ChannelInfo, w: Int, h: Int, isAlpha: Boolean): ByteArray {
        val rowBytes = rowBytes(w)
        val size = checkedSize(rowBytes.toLong() * h)
        if (c.length < 2) return ByteArray(w * h) { if (isAlpha) -1 else 0 }
        r.seek(c.offset)
        val compression = r.u16()
        val dataLen = c.length - 2
        val raw: ByteArray = when (compression) {
            0 -> { val out = ByteArray(size); r.readFully(out, 0, minOf(size.toLong(), dataLen).toInt()); out }
            1 -> {
                val counts = rowCounts(h, dataLen)
                val out = ByteArray(size)
                for (y in 0 until h) unpackBits(counts[y], out, y * rowBytes, rowBytes)
                out
            }
            2, 3 -> {
                val out = inflate(r.bytes(checkedSize(dataLen)), size)
                if (compression == 3) unpredict(out, w, h)
                out
            }
            else -> throw PsdException("Unknown compression $compression")
        }
        return toPlane8(raw, w, h, isAlpha)
    }

    /**
     * Reads RLE row byte counts. PSB uses 4-byte counts, PSD 2-byte; some writers get this wrong,
     * so the width is validated against the channel length when known.
     */
    private fun rowCounts(h: Int, dataLen: Long): IntArray {
        val start = r.position
        val wide = doc.isPsb
        val counts = IntArray(h) { if (wide) r.i32() else r.u16() }
        if (dataLen > 0) {
            val expected = counts.fold(0L) { a, c -> a + c } + h.toLong() * (if (wide) 4 else 2)
            if (expected != dataLen) {
                r.seek(start)
                val alt = IntArray(h) { if (wide) r.u16() else r.i32() }
                val altExpected = alt.fold(0L) { a, c -> a + c } + h.toLong() * (if (wide) 2 else 4)
                if (altExpected == dataLen) return alt
                r.seek(start + h.toLong() * (if (wide) 4 else 2))
            }
        }
        return counts
    }

    /** PackBits decode of [count] input bytes into [out] at [off], at most [len] output bytes. */
    private fun unpackBits(count: Int, out: ByteArray, off: Int, len: Int) {
        if (count <= 0) return
        val src = r.bytes(count)
        var i = 0; var o = off
        val end = off + len
        while (i < count && o < end) {
            val hdr = src[i++].toInt()
            if (hdr >= 0) {
                val n = minOf(hdr + 1, count - i, end - o)
                System.arraycopy(src, i, out, o, n)
                i += hdr + 1; o += n
            } else if (hdr != -128) {
                if (i >= count) break
                val v = src[i++]
                val n = minOf(1 - hdr, end - o)
                java.util.Arrays.fill(out, o, o + n, v)
                o += n
            }
        }
    }

    private fun inflate(input: ByteArray, size: Int): ByteArray {
        val inf = Inflater()
        val out = ByteArray(size)
        try {
            inf.setInput(input)
            var o = 0
            while (o < size && !inf.finished()) {
                val n = inf.inflate(out, o, size - o)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                o += n
            }
        } finally { inf.end() }
        return out
    }

    /** Undo ZIP-with-prediction delta encoding in place. */
    private fun unpredict(d: ByteArray, w: Int, h: Int) {
        when (doc.depth) {
            8 -> for (y in 0 until h) { val o = y * w; for (x in 1 until w) d[o + x] = (d[o + x] + d[o + x - 1]).toByte() }
            16 -> for (y in 0 until h) {
                val o = y * w * 2
                var prev = ((d[o].toInt() and 0xFF) shl 8) or (d[o + 1].toInt() and 0xFF)
                for (x in 1 until w) {
                    val p = o + x * 2
                    val v = ((((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF)) + prev) and 0xFFFF
                    d[p] = (v shr 8).toByte(); d[p + 1] = v.toByte(); prev = v
                }
            }
            32 -> {
                val rb = w * 4
                val tmp = ByteArray(rb)
                for (y in 0 until h) {
                    val o = y * rb
                    for (x in 1 until rb) d[o + x] = (d[o + x] + d[o + x - 1]).toByte()
                    // Bytes are stored planar per row: all byte0s, then byte1s, ...
                    for (x in 0 until w) {
                        tmp[x * 4] = d[o + x]; tmp[x * 4 + 1] = d[o + w + x]
                        tmp[x * 4 + 2] = d[o + 2 * w + x]; tmp[x * 4 + 3] = d[o + 3 * w + x]
                    }
                    System.arraycopy(tmp, 0, d, o, rb)
                }
            }
        }
    }

    /** Converts raw samples of the document depth to an 8-bit plane. */
    private fun toPlane8(raw: ByteArray, w: Int, h: Int, isAlpha: Boolean): ByteArray = when (doc.depth) {
        8 -> raw
        16 -> ByteArray(w * h) { i ->
            val v = ((raw[i * 2].toInt() and 0xFF) shl 8) or (raw[i * 2 + 1].toInt() and 0xFF)
            ((v * 255 + 32767) / 65535).toByte()
        }
        32 -> ByteArray(w * h) { i ->
            val p = i * 4
            val bits = ((raw[p].toInt() and 0xFF) shl 24) or ((raw[p + 1].toInt() and 0xFF) shl 16) or
                ((raw[p + 2].toInt() and 0xFF) shl 8) or (raw[p + 3].toInt() and 0xFF)
            var f = java.lang.Float.intBitsToFloat(bits)
            if (f.isNaN()) f = 0f
            f = f.coerceIn(0f, 1f)
            // 32-bit documents are linear; encode to display gamma for color (not alpha).
            (if (isAlpha) f * 255f + 0.5f else LINEAR_TO_SRGB[(f * 4095f + 0.5f).toInt()].toFloat()).toInt().toByte()
        }
        1 -> {
            val rb = (w + 7) / 8
            ByteArray(w * h) { i ->
                val y = i / w; val x = i % w
                if ((raw[y * rb + x / 8].toInt() shr (7 - x % 8)) and 1 == 1) 0 else -1
            }
        }
        else -> throw PsdException("Unsupported depth ${doc.depth}")
    }

    private fun toArgb(planes: Array<ByteArray?>, alpha: ByteArray?, out: IntArray, count: Int) {
        fun p(c: Int, i: Int): Int { val pl = planes[c] ?: return if (doc.mode == ColorMode.CMYK) 255 else 0; return pl[i].toInt() and 0xFF }
        fun a(i: Int) = if (alpha == null) 255 else alpha[i].toInt() and 0xFF
        when (doc.mode) {
            ColorMode.RGB -> for (i in 0 until count) out[i] = (a(i) shl 24) or (p(0, i) shl 16) or (p(1, i) shl 8) or p(2, i)
            ColorMode.CMYK -> for (i in 0 until count) {
                // Stored inverted: 255 = no ink.
                val k = p(3, i)
                out[i] = (a(i) shl 24) or (div255(p(0, i) * k) shl 16) or (div255(p(1, i) * k) shl 8) or div255(p(2, i) * k)
            }
            ColorMode.LAB -> for (i in 0 until count) out[i] = (a(i) shl 24) or labToRgb(p(0, i), p(1, i), p(2, i))
            ColorMode.INDEXED -> {
                val pal = doc.palette
                for (i in 0 until count) {
                    val idx = p(0, i)
                    out[i] = pal?.get(idx) ?: ((255 shl 24) or (idx * 0x010101))
                }
            }
            else -> for (i in 0 until count) { val g = p(0, i); out[i] = (a(i) shl 24) or (g * 0x010101) }
        }
    }

    // ---------------------------------------------------------------- scaling

    private fun downscale(src: Raster, n: Int): Raster {
        val l = Math.floorDiv(src.left, n); val t = Math.floorDiv(src.top, n)
        val rgt = ceilDiv(src.right, n); val btm = ceilDiv(src.bottom, n)
        val dw = rgt - l; val dh = btm - t
        val sa = LongArray(dw); val sr = LongArray(dw); val sg = LongArray(dw); val sb = LongArray(dw)
        val out = IntArray(dw * dh)
        val area = n * n
        var sy = 0
        for (dy in 0 until dh) {
            java.util.Arrays.fill(sa, 0); java.util.Arrays.fill(sr, 0); java.util.Arrays.fill(sg, 0); java.util.Arrays.fill(sb, 0)
            val yEnd = (t + dy + 1) * n - src.top
            while (sy < src.height && sy < yEnd) {
                val row = sy * src.width
                for (sx in 0 until src.width) {
                    val p = src.pixels[row + sx]
                    val a = (p ushr 24).toLong()
                    if (a == 0L) continue
                    val dx = Math.floorDiv(src.left + sx, n) - l
                    sa[dx] += a; sr[dx] += a * ((p shr 16) and 0xFF); sg[dx] += a * ((p shr 8) and 0xFF); sb[dx] += a * (p and 0xFF)
                }
                sy++
            }
            val o = dy * dw
            for (dx in 0 until dw) {
                val a = sa[dx]
                if (a == 0L) continue
                out[o + dx] = (((a + area / 2) / area).toInt().coerceAtMost(255) shl 24) or
                    (((sr[dx] + a / 2) / a).toInt() shl 16) or (((sg[dx] + a / 2) / a).toInt() shl 8) or ((sb[dx] + a / 2) / a).toInt()
            }
        }
        return Raster(l, t, dw, dh, out)
    }

    private fun downscalePlane(src: Plane, n: Int): Plane {
        if (src.width == 0) return src
        val l = Math.floorDiv(src.left, n); val t = Math.floorDiv(src.top, n)
        val dw = ceilDiv(src.left + src.width, n) - l; val dh = ceilDiv(src.top + src.height, n) - t
        val out = ByteArray(dw * dh)
        val area = n * n
        for (dy in 0 until dh) for (dx in 0 until dw) {
            var sum = 0
            val x0 = (l + dx) * n; val y0 = (t + dy) * n
            for (yy in y0 until y0 + n) for (xx in x0 until x0 + n) sum += src.at(xx, yy)
            out[dy * dw + dx] = ((sum + area / 2) / area).toByte()
        }
        return Plane(l, t, dw, dh, out, src.outside)
    }

    companion object {
        private val LINEAR_TO_SRGB = IntArray(4096) { i ->
            val f = i / 4095.0
            val s = if (f <= 0.0031308) f * 12.92 else 1.055 * f.pow(1 / 2.4) - 0.055
            (s * 255 + 0.5).toInt().coerceIn(0, 255)
        }

        fun ceilDiv(a: Int, b: Int) = -Math.floorDiv(-a, b)

        private fun labToRgb(l8: Int, a8: Int, b8: Int): Int {
            val l = l8 * 100.0 / 255.0; val a = a8 - 128.0; val b = b8 - 128.0
            var y = (l + 16) / 116; var x = a / 500 + y; var z = y - b / 200
            fun f(t: Double) = if (t * t * t > 0.008856) t * t * t else (t - 16.0 / 116) / 7.787
            x = 0.95047 * f(x); y = f(y); z = 1.08883 * f(z)
            fun g(c: Double): Int {
                val v = if (c > 0.0031308) 1.055 * c.pow(1 / 2.4) - 0.055 else 12.92 * c
                return (v * 255 + 0.5).toInt().coerceIn(0, 255)
            }
            return (g(x * 3.2406 + y * -1.5372 + z * -0.4986) shl 16) or
                (g(x * -0.9689 + y * 1.8758 + z * 0.0415) shl 8) or g(x * 0.0557 + y * -0.2040 + z * 1.0570)
        }
    }
}

internal fun div255(v: Int): Int { val t = v + 128; return (t + (t shr 8)) shr 8 }
