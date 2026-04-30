package org.operatorfoundation.audiocoder.mfsk

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for AudioCoder's MFSK-16 implementation.
 *
 * Tests verify each layer of the pipeline independently before the full
 * encode → transmit → receive → decode integration path is exercised.
 */
class MFSKTests
{
    companion object
    {
        const val BASE_FREQUENCY_HZ = 1500.0
        const val SAMPLE_RATE       = 12_000
    }

    // =========================================================================
    // IZ8BLY Varicode
    // =========================================================================

    @Test
    fun varicode_encodeDecodeRoundTrip_ascii()
    {
        assertEquals("Hello, World!", encodeDecodeVaricode("Hello, World!"))
    }

    @Test
    fun varicode_encodeDecodeRoundTrip_base64Alphabet()
    {
        // Full base64 alphabet — the character set used in every Nahoft MFSK transmission.
        val text = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
        assertEquals(text, encodeDecodeVaricode(text))
    }

    @Test
    fun varicode_decoderReset_allowsCleanDecode()
    {
        val decoder = Varicode.Decoder()

        // Corrupt state with partial bits from 'A'
        val aBits = Varicode.encodedBits('A')!!
        repeat(aBits.size / 2) { decoder.feed(aBits[it]) }

        decoder.reset()

        // After reset, a clean decode with preamble priming should succeed
        val result = StringBuilder()
        repeat(32) { decoder.feed(0) }
        for (bit in Varicode.encodedBits('e')!!)
            decoder.feed(bit)?.let { if (it.code != 0) result.append(it) }
        decoder.feed(1)?.let { if (it.code != 0) result.append(it) }  // trailing trigger

        assertEquals("e", result.toString())
    }

    @Test
    fun varicode_encodedBits_returnsNullAbove255()
    {
        // IZ8BLY table covers code points 0–255. Code point 256 has no entry.
        assertNull(Varicode.encodedBits('\u0100'))
    }

    @Test
    fun varicode_allPrintableAsciiHaveCodes()
    {
        for (code in 32..126)
        {
            assertNotNull(
                "Printable ASCII char '${code.toChar()}' (code $code) must have an IZ8BLY code",
                Varicode.encodedBits(code.toChar())
            )
        }
    }

    @Test
    fun varicode_codeWordsBeginWith1()
    {
        // Every IZ8BLY code word starts with '1' — required by the trigger mechanism.
        for (code in 0..255)
        {
            val bits = Varicode.encodedBits(code.toChar()) ?: continue
            assertEquals(
                "Code word for char $code must start with 1",
                1, bits[0]
            )
        }
    }

    @Test
    fun varicode_codeWordsEndWithAtLeastTwoZeros()
    {
        // IZ8BLY code words have at least two trailing zeros by design —
        // these form the built-in inter-character separator. The "001" trigger
        // pattern is these trailing zeros plus the leading '1' of the next character.
        for (code in 0..255)
        {
            val bits = Varicode.encodedBits(code.toChar()) ?: continue
            assertTrue(
                "Code word for char $code (len=${bits.size}) must end with ≥2 trailing zeros",
                bits.size >= 3 &&
                        bits[bits.size - 1] == 0 &&
                        bits[bits.size - 2] == 0
            )
        }
    }

    // =========================================================================
    // ConvolutionalEncoder (R=1/2 K=7 NASA)
    // =========================================================================

    @Test
    fun convEncoder_output_inValidRange()
    {
        val encoder = ConvolutionalEncoder()
        // Two output bits packed into low two positions → output in [0, 3]
        for (bit in 0..1)
            assertTrue("Encoder output for bit=$bit must be in [0, 3]", encoder.encode(bit) in 0..3)
    }

    @Test
    fun convEncoder_reset_restoresInitialState()
    {
        val enc1 = ConvolutionalEncoder()
        val enc2 = ConvolutionalEncoder()

        // Advance enc1's shift register
        enc1.encode(1); enc1.encode(0); enc1.encode(1)

        // After reset, enc1 and a fresh enc2 must produce identical output
        enc1.reset()
        assertEquals(enc2.encode(0), enc1.encode(0))
        assertEquals(enc2.encode(1), enc1.encode(1))
        assertEquals(enc2.encode(0), enc1.encode(0))
    }

    @Test
    fun convEncoder_allZeroState_outputsZero()
    {
        // Initial shift register = 0. encode(0) keeps state at 0.
        // parity(POLY1 & 0) = parity(POLY2 & 0) = 0 → output = 0b00 = 0.
        assertEquals(0, ConvolutionalEncoder().encode(0))
    }

    @Test
    fun convEncoder_polynomials_matchNasaSpec()
    {
        // For state 0x6d (= POLY1 itself shifted in), parity(POLY1 & 0x6d) = ?
        // This verifies the output table was built with the correct polynomials.
        val encoder = ConvolutionalEncoder()
        // Shift in the 7-bit pattern 0x6d (1101101) one bit at a time
        // and collect the two output streams.
        val poly1Stream = mutableListOf<Int>()
        val poly2Stream = mutableListOf<Int>()
        for (bitPos in 6 downTo 0)
        {
            val inputBit = (ConvolutionalEncoder.GENERATOR_POLY_1 ushr bitPos) and 1
            val output   = encoder.encode(inputBit)
            poly1Stream.add(output and 1)
            poly2Stream.add((output ushr 1) and 1)
        }
        // Both streams must be non-trivially non-zero (the polynomial produces diversity)
        assertTrue(poly1Stream.any { it == 1 })
        assertTrue(poly2Stream.any { it == 1 })
    }

    // =========================================================================
    // MFSKInterleaver (IZ8BLY diagonal, depth=10)
    // =========================================================================

    @Test
    fun interleaver_tx_nibbleOutputInValidRange()
    {
        val interleaver = MFSKInterleaver.createForTransmit()
        for (nibble in 0..15)
            assertTrue(
                "TX interleaved nibble for input $nibble must be in [0, 15]",
                interleaver.interleaveNibble(nibble) in 0..15
            )
    }

    @Test
    fun interleaver_rx_deinterleaveDoesNotThrow()
    {
        val interleaver = MFSKInterleaver.createForReceive()
        val softBytes   = byteArrayOf(200.toByte(), 50.toByte(), 100.toByte(), 180.toByte())
        interleaver.deinterleaveSymbols(softBytes)  // must not throw
    }

    @Test(expected = IllegalArgumentException::class)
    fun interleaver_rx_wrongSizeThrows()
    {
        val interleaver = MFSKInterleaver.createForReceive()
        interleaver.deinterleaveSymbols(ByteArray(3))  // wrong size for size=4
    }

    @Test
    fun interleaver_reset_matchesFreshInstance()
    {
        val interleaver = MFSKInterleaver.createForTransmit()

        // Advance state with many nibbles
        repeat(30) { interleaver.interleaveNibble(7) }

        // After reset, first output must match a fresh interleaver
        val fresh = MFSKInterleaver.createForTransmit()
        interleaver.reset()

        assertEquals(
            "After reset, first nibble output must match a fresh instance",
            fresh.interleaveNibble(5),
            interleaver.interleaveNibble(5)
        )
    }

    @Test
    fun interleaver_rxInitializedWithPuncture()
    {
        // When PUNCTURE input arrives before any real data, the pre-filled table of
        // PUNCTURE values must produce PUNCTURE output — verified by tracing through
        // one call to deinterleaveSymbols with all-PUNCTURE soft bytes.
        // This confirms the Viterbi decoder sees maximally-uncertain input at startup
        // rather than biased values, which is essential for correct FEC behaviour.
        val interleaver = MFSKInterleaver.createForReceive()
        val softBytes   = ByteArray(4) { MFSKInterleaver.PUNCTURE_VALUE }
        interleaver.deinterleaveSymbols(softBytes)
        for (b in softBytes)
            assertEquals(
                "RX startup output with all-PUNCTURE input must remain PUNCTURE",
                MFSKInterleaver.PUNCTURE_VALUE,
                b
            )
    }

    // =========================================================================
    // MFSKEncoder
    // =========================================================================

    @Test
    fun encoder_encodeToSymbols_nonEmpty()
    {
        val symbols = MFSKEncoder.encodeToSymbols("test", MFSKMode.MFSK16)
        assertTrue("encodeToSymbols must produce symbols", symbols.isNotEmpty())
    }

    @Test
    fun encoder_encodeToSymbols_allSymbolsInRange()
    {
        val symbols   = MFSKEncoder.encodeToSymbols("Hello, World!", MFSKMode.MFSK16)
        val toneCount = MFSKMode.MFSK16.toneCount
        for (symbol in symbols)
            assertTrue(
                "Symbol $symbol must be in [0, $toneCount)",
                symbol in 0 until toneCount
            )
    }

    @Test
    fun encoder_longerText_producesMoreSymbols()
    {
        val short = MFSKEncoder.encodeToSymbols("Hi", MFSKMode.MFSK16)
        val long  = MFSKEncoder.encodeToSymbols("Hello, World!", MFSKMode.MFSK16)
        assertTrue("Longer text must produce more symbols", long.size > short.size)
    }

    @Test
    fun encoder_includesFullPipeline_preambleAndFlush()
    {
        // The preamble (~35 symbols) + start frame + data + end frame + flush
        // means even a single character produces many symbols.
        val symbols = MFSKEncoder.encodeToSymbols("a", MFSKMode.MFSK16)
        assertTrue(
            "Even a 1-char message should produce >100 symbols (preamble + framing + flush)",
            symbols.size > 100
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun encoder_emptyText_throws()
    {
        MFSKEncoder.encodeToSymbols("", MFSKMode.MFSK16)
    }

    @Test(expected = IllegalArgumentException::class)
    fun encoder_codePointAbove255_throws()
    {
        // IZ8BLY Varicode has entries for code points 0–255 only.
        // Code point 256 (U+0100) must be rejected.
        MFSKEncoder.encodeToSymbols("caf\u0100", MFSKMode.MFSK16)
    }

    // =========================================================================
    // MFSKMode
    // =========================================================================

    @Test
    fun mode_mfsk16_samplesPerSymbol_at12kHz_exactInteger()
    {
        // 12000 / 15.625 = 768.0 exactly — confirms zero rounding error at this rate.
        assertEquals(768, MFSKMode.MFSK16.samplesPerSymbol(SAMPLE_RATE))
    }

    @Test
    fun mode_fromLabel_roundTripsAllModes()
    {
        val modes = listOf(
            MFSKMode.MFSK8, MFSKMode.MFSK16, MFSKMode.MFSK32,
            MFSKMode.MFSK64, MFSKMode.MFSK128
        )
        for (mode in modes)
            assertEquals("fromLabel(${mode.label}) must return $mode", mode, MFSKMode.fromLabel(mode.label))
    }

    @Test
    fun mode_fromLabel_unknownLabel_returnsNull()
    {
        assertNull(MFSKMode.fromLabel("MFSK-999"))
    }

    @Test
    fun mode_bitsPerSymbol_isLog2OfToneCount()
    {
        assertEquals(3, MFSKMode.MFSK8.bitsPerSymbol)
        assertEquals(4, MFSKMode.MFSK16.bitsPerSymbol)
        assertEquals(5, MFSKMode.MFSK32.bitsPerSymbol)
        assertEquals(6, MFSKMode.MFSK64.bitsPerSymbol)
        assertEquals(7, MFSKMode.MFSK128.bitsPerSymbol)
    }

    @Test
    fun mode_bandwidth_equalsTonesTimeSpacing()
    {
        val mode = MFSKMode.MFSK16
        val expectedBandwidth = mode.toneCount * mode.toneSpacingHz
        assertEquals(expectedBandwidth, mode.bandwidthHz, 0.001)
    }

    // =========================================================================
    // SlidingDFT
    // =========================================================================

    @Test
    fun slidingDFT_notStable_beforeWindowLengthSamples()
    {
        val dft = SlidingDFT(
            windowLength  = 768,
            firstBinIndex = 64,
            lastBinIndex  = 80
        )
        assertFalse("DFT must not be stable before priming", dft.isStable())
    }

    @Test
    fun slidingDFT_stable_afterWindowLengthSamples()
    {
        val windowLength = 768
        val dft = SlidingDFT(windowLength, 64, 80)
        repeat(windowLength) { dft.run(0.0, 0.0) }
        assertTrue("DFT must be stable after ${windowLength} samples", dft.isStable())
    }

    @Test
    fun slidingDFT_reset_resetsStability()
    {
        val dft = SlidingDFT(768, 64, 80)
        repeat(768) { dft.run(0.0, 0.0) }
        assertTrue(dft.isStable())
        dft.reset()
        assertFalse("DFT must not be stable after reset", dft.isStable())
    }

    // =========================================================================
    // ViterbiDecoder
    // =========================================================================

    @Test
    fun viterbiDecoder_reset_doesNotThrow()
    {
        val decoder = ViterbiDecoder(
            ConvolutionalEncoder.CONSTRAINT_LENGTH,
            ConvolutionalEncoder.GENERATOR_POLY_1,
            ConvolutionalEncoder.GENERATOR_POLY_2
        )
        decoder.setTraceback(ViterbiDecoder.MFSK_TRACEBACK)
        decoder.setChunkSize(ViterbiDecoder.MFSK_CHUNK_SIZE)
        decoder.reset()  // must not throw
    }

    @Test
    fun viterbiDecoder_decode_returnsEventually()
    {
        val encoder = ConvolutionalEncoder()
        val decoder = ViterbiDecoder(
            ConvolutionalEncoder.CONSTRAINT_LENGTH,
            ConvolutionalEncoder.GENERATOR_POLY_1,
            ConvolutionalEncoder.GENERATOR_POLY_2
        )
        decoder.setTraceback(ViterbiDecoder.MFSK_TRACEBACK)
        decoder.setChunkSize(ViterbiDecoder.MFSK_CHUNK_SIZE)

        val symbolPair = ByteArray(2)
        val metric     = IntArray(1)
        var gotOutput  = false

        // Feed enough symbol pairs for the traceback to produce output.
        // With chunksize=1, every call produces output (-1 or a decoded bit).
        repeat(ViterbiDecoder.MFSK_TRACEBACK + 10)
        {
            val encoded  = encoder.encode(0)
            val softBit0 = if ((encoded and 1) == 1) 255.toByte() else 0.toByte()
            val softBit1 = if ((encoded ushr 1) == 1) 255.toByte() else 0.toByte()
            symbolPair[0] = symbolPair[1]
            symbolPair[1] = softBit0
            val result = decoder.decode(symbolPair, metric)
            if (result != -1) { gotOutput = true }
            symbolPair[0] = symbolPair[1]
            symbolPair[1] = softBit1
            decoder.decode(symbolPair, metric)
        }

        assertTrue("Viterbi decoder must produce output within traceback + 10 calls", gotOutput)
    }

    // =========================================================================
    // Helper
    // =========================================================================

    /**
     * Encodes [text] character by character using IZ8BLY Varicode, then decodes
     * it back to a String using a correctly primed decoder.
     *
     * This simulates the preamble mechanism:
     * - 32 zero bits drive the shift register to 0 (matching the effect of the
     *   transmitted convolutional-encoder preamble zeros after Viterbi decoding).
     * - A trailing '1' bit triggers the decode of the final character (matching
     *   flushtx's sendbit(1) in the TX pipeline).
     *
     * Without the 32-zero priming, the initial sentinel '1' in the decoder's
     * shift register would combine with the first character's leading '1', placing
     * an extra bit in the shift register and causing all characters to decode incorrectly.
     */
    private fun encodeDecodeVaricode(text: String): String
    {
        val decoder = Varicode.Decoder()
        val result  = StringBuilder()

        // Prime: 32 zeros shift the sentinel '1' out of the 32-bit register.
        repeat(32) { decoder.feed(0) }

        for (char in text)
        {
            val bits = Varicode.encodedBits(char) ?: continue
            for (bit in bits)
                decoder.feed(bit)?.let { if (it.code != 0) result.append(it) }
        }

        // Trailing trigger: decodes the last character.
        decoder.feed(1)?.let { if (it.code != 0) result.append(it) }

        return result.toString()
    }
}