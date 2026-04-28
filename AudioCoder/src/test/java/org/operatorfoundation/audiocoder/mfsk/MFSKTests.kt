package org.operatorfoundation.audiocoder.mfsk

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class MFSKTests
{
    companion object
    {
        const val BASE_FREQUENCY_HZ = 1500.0
        const val SAMPLE_RATE       = 12_000
        const val AMPLITUDE         = 0.5
    }

    // -------------------------------------------------------------------------
    // Varicode
    // -------------------------------------------------------------------------

    @Test
    fun varicode_encodeDecodeRoundTrip_ascii()
    {
        val text    = "Hello, World!"
        val bits    = Varicode.encode(text)
        val decoder = Varicode.Decoder()
        val result  = StringBuilder()

        for (bit in bits)
        {
            val char = decoder.feed(bit)
            if (char != null) result.append(char)
        }

        assertEquals(text, result.toString())
    }

    @Test
    fun varicode_encodeDecodeRoundTrip_base64Characters()
    {
        // Base64 alphabet plus the frame delimiters — the exact character set
        // used in every Nahoft MFSK transmission.
        val text    = "<SGVsbG8gV29ybGQh>"
        val bits    = Varicode.encode(text)
        val decoder = Varicode.Decoder()
        val result  = StringBuilder()

        for (bit in bits)
        {
            val char = decoder.feed(bit)
            if (char != null) result.append(char)
        }

        assertEquals(text, result.toString())
    }

    @Test
    fun varicode_decoderReset_clearsState()
    {
        val decoder = Varicode.Decoder()
        val bits    = Varicode.encode("A")

        // Feed partial bits then reset
        for (i in 0 until bits.size / 2)
        {
            decoder.feed(bits[i])
        }

        decoder.reset()

        // Full encode/decode should work cleanly after reset
        val result = StringBuilder()
        for (bit in Varicode.encode("e"))
        {
            val char = decoder.feed(bit)
            if (char != null) result.append(char)
        }

        assertEquals("e", result.toString())
    }

    @Test
    fun varicode_nonAsciiCharactersSkipped()
    {
        // Non-ASCII characters should be silently skipped by Varicode.encode()
        val text = "caf\u00e9"  // 'é' is non-ASCII
        val bits = Varicode.encode(text)
        val decoder = Varicode.Decoder()
        val result = StringBuilder()

        for (bit in bits)
        {
            val char = decoder.feed(bit)
            if (char != null) result.append(char)
        }

        assertEquals("caf", result.toString())
    }

    // -------------------------------------------------------------------------
    // MFSKEncoder
    // -------------------------------------------------------------------------

    @Test
    fun encoder_encodeProducesSamples()
    {
        val text   = "<SGVsbG8=>"
        val samples = MFSKEncoder.encode(
            text            = text,
            mode            = MFSKMode.MFSK16,
            baseFrequencyHz = BASE_FREQUENCY_HZ,
            sampleRate      = SAMPLE_RATE,
            amplitude       = AMPLITUDE
        )

        assertTrue("encode() should produce samples", samples.isNotEmpty())
    }

    @Test
    fun encoder_encodeToSymbolsMatchesEncode()
    {
        // encodeToSymbols and encode must produce symbol sequences that are
        // consistent with each other — same text, same mode, same symbols.
        val text    = "<SGVsbG8=>"
        val symbols = MFSKEncoder.encodeToSymbols(text, MFSKMode.MFSK16)
        val samples = MFSKEncoder.encode(
            text            = text,
            mode            = MFSKMode.MFSK16,
            baseFrequencyHz = BASE_FREQUENCY_HZ,
            sampleRate      = SAMPLE_RATE,
            amplitude       = AMPLITUDE
        )

        val samplesPerSymbol = MFSKMode.MFSK16.samplesPerSymbol(SAMPLE_RATE)
        assertEquals(
            "symbol count should match sample count / samplesPerSymbol",
            symbols.size,
            samples.size / samplesPerSymbol
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun encoder_emptyText_throws()
    {
        MFSKEncoder.encode("", MFSKMode.MFSK16, BASE_FREQUENCY_HZ, SAMPLE_RATE)
    }

    @Test(expected = IllegalArgumentException::class)
    fun encoder_nonAsciiText_throws()
    {
        MFSKEncoder.encode("caf\u00e9", MFSKMode.MFSK16, BASE_FREQUENCY_HZ, SAMPLE_RATE)
    }

    @Test(expected = IllegalArgumentException::class)
    fun encoder_zeroSampleRate_throws()
    {
        MFSKEncoder.encode("<test>", MFSKMode.MFSK16, BASE_FREQUENCY_HZ, sampleRate = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun encoder_amplitudeAboveOne_throws()
    {
        MFSKEncoder.encode("<test>", MFSKMode.MFSK16, BASE_FREQUENCY_HZ, SAMPLE_RATE, amplitude = 1.1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun encodeToSymbols_emptyText_throws()
    {
        MFSKEncoder.encodeToSymbols("", MFSKMode.MFSK16)
    }

    @Test(expected = IllegalArgumentException::class)
    fun encodeToSymbols_nonAsciiText_throws()
    {
        MFSKEncoder.encodeToSymbols("caf\u00e9", MFSKMode.MFSK16)
    }

    // -------------------------------------------------------------------------
    // MFSKStation framing
    // -------------------------------------------------------------------------

    @Test
    fun framePayload_producesCorrectFormat()
    {
        val data    = "Hello".toByteArray()
        val framed  = MFSKStation.framePayload(data)
        val base64  = Base64.getEncoder().encodeToString(data)

        assertEquals("<$base64>", framed)
    }

    @Test
    fun framePayload_isAscii()
    {
        val data   = ByteArray(32) { it.toByte() }
        val framed = MFSKStation.framePayload(data)

        assertTrue(
            "framePayload output must be pure ASCII",
            framed.all { it.code <= 127 }
        )
    }

    @Test
    fun framePayload_roundTrip_base64Decode()
    {
        val original = "test payload".toByteArray()
        val framed   = MFSKStation.framePayload(original)

        assertTrue(framed.startsWith("<"))
        assertTrue(framed.endsWith(">"))

        val base64  = framed.drop(1).dropLast(1)
        val decoded = Base64.getDecoder().decode(base64)

        assertArrayEquals(original, decoded)
    }

    // -------------------------------------------------------------------------
    // End-to-end encode/decode via MFSKStation
    // -------------------------------------------------------------------------

    @Test
    fun station_encodeProducesSamples()
    {
        val config  = MFSKConfiguration(
            mode            = MFSKMode.MFSK16,
            baseFrequencyHz = BASE_FREQUENCY_HZ,
            sampleRate      = SAMPLE_RATE,
            amplitude       = AMPLITUDE
        )
        val station = MFSKStation(NoOpAudioSource(), config)
        val samples = station.encode("test payload".toByteArray())

        assertTrue(samples.isNotEmpty())
    }

    // -------------------------------------------------------------------------
    // NoOpAudioSource stub
    // -------------------------------------------------------------------------

    private class NoOpAudioSource : MFSKAudioSource
    {
        override suspend fun initialize(): Result<Unit>                    = Result.success(Unit)
        override suspend fun readAudioChunk(durationMs: Long): ShortArray  = ShortArray(0)
        override suspend fun cleanup()                                     {}
        override suspend fun getSourceStatus() =
            org.operatorfoundation.audiocoder.common.models.AudioSourceStatus
                .createNonOperationalStatus("NoOp stub for testing")
    }
}