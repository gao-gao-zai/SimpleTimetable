@file:Suppress("DEPRECATION")
package io.github.gaogaoza.simpletimetable.sync

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import com.google.gson.*
import io.github.gaogaoza.simpletimetable.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.ArrayDeque

@SuppressLint("MissingPermission")
class WatchClient(private val context: Context, private val repository: Repository) {
    data class Device(val address: String, val name: String, val source: String = "现场扫描", val connectable: Boolean = true, val service: Boolean = false, val type: Int = 0)
    data class PeerProfile(val id: String, val name: String, val revision: String)
    data class View(val status: String = "在手表开启同步后扫描", val scanning: Boolean = false, val paired: Boolean = false, val devices: List<Device> = emptyList(), val profiles: List<PeerProfile> = emptyList(), val pending: String? = null, val pendingNonce: String = "", val suggestion: String = "", val progress: Int = 0, val request: String? = null, val diagnostics: String = "", val connecting: Boolean = false)
    private val thread = HandlerThread("watch-sync").apply { start() }
    private val handler = Handler(thread.looper)
    private val store by lazy { SyncStore(context, repository) }
    private val mutable = MutableStateFlow(View())
    val view = mutable.asStateFlow()
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var code = ""
    private val liveDevices = mutableMapOf<String, BluetoothDevice>()
    private var refreshBeforeDiscovery = false
    private var mtu = 23
    private var options = Wire.Options()
    private var outgoing: JsonObject? = null
    private var incoming: JsonObject? = null
    private var allowedIncoming = false
    private var consentExpires = 0L
    private var phase = "未连接"
    private var receivedMessageBytes = 0
    private var notifications = 0; private var queued = 0; private var submitted = 0; private var written = 0
    private var lastProgressAt = 0L
    private fun diagnostic() = "阶段：$phase；MTU $mtu / payload ${options.chunk} / LZ4 ${options.lz4}；握手排队 $queued / GATT提交 $submitted / 写完成 $written / 通知 $notifications"
    private fun clearConsent() { outgoing = null; incoming = null; allowedIncoming = false; consentExpires = 0; change { it.copy(request = null) } }

    private var closed = false
    private var scanning = false
    private var subscribed = false
    private val rawQueue = ArrayDeque<ByteArray>()
    private var writing = false
    private val queue = ArrayDeque<Wire.Sender>()
    private var current: Wire.Sender? = null
    private var sentAt = 0L
    private val receivers = mutableMapOf<Int, Wire.Receiver>()
    private var timeoutGeneration = 0
    private fun change(f: (View) -> View) { mutable.value = f(mutable.value) }
    private fun report(s: String) { phase = s; change { it.copy(status = s, diagnostics = diagnostic()) } }
    private fun action(block: () -> Unit) { if (!closed) handler.post { try { block() } catch (e: Exception) { change { it.copy(connecting = false) }; report(if (e is JsonParseException) "同步数据格式无效，原数据已保留" else e.message ?: "同步失败") } } }
    fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    fun permitted() = permissions().all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    private val scan = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) { action {
            val device = result.device; liveDevices[device.address] = device
            val hasService = result.scanRecord?.serviceUuids?.any { it.uuid.toString() == Wire.SERVICE } == true
            val row = Device(device.address, device.name?.takeIf { it.isNotBlank() } ?: "附近蓝牙设备", connectable = if (Build.VERSION.SDK_INT >= 26) result.isConnectable else true, service = hasService, type = device.type)
            change { it.copy(devices = (it.devices.filterNot { d -> d.address == row.address } + row).sortedByDescending { d -> (if (d.service) 2 else 0) + (if (d.name.contains("WATCH", true) || d.name.contains("GT2", true)) 1 else 0) }.take(50)) }
        } }
        override fun onScanFailed(errorCode: Int) { action { scanning = false; change { it.copy(scanning = false, status = "扫描失败 $errorCode") } } }
    }
    fun scan() = action {
        check(permitted()) { "请先授权附近设备（旧安卓需位置权限）" }
        check(adapter?.isEnabled == true) { "请在系统中打开蓝牙" }
        stopScan(); scanning = true; change { it.copy(scanning = true, devices = emptyList(), status = "正在扫描简单课表服务") }
        requireNotNull(adapter?.bluetoothLeScanner).startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scan)
        handler.postDelayed({ if (!closed && scanning) { stopScan(); report("扫描结束；未发现时请检查手表同步是否开启") } }, 12000)
    }
    fun pairedDevices() = action {
        check(permitted()) { "请授权附近设备" }
        val rows = requireNotNull(adapter).bondedDevices.map { d -> liveDevices[d.address] = d; Device(d.address, d.name ?: "已配对设备", "仅配对记录", type = d.type) }
        change { it.copy(devices = (it.devices.filterNot { d -> d.source == "仅配对记录" } + rows.filterNot { d -> it.devices.any { live -> live.address == d.address } }).take(50)) }
        report("配对记录不能证明设备正在广播，请优先选择现场扫描结果")
    }
    private fun stopScan() { if (scanning && permitted()) adapter?.bluetoothLeScanner?.stopScan(scan); scanning = false; change { it.copy(scanning = false) } }
    fun connect(address: String, enteredCode: String, refreshCache: Boolean = false) = action {
        check(permitted()) { "请授权附近设备" }; require(enteredCode.matches(Regex("[0-9]{6}"))) { "请输入手表的六位连接码" }
        stopScan(); disconnectNow(); code = enteredCode; refreshBeforeDiscovery = refreshCache; report("连接中")
        val candidate = mutable.value.devices.firstOrNull { it.address == address }
        check(candidate?.connectable != false) { "该现场广播不可连接" }
        change { it.copy(connecting = true) }
        val device = liveDevices[address] ?: error("请选择本次扫描或配对列表中的设备")
        context.startForegroundService(Intent(context, SyncForegroundService::class.java))
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        val generation = ++timeoutGeneration
        handler.postDelayed({ if (!closed && generation == timeoutGeneration && !mutable.value.paired) { val last = phase; disconnectNow(); report("连接已超时；最后阶段：$last") } }, 30000)
    }
    private fun connected(g: BluetoothGatt) {
        fun discover() { if (g !== gatt) return; report("GATT已连接，协商MTU"); if (!g.requestMtu(113)) { report("发现服务中（默认MTU）"); check(g.discoverServices()) { "服务发现失败" } } }
        if (!refreshBeforeDiscovery) { discover(); return }
        refreshBeforeDiscovery = false
        val accepted = runCatching { g.javaClass.getMethod("refresh").invoke(g) as Boolean }.getOrNull()
        if (accepted != true) { disconnectNow(); report("缓存刷新请求失败或被拒绝；未进入握手"); return }
        report("缓存刷新请求已接受，等待后重新发现；接受不证明缓存已清除")
        handler.postDelayed({ if (!closed && g === gatt) runCatching { discover() }.onFailure { report("刷新后服务发现未启动") } }, 1000)
    }
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) { action {
            if (g !== gatt) return@action
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) { connected(g) }
            else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) { disconnectNow(); report("连接断开 ($status/$newState)；最后阶段保留，重新连接后需重新请求接收") }
        } }
        override fun onMtuChanged(g: BluetoothGatt, value: Int, status: Int) { action {
            if (g !== gatt) return@action
            mtu = if (status == BluetoothGatt.GATT_SUCCESS) value else 23
            report("发现服务中"); check(g.discoverServices()) { "服务发现失败" }
        } }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { action {
            if (g !== gatt) return@action
            check(status == BluetoothGatt.GATT_SUCCESS) { "服务发现失败 $status" }
            val service = requireNotNull(g.getService(UUID.fromString(Wire.SERVICE))) { "没有简单课表服务" }
            rx = requireNotNull(service.getCharacteristic(UUID.fromString(Wire.RX)))
            val tx = requireNotNull(service.getCharacteristic(UUID.fromString(Wire.TX)))
            check(g.setCharacteristicNotification(tx, true)) { "无法订阅通知" }
            val cccd = requireNotNull(tx.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")))
            val ok = if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS else { cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(cccd) }
            check(ok) { "通知订阅写入失败" }
        } }
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) { action {
            if (g !== gatt) return@action
            check(status == BluetoothGatt.GATT_SUCCESS) { "通知订阅失败 $status" }; subscribed = true
            queued++
            enqueue(SyncStore.message("HELLO").apply { addProperty("code", code); add("transport", JsonObject().apply { addProperty("chunk", if (mtu >= 113) 100 else 10); addProperty("lz4", true); addProperty("consent", 1) }) }, Wire.Options()); report("验证连接码中")
        } }
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) { action {
            if (g !== gatt) return@action
            writing = false; written++
            if (status != BluetoothGatt.GATT_SUCCESS) { disconnectNow(); report("GATT 写入失败 $status，请重连"); return@action }
            writeNext()
        } }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) { if (Build.VERSION.SDK_INT < 33) received(g, characteristic, characteristic.value?.clone() ?: byteArrayOf()) }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) { received(g, characteristic, value.clone()) }
    }
    private fun received(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, bytes: ByteArray) = action {
        if (g !== gatt || characteristic.uuid != UUID.fromString(Wire.TX)) return@action
        notifications++
        val f = Wire.decode(bytes)
        if (f.op == Wire.ACK) { if (current?.ack(bytes) == true) pump(); return@action }
        if (f.id !in receivers && receivers.size >= 6) receivers.remove(receivers.keys.first())
        val receiver = receivers.getOrPut(f.id) { Wire.Receiver(null, {}, ::deliver, memory = true, decodedSize = { receivedMessageBytes = it }) }
        sendRaw(receiver.receive(bytes))
    }
    private fun sendRaw(bytes: ByteArray) {
        check(subscribed && gatt != null); check(rawQueue.size < 64) { "发送队列拥堵，请重连" }
        rawQueue.add(bytes); writeNext()
    }
    private fun writeNext() {
        if (writing || rawQueue.isEmpty() || !subscribed) return
        val g = requireNotNull(gatt); val characteristic = requireNotNull(rx); val bytes = rawQueue.removeFirst()
        writing = true; submitted++
        val ok = if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS else { characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; characteristic.value = bytes; g.writeCharacteristic(characteristic) }
        if (!ok) { writing = false; disconnectNow(); error("GATT 写入未启动，请重新连接") }
    }
    private fun enqueue(m: JsonObject, transferOptions: Wire.Options = options) { val n = (Wire.crc32(m["nonce"].asString.toByteArray()) % 65535).toInt() + 1; queue.add(Wire.Sender(n, m, transferOptions)); pump() }
    private fun pump() {
        if (!subscribed) return
        if (current == null) current = queue.pollFirst()
        val s = current ?: return
        val packet = s.packet()
        if (packet == null) { current = null; pump(); return }
        val now = SystemClock.elapsedRealtime()
        if (now - lastProgressAt >= 250 || s.cursor == s.end) {
            lastProgressAt = now
            change { it.copy(progress = (s.cursor * 100 / (s.end + 1)).coerceIn(0, 100), diagnostics = "${diagnostic()}；正文 ${s.rawSize} → ${s.bytes.size} B / payload ${s.chunk} / codec ${s.codec}") }
        }
        if (s.cursor == s.end && mutable.value.paired) report("分包收齐，等待手表校验并生成预览；尚未保存")
        if (s.waiting) return
        s.waiting = true; sentAt = SystemClock.elapsedRealtime(); sendRaw(packet)
    }
    private val timer = object : Runnable {
        override fun run() {
            if (closed) return
            try {
                if (consentExpires > 0 && SystemClock.elapsedRealtime() > consentExpires && (outgoing != null && current == null || incoming != null && !allowedIncoming)) { clearConsent(); report("接收确认超时，请重新请求") }
                val s = current
                if (subscribed && s?.waiting == true && SystemClock.elapsedRealtime() - sentAt >= (if (s.cursor == s.end) 60000 else 2000)) {
                    if (s.fallback()) { report("大包确认超时，回退10 B并保留压缩"); pump() }
                    else if (++s.retries > 5) { disconnectNow(); report("确认超时，已保留待发送档案；请重连") }
                    else { s.waiting = false; pump() }
                }
            } catch (e: Exception) { report(e.message ?: "蓝牙失败") }
            handler.postDelayed(this, 250)
        }
    }
    init { handler.post(timer) }
    private fun catalog() = enqueue(SyncStore.message("CATALOG").apply { add("profiles", JsonArray().apply { repository.current.profiles.forEach { p -> add(JsonObject().apply { addProperty("id", p.id); addProperty("name", p.name); addProperty("revision", Wire.revision(store.export(p))) }) } }) })
    private fun deliver(m: JsonObject) {
        require(m["protocol"]?.asInt == 1 && (m["nonce"]?.asString?.length ?: 0) in 1..100)
        if (m["type"].asString == "READY") {
            val caps = m["transport"]?.asJsonObject
            require(caps?.get("consent")?.asInt == 1) { "手表版本过旧，请安装本次配套恢复版本" }
            options = Wire.Options(chunk = if (mtu >= 113 && caps?.get("chunk")?.asInt == 100) 100 else 10, lz4 = caps?.get("lz4")?.asBoolean == true, extended = caps != null)
            change { it.copy(paired = true, connecting = false) }; code = ""; ++timeoutGeneration
            report("连接码已验证；请选择档案并等待对端同意接收"); return
        }
        if (m["type"].asString == "HELLO_ERROR") { report(if (m["reason"]?.asString == "UPGRADE_REQUIRED") "对端版本过旧，请使用配套恢复版本" else "验证码不匹配，请重新连接并输入新码"); return }
        check(mutable.value.paired) { "连接码尚未验证" }
        when (m["type"].asString) {
            "LIST" -> catalog()
            "REQUEST" -> {
                require(incoming == null && outgoing == null) { "当前已有同步，请先取消" }
                require(!m.has("profile") && m["bytes"].asInt in 1..Wire.MAX && m["requestId"].asString.isNotBlank())
                incoming = m.deepCopy(); allowedIncoming = false; consentExpires = SystemClock.elapsedRealtime() + 60000
                change { it.copy(request = "${m["name"].asString} · ${m["bytes"].asInt} B") }; report("手表请求发送完整档案，请在手机同意接收")
            }
            "RESPONSE" -> {
                val body = requireNotNull(outgoing) { "接收确认已失效" }; require(m["requestId"].asString == body["requestId"].asString)
                require(SystemClock.elapsedRealtime() <= consentExpires) { "接收确认已超时" }
                if (m["accepted"].asBoolean) { consentExpires = 0; enqueue(body); report("正文传输中；随后等待手表生成预览和保存") }
                else { clearConsent(); report("手表已拒绝接收") }
            }
            "CANCEL" -> { val requestId = outgoing?.get("requestId") ?: incoming?.get("requestId"); if (requestId == null || m["requestId"] != requestId) return; clearConsent(); queue.clear(); current = null; receivers.clear(); report("手表已取消本次同步") }
            "CATALOG" -> { val profiles = m["profiles"].asJsonArray.take(100).map { it.asJsonObject.let { p -> PeerProfile(p["id"].asString, p["name"].asString, p["revision"].asString) } }; change { it.copy(profiles = profiles) } }
            "PULL" -> {
                val p = repository.current.profiles.firstOrNull { it.id == m["profileId"].asString } ?: error("档案不存在")
                sendProfileNow(p)
            }
            "OFFER" -> {
                val request = requireNotNull(incoming) { "没有同意接收此档案" }
                require(allowedIncoming && m["requestId"] == request["requestId"] && m["profile"].asJsonObject["id"] == request["profileId"] && m["revision"] == request["revision"])
                require(receivedMessageBytes == request["bytes"].asInt)
                report("校验并生成接收预览")
                val receipt = store.meta["receipts"].asJsonArray.firstOrNull { it.asJsonObject["offerNonce"].asString == m["nonce"].asString }
                if (receipt != null) enqueue(receipt.asJsonObject)
                else { store.stage(m); enqueue(SyncStore.message("STAGED").apply { addProperty("requestId", m["requestId"].asString); addProperty("revision", m["revision"].asString) }); clearConsent(); change { it.copy(pending = store.summary(), pendingNonce = m["nonce"].asString, suggestion = store.suggestion(), status = "已接收，请预览确认保存") } }
            }
            "STAGED" -> {
                val body = requireNotNull(outgoing); require(m["requestId"] == body["requestId"] && m["revision"] == body["revision"])
                report("手表已生成预览；等待手表选择保存方式，尚未完成")
            }
            "SAVED" -> {
                val out = outgoing ?: error("没有待确认发送")
                require(m["offerNonce"].asString == out["nonce"].asString && m["revision"].asString == out["revision"].asString)
                store.update { d -> if (m["choice"].asString == "replace") d["common"].asJsonObject.addProperty(m["profileId"].asString, m["revision"].asString); d.remove("outbox") }
                clearConsent(); report(if (m["choice"].asString == "keep") "手表保留了本机版本" else "手表已确认落盘保存")
            }
            else -> error("未知同步消息")
        }
    }
    private fun sendProfileNow(p: Profile) {
        check(mutable.value.paired) { "请先连接手表" }; check(incoming == null && outgoing == null) { "请先完成或取消当前同步" }
        val body = store.offer(p).apply { addProperty("requestId", get("nonce").asString) }
        val size = GsonBuilder().disableHtmlEscaping().create().toJson(body).toByteArray(Charsets.UTF_8).size
        require(size <= Wire.MAX) { "完整档案超过 64 KiB，未发送" }
        outgoing = body; consentExpires = SystemClock.elapsedRealtime() + 60000
        enqueue(SyncStore.message("REQUEST").apply {
            addProperty("requestId", body["requestId"].asString); addProperty("profileId", p.id); addProperty("name", p.name); addProperty("revision", body["revision"].asString); addProperty("bytes", size)
            add("counts", JsonObject().apply { addProperty("subjects", p.subjects.size); addProperty("layouts", p.layouts.size); addProperty("plans", p.plans.size); addProperty("temporary", p.temporary.size) })
        }); report("等待手表确认接收；尚未发送档案正文")
    }
    fun requestCatalog() = action { check(mutable.value.paired); enqueue(SyncStore.message("LIST")); report("已请求手表档案列表") }
    fun respond(accepted: Boolean) = action {
        val request = requireNotNull(incoming); require(SystemClock.elapsedRealtime() <= consentExpires) { "接收确认已超时" }
        allowedIncoming = accepted
        enqueue(SyncStore.message("RESPONSE").apply { addProperty("requestId", request["requestId"].asString); addProperty("accepted", accepted) })
        if (!accepted) clearConsent()
        change { it.copy(request = null) }; report(if (accepted) "已同意接收，等待手表发送正文" else "已拒绝接收")
    }
    fun cancelTransfer() = action {
        val requestId = outgoing?.get("requestId")?.asString ?: incoming?.get("requestId")?.asString
        clearConsent(); queue.clear(); current = null; receivers.clear();
        if (mutable.value.paired && requestId != null) enqueue(SyncStore.message("CANCEL").apply { addProperty("requestId", requestId) }); report("已取消本次同步")
    }
    fun sendProfile(id: String) = action { sendProfileNow(repository.current.profiles.first { it.id == id }) }
    fun pull(id: String) = action { check(mutable.value.paired); enqueue(SyncStore.message("PULL").apply { addProperty("profileId", id) }); report("等待手表发送档案") }
    fun accept(choice: String, expectedNonce: String) = action { val receipt = store.accept(choice, expectedNonce); if (mutable.value.paired) enqueue(receipt); change { it.copy(pending = null, pendingNonce = "", suggestion = "", status = "选择已保存；落盘后才发送回执") } }
    fun disconnect() = action { disconnectNow(); report("同步已结束") }
    private fun disconnectNow() {
        context.stopService(Intent(context, SyncForegroundService::class.java))
        stopScan(); ++timeoutGeneration; subscribed = false; code = ""; mtu = 23; options = Wire.Options(); clearConsent(); writing = false; rx = null
        rawQueue.clear(); queue.clear(); current = null; receivers.clear()
        gatt?.let { runCatching { it.disconnect() }; it.close() }; gatt = null
        change { it.copy(paired = false, connecting = false, profiles = emptyList(), progress = 0) }
    }
    fun close() { handler.post { runCatching { disconnectNow() }; closed = true; handler.removeCallbacksAndMessages(null); thread.quitSafely() } }
}
