package org.operatorfoundation.audiocoder

data class WSPRDecodeResult(
    /**
     * Amateur radio callsign of the transmitting station.
     *
     * Examples: "W1AW", "JA1XYZ", "DL0ABC"
     * Standard amateur radio callsign format as assigned by national authorities.
     * May include portable/mobile indicators like "/P" or "/M".
     */
    val callsign: String,

    /**
     * Maidenhead grid square locator indicating station location.
     *
     * Examples: "FN31", "JO65", "PM96"
     * 4-character or 6-character Maidenhead locator system coordinate.
     * Provides approximate geographic location for propagation analysis.
     */
    val gridSquare: String,

    /**
     * Transmit power level in dBm (decibels relative to 1 milliwatt).
     *
     * Common values: 0, 3, 7, 10, 13, 17, 20, 23, 27, 30, 33, 37, 40, 43, 47, 50, 53, 57, 60
     * Higher values indicate more transmit power.
     * WSPR protocol encodes specific power levels, not arbitrary values.
     */
    val powerLevelDbm: Int,

    /**
     * Signal-to-noise ratio in dB at the receiving station.
     *
     * Typical range: -30 dB to +10 dB
     * Negative values are common and indicate weak signal conditions.
     * More negative values indicate weaker signals relative to noise floor.
     */
    val signalToNoiseRatioDb: Float,

    /**
     * Frequency offset from the expected signal frequency in Hz.
     *
     * Typical range: ±200 Hz from center frequency (1500 Hz)
     * Indicates transmitter frequency accuracy and drift.
     * Used for automatic frequency correction and propagation analysis.
     */
    val frequencyOffsetHz: Double,

    /**
     * Complete decoded message as received.
     *
     * Format: "CALLSIGN GRID POWER"
     * Example: "W1AW FN31 30"
     * Contains the raw decoded message for verification and logging.
     */
    val completeMessage: String,

    /**
     * Timestamp when this message was decoded (milliseconds since Unix epoch).
     *
     * Used for:
     * - Chronological sorting of decode results
     * - Time-based analysis of propagation
     * - Duplicate detection across decode cycles
     */
    val decodeTimestamp: Long
)
{

}
