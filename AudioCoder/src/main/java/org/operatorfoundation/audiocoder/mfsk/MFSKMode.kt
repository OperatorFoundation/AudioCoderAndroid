package org.operatorfoundation.audiocoder.mfsk

import kotlin.math.log2
import kotlin.math.roundToInt

/**
 * Describes the modulation parameters for a member of the MFSK (Multiple Frequency Shift Keying)
 * mode family.
 *
 * MFSK encodes data by transmitting one of N discrete tones per symbol period. Each object in this
 * sealed class represents one standardized mode, parameterized by tone count, baud rate, and tone
 * spacing. All other properties are derived from these three values.
 *
 * ## Orthogonality requirement
 * For coherent MFSK, tones must be spaced at exactly the baud rate in Hz. All modes here satisfy
 * this constraint ([toneSpacingHz] == [baudRate]), ensuring that the Goertzel filters in the
 * decoder can distinguish tones without inter-tone interference.
 *
 * [MFSKMode] is a pure description of the modulation scheme. It is intentionally independent of:
 * - **Sample rate** — a property of the audio pipeline; belongs in `MFSKConfiguration`
 * - **Center / base frequency** — a property of the transmission configuration
 * - **Framing, preamble, varicode** — protocol layer concerns; belong in encoder/decoder
 *
 * ## Baud rate values
 * MFSK-8 and MFSK-16 share the same baud rate (15.625 Hz) and tone spacing, but MFSK-16
 * encodes 4 bits per symbol versus 3 for MFSK-8, giving higher throughput at the cost of
 * wider bandwidth (250 Hz vs 125 Hz).
 *
 * @property toneCount      Number of discrete tones. Must be a power of 2.
 * @property baudRate       Symbol rate in symbols/second. Equal to [toneSpacingHz].
 * @property toneSpacingHz  Frequency gap between adjacent tones in Hz. Equal to [baudRate].
 */
sealed class MFSKMode(
    val toneCount: Int,
    val baudRate: Double,
    val toneSpacingHz: Double
) {

    // -------------------------------------------------------------------------
    // Standard mode objects
    // -------------------------------------------------------------------------

    /**
     * MFSK-8: 8 tones, 15.625 baud, 125 Hz bandwidth.
     * Narrowest bandwidth and lowest throughput in the family.
     * Suitable for weak-signal HF work where spectrum is constrained.
     */
    object MFSK8 : MFSKMode(
        toneCount      = 8,
        baudRate       = 15.625,
        toneSpacingHz  = 15.625
    )

    /**
     * MFSK-16: 16 tones, 15.625 baud, 250 Hz bandwidth.
     * The most common MFSK mode. Same baud rate as [MFSK8] but carries 4 bits per symbol
     * instead of 3. Primary target mode for Nahoft.
     */
    object MFSK16 : MFSKMode(
        toneCount      = 16,
        baudRate       = 15.625,
        toneSpacingHz  = 15.625
    )

    /**
     * MFSK-32: 32 tones, 31.25 baud, 1000 Hz bandwidth.
     * Higher throughput than MFSK-16 at the cost of wider bandwidth.
     */
    object MFSK32 : MFSKMode(
        toneCount      = 32,
        baudRate       = 31.25,
        toneSpacingHz  = 31.25
    )

    /**
     * MFSK-64: 64 tones, 62.5 baud, 4000 Hz bandwidth.
     * High throughput; occupies the full width of an SSB passband.
     * Practical for wideband HF or VHF links.
     */
    object MFSK64 : MFSKMode(
        toneCount      = 64,
        baudRate       = 62.5,
        toneSpacingHz  = 62.5
    )

    /**
     * MFSK-128: 128 tones, 125 baud, 16,000 Hz bandwidth.
     * Highest throughput in the family.
     *
     * NOTE: Bandwidth of 16 kHz makes this mode unsuitable for HF/SSB.
     * Intended for wideband VHF/UHF links.
     *
     * TODO: Verify baud rate (125.0 Hz) against fldigi source before use.
     *       This value follows the doubling pattern from MFSK-32 onward but has
     *       not been confirmed against an authoritative reference.
     */
    object MFSK128 : MFSKMode(
        toneCount      = 128,
        baudRate       = 125.0,
        toneSpacingHz  = 125.0
    )

    // -------------------------------------------------------------------------
    // Derived properties (computed once at construction time)
    // -------------------------------------------------------------------------

    /**
     * Number of bits encoded per symbol. Derived as log₂([toneCount]).
     *
     * | Mode     | bitsPerSymbol |
     * |----------|---------------|
     * | MFSK-8   | 3             |
     * | MFSK-16  | 4             |
     * | MFSK-32  | 5             |
     * | MFSK-64  | 6             |
     * | MFSK-128 | 7             |
     */
    val bitsPerSymbol: Int = log2(toneCount.toDouble()).roundToInt()

    /**
     * Duration of one symbol in seconds. Derived as 1 / [baudRate].
     *
     * This is the canonical symbol period used by both the encoder (to determine how long
     * to sustain each tone) and the decoder (to size the Goertzel filter window). Exposing
     * it here avoids redundant calculation in each consumer.
     */
    val symbolDurationSeconds: Double = 1.0 / baudRate

    /**
     * Total occupied bandwidth in Hz. Derived as [toneCount] × [toneSpacingHz].
     *
     * Use this before transmitting to verify the signal fits within the available passband.
     * For example, a standard 3 kHz SSB passband can accommodate MFSK-16 (250 Hz) and
     * MFSK-32 (1000 Hz), but not MFSK-128 (16,000 Hz).
     */
    val bandwidthHz: Double = toneCount * toneSpacingHz

    /**
     * Human-readable mode label, e.g. "MFSK-16". Used for logging and UI display.
     */
    val label: String = "MFSK-$toneCount"

    // -------------------------------------------------------------------------
    // Sample-rate-dependent helper
    // -------------------------------------------------------------------------

    /**
     * Number of audio samples corresponding to one symbol period at [sampleRate].
     *
     * This is the primary bridge between [MFSKMode] and the audio pipeline. Both the Goertzel
     * filter (which evaluates exactly this many samples per symbol) and the encoder (which
     * generates exactly this many samples of each tone) depend on this value.
     *
     * The result is rounded to the nearest integer. Rounding error accumulates slowly over a
     * transmission; for MFSK-16 at 12 kHz, the exact value is 768.0, so there is no error.
     *
     * @param sampleRate Audio pipeline sample rate in Hz (e.g. 12000).
     * @return Sample count per symbol at the given rate.
     */
    fun samplesPerSymbol(sampleRate: Int): Int = (sampleRate / baudRate).toInt()

    // -------------------------------------------------------------------------
    // Standard overrides
    // -------------------------------------------------------------------------

    override fun toString(): String = label
}