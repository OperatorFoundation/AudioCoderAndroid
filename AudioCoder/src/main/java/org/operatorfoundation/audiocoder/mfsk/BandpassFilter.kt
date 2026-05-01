package org.operatorfoundation.audiocoder.mfsk

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 127-tap complex bandpass FIR filter applied after the Hilbert filter and mixer.
 *
 * Removes out-of-band signals and CW interference before the sliding DFT, keeping
 * only the frequency range occupied by the MFSK tones. Both the I and Q channels
 * use the same FIR coefficients (unlike the Hilbert filter, which uses different
 * I and Q taps to create the 90° phase shift).
 *
 * ## Usage in the receive pipeline
 * ```
 * (I, Q) from mixer
 *   → BandpassFilter.run(I, Q) → filtered (I, Q)
 *   → SlidingDFT
 * ```
 *
 * ## Cutoff frequency computation
 * The caller (MFSKStation) computes the normalized cutoff frequencies from the
 * MFSKConfiguration before constructing this filter:
 *
 * ```
 * val tonespacing = sampleRate / samplesPerSymbol
 * val baseFrequency = tonespacing * basetone       // Hz, where tone 0 lands after mixing
 * val bandwidth = (toneCount - 1) * tonespacing
 * val centerFrequency = baseFrequency + bandwidth / 2.0
 * val lowCutoff  = (centerFrequency - bandwidth / 2.0 - 2.0 * tonespacing) / sampleRate
 * val highCutoff = (centerFrequency + bandwidth / 2.0 + 2.0 * tonespacing) / sampleRate
 * ```
 *
 * The ±2 tone spacings of guard band on each side match fldigi's bpfilt construction in mfsk.cxx.
 *
 * Direct translation of fldigi's C_FIR_filter initialized via init_bandpass(127, 1, flo, fhi),
 * which calls bp_FIR(127, 0, flo, fhi) — the same bandpass formula used for the Hilbert I channel,
 * applied identically to both I and Q.
 */
class BandpassFilter(
    /** Lower normalized cutoff frequency in (0.0, 0.5). Fraction of the sample rate. */
    private val lowCutoffNormalized: Double,
    /** Upper normalized cutoff frequency in (0.0, 0.5). Fraction of the sample rate. */
    private val highCutoffNormalized: Double
)
{
    companion object
    {
        /**
         * Number of filter taps. From fldigi: init_bandpass(127, 1, flo, fhi).
         * More taps = sharper rolloff at the cost of more computation.
         * At 12 kHz and 127 taps, this is 127 multiply-accumulates per sample — negligible.
         */
        const val TAP_COUNT = 127
    }

    init
    {
        require(lowCutoffNormalized > 0.0 && lowCutoffNormalized < 0.5) {
            "lowCutoffNormalized must be in (0.0, 0.5), got $lowCutoffNormalized"
        }
        require(highCutoffNormalized > lowCutoffNormalized && highCutoffNormalized < 0.5) {
            "highCutoffNormalized must be in ($lowCutoffNormalized, 0.5), got $highCutoffNormalized"
        }
    }

    // Both I and Q channels share the same FIR coefficients.
    // Unlike the Hilbert filter, no phase shift is needed between channels here.
    private val taps: DoubleArray = computeBandpassTaps()

    // Separate circular buffers for I and Q — each channel filtered independently
    // with the same tap coefficients.
    private val iBuffer = DoubleArray(TAP_COUNT)
    private val qBuffer = DoubleArray(TAP_COUNT)
    private var writePointer = 0

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Processes one complex sample and returns the bandpass-filtered result.
     *
     * In fldigi's rx_process():
     *   bpfilt->run(z, z)  — complex in, complex out, same variable
     *
     * @param inputReal  Real (I) part of the mixer output sample.
     * @param inputImag  Imaginary (Q) part of the mixer output sample.
     * @param outReal    Caller-provided 1-element array — filled with filtered I component.
     * @param outImag    Caller-provided 1-element array — filled with filtered Q component.
     */
    fun run(inputReal: Double, inputImag: Double, outReal: DoubleArray, outImag: DoubleArray)
    {
        iBuffer[writePointer] = inputReal
        qBuffer[writePointer] = inputImag

        var iAccumulator = 0.0
        var qAccumulator = 0.0

        for (tapIndex in 0 until TAP_COUNT)
        {
            val bufferIndex = (writePointer + 1 + tapIndex) % TAP_COUNT
            iAccumulator += iBuffer[bufferIndex] * taps[tapIndex]
            qAccumulator += qBuffer[bufferIndex] * taps[tapIndex]
        }

        writePointer = (writePointer + 1) % TAP_COUNT

        outReal[0] = iAccumulator
        outImag[0] = qAccumulator
    }

    /**
     * Resets the I and Q buffers to zero. Call before each new receive session.
     */
    fun reset()
    {
        iBuffer.fill(0.0)
        qBuffer.fill(0.0)
        writePointer = 0
    }

    // -------------------------------------------------------------------------
    // Tap computation — direct translation of fldigi's bp_FIR(len, 0, f1, f2)
    // -------------------------------------------------------------------------

    /**
     * Computes the bandpass FIR coefficients for the configured cutoff frequencies.
     *
     * Translation of fldigi's C_FIR_filter::bp_FIR(len, hilbert=0, f1, f2):
     *   tap[i] = (2*f2*sinc(2*f2*t) - 2*f1*sinc(2*f1*t)) * hamming(h)
     *
     * Where:
     *   t = i - (TAP_COUNT - 1) / 2.0   (time offset from center tap)
     *   h = i / (TAP_COUNT - 1.0)        (normalized position for Hamming window)
     */
    private fun computeBandpassTaps(): DoubleArray
    {
        val taps = DoubleArray(TAP_COUNT)
        val f1 = lowCutoffNormalized
        val f2 = highCutoffNormalized

        for (tapIndex in 0 until TAP_COUNT)
        {
            val t = tapIndex - (TAP_COUNT - 1.0) / 2.0
            val hammingPosition = tapIndex / (TAP_COUNT - 1.0)
            val hammingWeight = hammingWindow(hammingPosition)
            taps[tapIndex] =
                (2.0 * f2 * sinc(2.0 * f2 * t) - 2.0 * f1 * sinc(2.0 * f1 * t)) * hammingWeight
        }

        return taps
    }

    // -------------------------------------------------------------------------
    // DSP utility functions — from fldigi's misc.h
    // -------------------------------------------------------------------------

    /** Normalized sinc: sin(π*x) / (π*x). Returns 1.0 for x ≈ 0. */
    private fun sinc(x: Double): Double =
        if (abs(x) < 1e-10) 1.0 else sin(PI * x) / (PI * x)

    /** Hamming window: 0.54 - 0.46 * cos(2π*h). From fldigi's misc.h. */
    private fun hammingWindow(h: Double): Double =
        0.54 - 0.46 * cos(2.0 * PI * h)
}