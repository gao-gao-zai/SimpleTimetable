package io.github.gaogaoza.simpletimetable

import androidx.test.core.app.ApplicationProvider
import com.google.gson.*
import io.github.gaogaoza.simpletimetable.data.*
import io.github.gaogaoza.simpletimetable.sync.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WireTest {
    private val vectors = javaClass.classLoader!!.getResourceAsStream("vectors.json")!!.bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
    @Test fun sharedFrameVectors() {
        vectors["frames"].asJsonArray.forEach { value -> val v = value.asJsonObject; val raw = Wire.frame(v["op"].asInt, v["id"].asInt, v["seq"].asInt, v["data"].asJsonArray.map { it.asInt.toByte() }.toByteArray()); assertEquals(v["hex"].asString, raw.joinToString("") { "%02x".format(it) }); assertEquals(v["seq"].asInt, Wire.decode(raw).seq) }
    }
    @Test fun sharedCanonicalRevisionVectors() { vectors["revisions"].asJsonArray.forEach { assertEquals(it.asJsonObject["revision"].asString, Wire.revision(it.asJsonObject["message"])) } }
    @Test fun crcVectors() { assertEquals(0x29b1, Wire.crc16("123456789".toByteArray())); assertEquals(0xcbf43926L, Wire.crc32("123456789".toByteArray())) }
    @Test fun corruptedFrameAndLimitRejected() { val f = Wire.frame(Wire.DATA, 1, 1, byteArrayOf(1)); f[10] = 2; assertThrows(IllegalArgumentException::class.java) { Wire.decode(f) }; assertThrows(IllegalArgumentException::class.java) { Wire.frame(2, 1, 1, ByteArray(11)) } }
    @Test fun resumeAndDuplicateEndDeliveredOnce() {
        val m = JsonParser.parseString("""{"type":"synthetic","text":"合成😀"}""").asJsonObject
        var disk: JsonObject? = null; var delivered = 0
        fun receiver() = Wire.Receiver(disk, { disk = it.deepCopy() }, { assertEquals(m, it); delivered++ })
        var r = receiver(); val s = Wire.Sender(4, m)
        assertTrue(s.ack(r.receive(s.packet()!!)))
        val first = s.packet()!!; val ack = r.receive(first); assertArrayEquals(ack, r.receive(first)); assertTrue(s.ack(ack))
        r = receiver(); s.cursor = 0; assertTrue(s.ack(r.receive(s.packet()!!))); assertEquals(2, s.cursor)
        while (s.packet() != null) { val p = s.packet()!!; val a = r.receive(p); if (Wire.decode(p).op == Wire.END) assertArrayEquals(a, r.receive(p)); assertTrue(s.ack(a)) }
        assertEquals(1, delivered)
    }
    @Test fun failedJournalHasNoAck() { val r = Wire.Receiver(null, { error("disk full") }, {}); val s = Wire.Sender(1, JsonObject().apply { addProperty("a", 1) }); assertThrows(IllegalStateException::class.java) { r.receive(s.packet()!!) } }
    @Test fun invalidUtf8RejectedAtEnd() {
        val bytes = byteArrayOf(0xc0.toByte(), 0x80.toByte()); val r = Wire.Receiver(null, {}, { fail("must not deliver") })
        r.receive(Wire.frame(Wire.START, 1, 0, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(2).putInt(Wire.crc32(bytes).toInt()).array()))
        r.receive(Wire.frame(Wire.DATA, 1, 1, bytes))
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { r.receive(Wire.frame(Wire.END, 1, 2)) }
    }
    @Test fun conflictDirection() { assertEquals("receive", Wire.direction("a", "b", "a")); assertEquals("send", Wire.direction("a", "b", "b")); assertEquals("conflict", Wire.direction("a", "b", "c")) }
}
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SyncStoreTest {
    private fun setup(): Pair<Repository, SyncStore> {
        val c = ApplicationProvider.getApplicationContext<android.content.Context>()
        listOf("timetable.json", "watch-sync.json", "watch-transfer.json").forEach { java.io.File(c.filesDir, it).delete(); java.io.File(c.filesDir, "$it.bak").delete() }
        val repo = Repository(c); repo.update { val p = SampleData.profile("合成测试"); it.copy(profiles = listOf(p), activeProfile = p.id, settings = it.settings.copy(calibrationMillis = 12345, theme = "dark")) }
        return repo to SyncStore(c, repo)
    }
    @Test fun incomingPreviewDoesNotChangeLiveAndKeepRetainsSettings() {
        val (repo, store) = setup(); val before = repo.current; val m = store.offer(before.active().copy(name = "合成手表版")); store.stage(m)
        assertEquals(before, repo.current); val receipt = store.accept("keep"); assertEquals("keep", receipt["choice"].asString); assertEquals(before, repo.current)
    }
    @Test fun replaceIsAtomicAndReceiptFollowsPersistence() {
        val (repo, store) = setup(); val m = store.offer(repo.current.active().copy(name = "合成接收版")); store.stage(m); val receipt = store.accept("replace")
        assertEquals("合成接收版", repo.current.active().name); assertEquals(12345L, repo.current.settings.calibrationMillis); assertEquals("dark", repo.current.settings.theme)
        assertEquals(m["nonce"], receipt["offerNonce"]); assertEquals(m["revision"], store.meta["common"].asJsonObject[repo.current.active().id])
        assertEquals(repo.current, Repository(ApplicationProvider.getApplicationContext()).current)
    }
    @Test fun stalePreviewCannotOverwriteLocalEdits() {
        val (repo, store) = setup(); store.stage(store.offer(repo.current.active().copy(name = "合成接收版"))); repo.editProfile(repo.current.active().id) { it.copy(name = "合成本地更改") }
        assertThrows(IllegalArgumentException::class.java) { store.accept("replace") }; assertEquals("合成本地更改", repo.current.active().name)
    }
    @Test fun replacementOfferCannotBypassPreviewConfirmation() {
        val (repo, store) = setup(); val first = store.offer(repo.current.active().copy(name = "合成第一次预览")); store.stage(first)
        store.stage(store.offer(repo.current.active().copy(name = "合成第二次接收")))
        assertThrows(IllegalArgumentException::class.java) { store.accept("replace", first["nonce"].asString) }
        assertEquals("合成测试", repo.current.active().name)
    }
    @Test fun copyPreservesUnknownMetadataAndLocalActivation() {
        val (repo, store) = setup(); val originalId = repo.current.activeProfile; val m = store.offer(repo.current.active()); val raw = m["profile"].asJsonObject
        raw.add("futureMetadata", JsonObject().apply { addProperty("synthetic", true) }); raw["subjects"].asJsonArray[0].asJsonObject.addProperty("futureSubject", "synthetic-only")
        m.addProperty("revision", Wire.revision(raw)); store.stage(m); store.accept("copy")
        assertEquals(2, repo.current.profiles.size); assertEquals(originalId, repo.current.activeProfile)
        val copied = store.export(repo.current.profiles.last()); assertTrue(copied["futureMetadata"].asJsonObject["synthetic"].asBoolean); assertEquals("synthetic-only", copied["subjects"].asJsonArray[0].asJsonObject["futureSubject"].asString)
        assertNull(store.meta["common"].asJsonObject[originalId])
    }
}
