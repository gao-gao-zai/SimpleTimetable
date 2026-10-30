package io.github.gaogaoza.simpletimetable

import com.google.gson.*
import io.github.gaogaoza.simpletimetable.sync.*
import org.junit.Assert.*
import org.junit.Test

class CompressionTest {
    @Test fun javascriptStreamsDecodeAndKotlinStreamsAreExportedForCrossCheck() {
        val vectors = javaClass.classLoader!!.getResourceAsStream("transfer-vectors.json")!!.bufferedReader().use { JsonParser.parseReader(it).asJsonArray }
        val exported = JsonArray()
        vectors.forEach { item ->
            val v = item.asJsonObject; var deliveries = 0; var decodedBytes = 0
            val receiver = Wire.Receiver(null, {}, { assertEquals(v["message"], it); deliveries++ }, memory = true, decodedSize = { decodedBytes = it })
            v["frames"].asJsonArray.forEach { packet -> receiver.receive(packet.asJsonArray.map { it.asInt.toByte() }.toByteArray()) }
            assertEquals(1, deliveries); assertTrue(decodedBytes > 0)
            val o = v["options"].asJsonObject
            val sender = Wire.Sender(42, v["message"].asJsonObject, Wire.Options(o["chunk"]?.asInt ?: 10, o["lz4"]?.asBoolean ?: false, o["extended"]?.asBoolean ?: false))
            val frames = JsonArray()
            while (sender.packet() != null) { val packet = sender.packet()!!; frames.add(JsonArray().apply { packet.forEach { add(it.toInt() and 255) } }); sender.cursor = Wire.decode(packet).seq + 1 }
            exported.add(JsonObject().apply { add("message", v["message"]); add("frames", frames) })
        }
        java.io.File("build/interop").mkdirs()
        java.io.File("build/interop/kotlin-transfers.json").writeText(GsonBuilder().disableHtmlEscaping().create().toJson(exported))
    }
    @Test fun independentLz4Vectors() {
        val vectors = javaClass.classLoader!!.getResourceAsStream("lz4-vectors.json")!!.bufferedReader().use { JsonParser.parseReader(it).asJsonArray }
        vectors.forEach { item -> val v = item.asJsonObject; val raw = v["raw"].asJsonArray.map { it.asInt.toByte() }.toByteArray(); val compressed = v["compressed"].asJsonArray.map { it.asInt.toByte() }.toByteArray(); assertArrayEquals(raw, Lz4.decompress(compressed)); assertArrayEquals(raw, Lz4.decompress(Lz4.compress(raw))) }
    }
    @Test fun chineseEmojiLargeTransferWithCompressionAndDuplicateEnd() {
        val m = JsonObject().apply { addProperty("text", "中文😀课表".repeat(2000)) }; var delivered = 0
        val s = Wire.Sender(7, m, Wire.Options(100, true, true)); val r = Wire.Receiver(null, {}, { assertEquals(m, it); delivered++ }, memory = true)
        assertEquals(1, s.codec)
        while (s.packet() != null) { val p = s.packet()!!; assertTrue(p.size <= 110); val ack = r.receive(p); if (Wire.decode(p).op == Wire.END) assertArrayEquals(ack, r.receive(p)); assertTrue(s.ack(ack)) }
        assertEquals(1, delivered)
    }
    @Test fun fallbackPreservesCompressionRejectsOldAck() {
        val s = Wire.Sender(7, JsonObject().apply { addProperty("text", "课程".repeat(1000)) }, Wire.Options(100, true, true)); val r = Wire.Receiver(null, {}, {}, memory = true)
        val old = r.receive(s.packet()!!); assertTrue(s.fallback()); assertEquals(1, s.codec); assertFalse(s.ack(old)); assertTrue(s.ack(r.receive(s.packet()!!)))
    }
    @Test fun incompressibleTinyMessageRemainsRaw() { assertEquals(0, Wire.Sender(1, JsonObject().apply { addProperty("a", 1) }, Wire.Options(100, true, true)).codec) }
    @Test fun rawSizeLimitIsCheckedBeforeCompression() { assertThrows(IllegalArgumentException::class.java) { Wire.Sender(1, JsonObject().apply { addProperty("text", "a".repeat(Wire.MAX)) }, Wire.Options(100, true, true)) } }
    @Test fun corruptLz4AndExpansionLimitRejected() { assertThrows(IllegalArgumentException::class.java) { Lz4.decompress(byteArrayOf(0x10, 65, 0, 0)) }; assertThrows(IllegalArgumentException::class.java) { Lz4.decompress(Lz4.compress(ByteArray(Wire.MAX + 1) { 65 })) } }
}
