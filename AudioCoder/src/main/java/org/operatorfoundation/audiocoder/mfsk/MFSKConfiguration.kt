package org.operatorfoundation.audiocoder.mfsk

/**
 * Configuration for an [MFSKStation].
 *
 * @param mode            MFSK modulation mode (tone count, baud rate, spacing).
 * @param baseFrequencyHz Frequency of tone index 0 in Hz. All other tones are placed at
 *                        integer multiples of [MFSKMode.toneSpacingHz] above this value.
 * @param sampleRate      Audio pipeline sample rate in Hz. 12000 Hz is strongly recommended —
 *                        it divides cleanly into all standard MFSK baud rates with no rounding
 *                        error, and matches the rate SignalBridge already delivers for WSPR.
 * @param amplitude       Transmit output level as a fraction of full scale, in [0.0, 1.0].
 * @param timeoutMs       Maximum time in milliseconds to wait for a complete message before
 *                        abandoning the current receive attempt. At MFSK-16's ~1.95 bytes/second,
 *                        a standard 40-byte Nahoft encrypted message transmits in ~21 seconds.
 *                        The default of 60 000 ms provides ~2.5× margin for timing jitter and
 *                        marginal signal conditions.
 */
data class MFSKConfiguration(
    val mode: MFSKMode,
    val baseFrequencyHz: Double,
    val sampleRate: Int   = 12_000,
    val amplitude: Double = 0.5,
    val timeoutMs: Long   = 60_000L
)