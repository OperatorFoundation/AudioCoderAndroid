package org.operatorfoundation.audiocoder.mfsk_andflmsg

/**
 * Outcome of a call to [MFSKAndFlmsgEncoder.encode].
 */
sealed class MFSKAndFlmsgEncodeResult
{
    /**
     * Encoding succeeded.
     *
     * @param symbolFrequenciesCHz Tone frequencies in centihertz, in transmission order.
     * @param symbolDurationMs     Duration of each tone in milliseconds.
     *                             Derived from the fldigi modem's symbol length:
     *                             512 samples at 8000 Hz = 64 ms for MFSK-16.
     */
    data class Success(
        val symbolFrequenciesCHz: LongArray,
        val symbolDurationMs: Long
    ) : MFSKAndFlmsgEncodeResult()

    /** The engine is already acquired by another caller. */
    object Busy : MFSKAndFlmsgEncodeResult()

    /**
     * Encoding failed.
     * @param reason Human-readable explanation, suitable for logging.
     */
    data class Failed(val reason: String) : MFSKAndFlmsgEncodeResult()
}