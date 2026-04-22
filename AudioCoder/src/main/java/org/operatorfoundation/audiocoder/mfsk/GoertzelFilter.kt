package org.operatorfoundation.audiocoder.mfsk

import kotlin.math.cos
import kotlin.math.PI

/**
 * Stateless implementation of the Goertzel algorithm for single-tone energy detection.
 *
 * The Goertzel algorithm is an efficient DFT evaluation for a single frequency bin. It is
 * the standard choice for MFSK decoding because it computes energy at exactly N target
 * frequencies (one per tone) rather than evaluating the full spectrum — far cheaper than
 * an FFT when N is small.
 *
 * ## Usage in MFSK decoding
 * The decoder calls [energy] once per tone per symbol window. It then selects the tone index
 * with the highest returned energy as the symbol decision. All 16 calls share the same
 * [samples] and [sampleRate]; only [targetFrequencyHz] varies.
 *
 * ## Formulation
 * This implementation uses the single-pass optimized formulation, which avoids a second
 * pass over the sample buffer and is equivalent to the standard two-pass version:
 *
 * ```
 * coefficient = 2 × cos(2π × f / sampleRate)
 * s_prev2 = 0, s_prev1 = 0
 * for each sample x:
 *     s = x + coefficient × s_prev1 − s_prev2
 *     s_prev2 = s_prev1
 *     s_prev1 = s
 * energy = s_prev2² + s_prev1² − coefficient × s_prev1 × s_prev2
 * ```
 */
object GoertzelFilter
{
    /**
     * Normalization divisor for 16-bit PCM samples.
     * Dividing a raw sample value by this constant maps the range [-32768, 32767] to [-1.0, 1.0].
     * Symbol decisions are correct without normalization (the relative energies are the same),
     * but normalized input keeps energy values in a meaningful range for any future
     * absolute-level thresholding (e.g. squelch or signal quality estimation).
     */
    private const val PCM_NORMALIZATION_FACTOR = 32768.0

    /**
     * Evaluates the energy at [targetFrequencyHz] over the given sample window.
     *
     * The input [samples] should contain exactly one symbol period worth of audio
     * (i.e. [MFSKMode.samplesPerSymbol] samples). Passing a shorter or longer window
     * is not an error but will affect energy accuracy — the Goertzel filter is
     * tuned for a window size equal to [samples.size].
     *
     * Returned energy is raw (not normalized by sample count). All tones in a symbol
     * decision are evaluated over the same window, so raw values are directly comparable.
     *
     * @param samples           One symbol window of 16-bit PCM audio.
     * @param targetFrequencyHz The tone frequency to measure, in Hz.
     * @param sampleRate        Audio pipeline sample rate in Hz (e.g. 12000).
     * @return Raw Goertzel energy at the target frequency. Higher values indicate
     *         stronger presence of that tone in the sample window.
     */
    fun energy(
        samples: ShortArray,
        targetFrequencyHz: Double,
        sampleRate: Int
    ): Double
    {
        // Precompute the Goertzel coefficient for this frequency and sample rate.
        // This would be worth caching if called in a tight inner loop with fixed parameters,
        // but at 16 tones per symbol the recalculation cost is negligible.
        val coefficient = 2.0 * cos(2.0 * PI * targetFrequencyHz / sampleRate)

        var sPrev2 = 0.0
        var sPrev1 = 0.0

        for (sample in samples)
        {
            // Normalize PCM sample from [-32768, 32767] to [-1.0, 1.0] before filtering.
            val normalizedSample = sample / PCM_NORMALIZATION_FACTOR

            val s = normalizedSample + coefficient * sPrev1 - sPrev2
            sPrev2 = sPrev1
            sPrev1 = s
        }

        // Single-pass energy formula: equivalent to |X(k)|² from the DFT.
        return sPrev2 * sPrev2 + sPrev1 * sPrev1 - coefficient * sPrev1 * sPrev2
    }
}