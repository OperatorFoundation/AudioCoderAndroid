package org.operatorfoundation.audiocoder

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.operatorfoundation.audiocoder.wspr.CJarInterface
import org.operatorfoundation.audiocoder.wspr.WSPREncoder
import kotlin.test.DefaultAsserter.assertNotNull

@RunWith(AndroidJUnit4::class)
class WSPREncoderTest
{
    companion object
    {
        const val TEST_CALLSIGN = "AA0AAA"
        const val TEST_GRID     = "FN20"
        const val TEST_POWER    = 30
        const val TEST_FREQ_MHZ = 14.0956

        // From jni_link.h: WSPR_SYMBOL_LENGTH = 8192
        // 162 symbols × 8192 samples/symbol × 2 bytes/sample (16-bit PCM)
        const val EXPECTED_PCM_BYTES = 162 * 8192 * 2  // = 2,654,208

        // Native decoder requires 114s of audio:
        // 114s × 12,000 samples/s × 2 bytes/sample
        const val DECODER_REQUIRED_BYTES = 114 * 12_000 * 2  // = 2,736,000
    }

    @Test
    fun testBasicEncodingMatchesJNI() {
        val callsign = "W1ABC"
        val locator = "FN20"
        val power = 30
        val offset = 0
        val lsb = false

        // Kotlin implementation
        val kotlinResult = WSPREncoder.encodeToFrequencies(
            WSPREncoder.WSPREncodeRequest(callsign, locator, power, offset, lsb)
        )

        // JNI implementation
        val jniResult = CJarInterface.WSPREncodeToFrequencies(
            callsign, locator, power, offset, lsb
        )

        // Should produce identical results
        assertEquals(162, kotlinResult.size)
        assertEquals(162, jniResult.size)
        assertArrayEquals(jniResult, kotlinResult)
    }

    @Test
    fun testEncodingWithFrequencyOffset() {
        val callsign = "K1JT"
        val locator = "FN20"
        val power = 23
        val offset = 1500
        val lsb = false

        val kotlinResult = WSPREncoder.encodeToFrequencies(
            WSPREncoder.WSPREncodeRequest(callsign, locator, power, offset, lsb)
        )

        val jniResult = CJarInterface.WSPREncodeToFrequencies(
            callsign, locator, power, offset, lsb
        )

        assertArrayEquals(jniResult, kotlinResult)
    }

    @Test
    fun testLSBModeEncoding() {
        val callsign = "N2ABC"
        val locator = "EM79"
        val power = 37
        val offset = 0
        val lsb = true

        val kotlinResult = WSPREncoder.encodeToFrequencies(
            WSPREncoder.WSPREncodeRequest(callsign, locator, power, offset, lsb)
        )

        val jniResult = CJarInterface.WSPREncodeToFrequencies(
            callsign, locator, power, offset, lsb
        )

        assertArrayEquals(jniResult, kotlinResult)
    }

    @Test
    fun testVariousCallsignFormats() {
        val testCases = listOf(
            "W1ABC",   // Standard US call
            "K1JT",    // Short US call
            "AA1A",    // 2-prefix call
            "VE3XYZ",  // Canadian call
            "G4ABC",   // UK call
            "DL1ABC",  // German call
            "JA1ABC"   // Japanese call
        )

        val locator = "FN20"
        val power = 30

        for (callsign in testCases) {
            val kotlinResult = WSPREncoder.encodeToFrequencies(
                WSPREncoder.WSPREncodeRequest(callsign, locator, power)
            )

            val jniResult = CJarInterface.WSPREncodeToFrequencies(
                callsign, locator, power, 0, false
            )

            assertArrayEquals(
                "Failed for callsign: $callsign",
                jniResult,
                kotlinResult
            )
        }
    }

    @Test
    fun testVariousGridLocators() {
        val testCases = listOf(
            "FN20",  // Northeast US
            "EM79",  // Southeast US
            "CN87",  // West coast US
            "IO91",  // UK
            "JN59",  // Central Europe
            "PM96",  // Hawaii
            "QF22"   // New Zealand
        )

        val callsign = "W1ABC"
        val power = 30

        for (locator in testCases) {
            val kotlinResult = WSPREncoder.encodeToFrequencies(
                WSPREncoder.WSPREncodeRequest(callsign, locator, power)
            )

            val jniResult = CJarInterface.WSPREncodeToFrequencies(
                callsign, locator, power, 0, false
            )

            assertArrayEquals(
                "Failed for locator: $locator",
                jniResult,
                kotlinResult
            )
        }
    }

    @Test
    fun testVariousPowerLevels() {
        val testPowers = listOf(0, 3, 7, 10, 13, 17, 20, 23, 27, 30, 33, 37, 40, 43, 47, 50, 53, 57, 60)
        val callsign = "W1ABC"
        val locator = "FN20"

        for (power in testPowers) {
            val kotlinResult = WSPREncoder.encodeToFrequencies(
                WSPREncoder.WSPREncodeRequest(callsign, locator, power)
            )

            val jniResult = CJarInterface.WSPREncodeToFrequencies(
                callsign, locator, power, 0, false
            )

            assertArrayEquals(
                "Failed for power: $power dBm",
                jniResult,
                kotlinResult
            )
        }
    }

    @Test
    fun testPowerLevelCorrections() {
        val testCases = listOf(1, 2, 11, 12, 15, 25, 35, 45, 55)
        val callsign = "W1ABC"
        val locator = "FN20"

        for (inputPower in testCases) {
            val kotlinResult = WSPREncoder.encodeToFrequencies(
                WSPREncoder.WSPREncodeRequest(callsign, locator, inputPower)
            )

            val jniResult = CJarInterface.WSPREncodeToFrequencies(
                callsign, locator, inputPower, 0, false
            )

            assertArrayEquals(
                "Failed for power correction: $inputPower dBm",
                jniResult,
                kotlinResult
            )
        }
    }

    @Test
    fun testFrequencyRangeCorrectness() {
        val message = WSPREncoder.WSPREncodeRequest(
            callsign = "W1ABC",
            locator = "FN20",
            powerDbm = 30,
            offsetHz = 0,
            lsbMode = false
        )

        val frequencies = WSPREncoder.encodeToFrequencies(message)

        val minFreq = frequencies.minOrNull()!!
        val maxFreq = frequencies.maxOrNull()!!

        assertTrue(minFreq >= 150000)
        assertTrue(maxFreq <= 150450)
        assertTrue(maxFreq > minFreq)
    }

    @Test
    fun testLSBModeInvertsSymbols() {
        val callsign = "W1ABC"
        val locator = "FN20"
        val power = 30

        val usbResult = WSPREncoder.encodeToFrequencies(
            WSPREncoder.WSPREncodeRequest(callsign, locator, power, 0, false)
        )

        val lsbResult = WSPREncoder.encodeToFrequencies(
            WSPREncoder.WSPREncodeRequest(callsign, locator, power, 0, true)
        )

        var differencesFound = 0
        for (i in usbResult.indices) {
            if (usbResult[i] != lsbResult[i]) {
                differencesFound++
            }
        }

        assertTrue(differencesFound > 0)
    }

    @Test
    fun testOffsetAddsToAllFrequencies() {
        val callsign = "W1ABC"
        val locator = "FN20"
        val power = 30
        val offset = 1000

        val noOffsetResult = WSPREncoder.encodeToFrequencies(
            WSPREncoder.WSPREncodeRequest(callsign, locator, power, 0, false)
        )

        val offsetResult = WSPREncoder.encodeToFrequencies(
            WSPREncoder.WSPREncodeRequest(callsign, locator, power, offset, false)
        )

        for (i in noOffsetResult.indices) {
            assertEquals(
                noOffsetResult[i] + (offset * 100),
                offsetResult[i]
            )
        }
    }

    @Test
    fun testComprehensiveComparison() {
        val callsigns = listOf("W1ABC", "K1JT", "VE3XYZ")
        val locators = listOf("FN20", "EM79", "IO91")
        val powers = listOf(10, 23, 37)
        val offsets = listOf(0, 500, 1500)
        val lsbModes = listOf(false, true)

        var testCount = 0
        for (callsign in callsigns) {
            for (locator in locators) {
                for (power in powers) {
                    for (offset in offsets) {
                        for (lsb in lsbModes) {
                            val kotlinResult = WSPREncoder.encodeToFrequencies(
                                WSPREncoder.WSPREncodeRequest(callsign, locator, power, offset, lsb)
                            )

                            val jniResult = CJarInterface.WSPREncodeToFrequencies(
                                callsign, locator, power, offset, lsb
                            )

                            assertArrayEquals(
                                "Failed: $callsign $locator ${power}dBm offset=${offset}Hz lsb=$lsb",
                                jniResult,
                                kotlinResult
                            )
                            testCount++
                        }
                    }
                }
            }
        }

        println("✓ Ran $testCount comprehensive comparison tests - all passed!")
    }

    @Test
    fun wsprEncodeToPCM_returnsCorrectByteCount()
    {
        val pcm = CJarInterface.WSPREncodeToPCM(
            TEST_CALLSIGN, TEST_GRID, TEST_POWER, 0, false
        )

        assertNotNull("WSPREncodeToPCM returned null", pcm)
        assertEquals(
            "PCM output size should be 162 symbols × 8192 samples × 2 bytes",
            EXPECTED_PCM_BYTES,
            pcm.size
        )
    }

    @Test
    fun wsprRoundTrip_decodesCallsignAndGrid()
    {
        val encodedPcm = CJarInterface.WSPREncodeToPCM(
            TEST_CALLSIGN, TEST_GRID, TEST_POWER, 0, false
        )

        assertNotNull("WSPREncodeToPCM returned null", encodedPcm)

        // Zero-pad to decoder minimum — encoded signal is 110.6s, decoder needs 114s
        val paddedBuffer = ByteArray(DECODER_REQUIRED_BYTES)
        encodedPcm.copyInto(paddedBuffer)

        val results = CJarInterface.WSPRDecodeFromPcm(paddedBuffer, TEST_FREQ_MHZ, false)

        assertNotNull("WSPRDecodeFromPcm returned null", results)
        assertTrue("Decoder should recover at least one message", results.isNotEmpty())
        assertEquals("Decoded callsign should match", TEST_CALLSIGN, results[0].call?.trim())
        assertEquals("Decoded grid should match", TEST_GRID, results[0].loc?.trim())
    }
}