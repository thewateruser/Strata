package app.strata.psd.engine

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Big-endian random access reader over a file with a small read buffer.
 * Not thread-safe: use [fork] to get an independent reader for background decoding.
 */
class PsdReader(private val channel: FileChannel, private val owner: Closeable?) : Closeable {

    constructor(file: File) : this(RandomAccessFile(file, "r"))
    private constructor(raf: RandomAccessFile) : this(raf.channel, raf)

    val length: Long = channel.size()
    private val buf: ByteBuffer = ByteBuffer.allocate(64 * 1024).order(ByteOrder.BIG_ENDIAN)
    private var bufStart = 0L   // file offset of buf[0]
    private var bufLen = 0

    var position: Long = 0
        private set

    /** Independent reader over the same channel (positional reads are thread-safe). */
    fun fork(): PsdReader = PsdReader(channel, null)

    fun seek(pos: Long) {
        if (pos < 0 || pos > length) throw PsdException("Seek out of range ($pos / $length)")
        position = pos
    }

    fun skip(n: Long) = seek(position + n)

    private fun fill(need: Int) {
        if (position >= bufStart && position + need <= bufStart + bufLen) return
        if (position + need > length) throw EOFException("Unexpected end of file at $position (+$need)")
        buf.clear()
        bufStart = position
        var read = 0
        while (read < need) {
            val r = channel.read(buf, bufStart + read)
            if (r <= 0) break
            read += r
        }
        bufLen = read
        if (read < need) throw EOFException("Unexpected end of file at $position")
    }

    fun u8(): Int { fill(1); val v = buf.get((position - bufStart).toInt()).toInt() and 0xFF; position++; return v }
    fun i8(): Int { fill(1); val v = buf.get((position - bufStart).toInt()).toInt(); position++; return v }
    fun u16(): Int { fill(2); val v = buf.getShort((position - bufStart).toInt()).toInt() and 0xFFFF; position += 2; return v }
    fun i16(): Int { fill(2); val v = buf.getShort((position - bufStart).toInt()).toInt(); position += 2; return v }
    fun i32(): Int { fill(4); val v = buf.getInt((position - bufStart).toInt()); position += 4; return v }
    fun u32(): Long = i32().toLong() and 0xFFFFFFFFL
    fun i64(): Long { fill(8); val v = buf.getLong((position - bufStart).toInt()); position += 8; return v }
    fun f64(): Double = java.lang.Double.longBitsToDouble(i64())
    fun f32(): Float = java.lang.Float.intBitsToFloat(i32())

    /** 4 or 8 byte length depending on PSD/PSB. */
    fun len(big: Boolean): Long = if (big) i64() else u32()

    fun bytes(n: Int): ByteArray {
        val out = ByteArray(n)
        readFully(out, 0, n)
        return out
    }

    fun readFully(dst: ByteArray, off: Int, n: Int) {
        if (n <= buf.capacity()) {
            fill(n)
            val p = (position - bufStart).toInt()
            for (i in 0 until n) dst[off + i] = buf.get(p + i)
            position += n
            return
        }
        if (position + n > length) throw EOFException("Unexpected end of file")
        val bb = ByteBuffer.wrap(dst, off, n)
        var pos = position
        while (bb.hasRemaining()) {
            val r = channel.read(bb, pos)
            if (r <= 0) throw EOFException("Unexpected end of file")
            pos += r
        }
        position += n
    }

    fun ascii(n: Int): String = String(bytes(n), Charsets.ISO_8859_1)

    fun pascal(padTo: Int): String {
        val n = u8()
        val s = String(bytes(n), Charsets.ISO_8859_1)
        val total = n + 1
        val rem = total % padTo
        if (rem != 0) skip((padTo - rem).toLong())
        return s
    }

    fun unicode(): String {
        val n = i32()
        if (n <= 0) return ""
        if (n > 1_000_000) throw PsdException("Bad unicode string length $n")
        val sb = StringBuilder(n)
        for (i in 0 until n) sb.append(u16().toChar())
        // Strip trailing NULs Photoshop likes to append.
        while (sb.isNotEmpty() && sb[sb.length - 1] == '\u0000') sb.setLength(sb.length - 1)
        return sb.toString()
    }

    override fun close() { owner?.close() }
}

class PsdException(message: String) : Exception(message)
