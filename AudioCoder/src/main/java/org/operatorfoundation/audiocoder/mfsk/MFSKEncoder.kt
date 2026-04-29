package org.operatorfoundation.audiocoder.mfsk

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Encodes ASCII text into standard MFSK-16 audio PCM samples or symbol index sequences.
 *
 * Input text is Varicode-encoded per the PSK31 standard before modulation. The resulting
 * transmissions are decodable by any compliant MFSK-16 receiver.
 *
 * ## Tone mapping
 * Symbol index S maps to frequency: `baseFrequencyHz + S × mode.toneSpacingHz`
 *
 * ## Phase continuity
 * Phase is tracked as a continuous accumulator across symbol boundaries. Resetting phase
 * to zero at each boundary would cause spectral splatter at transitions; continuous phase
 * keeps the output spectrally clean. Phase is wrapped to [0, 2π) once per symbol to
 * prevent floating-point precision loss over long transmissions.
 *
 * ## Input constraint
 * Both public functions require pure ASCII input (code points 0–127). Non-ASCII input is a
 * programming error and is rejected with [IllegalArgumentException].
 */
object MFSKEncoder
{
    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Encodes [text] as a standard MFSK audio signal.
     *
     * [text] is Varicode-encoded, then packed into MFSK symbols, then modulated
     * as PCM audio with continuous phase.
     *
     * @param text            ASCII text to encode. Must not be empty. Must contain
     *                        only characters with code points in [0, 127].
     * @param mode            MFSK mode defining tone count, baud rate, and tone spacing.
     * @param baseFrequencyHz Frequency of tone index 0, in Hz.
     * @param sampleRate      Audio pipeline sample rate in Hz (e.g. 12000).
     * @param amplitude       Output level as a fraction of full scale, in [0.0, 1.0].
     *                        Default 0.5. Sine output spans [-1.0, 1.0], so PCM output
     *                        spans [-amplitude × 32767, +amplitude × 32767].
     * @return 16-bit PCM samples representing the encoded signal.
     */
    fun encode(
        text: String,
        mode: MFSKMode,
        baseFrequencyHz: Double,
        sampleRate: Int,
        amplitude: Double = 0.5
    ): ShortArray
    {
        require(text.isNotEmpty())                          { "text must not be empty" }
        require(text.all { it.code <= 127 })                { "MFSKEncoder input must be ASCII" }
        require(sampleRate > 0)                             { "sampleRate must be positive, was $sampleRate" }
        require(amplitude in 0.0..1.0)                { "amplitude must be in [0.0, 1.0], was $amplitude" }

        val symbols          = extractSymbols(Varicode.encode(text), mode)
        val samplesPerSymbol = mode.samplesPerSymbol(sampleRate)
        val output           = ShortArray(symbols.size * samplesPerSymbol)
        val peakAmplitude    = amplitude * 32767.0

        // Continuous phase accumulator. Carries over between symbols so that the
        // waveform is uninterrupted at tone transitions.
        var phase = 0.0

        for (symbolIndex in symbols.indices)
        {
            val toneFrequencyHz = baseFrequencyHz + symbols[symbolIndex] * mode.toneSpacingHz
            val phaseIncrement  = 2.0 * PI * toneFrequencyHz / sampleRate
            val sampleOffset    = symbolIndex * samplesPerSymbol

            for (sampleIndex in 0 until samplesPerSymbol)
            {
                // roundToInt() rather than toInt(): toInt() truncates toward zero, introducing
                // a consistent 0.5 LSB bias on negative samples. roundToInt() distributes
                // error symmetrically — at most 0.5 LSB in either direction.
                output[sampleOffset + sampleIndex] =
                    (peakAmplitude * sin(phase)).roundToInt().toShort()
                phase += phaseIncrement
            }

            // Wrap phase to [0, 2π) once per symbol. Sin is 2π-periodic so this is lossless.
            // Per-symbol wrapping prevents precision loss without the cost of % on every sample.
            phase %= (2.0 * PI)
        }

        return output
    }

    /**
     * Encodes [text] as a sequence of MFSK symbol indices without generating audio.
     *
     * [text] is Varicode-encoded, then packed into symbol indices. Intended for hardware
     * TX paths where the caller converts each symbol index to a tone frequency
     * directly rather than consuming PCM.
     *
     * Symbol index S corresponds to frequency: `baseFrequencyHz + S × mode.toneSpacingHz`
     *
     * @param text ASCII text to encode. Must not be empty. Must contain only characters
     *             with code points in [0, 127].
     * @param mode MFSK mode defining tone count, baud rate, and tone spacing.
     * @return Symbol indices in transmission order, each in [0, mode.toneCount).
     */
    fun encodeToSymbols(text: String, mode: MFSKMode): IntArray
    {
        require(text.isNotEmpty())           { "text must not be empty" }
        require(text.all { it.code <= 127 }) { "MFSKEncoder input must be ASCII" }
        return extractSymbols(Varicode.encode(text), mode)
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Packs a Varicode bit stream into MFSK symbol indices.
     *
     * Bits are consumed [MFSKMode.bitsPerSymbol] at a time, MSB-first within each symbol.
     * If the total bit count is not evenly divisible by [mode.bitsPerSymbol], the final
     * symbol is zero-padded. The receiver's Varicode decoder handles this gracefully —
     * the padding bits form an incomplete code word that produces no output character.
     *
     * @param bits Varicode-encoded bit stream from [Varicode.encode].
     * @param mode MFSK mode defining bits per symbol.
     * @return Symbol indices, one per [mode.bitsPerSymbol] input bits.
     */
    private fun extractSymbols(bits: BooleanArray, mode: MFSKMode): IntArray
    {
        val symbolCount = ceil(bits.size.toDouble() / mode.bitsPerSymbol).toInt()
        val symbols     = IntArray(symbolCount)

        for (symbolIndex in 0 until symbolCount)
        {
            var toneIndex = 0

            for (bitOffset in 0 until mode.bitsPerSymbol)
            {
                val bitPosition = symbolIndex * mode.bitsPerSymbol + bitOffset
                val bit         = if (bitPosition < bits.size) bits[bitPosition] else false

                toneIndex = (toneIndex shl 1) or (if (bit) 1 else 0)
            }

            symbols[symbolIndex] = toneIndex xor (toneIndex shr 1)
        }

        return symbols
    }
}