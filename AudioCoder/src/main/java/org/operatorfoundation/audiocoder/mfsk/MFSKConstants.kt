package org.operatorfoundation.audiocoder.mfsk

object MFSKConstants
{
    /**
     * Recommended audio pipeline sample rate in Hz.
     *
     * Divides cleanly into all standard MFSK baud rates with no rounding error.
     */
    const val MFSK_RECOMMENDED_SAMPLE_RATE = 12_000

    /** Default transmit output level as a fraction of full scale, in [0.0, 1.0].
     *  Leaves headroom for signal processing while maintaining adequate output level. */
    const val MFSK_DEFAULT_AMPLITUDE = 0.5

    /** Default receive timeout in milliseconds.
     *  At MFSK-16's ~1.95 bytes/second, a 40-byte payload transmits in ~21 seconds.
     *  60 seconds provides approximately 2.5× margin for timing and signal variation. */
    const val MFSK_DEFAULT_TIMEOUT_MS = 60_000L
}