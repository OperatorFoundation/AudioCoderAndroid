package org.operatorfoundation.audiocoder.mfsk

import kotlin.math.ceil

/**
 * Decodes MFSK audio PCM samples back into the original byte data.
 *
 * This is the inverse of [MFSKEncoder]. For each symbol window, it runs a full Goertzel
 * filter bank across all [MFSKMode.toneCount] tone frequencies and selects the highest-energy
 * tone as the symbol decision. Symbol indices are then unpacked into a bit stream (MSB-first)
 * and assembled into output bytes.
 *
 * ## Length requirement
 * The decoder requires [byteCount] to be specified explicitly. Without it, there is no way to
 * distinguish real data bits from the zero-padding the encoder appended to fill out the final
 * symbol — a distinction that matters critically with ciphertext, where padding bytes are valid
 * byte values indistinguishable by content. The framing layer ([MFSKStation]) is responsible
 * for communicating byte count, typically via a length prefix prepended before encoding.
 *
 * ## Signal quality
 * The decoder always picks a winner — the tone with the highest Goertzel energy — with no
 * minimum threshold. A symbol window of pure noise will still yield a decoded symbol; it will
 * simply be wrong. Corruption will propagate to the output bytes and should be caught by the
 * authentication layer above (e.g. the encryption layer in Nahoft). Signal quality gating
 * belongs in [MFSKStation], which has the context to make that judgment before calling decode.
 */
object MFSKDecoder
{
    /**
     * Decodes MFSK audio samples into the original byte data.
     *
     * @param samples         PCM audio containing the MFSK signal. Must contain at least
     *                        enough samples for [byteCount] bytes at the given [mode] and
     *                        [sampleRate] — see [MFSKMode.samplesPerSymbol].
     * @param mode            MFSK mode used to encode the signal. Must match the encoder's mode.
     * @param baseFrequencyHz Frequency of tone index 0 in Hz. Must match the encoder's value.
     * @param sampleRate      Audio pipeline sample rate in Hz (e.g. 12000).
     * @param byteCount       Number of bytes to decode. Must match the original plaintext length.
     * @return Decoded bytes. Length is exactly [byteCount].
     */
    fun decode(
        samples: ShortArray,
        mode: MFSKMode,
        baseFrequencyHz: Double,
        sampleRate: Int,
        byteCount: Int
    ): ByteArray
    {
        require(byteCount > 0)  { "byteCount must be positive, was $byteCount" }
        require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate" }

        val samplesPerSymbol = mode.samplesPerSymbol(sampleRate)
        val totalBits        = byteCount * 8

        // Round up: mirrors the encoder's ceil() so symbol count is always consistent.
        val symbolCount         = ceil(totalBits.toDouble() / mode.bitsPerSymbol).toInt()
        val expectedSampleCount = symbolCount * samplesPerSymbol

        // Reject inputs that are too short to contain the expected data. Silent truncation
        // would produce garbage output with no indication of failure — unacceptable for
        // ciphertext where partial output is indistinguishable from valid output.
        // The require also guarantees the last window's copyOfRange upper bound
        // (symbolCount * samplesPerSymbol == expectedSampleCount) never exceeds samples.size.
        require(samples.size >= expectedSampleCount) {
            "samples too short: need $expectedSampleCount for $byteCount bytes in ${mode.label}, " +
                    "got ${samples.size}"
        }

        // Precompute tone frequencies once — constant across all symbol windows.
        val toneFrequencies = DoubleArray(mode.toneCount) { toneIndex ->
            baseFrequencyHz + toneIndex * mode.toneSpacingHz
        }

        val symbols = IntArray(symbolCount) { symbolIndex ->
            val windowStart = symbolIndex * samplesPerSymbol
            val window      = samples.copyOfRange(windowStart, windowStart + samplesPerSymbol)
            val energies    = DoubleArray(mode.toneCount) { toneIndex ->
                GoertzelFilter.energy(window, toneFrequencies[toneIndex], sampleRate)
            }
            // !! is safe: maxByOrNull returns null only for empty collections,
            // and toneCount is always >= 8 by MFSKMode's design.
            energies.indices.maxByOrNull { energies[it] }!!
        }

        return reconstructBytes(symbols, byteCount, mode.bitsPerSymbol)
    }

    private fun reconstructBytes(symbols: IntArray, byteCount: Int, bitsPerSymbol: Int): ByteArray
    {
        val output      = ByteArray(byteCount)
        val totalBits   = byteCount * 8
        var bitPosition = 0

        for (toneIndex in symbols)
        {
            for (bitOffset in 0 until bitsPerSymbol)
            {
                if (bitPosition >= totalBits) break

                val bitInSymbol = bitsPerSymbol - 1 - bitOffset
                val bit         = (toneIndex ushr bitInSymbol) and 1

                val byteIndex = bitPosition / 8
                val bitInByte = 7 - (bitPosition % 8)

                if (bit == 1)
                {
                    output[byteIndex] = (output[byteIndex].toInt() or (1 shl bitInByte)).toByte()
                }

                bitPosition++
            }
        }

        return output
    }
}