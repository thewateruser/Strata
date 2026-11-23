package app.strata.psd

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.strata.psd.ui.ButtonStyle
import app.strata.psd.ui.CheckerDrawable
import app.strata.psd.ui.ExpressiveButton
import app.strata.psd.ui.MATCH
import app.strata.psd.ui.MorphLayout
import app.strata.psd.ui.Motion
import app.strata.psd.ui.PolarShape
import app.strata.psd.ui.ShapeView
import app.strata.psd.ui.SnackbarHost
import app.strata.psd.ui.Spring
import app.strata.psd.ui.StrataActivity
import app.strata.psd.ui.Theme
import app.strata.psd.ui.Type
import app.strata.psd.ui.WRAP
import app.strata.psd.ui.dp
import app.strata.psd.ui.dpf
import app.strata.psd.ui.frameLp
import app.strata.psd.ui.haptic
import app.strata.psd.ui.iconView
import app.strata.psd.ui.linLp
import app.strata.psd.ui.text
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class MainActivity : StrataActivity() {

    companion object {
        private const val REQ_OPEN = 1
        val PSD_MIME = arrayOf(
            "image/vnd.adobe.photoshop", "application/x-photoshop", "application/photoshop", "application/psd",
            "image/psd", "image/x-photoshop", "application/vnd.adobe.photoshop", "application/octet-stream",
        )
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var content: LinearLayout
    private lateinit var grid: LinearLayout
    private lateinit var fab: MorphLayout
    private lateinit var fabLabel: TextView
    private lateinit var snackbar: SnackbarHost
    private var fabExpanded = true
    private var bottomInset = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = Theme.c
        val root = FrameLayout(this)
        root.setBackgroundColor(c.surface)

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipToPadding = false; overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content, FrameLayout.LayoutParams(MATCH, WRAP))
        root.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))

        content.addView(header())
        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(grid, linLp(MATCH, WRAP))

        // Extended FAB (collapses to an icon while scrolling down).
        fab = MorphLayout(this, c.primaryContainer, 20.dpf, 12.dpf).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            elevation = 6.dpf
            isClickable = true; isFocusable = true
            contentDescription = "Open file"
            setPadding(20.dp, 0, 24.dp, 0)
            addView(iconView(R.drawable.ic_folder_open, c.onPrimaryContainer, 24.dp))
            fabLabel = text("Open file", Type.titleMedium, c.onPrimaryContainer, 1)
            addView(fabLabel, linLp(WRAP, WRAP, l = 12.dp))
            setOnClickListener { haptic(); pickFile() }
        }
        root.addView(fab, frameLp(WRAP, 64.dp, Gravity.BOTTOM or Gravity.END))
        scroll.setOnScrollChangeListener { _, _, y, _, oldY ->
            val expand = y < oldY || y < 16.dp
            if (expand != fabExpanded) {
                fabExpanded = expand
                TransitionManager.beginDelayedTransition(fab, AutoTransition().setDuration(220))
                fabLabel.visibility = if (expand) View.VISIBLE else View.GONE
                fab.setPadding(20.dp, 0, if (expand) 24.dp else 20.dp, 0)
            }
        }

        snackbar = SnackbarHost(this)
        root.addView(snackbar, frameLp(MATCH, WRAP, Gravity.BOTTOM))

        onInsets(root) { i ->
            content.setPadding(i.left + 16.dp, i.top + 8.dp, i.right + 16.dp, i.bottom + 112.dp)
            bottomInset = i.bottom
            (fab.layoutParams as FrameLayout.LayoutParams).setMargins(0, 0, i.right + 16.dp, i.bottom + 16.dp)
            (snackbar.layoutParams as FrameLayout.LayoutParams).setMargins(i.left + 16.dp, 0, i.right + 16.dp, i.bottom + 92.dp)
            fab.requestLayout()
        }
        setContentView(root)

        // Entrance: FAB springs up.
        fab.translationY = 160.dpf
        Spring(Motion.defaultSpatial, 160.dpf) { fab.translationY = it }.animateTo(0f)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun header(): View {
        val c = Theme.c
        val box = FrameLayout(this)
        // Expressive decoration: a slowly turning scalloped shape with a clover tucked against it.
        val big = ShapeView(this, PolarShape.cookie12, c.primaryContainer)
        val small = ShapeView(this, PolarShape.clover, c.tertiaryContainer, 20f)
        box.addView(big, frameLp(196.dp, 196.dp, Gravity.END or Gravity.TOP, t = 8.dp, r = (-36).dp))
        box.addView(small, frameLp(72.dp, 72.dp, Gravity.END or Gravity.TOP, t = 168.dp, r = 8.dp))
        ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 60_000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { big.spin = it.animatedValue as Float; small.spin = -(it.animatedValue as Float) * 1.5f }
            start()
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = text("Strata", Type.displayLarge, c.onSurface)
        title.typeface = Theme.face(true, 800)
        title.textSize = 64f
        col.addView(title, linLp(WRAP, WRAP, t = 72.dp))
        col.addView(text("Photoshop files,\nlayer by layer.", Type.headlineSmall, c.onSurfaceVariant), linLp(WRAP, WRAP, t = 8.dp))
        box.addView(col, frameLp(MATCH, WRAP, l = 4.dp))
        box.layoutParams = linLp(MATCH, 260.dp)
        return box
    }

    private fun refresh() {
        val recents = Recents.load(this)
        grid.removeAllViews()
        if (recents.isEmpty()) { grid.addView(emptyState()); return }

        val c = Theme.c
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(text("Recent", Type.titleLarge, c.onSurface), linLp(0, WRAP, 1f, l = 4.dp))
        val clear = ExpressiveButton(this, "Clear", style = ButtonStyle.TEXT, height = 40.dp)
        clear.setOnClickListener {
            val saved = recents
            Recents.clear(this); refresh()
            snackbar.show("Cleared recent files", "Undo") { Recents.setAll(this, saved); refresh() }
        }
        row.addView(clear)
        grid.addView(row, linLp(MATCH, WRAP, b = 8.dp))

        val width = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val cols = maxOf(2, (width / 200f).toInt())
        var line: LinearLayout? = null
        recents.forEachIndexed { i, r ->
            if (i % cols == 0) {
                line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                grid.addView(line, linLp(MATCH, WRAP, b = 12.dp))
            }
            val card = card(r, i)
            line!!.addView(card, linLp(0, WRAP, 1f, l = if (i % cols == 0) 0 else 6.dp, r = if (i % cols == cols - 1) 0 else 6.dp))
            // Staggered spring entrance.
            card.alpha = 0f; card.translationY = 40.dpf
            card.postDelayed({
                card.animate().alpha(1f).setDuration(200).start()
                Spring(Motion.defaultSpatial, 40.dpf) { card.translationY = it }.animateTo(0f)
            }, 30L * minOf(i, 12))
        }
        val remaining = recents.size % cols
        if (remaining != 0) repeat(cols - remaining) { line!!.addView(View(this), linLp(0, 1, 1f, l = 6.dp)) }
    }

    private fun card(r: Recent, index: Int): View {
        val c = Theme.c
        val card = MorphLayout(this, c.surfaceContainer, 28.dpf, 16.dpf).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true; isFocusable = true; isLongClickable = true
            setPadding(8.dp, 8.dp, 8.dp, 16.dp)
        }
        val thumbBox = FrameLayout(this).apply {
            background = CheckerDrawable(20.dpf)
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) = outline.setRoundRect(0, 0, view.width, view.height, 20.dpf)
            }
        }
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        thumbBox.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
        card.addView(object : FrameLayout(this) {
            override fun onMeasure(w: Int, h: Int) {
                val width = MeasureSpec.getSize(w)
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(width * 3 / 4, MeasureSpec.EXACTLY))
            }
        }.apply { addView(thumbBox, FrameLayout.LayoutParams(MATCH, MATCH)) }, linLp(MATCH, WRAP))
        card.addView(text(r.name, Type.titleSmall, c.onSurface, 1), linLp(MATCH, WRAP, l = 8.dp, t = 12.dp, r = 8.dp))
        val meta = if (r.width > 0) "${r.width} × ${r.height} · ${formatBytes(r.size)}" else formatBytes(r.size)
        card.addView(text(meta, Type.bodySmall, c.onSurfaceVariant, 1), linLp(MATCH, WRAP, l = 8.dp, t = 2.dp, r = 8.dp))
        card.addView(text(timeAgo(r.opened), Type.labelSmall, c.primary, 1), linLp(MATCH, WRAP, l = 8.dp, t = 6.dp, r = 8.dp))

        val f = r.thumbFile(this)
        if (f.exists()) io.execute {
            val bmp = try { BitmapFactory.decodeFile(f.path) } catch (_: Throwable) { null }
            if (bmp != null) main.post { img.setImageBitmap(bmp); img.scaleType = ImageView.ScaleType.FIT_CENTER }
        } else {
            img.setImageDrawable(getDrawable(R.drawable.ic_image)!!.mutate().apply { setTint(c.outline) })
            img.scaleType = ImageView.ScaleType.CENTER
        }
        card.setOnClickListener { openViewer(Uri.parse(r.uri)) }
        card.setOnLongClickListener {
            it.haptic()
            Recents.remove(this, r.uri)
            refresh()
            snackbar.show("Removed “${r.name}”", "Undo") { Recents.restore(this, r, index); refresh() }
            true
        }
        return card
    }

    private fun emptyState(): View {
        val c = Theme.c
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val art = FrameLayout(this)
        art.addView(ShapeView(this, PolarShape.flower, c.secondaryContainer, 10f), frameLp(180.dp, 180.dp, Gravity.CENTER))
        art.addView(ShapeView(this, PolarShape.polygon(5, 0.5f), c.primary, -18f), frameLp(84.dp, 84.dp, Gravity.TOP or Gravity.START, l = 34.dp, t = 30.dp))
        art.addView(ShapeView(this, PolarShape.ellipse(0.62f), c.tertiary, 35f), frameLp(96.dp, 96.dp, Gravity.BOTTOM or Gravity.END, r = 26.dp, b = 22.dp))
        art.addView(iconView(R.drawable.ic_layers, c.onSecondaryContainer, 40.dp), frameLp(40.dp, 40.dp, Gravity.CENTER))
        col.addView(art, linLp(240.dp, 220.dp, t = 8.dp))
        col.addView(text("Nothing open yet", Type.headlineSmall, c.onSurface), linLp(WRAP, WRAP, t = 16.dp))
        val body = text("Open a .psd or .psb to explore it — every layer, group, mask and blend mode, rendered right on your phone.", Type.bodyLarge, c.onSurfaceVariant)
        body.gravity = Gravity.CENTER
        col.addView(body, linLp(MATCH, WRAP, l = 24.dp, t = 8.dp, r = 24.dp))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val open = ExpressiveButton(this, "Browse files", R.drawable.ic_folder_open, ButtonStyle.FILLED, 56.dp)
        open.setOnClickListener { pickFile() }
        val sample = ExpressiveButton(this, "Try a sample", R.drawable.ic_effects, ButtonStyle.TONAL, 56.dp)
        sample.setOnClickListener { openSample() }
        row.addView(open, linLp(WRAP, 56.dp, r = 8.dp))
        row.addView(sample, linLp(WRAP, 56.dp))
        col.addView(row, linLp(WRAP, WRAP, t = 24.dp))
        return col
    }

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, PSD_MIME)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_OPEN)
        } catch (_: Exception) { snackbar.show("No file picker available") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OPEN && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            openViewer(uri)
        }
    }

    private fun openSample() {
        io.execute {
            val f = File(filesDir, "Strata sample.psd")
            if (!f.exists()) assets.open("sample.psd").use { i -> FileOutputStream(f).use { i.copyTo(it) } }
            main.post { openViewer(Uri.fromFile(f)) }
        }
    }

    private fun openViewer(uri: Uri) {
        val i = Intent(this, ViewerActivity::class.java)
        // file:// URIs can't travel in intents (FileUriExposedException); pass app-private paths as an extra.
        if (uri.scheme == "file") i.putExtra(ViewerActivity.EXTRA_PATH, uri.path)
        else i.setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(i)
    }

    override fun onDestroy() { io.shutdown(); super.onDestroy() }
}
