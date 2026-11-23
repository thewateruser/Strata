package app.strata.psd.engine

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Renders every PSD/PSB in a directory: <name>.layers.png (our compositor) and <name>.merged.png. */
fun main(args: Array<String>) {
    val dir = File(args[0])
    val files = dir.listFiles { f -> f.name.endsWith(".psd", true) || f.name.endsWith(".psb", true) }!!.sorted()
    for (f in files) {
        val base = f.path.substringBeforeLast('.')
        try {
            val t0 = System.nanoTime()
            PsdSession(f, maxWorkingPixels = Long.MAX_VALUE).use { s ->
                val d = s.doc
                println("== ${f.name}: ${d.width}x${d.height} ${d.mode} ${d.depth}-bit ch=${d.channels} psb=${d.isPsb} layers=${d.layerCount} realMerged=${d.hasRealMergedData} mergedAlpha=${d.mergedHasAlpha} writer=${d.writer} dpi=${d.dpi} icc=${d.iccProfileName}")
                fun dump(ls: List<Layer>, ind: String) { for (l in ls) { println("$ind- ${l.name} [${l.kind}] ${l.blend} op=${l.opacity} fill=${l.fillOpacity} clip=${l.clipping} vis=${l.visible} rect=${l.rect} mask=${l.mask?.rect} text=${l.text?.take(30)}"); dump(l.children, "$ind  ") } }
                if (args.size < 2) dump(d.roots, "  ")
                if (d.layerCount > 0) {
                    val r = s.renderLayers()!!
                    write(r.pixels, r.width, r.height, File("$base.layers.png"))
                    if (r.unsupported.isNotEmpty()) println("  unsupported: ${r.unsupported.map { it.name }}")
                }
                if (d.mergedOffset >= 0) {
                    val m = s.composite(1)
                    write(m.pixels, m.width, m.height, File("$base.merged.png"))
                }
            }
            println("  ok in ${(System.nanoTime() - t0) / 1_000_000} ms")
        } catch (e: Exception) {
            println("!! ${f.name}: $e"); e.printStackTrace()
        }
    }
}

private fun write(px: IntArray, w: Int, h: Int, out: File) {
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    img.setRGB(0, 0, w, h, px, 0, w)
    ImageIO.write(img, "png", out)
}
