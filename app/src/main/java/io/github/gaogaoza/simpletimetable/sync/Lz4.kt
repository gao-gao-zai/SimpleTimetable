package io.github.gaogaoza.simpletimetable.sync

import java.io.ByteArrayOutputStream

/** Independent standard LZ4 block codec; wire size and decoded size are bounded separately. */
object Lz4 {
    fun compress(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(); val table = IntArray(65536) { -1 }
        fun byte(i: Int) = b[i].toInt() and 255
        fun hash(i: Int) = ((byte(i) or (byte(i + 1) shl 8) or (byte(i + 2) shl 16) or (byte(i + 3) shl 24)) * -1640531535) ushr 16
        fun length(value: Int) { var n = value; while (n >= 255) { out.write(255); n -= 255 }; out.write(n) }
        var anchor = 0; var i = 0
        while (i <= b.size - 12) {
            val h = hash(i); val ref = table[h]; table[h] = i
            if (ref < 0 || i - ref > 65535 || (0..3).any { b[ref + it] != b[i + it] }) { i++; continue }
            var match = 4
            while (i + match < b.size - 5 && b[ref + match] == b[i + match]) match++
            val literals = i - anchor; val ml = match - 4
            out.write((minOf(literals, 15) shl 4) or minOf(ml, 15)); if (literals >= 15) length(literals - 15)
            out.write(b, anchor, literals); val offset = i - ref; out.write(offset and 255); out.write(offset ushr 8)
            if (ml >= 15) length(ml - 15)
            i += match; anchor = i
        }
        val literals = b.size - anchor; out.write(minOf(literals, 15) shl 4); if (literals >= 15) length(literals - 15)
        out.write(b, anchor, literals); return out.toByteArray()
    }
    fun decompress(b: ByteArray, limit: Int = Wire.MAX): ByteArray {
        require(b.isNotEmpty()) { "Empty LZ4 block" }
        val out = ByteArray(limit); var i = 0; var n = 0
        fun byte(): Int { require(i < b.size) { "Truncated LZ4" }; return b[i++].toInt() and 255 }
        fun length(initial: Int): Int {
            var value = initial
            if (initial == 15) { var x: Int; do { x = byte(); value += x; require(value <= limit) { "LZ4 expansion limit" } } while (x == 255) }
            return value
        }
        while (i < b.size) {
            val token = byte(); val literals = length(token ushr 4)
            require(i + literals <= b.size && n + literals <= limit) { "Invalid LZ4 literal" }
            b.copyInto(out, n, i, i + literals); i += literals; n += literals
            if (i == b.size) return out.copyOf(n)
            val offset = byte() or (byte() shl 8); require(offset > 0 && offset <= n) { "Invalid LZ4 offset" }
            val match = length(token and 15) + 4; require(n + match <= limit) { "LZ4 expansion limit" }
            repeat(match) { out[n] = out[n - offset]; n++ }
            require(i < b.size) { "LZ4 final literals missing" }
        }
        error("Invalid LZ4 block")
    }
}
