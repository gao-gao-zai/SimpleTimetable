package com.ggz.diagnostics
import org.junit.Assert.*
import org.junit.Test
class ProbeTest {
    @Test fun independentUuidAnd64ByteReassembly(){assertNotEquals("d7300001-9e2c-4c53-a1aa-2cc0bf3d7710",Probe.service.toString());val bytes=ByteArray(64);for(seq in 0..3){val p=Probe.packet(seq);assertEquals(20,p.size);p.copyInto(bytes,seq*16,4,20)};assertTrue(Probe.verify(bytes));bytes[5]=0;assertFalse(Probe.verify(bytes))}
}
