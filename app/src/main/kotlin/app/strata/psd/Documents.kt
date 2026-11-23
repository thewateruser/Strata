package app.strata.psd

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import app.strata.psd.engine.PsdReader
import app.strata.psd.engine.Raster
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Opens a document URI for random access, copying to cache only when the provider can't seek. */
object Documents {

    class Opened(val reader: PsdReader, val name: String, val size: Long)

    fun displayName(ctx: Context, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "Document"
        var size = -1L
        if (uri.scheme == "content") {
            try {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { name = it }
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            } catch (_: Exception) { }
        } else if (uri.scheme == "file") {
            uri.path?.let { size = File(it).length() }
        }
        return name to size
    }

    fun open(ctx: Context, uri: Uri): Opened {
        val (name, size) = displayName(ctx, uri)
        if (uri.scheme == "file") {
            val f = File(uri.path!!)
            return Opened(PsdReader(f), name, f.length())
        }
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: throw IllegalStateException("Couldn't open the file")
        val fis = FileInputStream(pfd.fileDescriptor)
        val ch = fis.channel
        val seekable = try {
            ch.size() > 0 && ch.read(ByteBuffer.allocate(4), 0) == 4
        } catch (_: Exception) { false }
        if (seekable) {
            val closer = Closeable { try { fis.close() } finally { pfd.close() } }
            return Opened(PsdReader(ch, closer), name, ch.size())
        }
        // Streaming-only provider (e.g. some cloud drives): copy to cache.
        fis.close(); pfd.close()
        val dir = File(ctx.cacheDir, "docs").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val tmp = File(dir, "doc.bin")
        ctx.contentResolver.openInputStream(uri)!!.use { input -> FileOutputStream(tmp).use { input.copyTo(it, 1 shl 16) } }
        return Opened(PsdReader(tmp), name, tmp.length())
    }

    fun key(uri: String): String {
        val md = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray())
        return md.joinToString("") { "%02x".format(it) }.take(16)
    }
}

/** Raster (ARGB, non-premultiplied) -> Bitmap. */
fun Raster.toBitmap(): Bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)

fun Bitmap.scaledToFit(maxSide: Int): Bitmap {
    val m = maxOf(width, height)
    if (m <= maxSide) return this
    val s = maxSide.toFloat() / m
    return Bitmap.createScaledBitmap(this, maxOf(1, (width * s).toInt()), maxOf(1, (height * s).toInt()), true)
}

class Recent(
    val uri: String, val name: String, val size: Long, val width: Int, val height: Int,
    val mode: String, val layers: Int, val opened: Long,
) {
    fun thumbFile(ctx: Context) = File(File(ctx.filesDir, "thumbs"), Documents.key(uri) + ".webp")

    fun json() = JSONObject().put("uri", uri).put("name", name).put("size", size).put("w", width).put("h", height)
        .put("mode", mode).put("layers", layers).put("opened", opened)

    companion object {
        fun from(o: JSONObject) = Recent(o.getString("uri"), o.getString("name"), o.optLong("size"), o.optInt("w"),
            o.optInt("h"), o.optString("mode"), o.optInt("layers"), o.optLong("opened"))
    }
}

object Recents {
    private const val KEY = "recents"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("strata", Context.MODE_PRIVATE)

    fun load(ctx: Context): List<Recent> = try {
        val arr = JSONArray(prefs(ctx).getString(KEY, "[]"))
        List(arr.length()) { Recent.from(arr.getJSONObject(it)) }
    } catch (_: Exception) { emptyList() }

    private fun save(ctx: Context, list: List<Recent>) {
        val arr = JSONArray(); list.forEach { arr.put(it.json()) }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    @Synchronized
    fun add(ctx: Context, r: Recent, thumb: Bitmap?) {
        val list = load(ctx).filter { it.uri != r.uri }.toMutableList()
        list.add(0, r)
        while (list.size > 40) list.removeAt(list.size - 1).thumbFile(ctx).delete()
        save(ctx, list)
        if (thumb != null) {
            val f = r.thumbFile(ctx)
            f.parentFile?.mkdirs()
            try {
                FileOutputStream(f).use { @Suppress("DEPRECATION") val fmt = if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                    thumb.scaledToFit(640).compress(fmt, 88, it) }
            } catch (_: Exception) { }
        }
    }

    @Synchronized
    fun remove(ctx: Context, uri: String) = save(ctx, load(ctx).filter { it.uri != uri })

    @Synchronized
    fun restore(ctx: Context, r: Recent, index: Int) {
        val list = load(ctx).filter { it.uri != r.uri }.toMutableList()
        list.add(index.coerceIn(0, list.size), r)
        save(ctx, list)
    }

    @Synchronized
    fun clear(ctx: Context) = save(ctx, emptyList())

    @Synchronized
    fun setAll(ctx: Context, list: List<Recent>) = save(ctx, list)
}

/** Serves exported PNGs from cache/share for the share sheet (a tiny FileProvider). */
class ShareProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "app.strata.psd.share"
        fun dir(ctx: Context) = File(ctx.cacheDir, "share").apply { mkdirs() }
        fun uriFor(file: File): Uri = Uri.parse("content://$AUTHORITY/${Uri.encode(file.name)}")
    }

    private fun file(uri: Uri): File {
        val name = uri.lastPathSegment ?: throw SecurityException("Bad uri")
        val f = File(dir(context!!), name)
        if (f.parentFile?.canonicalPath != dir(context!!).canonicalPath) throw SecurityException("Bad path")
        return f
    }

    override fun onCreate() = true
    override fun getType(uri: Uri) = "image/png"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = file(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        c.addRow(cols.map { if (it == OpenableColumns.DISPLAY_NAME) f.name else if (it == OpenableColumns.SIZE) f.length() else null }.toTypedArray<Any?>())
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

fun formatBytes(b: Long): String = when {
    b < 0 -> "—"
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.0f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
    else -> "%.2f GB".format(b / 1073741824.0)
}

fun timeAgo(t: Long): String {
    val d = (System.currentTimeMillis() - t) / 1000
    return when {
        d < 60 -> "Just now"
        d < 3600 -> "${d / 60} min ago"
        d < 86400 -> "${d / 3600} h ago"
        d < 86400 * 7 -> "${d / 86400} d ago"
        else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(t))
    }
}
