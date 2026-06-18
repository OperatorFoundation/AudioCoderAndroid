package org.operatorfoundation.audiocoder

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.operatorfoundation.audiocoder.mfsk.MFSKConfiguration
import org.operatorfoundation.audiocoder.mfsk.MFSKEncoder
import org.operatorfoundation.audiocoder.mfsk.MFSKMessage
import org.operatorfoundation.audiocoder.mfsk.MFSKMode
import org.operatorfoundation.audiocoder.mfsk.MFSKStation
import org.operatorfoundation.audiocoder.mfsk.MFSKStationState
import timber.log.Timber
import kotlin.math.PI
import kotlin.math.sin

/**
 * Instrumented tests for the pure Kotlin MFSK implementation.
 *
 * These tests run on device so Timber output is visible
 */
@RunWith(AndroidJUnit4::class)
class MFSKStationTests
{
    companion object
    {
        private const val BASE_FREQUENCY_HZ = 1500.0
        private const val SAMPLE_RATE       = 12_000

        @JvmStatic
        @BeforeClass
        fun plantTimberOnce()
        {
            if (Timber.forest().none { it is Timber.DebugTree })
            {
                Timber.plant(Timber.DebugTree())
            }
        }
    }

    // =========================================================================
    // Loopback
    // =========================================================================

    @Test
    fun station_loopback_decodesKnownString()
    {
        val testText = "HELLO WORLD"
        val mode     = MFSKMode.MFSK16

        val symbols = MFSKEncoder.encodeToSymbols(testText, mode)
        Timber.d("MFSKStationTests: encoded to ${symbols.size} symbols")

        val audio = synthesizeAudio(symbols, mode, SAMPLE_RATE, BASE_FREQUENCY_HZ)
        Timber.d("MFSKStationTests: synthesized ${audio.size} samples (${audio.size.toDouble() / SAMPLE_RATE}s)")

        val audioFlow = flow<ShortArray> {
            audio.toList().chunked(960).forEach { chunk -> emit(chunk.toShortArray()) }
        }

        val station = MFSKStation(
            audioStream   = audioFlow,
            configuration = MFSKConfiguration(
                mode            = mode,
                baseFrequencyHz = BASE_FREQUENCY_HZ,
                sampleRate      = SAMPLE_RATE
            )
        )

        val messages = mutableListOf<MFSKMessage>()

        runBlocking {
            val collectionJob = launch(Dispatchers.IO) {
                station.receivedMessages.collect { messages.add(it) }
            }

            station.start()

            // Wait for the finite audio flow to complete — state returns to Idle.
            station.stationState.first { it is MFSKStationState.Listening }
            station.stationState.first { it is MFSKStationState.Idle }

            collectionJob.cancel()
        }

        Timber.d("MFSKStationTests: decoded ${messages.size} message(s)")
        messages.forEach { Timber.d("MFSKStationTests: text='${it.text.trim()}'") }

        assertTrue("Expected at least one decoded message, got none", messages.isNotEmpty())
        assertEquals(testText, messages.first().text.trim())
    }

    // =========================================================================
    // Helper
    // =========================================================================

    /**
     * Synthesizes 16-bit PCM audio from an MFSK symbol index sequence.
     *
     * Maintains phase continuity across symbol boundaries to avoid spectral
     * splatter at transitions that could corrupt the receiver's DFT bins.
     *
     * @param symbols          Gray-encoded tone indices from [MFSKEncoder.encodeToSymbols].
     * @param mode             MFSK mode (determines tone spacing and samples per symbol).
     * @param sampleRate       Audio sample rate in Hz.
     * @param baseFrequencyHz  Frequency of tone index 0 in Hz.
     * @return PCM samples, [symbols.size × samplesPerSymbol] in length.
     */
    private fun synthesizeAudio(
        symbols: IntArray,
        mode: MFSKMode,
        sampleRate: Int,
        baseFrequencyHz: Double
    ): ShortArray
    {
        val samplesPerSymbol = mode.samplesPerSymbol(sampleRate)
        val output           = ShortArray(symbols.size * samplesPerSymbol)
        val amplitude        = Short.MAX_VALUE * 0.5
        var phase            = 0.0

        for ((symbolIdx, toneIndex) in symbols.withIndex())
        {
            val frequencyHz    = baseFrequencyHz + toneIndex * mode.toneSpacingHz
            val phaseIncrement = 2.0 * PI * frequencyHz / sampleRate

            for (sampleIdx in 0 until samplesPerSymbol)
            {
                output[symbolIdx * samplesPerSymbol + sampleIdx] =
                    (amplitude * sin(phase)).toInt().toShort()
                phase += phaseIncrement
            }

            // Keep phase in [0, 2π) to prevent floating-point drift.
            phase %= 2.0 * PI
        }

        return output
    }
}