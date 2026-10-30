package io.github.gaogaoza.simpletimetable.sync

import com.google.gson.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32

object Wire {
    const val START = 1; const val DATA = 2; const val END = 3; const val ACK = 4
    const val MAX = 65536
    const val CHUNK = 100
    data class Options(val chunk: Int = 10, val lz4: Boolean = false, val extended: Boolean = false)
    const val SERVICE = "d7300001-9e2c-4c53-a1aa-2cc0bf3d7710"
    const val RX = "d7300002-9e2c-4c53-a1aa-2cc0bf3d7710"
    const val TX = "d7300003-9e2c-4c53-a1aa-2cc0bf3d7710"
    data class Packet(val op: Int, val id: Int, val seq: Int, val data: ByteArray)
    fun crc16(bytes: ByteArray): Int {
        var c = 65535
        bytes.forEach { b -> c = c xor ((b.toInt() and 255) shl 8); repeat(8) { c = ((c shl 1) xor (if (c and 32768 != 0) 0x1021 else 0)) and 65535 } }
        return c
    }
    fun crc32(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value
    fun frame(op: Int, id: Int, seq: Int, data: ByteArray = byteArrayOf()): ByteArray {
        require(data.size <= CHUNK)
        val b = ByteBuffer.allocate(10 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put(83).put(1).put(op.toByte()).putShort(id.toShort()).putShort(seq.toShort()).put(data.size.toByte()).putShort(0).put(data)
        val raw = b.array(); b.putShort(8, crc16(raw.copyOfRange(0, 8) + data).toShort()); return raw
    }
    fun decode(bytes: ByteArray): Packet {
        require(bytes.size in 10..(CHUNK + 10)) { "Invalid frame length" }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(b.get().toInt() == 83 && b.get().toInt() == 1) { "Invalid protocol" }
        val op = b.get().toInt(); val id = b.short.toInt() and 65535; val seq = b.short.toInt() and 65535
        val length = b.get().toInt() and 255; val crc = b.short.toInt() and 65535
        require(op in 1..5 && length == bytes.size - 10 && crc16(bytes.copyOfRange(0, 8) + bytes.copyOfRange(10, bytes.size)) == crc) { "Frame checksum" }
        return Packet(op, id, seq, bytes.copyOfRange(10, bytes.size))
    }
    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { c -> when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 32) append("\\u%04x".format(c.code)) else append(c)
        } }
        append('"')
    }
    fun canonical(v: JsonElement): String = when {
        v.isJsonObject -> v.asJsonObject.entrySet().sortedBy { it.key }.joinToString(",", "{", "}") { quote(it.key) + ":" + canonical(it.value) }
        v.isJsonArray -> v.asJsonArray.joinToString(",", "[", "]") { canonical(it) }
        v.isJsonPrimitive && v.asJsonPrimitive.isNumber -> v.asBigDecimal.stripTrailingZeros().toPlainString()
        v.isJsonPrimitive && v.asJsonPrimitive.isString -> quote(v.asString)
        else -> GsonBuilder().disableHtmlEscaping().create().toJson(v)
    }
    fun revision(v: JsonElement): String = "%08x".format(crc32(canonical(v).toByteArray(Charsets.UTF_8)))
    fun direction(local: String, remote: String, base: String?): String = when { local == remote -> "same"; base == null -> "choose"; local == base -> "receive"; remote == base -> "send"; else -> "conflict" }
    class Sender(val id: Int, val message: JsonObject, options: Options = Options()) {
        private val raw = GsonBuilder().disableHtmlEscaping().create().toJson(message).toByteArray(Charsets.UTF_8)
        val rawSize = raw.size
        private val compressed = if (options.lz4) Lz4.compress(raw) else raw
        val codec = if (options.lz4 && compressed.size < raw.size) 1 else 0
        val bytes = if (codec == 1) compressed else raw
        val sum = crc32(bytes)
        var chunk = if (options.chunk == 100) 100 else 10
            private set
        val extended = options.extended || options.lz4 || chunk == 100
        val end get() = (bytes.size + chunk - 1) / chunk + 1
        var cursor = 0; var waiting = false; var retries = 0
        init { require(raw.size in 1..MAX) { "完整档案超过 64 KiB" } }
        fun fallback(): Boolean { if (chunk != 100) return false; chunk = 10; cursor = 0; waiting = false; retries = 0; return true }
        fun packet(): ByteArray? = when {
            cursor > end -> null
            cursor == 0 -> frame(START, id, 0, ByteBuffer.allocate(if (extended) 10 else 8).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size).putInt(sum.toInt()).apply { if (extended) put(chunk.toByte()).put(codec.toByte()) }.array())
            cursor == end -> frame(END, id, cursor)
            else -> frame(DATA, id, cursor, bytes.copyOfRange((cursor - 1) * chunk, minOf(cursor * chunk, bytes.size)))
        }
        fun ack(raw: ByteArray): Boolean {
            val f = decode(raw)
            if (f.op != ACK || f.id != id || f.seq !in 1..end + 1 || cursor != 0 && f.seq != cursor + 1) return false
            if (extended) {
                if (f.data.size != 5 || (f.data[0].toInt() and 255) != chunk || (ByteBuffer.wrap(f.data, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL) != sum) return false
            } else if (f.data.isNotEmpty()) return false
            cursor = f.seq; waiting = false; retries = 0; return true
        }
    }
    class Receiver(private var state: JsonObject?, private val persist: (JsonObject) -> Unit, private val deliver: (JsonObject) -> Unit, private val memory: Boolean = false, private val decodedSize: (Int) -> Unit = {}) {
        private var buffer = ByteArray(MAX); private var received = 0
        fun receive(raw: ByteArray): ByteArray {
            val f = decode(raw)
            if (f.op == START) {
                require(f.seq == 0 && f.data.size in listOf(8, 10))
                val b = ByteBuffer.wrap(f.data).order(ByteOrder.LITTLE_ENDIAN); val size = b.int; val crc = b.int.toLong() and 0xffffffffL
                val chunk = if (f.data.size == 10) b.get().toInt() and 255 else 10; val codec = if (f.data.size == 10) b.get().toInt() else 0
                require(chunk in listOf(10, 100) && codec in 0..1)
                require(size in 1..MAX) { "Transfer limit" }
                val old = state
                if (old == null || old["id"].asInt != f.id || old["size"].asInt != size || old["sum"].asLong != crc || (old["chunk"]?.asInt ?: 10) != chunk || (old["codec"]?.asInt ?: 0) != codec) {
                    val n = JsonObject().apply { addProperty("id", f.id); addProperty("size", size); addProperty("sum", crc); addProperty("chunk", chunk); addProperty("codec", codec); addProperty("extended", f.data.size == 10); addProperty("next", 1); addProperty("complete", false); add("bytes", JsonArray()) }
                    persist(n); state = n; received = 0
                }
            } else {
                val s = requireNotNull(state)
                require(s["id"].asInt == f.id)
                val next = s["next"].asInt; val bytes = s["bytes"].asJsonArray
                val count = if (memory) received else bytes.size()
                if (f.op == DATA && !s["complete"].asBoolean && f.seq == next) {
                    require(f.data.size == minOf(s["chunk"]?.asInt ?: 10, s["size"].asInt - count) && f.data.isNotEmpty())
                    val n = if (memory) s else s.deepCopy()
                    if (memory) { f.data.copyInto(buffer, received); received += f.data.size } else f.data.forEach { n["bytes"].asJsonArray.add(it.toInt() and 255) }; n.addProperty("next", next + 1)
                    persist(n); state = n
                } else if (f.op == END && !s["complete"].asBoolean && f.seq == next) {
                    val all = if (memory) buffer.copyOf(received) else ByteArray(bytes.size()) { bytes[it].asInt.toByte() }
                    require(f.data.isEmpty() && all.size == s["size"].asInt && crc32(all) == s["sum"].asLong) { "Transfer checksum/length" }
                    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    val decoded = if ((s["codec"]?.asInt ?: 0) == 1) Lz4.decompress(all) else all
                    decodedSize(decoded.size)
                    deliver(JsonParser.parseString(decoder.decode(ByteBuffer.wrap(decoded)).toString()).asJsonObject)
                    val n = s.deepCopy(); n.addProperty("next", next + 1); n.addProperty("complete", true); persist(n); state = n
                } else {
                    require(f.op == DATA || f.op == END)
                }
            }
            val current = requireNotNull(state)
            val binding = if (current["extended"]?.asBoolean == true) ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).put(current["chunk"].asInt.toByte()).putInt(current["sum"].asLong.toInt()).array() else byteArrayOf()
            return frame(ACK, f.id, current["next"].asInt, binding)
        }
    }
}
