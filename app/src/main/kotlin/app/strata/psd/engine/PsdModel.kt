package app.strata.psd.engine

enum class ColorMode(val id: Int, val label: String) {
    BITMAP(0, "Bitmap"), GRAYSCALE(1, "Grayscale"), INDEXED(2, "Indexed"), RGB(3, "RGB"),
    CMYK(4, "CMYK"), MULTICHANNEL(7, "Multichannel"), DUOTONE(8, "Duotone"), LAB(9, "Lab");

    companion object {
        fun of(id: Int) = entries.firstOrNull { it.id == id } ?: throw PsdException("Unsupported color mode $id")
    }
}

enum class Blend(val key: String, val label: String) {
    PASS_THROUGH("pass", "Pass Through"),
    NORMAL("norm", "Normal"), DISSOLVE("diss", "Dissolve"),
    DARKEN("dark", "Darken"), MULTIPLY("mul ", "Multiply"), COLOR_BURN("idiv", "Color Burn"),
    LINEAR_BURN("lbrn", "Linear Burn"), DARKER_COLOR("dkCl", "Darker Color"),
    LIGHTEN("lite", "Lighten"), SCREEN("scrn", "Screen"), COLOR_DODGE("div ", "Color Dodge"),
    LINEAR_DODGE("lddg", "Linear Dodge (Add)"), LIGHTER_COLOR("lgCl", "Lighter Color"),
    OVERLAY("over", "Overlay"), SOFT_LIGHT("sLit", "Soft Light"), HARD_LIGHT("hLit", "Hard Light"),
    VIVID_LIGHT("vLit", "Vivid Light"), LINEAR_LIGHT("lLit", "Linear Light"), PIN_LIGHT("pLit", "Pin Light"),
    HARD_MIX("hMix", "Hard Mix"),
    DIFFERENCE("diff", "Difference"), EXCLUSION("smud", "Exclusion"), SUBTRACT("fsub", "Subtract"), DIVIDE("fdiv", "Divide"),
    HUE("hue ", "Hue"), SATURATION("sat ", "Saturation"), COLOR("colr", "Color"), LUMINOSITY("lum ", "Luminosity");

    companion object {
        private val byKey = entries.associateBy { it.key }
        fun of(key: String) = byKey[key] ?: NORMAL
    }
}

enum class LayerKind(val label: String) {
    PIXEL("Pixel"), GROUP("Group"), TEXT("Text"), SMART_OBJECT("Smart object"),
    ADJUSTMENT("Adjustment"), FILL("Fill"), SHAPE("Shape");
}

class Rect(val top: Int, val left: Int, val bottom: Int, val right: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val isEmpty get() = width <= 0 || height <= 0
    override fun toString() = "[$left,$top ${width}x$height]"
}

class ChannelInfo(val id: Int, val length: Long) {
    /** Absolute file offset of this channel's data (compression word included). */
    var offset: Long = 0
}

class LayerMask(
    val rect: Rect,
    val defaultColor: Int,
    val disabled: Boolean,
    val invert: Boolean,
)

/** One node of the layer tree. Groups have [children]; order is top-most first (like the Layers panel). */
class Layer(
    val index: Int,
    var name: String,
    val rect: Rect,
    val channels: List<ChannelInfo>,
    val blend: Blend,
    val opacity: Int,        // 0..255
    val fillOpacity: Int,    // 0..255
    val clipping: Boolean,
    val hiddenInFile: Boolean,
    val mask: LayerMask?,
    val kind: LayerKind,
    val hasEffects: Boolean,
    val text: String?,
    val solidColor: Int?,    // ARGB for solid color fill layers
    val adjustmentType: String?,
) {
    val children = ArrayList<Layer>()
    var parent: Layer? = null
    var expanded: Boolean = false

    /** User-controlled visibility (starts as the file's visibility). */
    var visible: Boolean = !hiddenInFile

    val isGroup get() = kind == LayerKind.GROUP
    val depth: Int get() { var d = 0; var p = parent; while (p != null) { d++; p = p.parent }; return d }

    /** Whether this layer and all ancestors are visible. */
    val effectivelyVisible: Boolean get() { var l: Layer? = this; while (l != null) { if (!l.visible) return false; l = l.parent }; return true }

    fun hasPixels() = !rect.isEmpty && channels.any { it.id >= 0 && it.length > 2 }
}

class PsdDocument(
    val width: Int,
    val height: Int,
    val channels: Int,
    val depth: Int,
    val mode: ColorMode,
    val isPsb: Boolean,
    val palette: IntArray?,               // indexed mode, ARGB
    val dpi: Double?,
    val hasRealMergedData: Boolean,
    val writer: String?,
    val roots: List<Layer>,               // top-most first
    val layerCount: Int,
    val mergedHasAlpha: Boolean,
    val mergedOffset: Long,               // offset of merged image data compression word, -1 if absent
    val fileSize: Long,
    val iccProfileName: String?,
) {
    fun flatten(): List<Layer> {
        val out = ArrayList<Layer>()
        fun walk(list: List<Layer>) { for (l in list) { out.add(l); walk(l.children) } }
        walk(roots)
        return out
    }
}
