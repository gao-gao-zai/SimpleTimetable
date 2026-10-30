package com.ggz.diagnostics
import java.util.UUID
import java.util.zip.CRC32
object Probe {
    fun uuid(number: Int) = UUID.fromString("d731%04x-9e2c-4c53-a1aa-2cc0bf3d7710".format(number))
    val service = uuid(1)
    val fixed = ByteArray(64) { (it xor 0xa5).toByte() }
    fun crc(bytes: ByteArray) = CRC32().apply { update(bytes) }.value
    fun packet(seq: Int) = byteArrayOf(0xd7.toByte(), seq.toByte(), 4, 16) + fixed.copyOfRange(seq * 16, seq * 16 + 16)
    fun verify(bytes: ByteArray) = bytes.contentEquals(fixed) && crc(bytes) == crc(fixed)
}
