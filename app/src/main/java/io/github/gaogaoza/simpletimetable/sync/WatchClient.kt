@file:Suppress("DEPRECATION")
package io.github.gaogaoza.simpletimetable.sync

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
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
    data class Device(val address: String, val name: String)
    data class PeerProfile(val id: String, val name: String, val revision: String)
    data class View(val status: String = "在手表开启同步后扫描", val scanning: Boolean = false, val paired: Boolean = false, val devices: List<Device> = emptyList(), val profiles: List<PeerProfile> = emptyList(), val pending: String? = null, val suggestion: String = "", val progress: Int = 0)
    private val thread = HandlerThread("watch-sync").apply { start() }
    private val handler = Handler(thread.looper)
    private val store = SyncStore(context, repository)
    private val mutable = MutableStateFlow(View(pending = store.summary(), suggestion = store.suggestion()))
    val view = mutable.asStateFlow()
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var code = ""
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
    private fun report(s: String) { change { it.copy(status = s) } }
    private fun action(block: () -> Unit) { if (!closed) handler.post { try { block() } catch (e: Exception) { report(e.message ?: "同步失败") } } }
    fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    fun permitted() = permissions().all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    private val scan = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) { action {
            val device = result.device; val row = Device(device.address, device.name?.takeIf { it.isNotBlank() } ?: "BlueOS 手表")
            change { it.copy(devices = (it.devices.filterNot { d -> d.address == row.address } + row).take(30)) }
        } }
        override fun onScanFailed(errorCode: Int) { action { scanning = false; change { it.copy(scanning = false, status = "扫描失败 $errorCode") } } }
    }
    fun scan() = action {
        check(permitted()) { "请先授权附近设备（旧安卓需位置权限）" }
        check(adapter?.isEnabled == true) { "请在系统中打开蓝牙" }
        stopScan(); scanning = true; change { it.copy(scanning = true, devices = emptyList(), status = "正在扫描简单课表服务") }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID.fromString(Wire.SERVICE))).build()
        requireNotNull(adapter?.bluetoothLeScanner).startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scan)
        handler.postDelayed({ if (!closed && scanning) { stopScan(); report("扫描结束；未发现时请检查手表同步是否开启") } }, 12000)
    }
    private fun stopScan() { if (scanning && permitted()) adapter?.bluetoothLeScanner?.stopScan(scan); scanning = false; change { it.copy(scanning = false) } }
    fun connect(address: String, enteredCode: String) = action {
        check(permitted()) { "请授权附近设备" }; require(enteredCode.matches(Regex("[0-9]{6}"))) { "请输入手表的六位连接码" }
        stopScan(); disconnectNow(); code = enteredCode; report("连接中")
        gatt = requireNotNull(adapter).getRemoteDevice(address).connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        val generation = ++timeoutGeneration
        handler.postDelayed({ if (!closed && generation == timeoutGeneration && !mutable.value.paired) { disconnectNow(); report("连接超时，请重新开启手表同步后连接") } }, 30000)
    }
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) { action {
            if (g !== gatt) return@action
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) { report("发现服务中"); check(g.discoverServices()) { "服务发现失败" } }
            else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) { disconnectNow(); report("连接断开 ($status)，分包已保留；重新连接恢复") }
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
            enqueue(SyncStore.message("HELLO").apply { addProperty("code", code) }); report("验证连接码中")
        } }
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) { action {
            if (g !== gatt) return@action
            writing = false
            if (status != BluetoothGatt.GATT_SUCCESS) { disconnectNow(); report("GATT 写入失败 $status，请重连"); return@action }
            writeNext()
        } }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) { if (Build.VERSION.SDK_INT < 33) received(g, characteristic, characteristic.value?.clone() ?: byteArrayOf()) }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) { received(g, characteristic, value.clone()) }
    }
    private fun received(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, bytes: ByteArray) = action {
        if (g !== gatt || characteristic.uuid != UUID.fromString(Wire.TX)) return@action
        val f = Wire.decode(bytes)
        if (f.op == Wire.ACK) { if (current?.ack(bytes) == true) pump(); return@action }
        val receiver = receivers.getOrPut(f.id) { Wire.Receiver(store.loadTransfer(f.id), { store.saveTransfer(f.id, it) }, ::deliver) }
        sendRaw(receiver.receive(bytes))
    }
    private fun sendRaw(bytes: ByteArray) {
        check(subscribed && gatt != null); check(rawQueue.size < 64) { "发送队列拥堵，请重连" }
        rawQueue.add(bytes); writeNext()
    }
    private fun writeNext() {
        if (writing || rawQueue.isEmpty() || !subscribed) return
        val g = requireNotNull(gatt); val characteristic = requireNotNull(rx); val bytes = rawQueue.removeFirst()
        writing = true
        val ok = if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS else { characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; characteristic.value = bytes; g.writeCharacteristic(characteristic) }
        if (!ok) { writing = false; disconnectNow(); error("GATT 写入未启动，请重新连接") }
    }
    private fun enqueue(m: JsonObject) { val n = (Wire.crc32(m["nonce"].asString.toByteArray()) % 65535).toInt() + 1; queue.add(Wire.Sender(n, m)); pump() }
    private fun pump() {
        if (!subscribed) return
        if (current == null) current = queue.pollFirst()
        val s = current ?: return
        val packet = s.packet()
        if (packet == null) { current = null; pump(); return }
        change { it.copy(progress = (s.cursor * 100 / (s.end + 1)).coerceIn(0, 100)) }
        if (s.waiting) return
        s.waiting = true; sentAt = SystemClock.elapsedRealtime(); sendRaw(packet)
    }
    private val timer = object : Runnable {
        override fun run() {
            if (closed) return
            try {
                val s = current
                if (subscribed && s?.waiting == true && SystemClock.elapsedRealtime() - sentAt >= 2000) {
                    if (++s.retries > 5) { disconnectNow(); report("确认超时，已保留待发送档案；请重连") }
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
            change { it.copy(paired = true, status = "已连接，请选择方向与档案") }; code = ""; ++timeoutGeneration; catalog()
            store.meta["outbox"]?.asJsonObject?.let(::enqueue); return
        }
        check(mutable.value.paired) { "连接码尚未验证" }
        when (m["type"].asString) {
            "CATALOG" -> { val profiles = m["profiles"].asJsonArray.take(100).map { it.asJsonObject.let { p -> PeerProfile(p["id"].asString, p["name"].asString, p["revision"].asString) } }; change { it.copy(profiles = profiles) } }
            "PULL" -> {
                val p = repository.current.profiles.firstOrNull { it.id == m["profileId"].asString } ?: error("档案不存在")
                sendProfileNow(p)
            }
            "OFFER" -> {
                val receipt = store.meta["receipts"].asJsonArray.firstOrNull { it.asJsonObject["offerNonce"].asString == m["nonce"].asString }
                if (receipt != null) enqueue(receipt.asJsonObject)
                else { store.stage(m); change { it.copy(pending = store.summary(), suggestion = store.suggestion(), status = "已接收，请预览确认保存") } }
            }
            "SAVED" -> {
                val out = store.meta["outbox"]?.asJsonObject ?: error("没有待确认发送")
                require(m["offerNonce"].asString == out["nonce"].asString && m["revision"].asString == out["revision"].asString)
                store.update { d -> if (m["choice"].asString == "replace") d["common"].asJsonObject.addProperty(m["profileId"].asString, m["revision"].asString); d.remove("outbox") }
                report(if (m["choice"].asString == "keep") "手表保留了本机版本" else "手表已确认落盘保存")
            }
            else -> error("未知同步消息")
        }
    }
    private fun sendProfileNow(p: Profile) { check(mutable.value.paired) { "请先连接手表" }; val m = store.offer(p); store.update { it.add("outbox", m) }; enqueue(m); report("发送中；等待手表预览确认保存") }
    fun sendProfile(id: String) = action { sendProfileNow(repository.current.profiles.first { it.id == id }) }
    fun pull(id: String) = action { check(mutable.value.paired); enqueue(SyncStore.message("PULL").apply { addProperty("profileId", id) }); report("等待手表发送档案") }
    fun accept(choice: String) = action { val receipt = store.accept(choice); if (mutable.value.paired) enqueue(receipt); change { it.copy(pending = null, suggestion = "", status = "选择已保存；落盘后才发送回执") } }
    fun disconnect() = action { disconnectNow(); report("同步已结束") }
    private fun disconnectNow() {
        stopScan(); ++timeoutGeneration; subscribed = false; code = ""; writing = false; rx = null
        rawQueue.clear(); queue.clear(); current = null; receivers.clear()
        gatt?.let { runCatching { it.disconnect() }; it.close() }; gatt = null
        change { it.copy(paired = false, profiles = emptyList(), progress = 0) }
    }
    fun close() { handler.post { runCatching { disconnectNow() }; closed = true; handler.removeCallbacksAndMessages(null); thread.quitSafely() } }
}
