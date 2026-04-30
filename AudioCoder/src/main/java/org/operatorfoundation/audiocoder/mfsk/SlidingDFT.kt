package org.operatorfoundation.audiocoder.mfsk

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sliding Discrete Fourier Transform (SDFT) for continuous per-sample tone energy computation.
 *
 * The SDFT is the core of the MFSK receiver. Unlike a block-based Goertzel computation,
 * the SDFT updates every DFT bin after every single audio sample, enabling:
 *   - Symbol timing recovery: energy transitions across the pipe buffer reveal symbol boundaries
 *   - AFC: complex phase comparison between consecutive pipe entries tracks frequency drift
 *   - Soft-decision demodulation: all tone energies are available simultaneously
 *
 * ## Algorithm
 * When the analysis window slides forward one sample, each bin requires only:
 *   1. Subtract the contribution of the oldest (now-expired) sample, scaled by STABILITY_FACTOR^N
 *   2. Add the new sample's contribution
 *   3. Phase-rotate by the bin's twiddle factor
 *
 * This is one complex multiply and two complex additions per bin per sample, cheaper
 * than recomputing the full DFT from scratch.
 *
 * ## Stability
 * A pure recursive SDFT is marginally stable; floating-point rounding causes slow divergence.
 * Multiplying each rotation factor by STABILITY_FACTOR (0.99999999999) reduces the feedback gain
 * below unity, guaranteeing stability. The oldest delayed sample is then scaled by
 * STABILITY_FACTOR^windowLength before subtraction to compensate. Value from fldigi's
 * sfft implementation.
 *
 * ## Pipe buffer
 * A circular buffer of 2×[windowLength] complex vectors (one per processed sample) stores
 * historical DFT output. This is the data source for:
 *   - Hard decode: pick the highest-magnitude bin at the current sample
 *   - Soft decode: form all 4 per-bit soft decisions at the current sample
 *   - Symbol sync: scan energy across time to locate symbol boundaries
 *   - AFC: compare complex phase between consecutive entries in the same bin
 *
 * Direct translation of fldigi's sfft class.
 *
 * @param windowLength  Number of samples in the DFT window.
 *                      Equals samplesPerSymbol: 768 at 12 kHz for MFSK-16.
 * @param firstBinIndex DFT bin index of tone 0, computed as
 *                      round(baseFrequencyHz / (sampleRate / windowLength)).
 * @param lastBinIndex  Exclusive upper bound: firstBinIndex + toneCount.
 */
class SlidingDFT(
    val windowLength: Int,
    val firstBinIndex: Int,
    val lastBinIndex: Int
)
{
    companion object
    {
        /**
         * Stability factor for the recursive SDFT.
         *
         * Each rotation coefficient is multiplied by this value, making the feedback
         * gain |K1| < 1 and preventing floating-point errors from accumulating over time.
         * The oldest delayed sample is compensated by K1^windowLength before subtraction.
         *
         * At this value, K1^768 ≈ 1 - 7.68e-9 — essentially full-strength subtraction,
         * meaning the SDFT is a true sliding window DFT with only the minimum feedback
         * damping needed to guarantee stability. A significantly smaller value (e.g. 0.99999999999)
         * would cause systematic tone energy errors by under-subtracting the oldest sample.
         */
        const val STABILITY_FACTOR = 0.99999999999
    }

    /** Number of tone bins computed per sample: (lastBinIndex - firstBinIndex). */
    val toneCount: Int = lastBinIndex - firstBinIndex

    // Per-bin rotation factors for bins [firstBinIndex, lastBinIndex).
    // rotationReal[i] + j*rotationImag[i] = STABILITY_FACTOR * exp(j * 2π * (firstBinIndex+i) / windowLength)
    // Precomputed once — constant for the lifetime of this instance.
    private val rotationReal = DoubleArray(toneCount)
    private val rotationImag = DoubleArray(toneCount)

    // Current DFT bin values. Updated on every call to [run].
    // Index i corresponds to DFT bin (firstBinIndex + i).
    private val binReal = DoubleArray(toneCount)
    private val binImag = DoubleArray(toneCount)

    // Circular delay buffer of length [windowLength].
    // Holds the last windowLength complex input samples.
    // The oldest sample (at delayPointer) is retrieved on each [run] call.
    private val delayReal = DoubleArray(windowLength)
    private val delayImag = DoubleArray(windowLength)
    private var delayPointer = 0

    // STABILITY_FACTOR^windowLength — used to scale the outgoing (oldest) sample
    // before subtracting its contribution from the bins.
    private val stabilityAtWindowLength: Double

    // -------------------------------------------------------------------------
    // Pipe buffer — 2 * windowLength entries, each holding [toneCount] complex values.
    //
    // Written on every [run] call. Provides the historical DFT data required by
    // symbol timing recovery (synchronize) and AFC.
    //
    // Indexed as pipeReal[timeStep][toneIndex] and pipeImag[timeStep][toneIndex].
    // -------------------------------------------------------------------------
    private val pipeReal = Array(2 * windowLength) { DoubleArray(toneCount) }
    private val pipeImag = Array(2 * windowLength) { DoubleArray(toneCount) }

    /**
     * Index of the pipe entry that will be written on the next [run] call.
     * After [run] returns, the just-written entry is at (pipePointer - 1).
     */
    var pipePointer: Int = 0
        private set

    private var processedSampleCount = 0

    init
    {
        val angularFrequencyPerBin = 2.0 * PI / windowLength

        for (toneIndex in 0 until toneCount)
        {
            val binIndex = firstBinIndex + toneIndex
            val phi = angularFrequencyPerBin * binIndex
            rotationReal[toneIndex] = STABILITY_FACTOR * cos(phi)
            rotationImag[toneIndex] = STABILITY_FACTOR * sin(phi)
        }

        // Compute STABILITY_FACTOR^windowLength for the delay compensation term.
        var k2 = 1.0
        repeat(windowLength) { k2 *= STABILITY_FACTOR }
        stabilityAtWindowLength = k2
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Processes one complex audio sample and updates all [toneCount] DFT bins.
     *
     * The input is the complex analytic signal after the Hilbert filter and mixer.
     * After this call, use [copyCurrentBins] or [getBinMagnitude] to read tone energies.
     *
     * @param inputReal Real part of the current analytic audio sample.
     * @param inputImag Imaginary part of the current analytic audio sample.
     */
    fun run(inputReal: Double, inputImag: Double)
    {
        // Retrieve the oldest sample from the delay buffer.
        val oldReal = delayReal[delayPointer]
        val oldImag = delayImag[delayPointer]

        // The differential term: new sample minus stability-scaled oldest sample.
        // Adding this to each bin accounts for the one sample sliding into the window
        // and one (scaled) sample sliding out.
        val diffReal = inputReal - stabilityAtWindowLength * oldReal
        val diffImag = inputImag - stabilityAtWindowLength * oldImag

        // Store the new sample in the delay buffer and advance.
        delayReal[delayPointer] = inputReal
        delayImag[delayPointer] = inputImag
        delayPointer = (delayPointer + 1) % windowLength

        // Update each DFT bin: bins = (bins + diff) * vrot
        // Expands to: bins * vrot + diff * vrot
        // This matches fldigi: bins = bins * vrot + z * vrot
        val currentPipeReal = pipeReal[pipePointer]
        val currentPipeImag = pipeImag[pipePointer]

        for (toneIndex in 0 until toneCount)
        {
            val combinedReal = binReal[toneIndex] + diffReal
            val combinedImag = binImag[toneIndex] + diffImag

            // Complex multiply: (combinedReal + j*combinedImag) * (rotReal + j*rotImag)
            val newReal = combinedReal * rotationReal[toneIndex] - combinedImag * rotationImag[toneIndex]
            val newImag = combinedReal * rotationImag[toneIndex] + combinedImag * rotationReal[toneIndex]

            binReal[toneIndex] = newReal
            binImag[toneIndex] = newImag

            currentPipeReal[toneIndex] = newReal
            currentPipeImag[toneIndex] = newImag
        }

        pipePointer = (pipePointer + 1) % (2 * windowLength)
        if (processedSampleCount < windowLength) processedSampleCount++
    }

    /**
     * Returns the magnitude (not squared) of DFT bin [toneIndex] from the most
     * recently processed sample.
     *
     * Equivalent to abs(bins[currsymbol]) in fldigi's harddecode().
     *
     * @param toneIndex Index within [0, toneCount), where 0 = lowest tone.
     */
    fun getBinMagnitude(toneIndex: Int): Double =
        sqrt(binReal[toneIndex] * binReal[toneIndex] + binImag[toneIndex] * binImag[toneIndex])

    /**
     * Copies all current bin complex values into the provided arrays.
     *
     * Called once per symbol decision to supply [toneCount] complex values
     * to harddecode() and softdecode(). Index i = tone frequency i.
     *
     * @param outReal Destination for real parts. Must have length >= [toneCount].
     * @param outImag Destination for imaginary parts. Must have length >= [toneCount].
     */
    fun copyCurrentBins(outReal: DoubleArray, outImag: DoubleArray)
    {
        binReal.copyInto(outReal, endIndex = toneCount)
        binImag.copyInto(outImag, endIndex = toneCount)
    }

    /**
     * Copies the pipe buffer entry at [stepsBack] positions before the current pointer.
     *
     * Used by symbol timing recovery (synchronize) and AFC (afc).
     *
     * @param stepsBack Steps backward from the current write position.
     *                  Must be in [1, 2 * windowLength].
     * @param outReal   Destination for real parts. Must have length >= [toneCount].
     * @param outImag   Destination for imaginary parts. Must have length >= [toneCount].
     */
    fun getPipeEntry(stepsBack: Int, outReal: DoubleArray, outImag: DoubleArray)
    {
        require(stepsBack in 1..(2 * windowLength)) {
            "stepsBack must be in [1, ${2 * windowLength}], got $stepsBack"
        }
        val position = (pipePointer - stepsBack + 2 * windowLength) % (2 * windowLength)
        pipeReal[position].copyInto(outReal, endIndex = toneCount)
        pipeImag[position].copyInto(outImag, endIndex = toneCount)
    }

    /**
     * Returns the magnitude of a specific tone bin from a historical pipe entry.
     *
     * Used in synchronize() to scan energy across time positions:
     *   for i in 0 until 2*windowLength: getPipeEntryBinMagnitude(i+1, prevSymbolIndex)
     *
     * @param stepsBack  Steps backward from the current write position.
     * @param toneIndex  Tone bin index within [0, toneCount).
     */
    fun getPipeEntryBinMagnitude(stepsBack: Int, toneIndex: Int): Double
    {
        val position = (pipePointer - stepsBack + 2 * windowLength) % (2 * windowLength)
        val r = pipeReal[position][toneIndex]
        val i = pipeImag[position][toneIndex]
        return sqrt(r * r + i * i)
    }

    /**
     * Returns the real and imaginary parts of a specific tone bin from a historical
     * pipe entry, without copying the full vector.
     *
     * Used in AFC to compare complex phase between consecutive entries:
     *   prevVector = getPipeEntryBin(2, currsymbol) (one step before current)
     *   currVector = bins[currsymbol]               (current)
     *   z = conj(prevVector) * currVector
     *   frequencyError = arg(z) * sampleRate / 2π
     */
    fun getPipeEntryBin(stepsBack: Int, toneIndex: Int, outReal: DoubleArray, outImag: DoubleArray)
    {
        val position = (pipePointer - stepsBack + 2 * windowLength) % (2 * windowLength)
        outReal[0] = pipeReal[position][toneIndex]
        outImag[0] = pipeImag[position][toneIndex]
    }

    /**
     * Returns true once [windowLength] samples have been processed.
     * Before this point, the delay buffer is not fully primed and bin values
     * reflect startup transients rather than the actual signal.
     */
    fun isStable(): Boolean = processedSampleCount >= windowLength

    /**
     * Resets all bin values, delay buffer, and pipe buffer to zero.
     * Call before starting a new receive session.
     */
    fun reset()
    {
        binReal.fill(0.0)
        binImag.fill(0.0)
        delayReal.fill(0.0)
        delayImag.fill(0.0)
        for (i in 0 until 2 * windowLength)
        {
            pipeReal[i].fill(0.0)
            pipeImag[i].fill(0.0)
        }
        delayPointer = 0
        pipePointer = 0
        processedSampleCount = 0
    }
}