package app.strata.psd.engine

/**
 * Parses the structure of a PSD/PSB file. Pixel data is not decoded here; only offsets are recorded
 * so layers can be decoded lazily (and at any scale) by [PsdDecoder].
 */
object PsdParser {

    private val BIG_KEYS = setOf("LMsk", "Lr16", "Lr32", "Layr", "Mt16", "Mt32", "Mtrn", "Alph", "FMsk", "lnk2", "FEid", "FXid", "PxSD")
    private val ADJUSTMENT_KEYS = mapOf(
        "levl" to "Levels", "curv" to "Curves", "brit" to "Brightness/Contrast", "hue " to "Hue/Saturation",
        "hue2" to "Hue/Saturation", "blnc" to "Color Balance", "vibA" to "Vibrance", "phfl" to "Photo Filter",
        "mixr" to "Channel Mixer", "clrL" to "Color Lookup", "nvrt" to "Invert", "post" to "Posterize",
        "thrs" to "Threshold", "grdm" to "Gradient Map", "selc" to "Selective Color", "expA" to "Exposure",
        "blwh" to "Black & White",
    )
    private val FILL_KEYS = mapOf("SoCo" to "Solid Color", "GdFl" to "Gradient", "PtFl" to "Pattern")

    fun parse(r: PsdReader): PsdDocument {
        r.seek(0)
        if (r.ascii(4) != "8BPS") throw PsdException("Not a Photoshop file")
        val version = r.u16()
        if (version != 1 && version != 2) throw PsdException("Unknown PSD version $version")
        val big = version == 2
        r.skip(6)
        val channels = r.u16()
        val height = r.i32()
        val width = r.i32()
        val depth = r.u16()
        val mode = ColorMode.of(r.u16())
        if (width <= 0 || height <= 0) throw PsdException("Invalid canvas size ${width}x$height")
        if (depth !in intArrayOf(1, 8, 16, 32)) throw PsdException("Unsupported bit depth $depth")

        // Color mode data
        val cmLen = r.u32()
        var palette: IntArray? = null
        val cmStart = r.position
        if (mode == ColorMode.INDEXED && cmLen >= 768) {
            val raw = r.bytes(768)
            palette = IntArray(256) { i ->
                (0xFF shl 24) or ((raw[i].toInt() and 0xFF) shl 16) or ((raw[256 + i].toInt() and 0xFF) shl 8) or (raw[512 + i].toInt() and 0xFF)
            }
        }
        r.seek(cmStart + cmLen)

        // Image resources
        val resLen = r.u32()
        val resEnd = r.position + resLen
        var dpi: Double? = null
        var hasReal = true
        var writer: String? = null
        var icc: String? = null
        while (r.position + 12 <= resEnd) {
            val sig = r.ascii(4)
            if (sig != "8BIM" && sig != "MeSa" && sig != "AgHg" && sig != "PHUT" && sig != "DCSR") break
            val id = r.u16()
            r.pascal(2)
            val size = r.u32()
            val start = r.position
            try {
                when (id) {
                    0x03ED -> if (size >= 16) {
                        dpi = r.i32() / 65536.0
                    }
                    0x0421 -> if (size >= 5) {
                        r.i32()
                        hasReal = r.u8() != 0
                        writer = r.unicode().ifBlank { null }
                    }
                    0x0417 -> if (size >= 2 && palette != null) {
                        val idx = r.u16()
                        if (idx in 0..255) palette[idx] = 0
                    }
                    0x040F -> icc = iccDescription(r, size.toInt())
                }
            } catch (_: Exception) { /* tolerate malformed resources */ }
            r.seek(minOf(resEnd, start + size + (size and 1)))
        }
        r.seek(resEnd)

        // Layer and mask information
        val lmLen = r.len(big)
        val lmStart = r.position
        val lmEnd = lmStart + lmLen
        var layers: List<Layer> = emptyList()
        var mergedAlpha = false
        var count = 0
        if (lmLen > 0) {
            val liLen = r.len(big)
            val liStart = r.position
            if (liLen > 0) {
                val res = readLayerInfo(r, big)
                layers = res.first; mergedAlpha = res.second; count = res.third
            }
            r.seek(liStart + liLen)
            // Global layer mask info
            if (r.position + 4 <= lmEnd) {
                val gm = r.u32()
                r.seek(minOf(lmEnd, r.position + gm))
            }
            // Additional layer information: 16/32-bit files keep their layers in Lr16 / Lr32.
            if (layers.isEmpty()) {
                forEachTaggedBlock(r, lmEnd, big) { key, length ->
                    if ((key == "Lr16" || key == "Lr32" || key == "Layr") && length > 0 && layers.isEmpty()) {
                        val res = readLayerInfo(r, big)
                        layers = res.first; mergedAlpha = res.second; count = res.third
                    }
                }
            }
        }
        val mergedOffset = if (lmEnd + 2 <= r.length) lmEnd else -1L

        return PsdDocument(
            width = width, height = height, channels = channels, depth = depth, mode = mode, isPsb = big,
            palette = palette, dpi = dpi, hasRealMergedData = hasReal, writer = writer,
            roots = layers, layerCount = count, mergedHasAlpha = mergedAlpha, mergedOffset = mergedOffset,
            fileSize = r.length, iccProfileName = icc,
        )
    }

    private fun iccDescription(r: PsdReader, size: Int): String? {
        if (size < 132 || size > 8 * 1024 * 1024) return null
        val data = r.bytes(size)
        fun i32(p: Int) = ((data[p].toInt() and 0xFF) shl 24) or ((data[p + 1].toInt() and 0xFF) shl 16) or ((data[p + 2].toInt() and 0xFF) shl 8) or (data[p + 3].toInt() and 0xFF)
        val tags = i32(128)
        for (t in 0 until minOf(tags, 100)) {
            val base = 132 + t * 12
            if (base + 12 > size) break
            if (String(data, base, 4, Charsets.ISO_8859_1) != "desc") continue
            val off = i32(base + 4)
            if (off + 12 > size) return null
            val type = String(data, off, 4, Charsets.ISO_8859_1)
            if (type == "desc") {
                val n = i32(off + 8)
                if (off + 12 + n <= size) return String(data, off + 12, maxOf(0, n - 1), Charsets.ISO_8859_1).trim()
            } else if (type == "mluc") {
                val recs = i32(off + 8)
                if (recs > 0) {
                    val len = i32(off + 20); val so = i32(off + 24)
                    if (off + so + len <= size) return String(data, off + so, len, Charsets.UTF_16BE).trim()
                }
            }
        }
        return null
    }

    /** Iterates tagged blocks ("8BIM"/"8B64") until [end], tolerating odd padding between blocks. */
    private inline fun forEachTaggedBlock(r: PsdReader, end: Long, big: Boolean, block: (key: String, length: Long) -> Unit) {
        while (r.position + 12 <= end) {
            var sig = r.ascii(4)
            if (sig != "8BIM" && sig != "8B64") {
                // Some writers pad blocks to 4 bytes without including padding in the length.
                var found = false
                val p = r.position - 4
                for (shift in 1..3) {
                    if (p + shift + 12 > end) break
                    r.seek(p + shift)
                    sig = r.ascii(4)
                    if (sig == "8BIM" || sig == "8B64") { found = true; break }
                }
                if (!found) break
            }
            val key = r.ascii(4)
            val length = if (big && key in BIG_KEYS) r.i64() else r.u32()
            val start = r.position
            if (length < 0 || start + length > end) break
            try { block(key, length) } catch (_: Exception) { }
            r.seek(start + length)
        }
        r.seek(end)
    }

    private class Raw(
        val index: Int, val rect: Rect, val channels: List<ChannelInfo>, val blend: Blend, val opacity: Int,
        val clipping: Boolean, val hidden: Boolean, val mask: LayerMask?, var name: String,
    ) {
        var fill = 255
        var section = 0
        var sectionBlend: Blend? = null
        var kind = LayerKind.PIXEL
        var effects = false
        var text: String? = null
        var solid: Int? = null
        var adjustment: String? = null
        var hasVectorMask = false
    }

    /** Returns (roots top-first, mergedHasAlpha, record count). Leaves the reader after channel data. */
    private fun readLayerInfo(r: PsdReader, big: Boolean): Triple<List<Layer>, Boolean, Int> {
        val rawCount = r.i16()
        val count = kotlin.math.abs(rawCount)
        val mergedAlpha = rawCount < 0
        val raws = ArrayList<Raw>(count)
        for (i in 0 until count) {
            val top = r.i32(); val left = r.i32(); val bottom = r.i32(); val right = r.i32()
            val nch = r.u16()
            if (nch > 56) throw PsdException("Corrupt layer record ($nch channels)")
            val chans = ArrayList<ChannelInfo>(nch)
            repeat(nch) { chans.add(ChannelInfo(r.i16(), r.len(big))) }
            val sig = r.ascii(4)
            if (sig != "8BIM") throw PsdException("Corrupt layer record signature")
            val blend = Blend.of(r.ascii(4))
            val opacity = r.u8()
            val clipping = r.u8() != 0
            val flags = r.u8()
            r.skip(1)
            val extraLen = r.u32()
            val extraEnd = r.position + extraLen

            var mask: LayerMask? = null
            val maskLen = r.u32()
            val maskEnd = r.position + maskLen
            if (maskLen >= 18) {
                val mt = r.i32(); val ml = r.i32(); val mb = r.i32(); val mr = r.i32()
                val def = r.u8(); val mflags = r.u8()
                mask = LayerMask(Rect(mt, ml, mb, mr), def, (mflags and 2) != 0, (mflags and 4) != 0)
            }
            r.seek(maskEnd)
            val brLen = r.u32()
            r.skip(brLen)
            val name = r.pascal(4)
            val raw = Raw(i, Rect(top, left, bottom, right), chans, blend, opacity, clipping, (flags and 2) != 0, mask, name)
            forEachTaggedBlock(r, extraEnd, big) { key, length -> readLayerBlock(r, key, length, raw) }
            r.seek(extraEnd)
            raws.add(raw)
        }
        // Channel image data follows in record order.
        for (raw in raws) for (c in raw.channels) {
            c.offset = r.position
            r.skip(c.length)
        }
        return Triple(buildTree(raws), mergedAlpha, count)
    }

    private fun readLayerBlock(r: PsdReader, key: String, length: Long, raw: Raw) {
        when (key) {
            "luni" -> r.unicode().let { if (it.isNotEmpty()) raw.name = it }
            "lsct", "lsdk" -> {
                raw.section = r.i32()
                if (length >= 12) {
                    val sig = r.ascii(4)
                    if (sig == "8BIM") raw.sectionBlend = Blend.of(r.ascii(4))
                }
            }
            "iOpa" -> raw.fill = r.u8()
            "TySh" -> {
                raw.kind = LayerKind.TEXT
                r.u16(); r.skip(6 * 8); r.u16(); r.i32()
                val d = Descriptor.read(r)
                raw.text = (d["Txt "] as? String)?.replace('\r', '\n')?.trimEnd()
            }
            "SoLd", "PlLd", "SoLE" -> if (raw.kind == LayerKind.PIXEL) raw.kind = LayerKind.SMART_OBJECT
            "lfx2", "lrFX", "lmfx" -> raw.effects = true
            "vmsk", "vsms", "vscg", "vstk" -> raw.hasVectorMask = true
            "SoCo" -> {
                raw.adjustment = FILL_KEYS[key]; raw.kind = LayerKind.FILL
                r.i32()
                raw.solid = Descriptor.color(Descriptor.read(r)["Clr "])
            }
            else -> {
                FILL_KEYS[key]?.let { raw.adjustment = it; raw.kind = LayerKind.FILL }
                ADJUSTMENT_KEYS[key]?.let { raw.adjustment = it; raw.kind = LayerKind.ADJUSTMENT }
            }
        }
    }

    private fun buildTree(raws: List<Raw>): List<Layer> {
        // Records are stored bottom-most first. A group is: divider (type 3) ... children ... group record (type 1/2).
        val root = ArrayList<Layer>()
        val stack = ArrayList<ArrayList<Layer>>()
        var current = root
        for (raw in raws) {
            when (raw.section) {
                3 -> { stack.add(current); current = ArrayList() }
                1, 2 -> {
                    val g = makeLayer(raw, LayerKind.GROUP)
                    g.expanded = false
                    // children are bottom-first; store top-first.
                    for (c in current.asReversed()) { c.parent = g; g.children.add(c) }
                    current = if (stack.isNotEmpty()) stack.removeAt(stack.size - 1) else ArrayList()
                    current.add(g)
                }
                else -> current.add(makeLayer(raw, null))
            }
        }
        // Unbalanced dividers: flatten whatever is left into the root.
        while (stack.isNotEmpty()) {
            val parent = stack.removeAt(stack.size - 1)
            parent.addAll(current)
            current = parent
        }
        return current.asReversed().toList()
    }

    private fun makeLayer(raw: Raw, forceKind: LayerKind?): Layer {
        var kind = forceKind ?: raw.kind
        if (kind == LayerKind.FILL && raw.hasVectorMask) kind = LayerKind.SHAPE
        return Layer(
            index = raw.index, name = raw.name.ifEmpty { "Layer ${raw.index + 1}" }, rect = raw.rect,
            channels = raw.channels,
            blend = if (forceKind == LayerKind.GROUP) (raw.sectionBlend ?: raw.blend) else raw.blend,
            opacity = raw.opacity, fillOpacity = raw.fill, clipping = raw.clipping, hiddenInFile = raw.hidden,
            mask = raw.mask, kind = kind, hasEffects = raw.effects, text = raw.text, solidColor = raw.solid,
            adjustmentType = raw.adjustment,
        )
    }
}

/** Minimal Photoshop ActionDescriptor reader. Values: String, Double, Int, Long, Boolean, Map, List, or null. */
internal object Descriptor {
    fun read(r: PsdReader): Map<String, Any?> {
        r.unicode()   // class name
        key(r)        // class id
        val n = r.i32()
        if (n < 0 || n > 10_000) throw PsdException("Bad descriptor")
        val out = LinkedHashMap<String, Any?>()
        repeat(n) {
            val k = key(r)
            out[k] = value(r, r.ascii(4))
        }
        return out
    }

    private fun key(r: PsdReader): String {
        val len = r.i32()
        return if (len == 0) r.ascii(4) else r.ascii(len)
    }

    private fun value(r: PsdReader, type: String): Any? = when (type) {
        "Objc", "GlbO" -> read(r)
        "VlLs" -> { val n = r.i32(); List(n) { value(r, r.ascii(4)) } }
        "doub" -> r.f64()
        "UntF" -> { r.ascii(4); r.f64() }
        "UnFl" -> { r.ascii(4); val n = r.i32(); List(n) { r.f64() } }
        "TEXT" -> r.unicode()
        "enum" -> { key(r); key(r) }
        "long" -> r.i32()
        "comp" -> r.i64()
        "bool" -> r.u8() != 0
        "type", "GlbC" -> { r.unicode(); key(r) }
        "alis", "Pth " -> { r.skip(r.u32()); null }
        "tdta" -> { r.skip(r.u32()); null }
        "obj " -> {
            val n = r.i32()
            repeat(n) {
                when (r.ascii(4)) {
                    "prop" -> { r.unicode(); key(r); key(r) }
                    "Clss" -> { r.unicode(); key(r) }
                    "Enmr" -> { r.unicode(); key(r); key(r); key(r) }
                    "rele" -> { r.unicode(); key(r); r.i32() }
                    "Idnt", "indx" -> r.i32()
                    "name" -> { r.unicode(); key(r); r.unicode() }
                    else -> throw PsdException("Bad reference")
                }
            }
            null
        }
        else -> throw PsdException("Unknown descriptor type '$type'")
    }

    fun color(v: Any?): Int? {
        val m = v as? Map<*, *> ?: return null
        fun d(k: String) = (m[k] as? Number)?.toDouble()
        val r = d("Rd  "); val g = d("Grn "); val b = d("Bl  ")
        if (r != null && g != null && b != null) {
            return argb(255, r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), b.toInt().coerceIn(0, 255))
        }
        val gray = d("Gry ")
        if (gray != null) { val v8 = (255 - gray * 2.55).toInt().coerceIn(0, 255); return argb(255, v8, v8, v8) }
        return null
    }
}

internal fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
