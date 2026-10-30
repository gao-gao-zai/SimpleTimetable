package io.github.gaogaoza.simpletimetable

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.google.gson.*
import io.github.gaogaoza.simpletimetable.data.*
import io.github.gaogaoza.simpletimetable.sync.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.ArrayDeque

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncConsentTest {
    private fun field(client: WatchClient, name: String) = WatchClient::class.java.getDeclaredField(name).apply { isAccessible = true }.get(client)
    private fun invoke(client: WatchClient, name: String, type: Class<*>, arg: Any) = WatchClient::class.java.getDeclaredMethod(name, type).apply { isAccessible = true }.invoke(client, arg)
    @Suppress("UNCHECKED_CAST")
    private fun paired(client: WatchClient) { (field(client, "mutable") as MutableStateFlow<WatchClient.View>).value = WatchClient.View(paired = true) }
    private fun setup(): Triple<Repository, WatchClient, Context> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        listOf("timetable.json", "watch-sync.json", "watch-transfer.json").forEach { java.io.File(context.filesDir, it).delete() }
        val repo = Repository(context); repo.update { val p = SampleData.profile("合成"); it.copy(profiles = listOf(p), activeProfile = p.id) }
        return Triple(repo, WatchClient(context, repo), context)
    }
    @Test fun connectionDoesNotReadSyncDiskOrReplayOldBody() {
        val (repo, client, context) = setup()
        java.io.File(context.filesDir, "watch-sync.json").writeText("broken storage")
        try { invoke(client, "deliver", JsonObject::class.java, SyncStore.message("READY").apply { add("transport", JsonObject().apply { addProperty("consent", 1) }) }); assertTrue(client.view.value.paired); assertNull(field(client, "outgoing")); assertTrue((field(client, "queue") as ArrayDeque<*>).isEmpty()) } finally { client.close() }
    }
    @Test fun requestQueuesOnlyMetadataUntilRemoteConsent() {
        val (repo, client) = setup(); paired(client)
        try {
            invoke(client, "sendProfileNow", Profile::class.java, repo.current.active())
            val queue = field(client, "queue") as ArrayDeque<*>
            val request = (queue.first as Wire.Sender).message
            assertEquals("REQUEST", request["type"].asString); assertFalse(request.has("profile")); assertEquals(1, queue.size)
            invoke(client, "deliver", JsonObject::class.java, SyncStore.message("RESPONSE").apply { addProperty("requestId", request["requestId"].asString); addProperty("accepted", true) })
            assertEquals("OFFER", (queue.last as Wire.Sender).message["type"].asString)
            assertNotNull((queue.last as Wire.Sender).message["profile"])
        } finally { client.close() }
    }
    @Test fun rejectedConsentCannotQueueBody() {
        val (repo, client) = setup(); paired(client)
        try { invoke(client, "sendProfileNow", Profile::class.java, repo.current.active()); val queue = field(client, "queue") as ArrayDeque<*>; val request = (queue.first as Wire.Sender).message
            invoke(client, "deliver", JsonObject::class.java, SyncStore.message("RESPONSE").apply { addProperty("requestId", request["requestId"].asString); addProperty("accepted", false) })
            assertEquals(1, queue.size); assertNull(field(client, "outgoing")); assertTrue(client.view.value.status.contains("拒绝"))
        } finally { client.close() }
    }
    @Test fun unapprovedBodyCannotChangeLiveTimetable() {
        val (repo, client) = setup(); paired(client); val before = repo.current
        try { val store = SyncStore(ApplicationProvider.getApplicationContext(), repo); val body = store.offer(before.active().copy(name = "合成新版本")); body.addProperty("requestId", "unapproved")
            assertThrows(java.lang.reflect.InvocationTargetException::class.java) { invoke(client, "deliver", JsonObject::class.java, body) }; assertEquals(before, repo.current)
        } finally { client.close() }
    }
}
