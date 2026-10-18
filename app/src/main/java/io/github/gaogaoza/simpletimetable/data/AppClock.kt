package io.github.gaogaoza.simpletimetable.data

import android.content.Context
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class AppClock(private val context: Context, private val repository: Repository) {
    private val executor = Executors.newSingleThreadExecutor()
    private val syncing = AtomicBoolean(false)
    private fun bootCount() = runCatching { AndroidSettings.Global.getInt(context.contentResolver, AndroidSettings.Global.BOOT_COUNT) }.getOrDefault(-2)
    fun reliable(s: Settings) = s.syncEpoch > 0 && s.syncBootCount == bootCount() && s.syncElapsed <= SystemClock.elapsedRealtime()
    fun now(s: Settings = repository.current.settings): Long {
        val base = if (s.softwareClock && reliable(s)) s.syncEpoch + SystemClock.elapsedRealtime() - s.syncElapsed else System.currentTimeMillis()
        return base + s.schoolOffset * 1000L
    }
    fun status(s: Settings = repository.current.settings): String = when {
        !s.softwareClock -> "使用系统时间"
        s.syncEpoch == 0L -> "尚未校时，暂用系统时间"
        !reliable(s) -> "设备已重启，暂用系统时间，请重新校时"
        else -> s.syncStatus
    }
    fun autoSync() { val s = repository.current.settings; if (s.softwareClock && s.autoSync && (!reliable(s) || SystemClock.elapsedRealtime() - s.syncElapsed > 6 * 3600000)) sync() }
    fun sync(callback: (Result<Long>) -> Unit = {}) {
        if (!syncing.compareAndSet(false, true)) return
        executor.execute {
            val result = runCatching {
                val host = repository.current.settings.ntpHost.trim()
                require(host.isNotEmpty() && host.length <= 253 && !host.contains('/')) { "NTP 地址应为主机名" }
                DatagramSocket().use { socket ->
                    socket.soTimeout = 4000
                    socket.connect(InetAddress.getByName(host), 123)
                    val request = ByteArray(48); request[0] = 0x23
                    val client = System.currentTimeMillis()
                    writeTimestamp(request, 40, client)
                    val begin = SystemClock.elapsedRealtime()
                    socket.send(DatagramPacket(request, request.size))
                    val answer = ByteArray(512); val packet = DatagramPacket(answer, answer.size)
                    socket.receive(packet)
                    val end = SystemClock.elapsedRealtime()
                    require(packet.length >= 48 && (answer[0].toInt() and 7) in 4..5 && (answer[0].toInt().ushr(6) and 3) != 3 && (answer[1].toInt() and 255) in 1..15) { "NTP 响应无效" }
                    require(request.copyOfRange(40, 48).contentEquals(answer.copyOfRange(24, 32))) { "NTP 响应不匹配本次请求" }
                    require(answer.copyOfRange(32, 48).any { it != 0.toByte() }) { "NTP 时间戳为空" }
                    val receiveEpoch = readTimestamp(answer, 32); val transmitEpoch = readTimestamp(answer, 40)
                    val processing = transmitEpoch - receiveEpoch
                    require(processing in 0..(end - begin + 1000)) { "NTP 服务端时间戳无效" }
                    val epoch = transmitEpoch + ((end - begin - processing).coerceAtLeast(0)) / 2
                    repository.settings { it.copy(calibrationMillis = epoch - System.currentTimeMillis(), syncEpoch = epoch, syncElapsed = end, syncBootCount = bootCount(), syncStatus = "已校时") }
                    epoch
                }
            }
            if (result.isFailure) runCatching { repository.settings { it.copy(syncStatus = if (reliable(it)) "校时失败，继续最近校准推算" else "校时失败，暂用系统时间") } }
            syncing.set(false); callback(result)
        }
    }
    companion object {
        fun readTimestamp(bytes: ByteArray, start: Int): Long {
            fun unsigned(at: Int) = (0..3).fold(0L) { n, i -> (n shl 8) or (bytes[at + i].toLong() and 255) }
            var seconds = unsigned(start)
            if (seconds < 2208988800L) seconds += 0x1_0000_0000L
            return (seconds - 2208988800L) * 1000 + unsigned(start + 4) * 1000 / 0x1_0000_0000L
        }
        fun writeTimestamp(bytes: ByteArray, start: Int, epoch: Long) {
            val seconds = epoch / 1000 + 2208988800L; val fraction = epoch % 1000 * 0x1_0000_0000L / 1000
            for (i in 0..3) { bytes[start + i] = (seconds ushr (24 - i * 8)).toByte(); bytes[start + i + 4] = (fraction ushr (24 - i * 8)).toByte() }
        }
    }
}
