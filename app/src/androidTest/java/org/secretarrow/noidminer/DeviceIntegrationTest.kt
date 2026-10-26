package org.secretarrow.noidminer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.secretarrow.noidminer.data.stratum.StratumCodec
import org.secretarrow.noidminer.pow.HardwareReader
import org.secretarrow.noidminer.pow.NativePow
import org.secretarrow.noidminer.util.Bech32m

/** On-device checks: hardware detection, native availability, codec sanity. */
@RunWith(AndroidJUnit4::class)
class DeviceIntegrationTest {

    @Test
    fun hardwareReaderReturnsSaneValues() {
        val info = HardwareReader.read()
        assertTrue(info.coreCount >= 1)
        assertTrue(info.ramTotalMb >= 512)
        assertTrue(info.socName.isNotBlank())
    }

    @Test
    fun nativePowLoadsOnArm64ElseFallsBack() {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val loaded = NativePow.ensureLoaded()
        if (abi.startsWith("arm64")) {
            assertTrue("native core must load on $abi", loaded)
        } else {
            // x86_64 emulator: software fallback is expected.
            assertTrue(loaded || NativePow.loadError != null)
        }
    }

    @Test
    fun codecIgnoresGarbageWithoutThrowing() {
        val event = StratumCodec.decode("not json at all")
        assertEquals(StratumCodec.Event.Ignored, event)
    }

    @Test
    fun bech32mValidatorIsSafeOnDeviceInput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.packageName.contains("noidminer"))
        assertTrue(Bech32m.isValidO1("o1").not())
    }
}
