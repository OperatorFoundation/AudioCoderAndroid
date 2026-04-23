package org.operatorfoundation.audiocoder.mfsk

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.ceil

class MFSKTests
{
    companion object
    {
        const val BASE_FREQUENCY_HZ = 1500.0
        const val SAMPLE_RATE       = 12_000
        const val AMPLITUDE         = 0.5
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Encodes [data] and immediately decodes the result, returning the decoded bytes.
     * Exercises MFSKEncoder and MFSKDecoder directly without MFSKStation framing.
     */
    private fun roundTrip(data: ByteArray, mode: MFSKMode): ByteArray
    {
        val pcm = MFSKEncoder.encode(
            data            = data,
            mode            = mode,
            baseFrequencyHz = BASE_FREQUENCY_HZ,
            sampleRate      = SAMPLE_RATE,
            amplitude       = AMPLITUDE
        )

        return MFSKDecoder.decode(
            samples         = pcm,
            mode            = mode,
            baseFrequencyHz = BASE_FREQUENCY_HZ,
            sampleRate      = SAMPLE_RATE,
            byteCount       = data.size
        )
    }

    /**
     * Returns a deterministic byte array of [size] bytes with values 0..255 cycling.
     * Avoids random data so failures are reproducible.
     */
    private fun testData(size: Int): ByteArray =
        ByteArray(size) { i -> (i and 0xFF).toByte() }

    // -------------------------------------------------------------------------
    // Round-trip tests — one per mode
    // -------------------------------------------------------------------------

    @Test
    fun roundTrip_MFSK8_recovering16Bytes()
    {
        val data = testData(16)
        assertArrayEquals(data, roundTrip(data, MFSKMode.MFSK8))
    }

    @Test
    fun roundTrip_MFSK16_recovering16Bytes()
    {
        val data = testData(16)
        assertArrayEquals(data, roundTrip(data, MFSKMode.MFSK16))
    }

    @Test
    fun roundTrip_MFSK32_recovering16Bytes()
    {
        val data = testData(16)
        assertArrayEquals(data, roundTrip(data, MFSKMode.MFSK32))
    }

    @Test
    fun roundTrip_MFSK64_recovering16Bytes()
    {
        val data = testData(16)
        assertArrayEquals(data, roundTrip(data, MFSKMode.MFSK64))
    }

    @Test
    fun roundTrip_MFSK128_recovering16Bytes()
    {
        // MFSK-128 has 128 tones at 125 Hz spacing, giving a bandwidth of 16,000 Hz.
        // At 12kHz (Nyquist 6,000 Hz), tones above index 47 alias — the standard
        // SAMPLE_RATE and BASE_FREQUENCY_HZ used by other tests are incompatible.
        // 44,100 Hz (Nyquist 22,050 Hz) accommodates all 128 tones starting at 0 Hz:
        // highest tone = 0 + (127 × 125) = 15,875 Hz, which is below Nyquist.
        // In practice, MFSK-128 requires a wideband VHF/UHF audio pipeline.
        val mfsk128SampleRate    = 44_100
        val mfsk128BaseFrequency = 0.0

        val data = testData(16)
        val pcm  = MFSKEncoder.encode(
            data            = data,
            mode            = MFSKMode.MFSK128,
            baseFrequencyHz = mfsk128BaseFrequency,
            sampleRate      = mfsk128SampleRate,
            amplitude       = AMPLITUDE
        )
        val decoded = MFSKDecoder.decode(
            samples         = pcm,
            mode            = MFSKMode.MFSK128,
            baseFrequencyHz = mfsk128BaseFrequency,
            sampleRate      = mfsk128SampleRate,
            byteCount       = data.size
        )

        assertArrayEquals(data, decoded)
    }

    // -------------------------------------------------------------------------
    // MFSK-16 nibble equivalence
    // The general bit-stream implementation must produce the same symbol sequence
    // as manually splitting each byte into high nibble (bits 7-4) then low nibble
    // (bits 3-0). Verifies modulation semantics via the Goertzel bank rather than
    // comparing raw PCM bytes, which would be brittle to phase accumulation.
    // -------------------------------------------------------------------------

    @Test
    fun mfsk16_bitStreamMatchesNibbleSplit()
    {
        val data = testData(8)

        // Build expected symbol sequence via manual nibble split
        val expectedSymbols = IntArray(data.size * 2)
        for (i in data.indices)
        {
            val byte = data[i].toInt() and 0xFF
            expectedSymbols[i * 2]     = (byte ushr 4) and 0x0F  // high nibble
            expectedSymbols[i * 2 + 1] = byte and 0x0F           // low nibble
        }

        // Decode actual symbol sequence from encoder output via Goertzel bank
        val samplesPerSymbol = MFSKMode.MFSK16.samplesPerSymbol(SAMPLE_RATE)
        val toneFrequencies  = DoubleArray(MFSKMode.MFSK16.toneCount) { i ->
            BASE_FREQUENCY_HZ + i * MFSKMode.MFSK16.toneSpacingHz
        }

        val pcm = MFSKEncoder.encode(
            data            = data,
            mode            = MFSKMode.MFSK16,
            baseFrequencyHz = BASE_FREQUENCY_HZ,
            sampleRate      = SAMPLE_RATE,
            amplitude       = AMPLITUDE
        )

        val actualSymbols = IntArray(data.size * 2)
        for (symbolIndex in actualSymbols.indices)
        {
            val window = pcm.copyOfRange(
                symbolIndex * samplesPerSymbol,
                (symbolIndex + 1) * samplesPerSymbol
            )
            val energies = DoubleArray(MFSKMode.MFSK16.toneCount) { toneIndex ->
                GoertzelFilter.energy(window, toneFrequencies[toneIndex], SAMPLE_RATE)
            }
            actualSymbols[symbolIndex] = energies.indices.maxByOrNull { energies[it] }!!
        }

        assertArrayEquals(expectedSymbols, actualSymbols)
    }

    // -------------------------------------------------------------------------
    // Non-byte-aligned lengths
    // For modes where 8 % bitsPerSymbol != 0, the final symbol is zero-padded.
    // MFSK-16 always aligns exactly (8 % 4 == 0) and is excluded.
    // -------------------------------------------------------------------------

    @Test
    fun roundTrip_MFSK8_singleByte_nonAligned()
    {
        // 1 byte = 8 bits; 3 bits/symbol → ceil(8/3) = 3 symbols, 1 padded bit
        val data = testData(1)
        assertArrayEquals(data, roundTrip(data, MFSKMode.MFSK8))
    }

    @Test
    fun roundTrip_MFSK32_singleByte_nonAligned()
    {
        // 1 byte = 8 bits; 5 bits/symbol → ceil(8/5) = 2 symbols, 2 padded bits
        val data = testData(1)
        assertArrayEquals(data, roundTrip(data, MFSKMode.MFSK32))
    }

    @Test
    fun roundTrip_MFSK64_singleByte_nonAligned()
    {
        // 1 byte = 8 bits; 6 bits/symbol → ceil(8/6) = 2 symbols, 4 padded bits
        val data = testData(1)
        assertArrayEquals(data, roundTrip(data, MFSKMode.MFSK64))
    }

    @Test
    fun roundTrip_MFSK128_singleByte_nonAligned()
    {
        // 1 byte = 8 bits; 7 bits/symbol → ceil(8/7) = 2 symbols, 6 padded bits
        val data = testData(1)
        assertArrayEquals(data, roundTrip(data, MFSKMode.MFSK128))
    }

    // -------------------------------------------------------------------------
    // Length prefix correctness
    // MFSKStation.encode() prepends a 2-byte big-endian length field.
    // -------------------------------------------------------------------------

    @Test
    fun stationEncode_lengthPrefixIsCorrect()
    {
        val data          = testData(37)
        val configuration = MFSKConfiguration(
            mode            = MFSKMode.MFSK16,
            baseFrequencyHz = BASE_FREQUENCY_HZ,
            sampleRate      = SAMPLE_RATE,
            amplitude       = AMPLITUDE
        )
        val station       = MFSKStation(NoOpAudioSource(), configuration)
        val pcm           = station.encode(data)

        val prefixSampleCount = ceil(16.0 / MFSKMode.MFSK16.bitsPerSymbol).toInt() *
                MFSKMode.MFSK16.samplesPerSymbol(SAMPLE_RATE)

        val prefixBytes = MFSKDecoder.decode(
            samples         = pcm.copyOfRange(0, prefixSampleCount),
            mode            = MFSKMode.MFSK16,
            baseFrequencyHz = BASE_FREQUENCY_HZ,
            sampleRate      = SAMPLE_RATE,
            byteCount       = 2
        )

        val decodedLength = ((prefixBytes[0].toInt() and 0xFF) shl 8) or
                (prefixBytes[1].toInt() and 0xFF)

        assertEquals("Length prefix should encode payload byte count", data.size, decodedLength)
    }

    @Test
    fun stationEncode_fullFrameRoundTrip()
    {
        val data          = testData(37)
        val configuration = MFSKConfiguration(
            mode            = MFSKMode.MFSK16,
            baseFrequencyHz = BASE_FREQUENCY_HZ,
            sampleRate      = SAMPLE_RATE,
            amplitude       = AMPLITUDE
        )
        val station       = MFSKStation(NoOpAudioSource(), configuration)
        val pcm           = station.encode(data)

        // Skip the prefix, decode only the payload
        val prefixSampleCount = ceil(16.0 / MFSKMode.MFSK16.bitsPerSymbol).toInt() *
                MFSKMode.MFSK16.samplesPerSymbol(SAMPLE_RATE)

        val decoded = MFSKDecoder.decode(
            samples         = pcm.copyOfRange(prefixSampleCount, pcm.size),
            mode            = MFSKMode.MFSK16,
            baseFrequencyHz = BASE_FREQUENCY_HZ,
            sampleRate      = SAMPLE_RATE,
            byteCount       = data.size
        )

        assertArrayEquals("Payload round-trip should recover original data", data, decoded)
    }

    @Test
    fun encodeToSymbols_mfsk16_matchesNibbleSplit()
    {
        val data = testData(8)

        val expectedSymbols = IntArray(data.size * 2)
        for (i in data.indices)
        {
            val byte = data[i].toInt() and 0xFF
            expectedSymbols[i * 2]     = (byte ushr 4) and 0x0F
            expectedSymbols[i * 2 + 1] = byte and 0x0F
        }

        assertArrayEquals(expectedSymbols, MFSKEncoder.encodeToSymbols(data, MFSKMode.MFSK16))
    }

    @Test(expected = IllegalArgumentException::class)
    fun encodeToSymbols_emptyData_throws()
    {
        MFSKEncoder.encodeToSymbols(ByteArray(0), MFSKMode.MFSK16)
    }

    // -------------------------------------------------------------------------
    // require() guard tests
    // -------------------------------------------------------------------------

    @Test(expected = IllegalArgumentException::class)
    fun encoder_emptyData_throws()
    {
        MFSKEncoder.encode(ByteArray(0), MFSKMode.MFSK16, BASE_FREQUENCY_HZ, SAMPLE_RATE)
    }

    @Test(expected = IllegalArgumentException::class)
    fun encoder_zeroSampleRate_throws()
    {
        MFSKEncoder.encode(testData(4), MFSKMode.MFSK16, BASE_FREQUENCY_HZ, sampleRate = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun encoder_amplitudeAboveOne_throws()
    {
        MFSKEncoder.encode(testData(4), MFSKMode.MFSK16, BASE_FREQUENCY_HZ, SAMPLE_RATE, amplitude = 1.1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun encoder_negativeAmplitude_throws()
    {
        MFSKEncoder.encode(testData(4), MFSKMode.MFSK16, BASE_FREQUENCY_HZ, SAMPLE_RATE, amplitude = -0.1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun decoder_zeroByteCount_throws()
    {
        MFSKDecoder.decode(ShortArray(768), MFSKMode.MFSK16, BASE_FREQUENCY_HZ, SAMPLE_RATE, byteCount = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun decoder_samplesTooShort_throws()
    {
        // 1 byte needs ceil(8/4) * 768 = 1536 samples; supply only 100
        MFSKDecoder.decode(ShortArray(100), MFSKMode.MFSK16, BASE_FREQUENCY_HZ, SAMPLE_RATE, byteCount = 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun decoder_zeroSampleRate_throws()
    {
        MFSKDecoder.decode(ShortArray(768), MFSKMode.MFSK16, BASE_FREQUENCY_HZ, sampleRate = 0, byteCount = 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun stationEncode_payloadTooLarge_throws()
    {
        val configuration = MFSKConfiguration(MFSKMode.MFSK16, BASE_FREQUENCY_HZ)
        val station       = MFSKStation(NoOpAudioSource(), configuration)
        station.encode(ByteArray(65_536))  // UShort.MAX_VALUE + 1
    }

    // -------------------------------------------------------------------------
    // NoOpAudioSource stub
    // Satisfies MFSKStation's constructor for tests that only call encode(),
    // which has no lifecycle dependency on the audio source.
    // -------------------------------------------------------------------------

    private class NoOpAudioSource : MFSKAudioSource
    {
        override suspend fun initialize(): Result<Unit>             = Result.success(Unit)
        override suspend fun readAudioChunk(durationMs: Long): ShortArray = ShortArray(0)
        override suspend fun cleanup()                              {}
        override suspend fun getSourceStatus() =
            org.operatorfoundation.audiocoder.common.models.AudioSourceStatus
                .createNonOperationalStatus("NoOp stub for testing")
    }
}