package app.strata.psd.engine

import java.io.Closeable
import java.io.File

/**
 * An opened document: parsed structure plus caches of decoded layers at the working scale.
 * All methods are synchronized; call them from a background thread.
 */
class PsdSession(
    private val reader: PsdReader,
    maxWorkingPixels: Long = 6_000_000L,
    /** Byte budget for decoded layers; least recently used layers are re-decoded when evicted. */
    private val cacheBytes: Long = 256L shl 20,
) : Closeable {

    constructor(file: File, maxWorkingPixels: Long = 6_000_000L) : this(PsdReader(file), maxWorkingPixels)

    val doc: PsdDocument = PsdParser.parse(reader)
    private val decoder = PsdDecoder(doc, reader)

    /** Integer downscale used for layer rendering so memory stays bounded. */
    val workingScale: Int = run {
        var n = 1
        while ((doc.width.toLong() / n) * (doc.height.toLong() / n) > maxWorkingPixels) n++
        n
    }

    private val layerCache = object : LinkedHashMap<Int, Raster?>(16, 0.75f, true) {}
    private val maskCache = HashMap<Int, Plane?>()
    private var cachedBytes = 0L

    val allLayers: List<Layer> = doc.flatten()

    /** True when the file carries a usable flattened composite. */
    val hasComposite: Boolean get() = doc.mergedOffset >= 0 && (doc.hasRealMergedData || doc.layerCount == 0)

    @Synchronized
    fun composite(scale: Int): Raster = decoder.decodeMerged(scale)

    @Synchronized
    fun layerRaster(layer: Layer): Raster? {
        if (layerCache.containsKey(layer.index)) return layerCache[layer.index]
        val r = try { decoder.decodeLayer(layer, workingScale) } catch (e: Exception) { null }
        val size = (r?.pixels?.size ?: 0) * 4L
        // Evict least recently used layers to stay within budget.
        val it = layerCache.entries.iterator()
        while (cachedBytes + size > cacheBytes && it.hasNext()) {
            val e = it.next()
            cachedBytes -= (e.value?.pixels?.size ?: 0) * 4L
            it.remove()
        }
        layerCache[layer.index] = r
        cachedBytes += size
        return r
    }

    @Synchronized
    fun layerMask(layer: Layer): Plane? = maskCache.getOrPut(layer.index) {
        try { decoder.decodeMask(layer, workingScale) } catch (e: Exception) { null }
    }

    class Render(val width: Int, val height: Int, val pixels: IntArray, val unsupported: Set<Layer>)

    /** Renders the layer tree honoring each layer's current [Layer.visible]. */
    @Synchronized
    fun renderLayers(cancelled: () -> Boolean = { false }): Render? {
        val c = Compositor(doc, workingScale, ::layerRaster, ::layerMask, cancelled)
        val px = c.renderOrNull() ?: return null
        return Render(c.width, c.height, px, c.unsupported)
    }

    /** Full resolution render for export; uses no cache to keep memory bounded. */
    @Synchronized
    fun renderLayersFull(): Render {
        val r2 = reader.fork()
        try {
            val dec = PsdDecoder(doc, r2)
            val c = Compositor(doc, 1, { l -> try { dec.decodeLayer(l, 1) } catch (e: Exception) { null } },
                { l -> try { dec.decodeMask(l, 1) } catch (e: Exception) { null } })
            return Render(c.width, c.height, c.render(), c.unsupported)
        } finally { r2.close() }
    }

    /** Full resolution pixels of a single layer (mask applied), for export. */
    @Synchronized
    fun layerFull(layer: Layer): Raster? = decoder.decodeLayer(layer, 1)

    override fun close() = reader.close()
}
