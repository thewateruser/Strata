package app.strata.psd

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import app.strata.psd.engine.Layer
import app.strata.psd.engine.LayerKind
import app.strata.psd.engine.PsdSession
import app.strata.psd.engine.Blend
import app.strata.psd.ui.ButtonStyle
import app.strata.psd.ui.CheckerDrawable
import app.strata.psd.ui.ExpressiveButton
import app.strata.psd.ui.IconButton
import app.strata.psd.ui.LoadingIndicator
import app.strata.psd.ui.MATCH
import app.strata.psd.ui.MorphLayout
import app.strata.psd.ui.MorphRect
import app.strata.psd.ui.Motion
import app.strata.psd.ui.PolarShape
import app.strata.psd.ui.ShapeView
import app.strata.psd.ui.Sheet
import app.strata.psd.ui.SnackbarHost
import app.strata.psd.ui.Spring
import app.strata.psd.ui.StrataActivity
import app.strata.psd.ui.Theme
import app.strata.psd.ui.Type
import app.strata.psd.ui.WRAP
import app.strata.psd.ui.ZoomView
import app.strata.psd.ui.chip
import app.strata.psd.ui.dp
import app.strata.psd.ui.dpf
import app.strata.psd.ui.frameLp
import app.strata.psd.ui.haptic
import app.strata.psd.ui.iconBadge
import app.strata.psd.ui.iconView
import app.strata.psd.ui.linLp
import app.strata.psd.ui.text
import app.strata.psd.ui.withAlpha
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

class ViewerActivity : StrataActivity() {

    companion object {
        const val EXTRA_PATH = "app.strata.psd.PATH"
        private const val REQ_SAVE = 10
        private const val REQ_SAVE_LAYER = 11
        private const val DISPLAY_MAX_PIXELS = 16_000_000L
    }

    private val heavy = Executors.newSingleThreadExecutor()
    private val thumbs = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val renderGen = AtomicInteger()

    private var session: PsdSession? = null
    private var docName = "Document"
    private var fileSize = -1L
    private var compositeBitmap: Bitmap? = null
    private var modified = false
    private var unsupported: Set<Layer> = emptySet()
    private var pendingLayerExport: Layer? = null
    private var chromeVisible = true
    private var insets = StrataActivity.Insets(0, 0, 0, 0)
    private var lift = 0f

    private lateinit var root: FrameLayout
    private lateinit var zoom: ZoomView
    private lateinit var topBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var toolbar: LinearLayout
    private lateinit var toolbarBox: LinearLayout
    private lateinit var layersButton: IconButton
    private lateinit var resetButton: IconButton
    private lateinit var busy: LoadingIndicator
    private lateinit var loading: LinearLayout
    private lateinit var statusChip: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var zoomPill: TextView
    private lateinit var snackbar: SnackbarHost
    private lateinit var layersSheet: Sheet
    private lateinit var layerList: ListView
    private lateinit var layerCount: TextView
    private val adapter = LayerAdapter()
    private val thumbCache = object : LruCache<Int, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }
    private val chromeSpring = Spring(Motion.defaultSpatial, 1f) { applyChrome(it) }

    // ================================================================== lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = Theme.c
        root = FrameLayout(this)
        val bg = if (c.dark) c.surfaceContainerLowest else c.surfaceContainer
        root.setBackgroundColor(bg)
        window.decorView.setBackgroundColor(bg)

        zoom = ZoomView(this)
        zoom.onSingleTap = { toggleChrome() }
        zoom.onZoomChanged = { onZoom(it) }
        root.addView(zoom, FrameLayout.LayoutParams(MATCH, MATCH))

        buildTopBar()
        buildToolbar()
        buildLoading()
        buildStatus()

        layersSheet = Sheet(this, modal = false, maxHeightFraction = 0.5f, fill = true)
        buildLayersSheet()
        // Below the floating toolbar, which rides on top of the sheet.
        root.addView(layersSheet, root.indexOfChild(toolbarBox), FrameLayout.LayoutParams(MATCH, MATCH))
        layersSheet.onDismissed = { layersButton.isSelected = false; updateSafeArea() }
        layersSheet.onOffset = { visible -> lift = -maxOf(0f, visible - insets.bottom); applyChrome(chromeSpring.value) }

        snackbar = SnackbarHost(this)
        root.addView(snackbar, frameLp(MATCH, WRAP, Gravity.BOTTOM))

        onInsets(root) { i ->
            insets = i
            topBar.setPadding(i.left + 8.dp, i.top + 8.dp, i.right + 8.dp, 24.dp)
            (toolbarBox.layoutParams as FrameLayout.LayoutParams).bottomMargin = i.bottom + 16.dp
            (snackbar.layoutParams as FrameLayout.LayoutParams).setMargins(i.left + 16.dp, 0, i.right + 16.dp, i.bottom + 96.dp)
            (statusChip.layoutParams as FrameLayout.LayoutParams).topMargin = i.top + 76.dp
            (zoomPill.layoutParams as FrameLayout.LayoutParams).topMargin = i.top + 76.dp
            layersSheet.bottomInset = i.bottom
            root.requestLayout()
            updateSafeArea()
        }
        setContentView(root)

        val uri = intent.data ?: intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            ?: intent.getStringExtra(EXTRA_PATH)?.let { Uri.fromFile(File(it)) }
        if (uri == null) { showError("Nothing to open"); return }
        load(uri)
    }

    override fun onDestroy() {
        renderGen.incrementAndGet()
        heavy.execute { session?.close() }
        heavy.shutdown(); thumbs.shutdown()
        super.onDestroy()
    }

    // ================================================================== loading

    private fun load(uri: Uri) {
        val (name, _) = Documents.displayName(this, uri)
        docName = name
        titleView.text = name
        subtitleView.text = "Opening…"
        heavy.execute {
            try {
                val opened = Documents.open(this, uri)
                fileSize = opened.size
                val maxMem = Runtime.getRuntime().maxMemory()
                val s = PsdSession(opened.reader,
                    maxWorkingPixels = if (maxMem >= 400L shl 20) 6_000_000L else 3_000_000L,
                    cacheBytes = maxMem / 4)
                session = s
                val d = s.doc
                var n = 1
                while ((d.width.toLong() / n) * (d.height.toLong() / n) > DISPLAY_MAX_PIXELS) n++
                var bmp: Bitmap? = null
                if (s.hasComposite) {
                    bmp = try { s.composite(n).toBitmap() } catch (e: Exception) { null }
                }
                var render: PsdSession.Render? = null
                if (bmp == null) {
                    render = s.renderLayers() ?: throw IllegalStateException("Render cancelled")
                    bmp = Bitmap.createBitmap(render.pixels, render.width, render.height, Bitmap.Config.ARGB_8888)
                }
                val shown = bmp!!
                compositeBitmap = if (render == null) shown else null
                Recents.add(this, Recent(uri.toString(), name, opened.size, d.width, d.height,
                    "${d.mode.label} ${d.depth}-bit", s.allLayers.size, System.currentTimeMillis()), shown)
                main.post { onLoaded(shown, render) }
            } catch (t: Throwable) {
                val msg = when (t) {
                    is OutOfMemoryError -> "This document is too large for this device's memory."
                    is SecurityException -> "Strata no longer has permission to read this file. Open it again from the file picker."
                    else -> t.message ?: t.javaClass.simpleName
                }
                main.post { showError(msg) }
            }
        }
    }

    private fun onLoaded(bmp: Bitmap, render: PsdSession.Render?) {
        val s = session ?: return
        val d = s.doc
        subtitleView.text = "${d.width} × ${d.height} · ${d.mode.label} ${d.depth}-bit" + if (d.isPsb) " · PSB" else ""
        loading.animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(200).withEndAction { loading.visibility = View.GONE }.start()
        zoom.setImage(bmp, d.width, d.height)
        adapter.rebuild()
        layerCount.text = "${s.allLayers.size}"
        layersButton.isEnabled = s.allLayers.isNotEmpty()
        layersButton.alpha = if (s.allLayers.isNotEmpty()) 1f else 0.38f
        if (render != null) {
            unsupported = render.unsupported
            updateStatus()
        }
        // Toolbar springs in.
        Spring(Motion.defaultSpatial, 120.dpf) { toolbarBox.translationY = it }.animateTo(0f)
        toolbarBox.animate().alpha(1f).setDuration(150).start()
    }

    private fun showError(message: String) {
        loading.removeAllViews()
        val c = Theme.c
        val art = FrameLayout(this)
        art.addView(ShapeView(this, PolarShape.sunny, c.errorContainer), frameLp(120.dp, 120.dp, Gravity.CENTER))
        art.addView(iconView(R.drawable.ic_close, c.onErrorContainer, 40.dp), frameLp(40.dp, 40.dp, Gravity.CENTER))
        loading.addView(art, linLp(120.dp, 120.dp))
        loading.addView(text("Couldn't open this file", Type.headlineSmall, c.onSurface), linLp(WRAP, WRAP, t = 20.dp))
        val m = text(message, Type.bodyMedium, c.onSurfaceVariant)
        m.gravity = Gravity.CENTER
        loading.addView(m, linLp(WRAP, WRAP, l = 32.dp, t = 8.dp, r = 32.dp))
        val back = ExpressiveButton(this, "Go back", R.drawable.ic_arrow_back, ButtonStyle.TONAL)
        back.setOnClickListener { finish() }
        loading.addView(back, linLp(WRAP, 48.dp, t = 24.dp))
        subtitleView.text = "Error"
    }

    // ================================================================== chrome

    private fun buildTopBar() {
        val c = Theme.c
        val scrimColor = if (c.dark) c.surfaceContainerLowest else c.surfaceContainer
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(withAlpha(scrimColor, 0.92f), withAlpha(scrimColor, 0f)))
        }
        val back = IconButton(this, R.drawable.ic_arrow_back, c.surfaceContainerHigh, c.onSurface, desc = "Back")
        back.setOnClickListener { finish() }
        topBar.addView(back)
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleView = text(docName, Type.titleMedium, c.onSurface, 1)
        subtitleView = text("", Type.bodySmall, c.onSurfaceVariant, 1)
        titles.addView(titleView); titles.addView(subtitleView, linLp(WRAP, WRAP, t = 2.dp))
        topBar.addView(titles, linLp(0, WRAP, 1f, l = 12.dp, r = 8.dp))
        val info = IconButton(this, R.drawable.ic_info, c.surfaceContainerHigh, c.onSurface, desc = "Document info")
        info.setOnClickListener { showInfo() }
        topBar.addView(info)
        root.addView(topBar, frameLp(MATCH, WRAP, Gravity.TOP))
    }

    private fun buildToolbar() {
        val c = Theme.c
        // M3 Expressive floating toolbar (vibrant) paired with a FAB.
        toolbarBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; alpha = 0f; translationY = 120.dpf }
        toolbar = MorphLayout(this, c.primaryContainer, 32.dpf, 32.dpf).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            elevation = 6.dpf
            setPadding(8.dp, 8.dp, 8.dp, 8.dp)
        }
        val fg = c.onPrimaryContainer
        layersButton = IconButton(this, R.drawable.ic_layers, 0, fg, selColor = c.primary, selIconColor = c.onPrimary, desc = "Layers")
        layersButton.setOnClickListener { toggleLayers() }
        val fit = IconButton(this, R.drawable.ic_fit, 0, fg, desc = "Fit to screen")
        fit.setOnClickListener { zoom.resetToFit() }
        val backdrop = IconButton(this, R.drawable.ic_checker, 0, fg, desc = "Transparency background")
        backdrop.setOnClickListener {
            zoom.backdrop = ZoomView.Backdrop.entries[(zoom.backdrop.ordinal + 1) % ZoomView.Backdrop.entries.size]
            snackbar.show(when (zoom.backdrop) {
                ZoomView.Backdrop.CHECKER -> "Transparency shown as checkerboard"
                ZoomView.Backdrop.LIGHT -> "Transparency shown as white"
                ZoomView.Backdrop.DARK -> "Transparency shown as black"
            })
        }
        resetButton = IconButton(this, R.drawable.ic_restore, 0, fg, desc = "Restore original visibility")
        resetButton.visibility = View.GONE
        resetButton.setOnClickListener { resetVisibility() }
        busy = LoadingIndicator(this, contained = false).apply { indicatorColor = fg; visibility = View.GONE }
        toolbar.addView(layersButton, linLp(48.dp, 48.dp))
        toolbar.addView(fit, linLp(48.dp, 48.dp, l = 4.dp))
        toolbar.addView(backdrop, linLp(48.dp, 48.dp, l = 4.dp))
        toolbar.addView(resetButton, linLp(48.dp, 48.dp, l = 4.dp))
        toolbar.addView(busy, linLp(32.dp, 32.dp, l = 8.dp, r = 8.dp))
        toolbarBox.addView(toolbar, linLp(WRAP, 64.dp))

        val fab = MorphLayout(this, c.tertiaryContainer, 20.dpf, 12.dpf).apply {
            gravity = Gravity.CENTER
            elevation = 6.dpf
            isClickable = true; isFocusable = true
            contentDescription = "Export"
            addView(iconView(R.drawable.ic_download, c.onTertiaryContainer, 24.dp))
            setOnClickListener { haptic(); showExport() }
        }
        toolbarBox.addView(fab, linLp(64.dp, 64.dp, l = 8.dp))
        root.addView(toolbarBox, frameLp(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
    }

    private fun buildLoading() {
        val c = Theme.c
        loading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val ind = LoadingIndicator(this)
        loading.addView(ind, linLp(72.dp, 72.dp))
        loading.addView(text("Opening", Type.titleMedium, c.onSurfaceVariant), linLp(WRAP, WRAP, t = 20.dp))
        root.addView(loading, frameLp(MATCH, WRAP, Gravity.CENTER))
    }

    private fun buildStatus() {
        val c = Theme.c
        statusChip = MorphLayout(this, c.secondaryContainer, 16.dpf, 10.dpf).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp, 0, 16.dp, 0)
            isClickable = true
            elevation = 2.dpf
            visibility = View.GONE
            addView(iconView(R.drawable.ic_layers, c.onSecondaryContainer, 18.dp), linLp(18.dp, 18.dp, r = 8.dp))
        }
        statusText = text("", Type.labelLarge, c.onSecondaryContainer, 1)
        statusChip.addView(statusText)
        statusChip.setOnClickListener { explainLiveRender() }
        root.addView(statusChip, frameLp(WRAP, 36.dp, Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        zoomPill = text("", Type.labelLarge, c.inverseOnSurface, 1).apply {
            background = MorphRect(withAlpha(c.inverseSurface, 0.9f), 18.dpf)
            gravity = Gravity.CENTER
            setPadding(14.dp, 0, 14.dp, 0)
            alpha = 0f
        }
        root.addView(zoomPill, frameLp(WRAP, 36.dp, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
    }

    private val hideZoomPill = Runnable { zoomPill.animate().alpha(0f).setDuration(250).start() }
    private var lastZoom = -1f

    private fun onZoom(scale: Float) {
        val pct = (scale * 100f).roundToInt()
        if (lastZoom < 0f) { lastZoom = scale; return }
        if (pct == (lastZoom * 100f).roundToInt()) return
        lastZoom = scale
        zoomPill.text = "$pct%"
        if (statusChip.visibility != View.VISIBLE) {
            zoomPill.animate().cancel(); zoomPill.alpha = 1f
            zoomPill.removeCallbacks(hideZoomPill); zoomPill.postDelayed(hideZoomPill, 900)
        }
    }

    private fun toggleChrome() {
        chromeVisible = !chromeVisible
        chromeSpring.animateTo(if (chromeVisible) 1f else 0f, Motion.defaultSpatial)
        setSystemBarsVisible(chromeVisible)
        if (!chromeVisible && layersSheet.isShowing) layersSheet.dismiss()
    }

    private fun applyChrome(v: Float) {
        topBar.translationY = -(1f - v) * (topBar.height + 16.dp)
        topBar.alpha = v.coerceIn(0f, 1f)
        if (toolbarBox.alpha > 0f || v > 0f) {
            toolbarBox.translationY = (1f - v) * (toolbarBox.height + insets.bottom + 32.dp) + lift
        }
        statusChip.alpha = v.coerceIn(0f, 1f)
    }

    private fun updateSafeArea() {
        val sheetH = if (layersSheet.isShowing) layersSheet.shownHeight else 0
        zoom.setSafeInsets(insets.left, insets.top + 64.dp, insets.right, if (sheetH > 0) sheetH + 72.dp else insets.bottom + 80.dp)
    }

    // ================================================================== layers

    private fun toggleLayers() {
        if (session?.allLayers.isNullOrEmpty()) return
        if (layersSheet.isShowing) {
            layersSheet.dismiss()
        } else {
            showSheet(layersSheet)
            layersButton.isSelected = true
            layersSheet.post { updateSafeArea() }
        }
    }

    private fun buildLayersSheet() {
        val c = Theme.c
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24.dp, 0, 12.dp, 8.dp)
        }
        header.addView(text("Layers", Type.titleLarge, c.onSurface))
        layerCount = text("", Type.labelMedium, c.onSecondaryContainer, 1).apply {
            background = MorphRect(c.secondaryContainer, 10.dpf)
            gravity = Gravity.CENTER
            setPadding(8.dp, 0, 8.dp, 0)
            minWidth = 24.dp
        }
        header.addView(layerCount, linLp(WRAP, 22.dp, l = 10.dp))
        header.addView(View(this), linLp(0, 1, 1f))
        val expand = ExpressiveButton(this, "Expand all", style = ButtonStyle.TEXT, height = 40.dp)
        expand.setOnClickListener {
            val all = session?.allLayers ?: return@setOnClickListener
            val anyCollapsed = all.any { it.isGroup && !it.expanded }
            all.forEach { if (it.isGroup) it.expanded = anyCollapsed }
            expand.label.text = if (anyCollapsed) "Collapse all" else "Expand all"
            adapter.rebuild()
        }
        header.addView(expand)
        layersSheet.body.addView(header, linLp(MATCH, WRAP))

        layerList = ListView(this).apply {
            divider = null
            dividerHeight = 0
            selector = android.graphics.drawable.ColorDrawable(0)
            clipToPadding = false
            setPadding(12.dp, 0, 12.dp, 12.dp)
            isVerticalScrollBarEnabled = false
            adapter = this@ViewerActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> onLayerClicked(this@ViewerActivity.adapter.rows[pos]) }
        }
        layersSheet.body.addView(layerList, linLp(MATCH, 0, 1f))
        layersSheet.panel.dragRegion = 80.dp
    }

    private fun onLayerClicked(l: Layer) {
        if (l.isGroup) {
            l.expanded = !l.expanded
            adapter.rebuild()
        } else showLayerDetail(l)
    }

    private fun setVisible(l: Layer, v: Boolean) {
        if (l.visible == v) return
        l.visible = v
        markModified()
    }

    private fun markModified() {
        val s = session ?: return
        modified = s.allLayers.any { it.visible == it.hiddenInFile }
        if (resetButton.visibility != View.VISIBLE && modified) {
            resetButton.visibility = View.VISIBLE
            resetButton.scaleX = 0f; resetButton.scaleY = 0f
            Spring(Motion.fastSpatial, 0f) { resetButton.scaleX = it; resetButton.scaleY = it }.animateTo(1f)
        } else if (!modified) resetButton.visibility = View.GONE
        adapter.notifyDataSetChanged()
        if (!modified && compositeBitmap != null) {
            renderGen.incrementAndGet()
            busy.visibility = View.GONE
            zoom.setImage(compositeBitmap!!, s.doc.width, s.doc.height)
            unsupported = emptySet(); updateStatus()
        } else requestRender()
    }

    private val renderRunnable = Runnable { startRender() }

    private fun requestRender() {
        main.removeCallbacks(renderRunnable)
        main.postDelayed(renderRunnable, 60)
    }

    private fun startRender() {
        val s = session ?: return
        val gen = renderGen.incrementAndGet()
        busy.visibility = View.VISIBLE
        heavy.execute {
            if (gen != renderGen.get()) return@execute
            val r = try { s.renderLayers { gen != renderGen.get() } } catch (t: OutOfMemoryError) { null }
            if (r == null) {
                main.post { if (gen == renderGen.get()) busy.visibility = View.GONE }
                return@execute
            }
            val bmp = Bitmap.createBitmap(r.pixels, r.width, r.height, Bitmap.Config.ARGB_8888)
            main.post {
                if (gen != renderGen.get()) return@post
                busy.visibility = View.GONE
                zoom.setImage(bmp, s.doc.width, s.doc.height)
                unsupported = r.unsupported
                updateStatus()
            }
        }
    }

    private fun resetVisibility() {
        val s = session ?: return
        s.allLayers.forEach { it.visible = !it.hiddenInFile }
        root.haptic()
        markModified()
    }

    private fun updateStatus() {
        val s = session ?: return
        val live = modified || compositeBitmap == null
        val show = live && s.allLayers.isNotEmpty()
        if (show) {
            val n = unsupported.size
            statusText.text = if (n > 0) "Live render · $n not shown" else "Live render"
            if (statusChip.visibility != View.VISIBLE) {
                statusChip.visibility = View.VISIBLE
                statusChip.scaleX = 0.6f; statusChip.scaleY = 0.6f
                Spring(Motion.fastSpatial, 0.6f) { statusChip.scaleX = it; statusChip.scaleY = it }.animateTo(1f)
            }
        } else statusChip.visibility = View.GONE
    }

    private fun explainLiveRender() {
        val names = unsupported.take(3).joinToString(", ") { "“${it.name}”" }
        val more = if (unsupported.size > 3) " and ${unsupported.size - 3} more" else ""
        val base = if (compositeBitmap == null && !modified) "This file has no saved preview, so Strata renders it from its layers."
        else "Showing your layer changes, rendered on device."
        snackbar.show(if (unsupported.isEmpty()) base else "$base Adjustment layers and effects can't be reproduced: $names$more.")
    }

    inner class LayerAdapter : BaseAdapter() {
        val rows = ArrayList<Layer>()

        fun rebuild() {
            rows.clear()
            fun walk(list: List<Layer>) { for (l in list) { rows.add(l); if (l.isGroup && l.expanded) walk(l.children) } }
            walk(session?.doc?.roots ?: emptyList())
            notifyDataSetChanged()
        }

        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = rows[position].index.toLong()
        override fun hasStableIds() = true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? LayerRow) ?: LayerRow()
            row.bind(rows[position])
            return row
        }
    }

    inner class LayerRow : LinearLayout(this@ViewerActivity) {
        private val c = Theme.c
        private val indent = View(context)
        private val chevron = ImageView(context)
        private val clipIcon = ImageView(context)
        private val thumbBox = FrameLayout(context)
        private val thumb = ImageView(context)
        private val name: TextView
        private val meta: TextView
        private val fx = ImageView(context)
        private val eye: IconButton
        private var layer: Layer? = null
        private val bg = MorphRect(0, 16.dpf)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 64.dp
            setPadding(4.dp, 4.dp, 4.dp, 4.dp)
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(withAlpha(c.onSurface, 0.10f)), bg, MorphRect(0xFFFFFFFF.toInt(), 16.dpf))
            addView(indent, linLp(0, 1))
            chevron.setImageDrawable(context.getDrawable(R.drawable.ic_chevron_right)!!.mutate().apply { setTint(c.onSurfaceVariant) })
            addView(chevron, linLp(24.dp, 24.dp))
            clipIcon.setImageDrawable(context.getDrawable(R.drawable.ic_clip)!!.mutate().apply { setTint(c.onSurfaceVariant) })
            addView(clipIcon, linLp(18.dp, 18.dp, r = 4.dp))
            thumbBox.background = CheckerDrawable(12.dpf, 4.dp)
            thumbBox.clipToOutline = true
            thumbBox.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) = outline.setRoundRect(0, 0, view.width, view.height, 12.dpf)
            }
            thumb.scaleType = ImageView.ScaleType.FIT_CENTER
            thumbBox.addView(thumb, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(thumbBox, linLp(48.dp, 48.dp, l = 4.dp))
            val texts = LinearLayout(context).apply { orientation = VERTICAL }
            name = context.text("", Type.bodyLarge, c.onSurface, 1)
            meta = context.text("", Type.bodySmall, c.onSurfaceVariant, 1)
            texts.addView(name); texts.addView(meta, linLp(WRAP, WRAP, t = 2.dp))
            addView(texts, linLp(0, WRAP, 1f, l = 12.dp, r = 4.dp))
            fx.setImageDrawable(context.getDrawable(R.drawable.ic_effects)!!.mutate().apply { setTint(c.tertiary) })
            addView(fx, linLp(18.dp, 18.dp, r = 4.dp))
            eye = IconButton(context, R.drawable.ic_visibility, 0, c.onSurfaceVariant, 44.dp, selColor = 0, selIconColor = c.outline, desc = "Toggle visibility")
            eye.isFocusable = false
            eye.setOnClickListener {
                val l = layer ?: return@setOnClickListener
                it.haptic()
                setVisible(l, !l.visible)
            }
            addView(eye, linLp(44.dp, 44.dp))
            layoutParams = android.widget.AbsListView.LayoutParams(MATCH, WRAP)
        }

        fun bind(l: Layer) {
            layer = l
            val depth = l.depth
            (indent.layoutParams as LayoutParams).width = depth * 20.dp
            chevron.visibility = if (l.isGroup) VISIBLE else INVISIBLE
            chevron.rotation = if (l.expanded) 90f else 0f
            clipIcon.visibility = if (l.clipping) VISIBLE else GONE
            fx.visibility = if (l.hasEffects) VISIBLE else GONE
            name.text = l.name
            meta.text = describe(l)
            eye.isSelected = !l.visible
            eye.setIcon(if (l.visible) R.drawable.ic_visibility else R.drawable.ic_visibility_off)
            val dim = if (l.effectivelyVisible) 1f else 0.45f
            name.alpha = dim; meta.alpha = dim; thumbBox.alpha = dim
            bg.color = if (l.isGroup && l.expanded) c.surfaceContainerHigh else 0
            background.invalidateSelf()
            bindThumb(l)
        }

        private fun bindThumb(l: Layer) {
            thumb.tag = l.index
            thumbBox.background = CheckerDrawable(12.dpf, 4.dp)
            when {
                l.isGroup -> { iconThumb(R.drawable.ic_folder, c.secondaryContainer, c.onSecondaryContainer); return }
                l.kind == LayerKind.ADJUSTMENT -> { iconThumb(R.drawable.ic_tune, c.tertiaryContainer, c.onTertiaryContainer); return }
            }
            val cached = thumbCache.get(l.index)
            if (cached != null) { thumb.setImageBitmap(cached); thumb.scaleType = ImageView.ScaleType.FIT_CENTER; return }
            thumb.setImageDrawable(null)
            val s = session ?: return
            thumbs.execute {
                val r = try { s.layerRaster(l) } catch (_: Throwable) { null }
                val bmp = r?.let { (if (it.width > 0 && it.height > 0) it.toBitmap() else null)?.scaledToFit(144) }
                main.post {
                    if (bmp != null) thumbCache.put(l.index, bmp)
                    if (thumb.tag != l.index) return@post
                    if (bmp != null) { thumb.setImageBitmap(bmp); thumb.scaleType = ImageView.ScaleType.FIT_CENTER }
                    else if (l.solidColor != null) { thumb.setImageDrawable(null); thumbBox.background = MorphRect(l.solidColor, 12.dpf) }
                    else iconThumb(if (l.kind == LayerKind.TEXT) R.drawable.ic_text else R.drawable.ic_image, c.surfaceContainerHighest, c.onSurfaceVariant)
                }
            }
        }

        private fun iconThumb(icon: Int, bgc: Int, fg: Int) {
            thumbBox.background = MorphRect(bgc, 12.dpf)
            thumb.setImageDrawable(context.getDrawable(icon)!!.mutate().apply { setTint(fg) })
            thumb.scaleType = ImageView.ScaleType.CENTER
        }
    }

    private fun describe(l: Layer): String {
        val parts = ArrayList<String>()
        parts.add(when (l.kind) {
            LayerKind.GROUP -> "Group · ${l.children.size}"
            LayerKind.ADJUSTMENT -> l.adjustmentType ?: "Adjustment"
            LayerKind.FILL -> "${l.adjustmentType ?: "Fill"} fill"
            else -> l.kind.label
        })
        if (l.blend != Blend.NORMAL && !(l.isGroup && l.blend == Blend.PASS_THROUGH)) parts.add(l.blend.label)
        if (l.opacity < 255) parts.add("${(l.opacity * 100 / 255f).roundToInt()}%")
        return parts.joinToString(" · ")
    }

    // ================================================================== sheets

    private fun modalSheet(): Sheet {
        val s = Sheet(this, modal = true)
        root.addView(s, root.indexOfChild(snackbar), FrameLayout.LayoutParams(MATCH, MATCH))
        s.bottomInset = insets.bottom
        s.onDismissed = { root.removeView(s) }
        return s
    }

    private fun sheetTitle(sheet: Sheet, title: String, subtitle: String? = null) {
        val c = Theme.c
        sheet.body.addView(text(title, Type.headlineSmall, c.onSurface), linLp(MATCH, WRAP, l = 24.dp, r = 24.dp))
        if (subtitle != null) sheet.body.addView(text(subtitle, Type.bodyMedium, c.onSurfaceVariant), linLp(MATCH, WRAP, l = 24.dp, t = 4.dp, r = 24.dp))
    }

    private fun scrollBody(sheet: Sheet): LinearLayout {
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16.dp, 12.dp, 16.dp, 24.dp) }
        scroll.addView(col)
        sheet.body.addView(scroll, linLp(MATCH, WRAP))
        return col
    }

    private fun infoRow(parent: LinearLayout, icon: Int, label: String, value: String, tone: Int = 0) {
        val c = Theme.c
        val (bg, fg) = when (tone) {
            1 -> c.tertiaryContainer to c.onTertiaryContainer
            2 -> c.secondaryContainer to c.onSecondaryContainer
            else -> c.primaryContainer to c.onPrimaryContainer
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(8.dp, 8.dp, 8.dp, 8.dp) }
        row.addView(iconBadge(icon, bg, fg, 44.dp, if (tone == 1) PolarShape.cookie(4, 0.12f) else if (tone == 2) PolarShape.cookie(8, 0.07f) else null))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(label, Type.labelMedium, c.onSurfaceVariant, 1))
        val v = text(value, Type.bodyLarge, c.onSurface)
        v.setTextIsSelectable(true)
        col.addView(v, linLp(WRAP, WRAP, t = 2.dp))
        row.addView(col, linLp(0, WRAP, 1f, l = 16.dp))
        parent.addView(row, linLp(MATCH, WRAP))
    }

    private fun showInfo() {
        val s = session ?: return
        val d = s.doc
        val sheet = modalSheet()
        sheetTitle(sheet, docName, "${d.width} × ${d.height} pixels")
        val col = scrollBody(sheet)
        infoRow(col, R.drawable.ic_straighten, "Dimensions", "${d.width} × ${d.height} px · ${"%.1f".format(d.width * d.height / 1e6)} MP")
        d.dpi?.let { dpi ->
            if (dpi > 0) infoRow(col, R.drawable.ic_grid, "Resolution", "${dpi.roundToInt()} ppi · ${"%.2f".format(d.width / dpi * 2.54)} × ${"%.2f".format(d.height / dpi * 2.54)} cm", 2)
        }
        infoRow(col, R.drawable.ic_palette, "Color", "${d.mode.label} · ${d.depth} bits/channel" + (d.iccProfileName?.let { "\n$it" } ?: ""), 1)
        val kinds = s.allLayers.groupingBy { it.kind }.eachCount()
        val summary = if (s.allLayers.isEmpty()) "Flattened (no layers)" else
            "${s.allLayers.size} total · " + kinds.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.value} ${it.key.label.lowercase()}" }
        infoRow(col, R.drawable.ic_layers, "Layers", summary)
        infoRow(col, R.drawable.ic_file, "File", "${if (d.isPsb) "Large Document (PSB)" else "Photoshop Document (PSD)"} · ${formatBytes(fileSize)}", 2)
        infoRow(col, R.drawable.ic_image, "Saved preview", if (s.hasComposite) "Included (maximized compatibility)" else "Not included — rendered from layers", 1)
        d.writer?.let { infoRow(col, R.drawable.ic_brush, "Created with", it) }
        showSheet(sheet)
    }

    private fun showExport() {
        val s = session ?: return
        val sheet = modalSheet()
        sheetTitle(sheet, "Export", if (modified) "Exports your current layer visibility as a PNG." else "Exports the full-resolution image as a PNG.")
        val col = scrollBody(sheet)
        val save = ExpressiveButton(this, "Save as PNG", R.drawable.ic_download, ButtonStyle.FILLED, 56.dp)
        save.setOnClickListener { sheet.dismiss(); createDocument(REQ_SAVE, baseName() + ".png") }
        val share = ExpressiveButton(this, "Share", R.drawable.ic_share, ButtonStyle.TONAL, 56.dp)
        share.setOnClickListener { sheet.dismiss(); shareImage(null) }
        col.addView(save, linLp(MATCH, 56.dp, l = 8.dp, t = 8.dp, r = 8.dp))
        col.addView(share, linLp(MATCH, 56.dp, l = 8.dp, t = 8.dp, r = 8.dp))
        col.addView(text("${s.doc.width} × ${s.doc.height} px", Type.bodySmall, Theme.c.onSurfaceVariant).apply { gravity = Gravity.CENTER },
            linLp(MATCH, WRAP, t = 16.dp))
        showSheet(sheet)
    }

    private fun showLayerDetail(l: Layer) {
        val s = session ?: return
        val c = Theme.c
        val sheet = modalSheet()
        sheetTitle(sheet, l.name, describe(l))
        val col = scrollBody(sheet)
        val preview = FrameLayout(this).apply {
            background = CheckerDrawable(24.dpf, 8.dp)
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) = outline.setRoundRect(0, 0, view.width, view.height, 24.dpf)
            }
        }
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(12.dp, 12.dp, 12.dp, 12.dp) }
        preview.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
        val ind = LoadingIndicator(this)
        preview.addView(ind, frameLp(48.dp, 48.dp, Gravity.CENTER))
        col.addView(preview, linLp(MATCH, 220.dp, t = 4.dp))
        thumbs.execute {
            val bmp = try { s.layerRaster(l)?.let { if (it.width > 0 && it.height > 0) it.toBitmap() else null } } catch (_: Throwable) { null }
            main.post {
                preview.removeView(ind)
                if (bmp != null) img.setImageBitmap(bmp)
                else if (l.solidColor != null) preview.background = MorphRect(l.solidColor, 24.dpf)
                else img.setImageDrawable(getDrawable(R.drawable.ic_image)!!.mutate().apply { setTint(c.outline) })
            }
        }

        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun addChip(v: View) = chips.addView(v, linLp(WRAP, 28.dp, r = 8.dp))
        addChip(chip(l.kind.label, kindIcon(l), c.secondaryContainer, c.onSecondaryContainer))
        if (l.clipping) addChip(chip("Clipped", R.drawable.ic_clip))
        if (l.mask != null) addChip(chip(if (l.mask.disabled) "Mask off" else "Mask", R.drawable.ic_mask))
        if (l.hasEffects) addChip(chip("Effects", R.drawable.ic_effects, c.tertiaryContainer, c.onTertiaryContainer))
        col.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, linLp(MATCH, WRAP, t = 16.dp))

        infoRow(col, R.drawable.ic_effects, "Blending", "${l.blend.label} · ${(l.opacity * 100 / 255f).roundToInt()}% opacity" +
            if (l.fillOpacity < 255) " · ${(l.fillOpacity * 100 / 255f).roundToInt()}% fill" else "", 1)
        if (!l.rect.isEmpty) infoRow(col, R.drawable.ic_straighten, "Bounds", "${l.rect.width} × ${l.rect.height} px at ${l.rect.left}, ${l.rect.top}", 2)
        l.text?.let { infoRow(col, R.drawable.ic_text, "Text", it) }
        l.adjustmentType?.let { infoRow(col, R.drawable.ic_tune, if (l.kind == LayerKind.ADJUSTMENT) "Adjustment" else "Fill", it + if (l.kind == LayerKind.ADJUSTMENT) " (not rendered by Strata)" else "", 1) }

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val solo = ExpressiveButton(this, "Show only this", R.drawable.ic_visibility, ButtonStyle.TONAL, 48.dp)
        solo.setOnClickListener { sheet.dismiss(); solo(l) }
        actions.addView(solo, linLp(0, 48.dp, 1f, r = 8.dp))
        if (l.hasPixels()) {
            val save = ExpressiveButton(this, "Save PNG", R.drawable.ic_download, ButtonStyle.FILLED, 48.dp)
            save.setOnClickListener { sheet.dismiss(); pendingLayerExport = l; createDocument(REQ_SAVE_LAYER, "${baseName()} - ${l.name}.png") }
            actions.addView(save, linLp(0, 48.dp, 1f))
        }
        col.addView(actions, linLp(MATCH, WRAP, l = 8.dp, t = 16.dp, r = 8.dp))
        if (l.hasPixels()) {
            val share = ExpressiveButton(this, "Share layer", R.drawable.ic_share, ButtonStyle.TEXT, 48.dp)
            share.setOnClickListener { sheet.dismiss(); shareImage(l) }
            col.addView(share, linLp(WRAP, 48.dp, gravity = Gravity.CENTER_HORIZONTAL, t = 4.dp))
        }
        showSheet(sheet)
    }

    private fun kindIcon(l: Layer) = when (l.kind) {
        LayerKind.GROUP -> R.drawable.ic_folder
        LayerKind.TEXT -> R.drawable.ic_text
        LayerKind.SMART_OBJECT -> R.drawable.ic_link
        LayerKind.ADJUSTMENT -> R.drawable.ic_tune
        LayerKind.FILL -> R.drawable.ic_fill
        LayerKind.SHAPE -> R.drawable.ic_shape
        LayerKind.PIXEL -> R.drawable.ic_image
    }

    private fun solo(target: Layer) {
        val s = session ?: return
        val ancestors = HashSet<Layer>()
        var p = target.parent
        while (p != null) { ancestors.add(p); p = p.parent }
        fun isDescendant(l: Layer): Boolean { var q = l.parent; while (q != null) { if (q === target) return true; q = q.parent }; return false }
        for (l in s.allLayers) {
            when {
                l === target || l in ancestors -> l.visible = true
                isDescendant(l) -> {}
                // Keep layers clipped to the target visible as they belong to it.
                l.clipping && l.parent === target.parent && clippedTo(l) === target -> {}
                else -> l.visible = false
            }
        }
        markModified()
        snackbar.show("Showing only “${target.name}”", "Undo") { resetVisibility() }
    }

    private fun clippedTo(l: Layer): Layer? {
        val siblings = l.parent?.children ?: session?.doc?.roots ?: return null
        val i = siblings.indexOf(l)
        // Siblings are top-first; the clip base is the first non-clipped layer below.
        for (j in i + 1 until siblings.size) if (!siblings[j].clipping) return siblings[j]
        return null
    }

    // ================================================================== export

    private fun baseName() = docName.substringBeforeLast('.').ifBlank { "image" }

    private fun createDocument(req: Int, name: String) {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/png"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, req)
        } catch (_: Exception) { snackbar.show("No app available to save files") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        val layer = if (requestCode == REQ_SAVE_LAYER) pendingLayerExport else null
        if (requestCode != REQ_SAVE && requestCode != REQ_SAVE_LAYER) return
        exportTo(layer, { contentResolver.openOutputStream(uri, "wt")!! }) { ok, note ->
            snackbar.show(if (ok) "Saved${note}" else "Export failed$note")
        }
    }

    private fun shareImage(layer: Layer?) {
        val name = (if (layer != null) "${baseName()} - ${layer.name}" else baseName()).replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".png"
        val file = File(ShareProvider.dir(this), name)
        ShareProvider.dir(this).listFiles()?.forEach { if (it != file) it.delete() }
        exportTo(layer, { FileOutputStream(file) }) { ok, note ->
            if (!ok) { snackbar.show("Export failed$note"); return@exportTo }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, ShareProvider.uriFor(file))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, name))
        }
    }

    /** Renders at full resolution (falling back to the preview size if memory is short) and writes a PNG. */
    private fun exportTo(layer: Layer?, open: () -> OutputStream, done: (Boolean, String) -> Unit) {
        val s = session ?: return
        snackbar.show("Preparing image…")
        busy.visibility = View.VISIBLE
        heavy.execute {
            var note = ""
            val ok = try {
                val bmp: Bitmap = try {
                    when {
                        layer != null -> s.layerFull(layer)!!.toBitmap()
                        !modified && s.hasComposite -> s.composite(1).toBitmap()
                        else -> s.renderLayersFull().let { Bitmap.createBitmap(it.pixels, it.width, it.height, Bitmap.Config.ARGB_8888) }
                    }
                } catch (oom: OutOfMemoryError) {
                    note = " (reduced size — not enough memory for full resolution)"
                    if (layer != null) s.layerRaster(layer)!!.toBitmap()
                    else s.renderLayers()!!.let { Bitmap.createBitmap(it.pixels, it.width, it.height, Bitmap.Config.ARGB_8888) }
                }
                open().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                true
            } catch (t: Throwable) { note = ": ${t.message ?: t.javaClass.simpleName}"; false }
            main.post { busy.visibility = View.GONE; done(ok, note) }
        }
    }
}
