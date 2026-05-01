package org.operatorfoundation.audiocoder.mfsk

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 37-tap complex FIR filter that converts a real audio stream into an analytic signal.
 *
 * An analytic signal has a real part (I) and imaginary part (Q) that are a Hilbert
 * transform pair. It enables the receiver to distinguish positive from negative frequencies,
 * which is required for the mixer and sliding DFT to work correctly.
 *
 * ## Usage in the receive pipeline
 * ```
 * real PCM sample
 *   → HilbertFilter.run(sample) → (I, Q) complex analytic sample
 *   → mixer (frequency shift)
 *   → BandpassFilter
 *   → SlidingDFT
 * ```
 *
 * ## Filter design
 * Both I and Q taps are computed from the bp_FIR function in fldigi's filters.cxx,
 * covering the frequency range [LOW_CUTOFF, HIGH_CUTOFF] of the sample rate:
 *   - I taps: bandpass FIR (hilbert=0)  → standard bandpass impulse response
 *   - Q taps: Hilbert FIR  (hilbert=1)  → 90° phase-shifted impulse response (negated)
 *
 * The input sample is fed identically to both I and Q channels. The filter produces the
 * real (I) and imaginary (Q) parts of the analytic signal as its output.
 *
 * Direct translation of fldigi's C_FIR_filter initialized via init_hilbert(37, 1),
 * which calls bp_FIR(37, 0, 0.05, 0.45) for I and bp_FIR(37, 1, 0.05, 0.45) for Q.
 *
 * Parameters match fldigi's mfsk.cxx: `hbfilt->init_hilbert(37, 1)`
 */
class HilbertFilter
{
    companion object
    {
        /** Number of filter taps. From fldigi: init_hilbert(37, 1). */
        const val TAP_COUNT = 37

        /**
         * Lower normalized cutoff frequency (fraction of sample rate).
         * Chosen to exclude DC and very low frequencies from the analytic signal.
         * From fldigi: bp_FIR(len, hilbert, 0.05, 0.45).
         */
        private const val LOW_CUTOFF_NORMALIZED = 0.05

        /**
         * Upper normalized cutoff frequency (fraction of sample rate).
         * Chosen to stop below the Nyquist limit.
         * From fldigi: bp_FIR(len, hilbert, 0.05, 0.45).
         */
        private const val HIGH_CUTOFF_NORMALIZED = 0.45
    }

    // FIR coefficients for the real (I) and imaginary (Q) channels.
    private val iTaps: DoubleArray = computeBandpassTaps(isHilbert = false)
    private val qTaps: DoubleArray = computeBandpassTaps(isHilbert = true)

    // Circular sample buffer — same sample value feeds both channels.
    // Size equals TAP_COUNT so the buffer exactly holds one filter length of history.
    private val sampleBuffer = DoubleArray(TAP_COUNT)
    private var writePointer = 0

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Processes one real audio sample and returns the I (real) and Q (imaginary)
     * components of the analytic signal.
     *
     * In fldigi's rx_process():
     *   z = cmplx(sample, sample)
     *   hbfilt->run(z, z)
     *
     * @param sample A 16-bit PCM sample normalized to [-1.0, 1.0].
     * @param outReal Caller-provided 1-element array — filled with the I component.
     * @param outImag Caller-provided 1-element array — filled with the Q component.
     */
    fun run(sample: Double, outReal: DoubleArray, outImag: DoubleArray)
    {
        // Write the new sample into the circular buffer.
        sampleBuffer[writePointer] = sample

        var iAccumulator = 0.0
        var qAccumulator = 0.0

        // Convolve: tap[0] is applied to the newest sample, tap[TAP_COUNT-1] to the oldest.
        for (tapIndex in 0 until TAP_COUNT)
        {
            val bufferIndex = (writePointer + 1 + tapIndex) % TAP_COUNT
            val bufferSample = sampleBuffer[bufferIndex]
            iAccumulator += bufferSample * iTaps[tapIndex]
            qAccumulator += bufferSample * qTaps[tapIndex]
        }

        writePointer = (writePointer + 1) % TAP_COUNT

        outReal[0] = iAccumulator
        outImag[0] = qAccumulator
    }

    /**
     * Resets the sample buffer to zero. Call before each new receive session.
     */
    fun reset()
    {
        sampleBuffer.fill(0.0)
        writePointer = 0
    }

    // -------------------------------------------------------------------------
    // Tap computation — direct translation of fldigi's bp_FIR()
    // -------------------------------------------------------------------------

    /**
     * Computes the FIR filter coefficients for one channel.
     *
     * Translation of fldigi's C_FIR_filter::bp_FIR(len, hilbert, f1, f2):
     *
     * For the bandpass channel (isHilbert = false):
     *   tap[i] = (2*f2*sinc(2*f2*t) - 2*f1*sinc(2*f1*t)) * hamming(h)
     *
     * For the Hilbert channel (isHilbert = true):
     *   tap[i] = -(2*f2*cosc(2*f2*t) - 2*f1*cosc(2*f1*t)) * hamming(h)
     *
     * Where:
     *   t = i - (TAP_COUNT - 1) / 2.0   (time offset from center tap)
     *   h = i / (TAP_COUNT - 1.0)        (normalized position for Hamming window)
     */
    private fun computeBandpassTaps(isHilbert: Boolean): DoubleArray
    {
        val taps = DoubleArray(TAP_COUNT)
        val f1 = LOW_CUTOFF_NORMALIZED
        val f2 = HIGH_CUTOFF_NORMALIZED

        for (tapIndex in 0 until TAP_COUNT)
        {
            val t = tapIndex - (TAP_COUNT - 1.0) / 2.0
            val hammingPosition = tapIndex / (TAP_COUNT - 1.0)
            val hammingWeight = hammingWindow(hammingPosition)

            taps[tapIndex] = if (!isHilbert)
            {
                // Bandpass (I channel): difference of two sinc functions
                (2.0 * f2 * sinc(2.0 * f2 * t) - 2.0 * f1 * sinc(2.0 * f1 * t)) * hammingWeight
            }
            else
            {
                // Hilbert (Q channel): difference of two cosc functions, negated.
                // The negation is because fldigi's implementation assumes the impulse
                // response is stored in time-reversed order for the Q channel.
                -(2.0 * f2 * cosc(2.0 * f2 * t) - 2.0 * f1 * cosc(2.0 * f1 * t)) * hammingWeight
            }
        }

        return taps
    }

    // -------------------------------------------------------------------------
    // DSP utility functions — from fldigi's misc.h
    // -------------------------------------------------------------------------

    /**
     * Normalized sinc function: sin(π*x) / (π*x).
     * Returns 1.0 when x is within floating-point zero tolerance.
     * From fldigi's misc.h.
     */
    private fun sinc(x: Double): Double =
        if (abs(x) < 1e-10) 1.0 else sin(PI * x) / (PI * x)

    /**
     * Normalized cosc function: (1 - cos(π*x)) / (π*x).
     * Returns 0.0 when x is within floating-point zero tolerance.
     * From fldigi's misc.h.
     */
    private fun cosc(x: Double): Double =
        if (abs(x) < 1e-10) 0.0 else (1.0 - cos(PI * x)) / (PI * x)

    /**
     * Hamming window evaluated at normalized position [h] in [0, 1].
     * From fldigi's misc.h: 0.54 - 0.46 * cos(2π * h).
     */
    private fun hammingWindow(h: Double): Double =
        0.54 - 0.46 * cos(2.0 * PI * h)
}