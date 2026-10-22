package io.github.gaogaoza.simpletimetable.sync

import android.content.Context
import android.util.AtomicFile
import com.google.gson.*
import io.github.gaogaoza.simpletimetable.data.*
import java.io.File

class SyncStore(context: Context, private val repository: Repository) {
    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private val file = AtomicFile(File(context.filesDir, "watch-sync.json"))
    private val journal = AtomicFile(File(context.filesDir, "watch-transfer.json"))
    var meta: JsonObject = read(file) ?: JsonObject().apply { add("common", JsonObject()); add("original", JsonObject()); add("receipts", JsonArray()) }
        private set
    private var transfers: JsonObject = read(journal) ?: JsonObject()
    private fun read(file: AtomicFile): JsonObject? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return file.openRead().bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
    }
    private fun write(file: AtomicFile, value: JsonObject) {
        val out = file.startWrite()
        try { out.write(gson.toJson(value).toByteArray(Charsets.UTF_8)); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
    }
    fun update(change: (JsonObject) -> Unit) { val next = meta.deepCopy(); change(next); write(file, next); meta = next }
    fun loadTransfer(id: Int): JsonObject? = transfers[id.toString()]?.asJsonObject
    fun saveTransfer(id: Int, value: JsonObject) {
        val next = transfers.deepCopy(); next.add(id.toString(), value)
        while (next.size() > 6) next.remove(next.entrySet().firstOrNull { it.key != id.toString() && it.value.asJsonObject["complete"].asBoolean }?.key ?: next.keySet().first { it != id.toString() })
        write(journal, next); transfers = next
    }
    // Preserve future profile metadata without transferring device settings.
    private fun merge(old: JsonElement?, current: JsonElement): JsonElement {
        if (current.isJsonObject) { val result = if (old?.isJsonObject == true) old.asJsonObject.deepCopy() else JsonObject(); current.asJsonObject.entrySet().forEach { (k, v) -> result.add(k, merge(result[k], v)) }; return result }
        if (current.isJsonArray) { val result = JsonArray(); current.asJsonArray.forEach { item -> val prev = if (item.isJsonObject && item.asJsonObject.has("id") && old?.isJsonArray == true) old.asJsonArray.firstOrNull { it.isJsonObject && it.asJsonObject["id"] == item.asJsonObject["id"] } else null; result.add(merge(prev, item)) }; return result }
        return current.deepCopy()
    }
    fun export(profile: Profile): JsonObject = merge(meta["original"].asJsonObject[profile.id], gson.toJsonTree(profile)).asJsonObject
    fun offer(profile: Profile): JsonObject = message("OFFER").apply { val raw = export(profile); add("profile", raw); addProperty("revision", Wire.revision(raw)) }
    fun stage(m: JsonObject) {
        require(m["protocol"].asInt == 1 && m["nonce"].asString.length in 1..100 && m["type"].asString == "OFFER")
        val raw = m["profile"].asJsonObject; val p = gson.fromJson(raw, Profile::class.java)
        Repository.validate(AppData(profiles = listOf(p), activeProfile = p.id))
        require(Wire.revision(raw) == m["revision"].asString) { "档案版本校验失败" }
        val local = repository.current.profiles.firstOrNull { it.id == p.id }
        update { d -> d.add("pending", JsonObject().apply { add("message", m); if (local != null) addProperty("localRevision", Wire.revision(export(local))) }) }
    }
    fun summary(): String? = meta["pending"]?.asJsonObject?.get("message")?.asJsonObject?.get("profile")?.asJsonObject?.let { p -> "${p["name"].asString} · ${p["subjects"].asJsonArray.size()} 科目 / ${p["layouts"].asJsonArray.size()} 作息 / ${p["plans"].asJsonArray.size()} 课表 / ${p["temporary"].asJsonArray.size()} 临时安排" }
    fun suggestion(): String {
        val pending = meta["pending"]?.asJsonObject ?: return ""
        val m = pending["message"].asJsonObject; val p = m["profile"].asJsonObject; val local = repository.current.profiles.firstOrNull { it.id == p["id"].asString }
        return if (local == null) "receive" else Wire.direction(Wire.revision(export(local)), m["revision"].asString, meta["common"].asJsonObject[p["id"].asString]?.asString)
    }
    fun accept(choice: String): JsonObject {
        require(choice in listOf("replace", "copy", "keep"))
        val pending = requireNotNull(meta["pending"]?.asJsonObject); val m = pending["message"].asJsonObject
        val raw = m["profile"].asJsonObject; val p = gson.fromJson(raw, Profile::class.java)
        val local = repository.current.profiles.firstOrNull { it.id == p.id }
        require(local == null || Wire.revision(export(local)) == pending["localRevision"]?.asString) { "预览后手机版已修改，请重新接收预览" }
        // Deterministic copy ID prevents duplicates after crash between live save and receipt.
        val copyId = "received-" + Wire.revision(JsonPrimitive(m["nonce"].asString))
        val saved = if (choice == "copy") p.copy(id = copyId, name = p.name + " 接收副本") else p
        if (choice != "keep") repository.update { d -> d.copy(profiles = if (choice == "copy") d.profiles.filterNot { it.id == copyId } + saved else if (local != null) d.profiles.map { if (it.id == p.id) p else it } else d.profiles + p) }
        val receipt = message("SAVED").apply { addProperty("offerNonce", m["nonce"].asString); addProperty("profileId", p.id); addProperty("revision", m["revision"].asString); addProperty("choice", choice) }
        update { d ->
            if (choice == "replace") d["common"].asJsonObject.addProperty(p.id, m["revision"].asString)
            if (choice != "keep") d["original"].asJsonObject.add(saved.id, raw)
            val list = d["receipts"].asJsonArray; list.add(receipt); while (list.size() > 32) list.remove(0)
            d.remove("pending")
        }
        return receipt
    }
    companion object {
        fun message(type: String): JsonObject = JsonObject().apply { addProperty("type", type); addProperty("protocol", 1); addProperty("nonce", newId()) }
    }
}
