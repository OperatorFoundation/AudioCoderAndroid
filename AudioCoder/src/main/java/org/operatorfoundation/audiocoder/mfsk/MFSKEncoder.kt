package org.operatorfoundation.audiocoder.mfsk

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Encodes arbitrary byte data into MFSK audio PCM samples.
 *
 * Input bytes are treated as a flat bit stream (MSB-first within each byte). Bits are extracted
 * [MFSKMode.bitsPerSymbol] at a time to produce a symbol index, which selects a tone frequency.
 * If the total bit count is not evenly divisible by [MFSKMode.bitsPerSymbol], the final symbol
 * is zero-padded to complete it.
 *
 * ## Tone mapping
 * Symbol index S maps to frequency: `baseFrequencyHz + S × mode.toneSpacingHz`
 * For MFSK-16 this is equivalent to treating each byte as two 4-bit nibbles (high nibble first),
 * which is a useful sanity check when testing the general implementation.
 *
 * ## Phase continuity
 * Phase is tracked as a continuous accumulator across symbol boundaries. Resetting phase to zero
 * at each boundary would cause spectral splatter at transitions; continuous phase keeps the
 * output spectrally clean. Phase is wrapped to [0, 2π) once per symbol to prevent
 * floating-point precision loss over long transmissions.
 */
object MFSKEncoder
{
    /**
     * Encodes [data] as an MFSK audio signal.
     *
     * @param data            Raw bytes to encode (e.g. ciphertext). Must not be empty.
     * @param mode            MFSK mode defining tone count, baud rate, and tone spacing.
     * @param baseFrequencyHz Frequency of tone index 0, in Hz.
     * @param sampleRate      Audio pipeline sample rate in Hz (e.g. 12000).
     * @param amplitude       Output level as a fraction of full scale, in [0.0, 1.0]. Default 0.5.
     *                        Sine output spans [-1.0, 1.0], so PCM output spans
     *                        [-amplitude × 32767, +amplitude × 32767].
     *                        Uses Kotlin's ClosedFloatingPointRange — boundary values 0.0 and
     *                        1.0 are valid.
     * @return 16-bit PCM samples representing the encoded signal.
     */
    fun encode(
        data: ByteArray,
        mode: MFSKMode,
        baseFrequencyHz: Double,
        sampleRate: Int,
        amplitude: Double = 0.5
    ): ShortArray
    {
        require(data.isNotEmpty())      { "data must not be empty" }
        require(sampleRate > 0)         { "sampleRate must be positive, was $sampleRate" }
        require(amplitude in 0.0..1.0)  { "amplitude must be in [0.0, 1.0], was $amplitude" }

        val samplesPerSymbol = mode.samplesPerSymbol(sampleRate)
        val totalBits        = data.size * 8

        // Round up so the final symbol is zero-padded if bits don't divide evenly.
        val symbolCount = ceil(totalBits.toDouble() / mode.bitsPerSymbol).toInt()

        val output = ShortArray(symbolCount * samplesPerSymbol)

        // Scale factor for PCM output. Sine is in [-1.0, 1.0], so this bounds
        // output to [-peakAmplitude, +peakAmplitude] within Short range.
        val peakAmplitude = amplitude * 32767.0

        // Continuous phase accumulator. Carries over between symbols so that the
        // waveform is uninterrupted at tone transitions.
        var phase = 0.0

        for (symbolIndex in 0 until symbolCount)
        {
            // --- Extract bitsPerSymbol bits from the stream, MSB-first ---
            var toneIndex = 0
            val startBit  = symbolIndex * mode.bitsPerSymbol

            for (bitOffset in 0 until mode.bitsPerSymbol)
            {
                val bitPosition = startBit + bitOffset

                val bit = if (bitPosition < totalBits)
                {
                    // MSB-first: bit 7 of each byte is the first bit out.
                    // ushr (logical shift) is used instead of shr (arithmetic shift) to
                    // avoid sign-extension corrupting the bit value for bytes ≥ 128.
                    val byteIndex = bitPosition / 8
                    val bitInByte = 7 - (bitPosition % 8)
                    (data[byteIndex].toInt() ushr bitInByte) and 1
                }
                else
                {
                    0 // Zero-pad the incomplete final symbol
                }

                toneIndex = (toneIndex shl 1) or bit
            }

            // --- Generate samples for this symbol's tone ---
            val toneFrequencyHz = baseFrequencyHz + toneIndex * mode.toneSpacingHz
            val phaseIncrement  = 2.0 * PI * toneFrequencyHz / sampleRate
            val sampleOffset    = symbolIndex * samplesPerSymbol

            for (sampleIndex in 0 until samplesPerSymbol)
            {
                // roundToInt() rather than toInt(): toInt() truncates toward zero, introducing
                // a consistent 0.5 LSB bias on negative samples. roundToInt() distributes
                // error symmetrically — at most 0.5 LSB in either direction.
                output[sampleOffset + sampleIndex] = (peakAmplitude * sin(phase)).roundToInt().toShort()
                phase += phaseIncrement
            }

            // Wrap phase to [0, 2π) once per symbol. Sin is 2π-periodic so this is lossless.
            // Per-symbol wrapping (rather than per-sample) is sufficient to prevent precision
            // loss and avoids the % operation on every sample.
            phase %= (2.0 * PI)
        }

        return output
    }
}