package org.operatorfoundation.audiocoder.models

import android.icu.util.Calendar
import org.operatorfoundation.audiocoder.extensions.format
import org.operatorfoundation.audiocoder.extensions.formatOffset
import kotlin.math.abs

/**
 * Represents a successfully decoded WSPR message with all associated metadata.
 *
 * WSPR (Weak Signal Propagation Reporter) messages contain standardized information
 * about the transmitting station, propagation conditions, and signal characteristics.
 *
 * A typical WSPR message contains:
 * - Station identification (callsign)
 * - Location information (Maidenhead grid square)
 * - Transmit power level (in dBm)
 * - Reception quality metrics (SNR, frequency offset)
 * - Decode timing information
 *
 * Example usage:
 * val result = WSPRDecodeResult(
 *     callsign = "W1AW",
 *     gridSquare = "FN31",
 *     powerLevelDbm = 30,
 *     signalToNoiseRatioDb = -15.2f,
 *     frequencyOffsetHz = 1456.3,
 *     completeMessage = "W1AW FN31 30",
 *     decodeTimestamp = System.currentTimeMillis()
 * )
 *
 * println("Station ${result.callsign} in ${result.gridSquare}")
 * println("Signal quality: ${result.signalQualityDescription}")
 */
data class WSPRDecodeResult(
    /**
     * Amateur radio callsign of the transmitting station.
     *
     * Examples: "Q0QQQ"
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
     * Example: "Q0QQQ FN31 30"
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
    /**
     * Human-readable description of signal quality based on SNR.
     */
    val signalQualityDescription: String
        get() = when {
            signalToNoiseRatioDb >= 0 -> "Excellent (${signalToNoiseRatioDb.format(1)} dB)"
            signalToNoiseRatioDb >= -10 -> "Good (${signalToNoiseRatioDb.format(1)} dB)"
            signalToNoiseRatioDb >= -20 -> "Fair (${signalToNoiseRatioDb.format(1)} dB)"
            signalToNoiseRatioDb >= -30 -> "Weak (${signalToNoiseRatioDb.format(1)} dB)"
            else -> "Very weak (${signalToNoiseRatioDb.format(1)} dB)"
        }

    /**
     * Transmit power converted to watts.
     * Calculated from dBm using standard conversion formula: P(W) = 10^((P(dBm) - 30) / 10)
     */
    val transmitPowerWatts: Double
        get() = Math.pow(10.0, (powerLevelDbm - 30.0) / 10.0)

    /**
     * Human-readable power level description.
     */
    val powerLevelDescription: String
        get() = when {
            transmitPowerWatts < 0.001 -> "QRP (${transmitPowerWatts.format(3)} W)"
            transmitPowerWatts < 0.01 -> "Low power (${transmitPowerWatts.format(3)} W)"
            transmitPowerWatts < 0.1 -> "Medium power (${transmitPowerWatts.format(2)} W)"
            transmitPowerWatts < 1.0 -> "High power (${transmitPowerWatts.format(1)} W)"
            else -> "Very high power (${transmitPowerWatts.format(0)} W)"
        }

    /**
     * Formatted frequency display showing offset from WSPR center frequency.
     * Displays the actual received frequency for technical analysis.
     */
    val displayFrequency: String
        get() {
            val centerFrequencyHz = 1500.0 // WSPR center frequency
            val actualFrequencyHz = centerFrequencyHz + frequencyOffsetHz
            return "${actualFrequencyHz.format(1)} Hz (${frequencyOffsetHz.formatOffset()} Hz)"
        }

    /**
     * Formatted timestamp for display purposes.
     * Shows decode time in local timezone with standard format.
     */
    val formattedDecodeTime: String
        get() {
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = decodeTimestamp
            return String.format(
                "%04d-%02d-%02d %02d:%02d:%02d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH),
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
                calendar.get(Calendar.SECOND)
            )
        }

    /**
     * Creates a summary string for logging or display.
     */
    fun createSummaryLine(): String {
        return "$callsign ($gridSquare) ${powerLevelDbm}dBm SNR:${signalToNoiseRatioDb.format(1)}dB"
    }

    /**
     * Creates detailed information for technical analysis.
     * Includes all available data for comprehensive record keeping.
     */
    fun createDetailedReport(): String {
        return buildString {
            appendLine("WSPR Decode Result")
            appendLine("================")
            appendLine("Callsign: $callsign")
            appendLine("Grid Square: $gridSquare")
            appendLine("Power: $powerLevelDbm dBm ($powerLevelDescription)")
            appendLine("Signal Quality: $signalQualityDescription")
            appendLine("Frequency: $displayFrequency")
            appendLine("Complete Message: '$completeMessage'")
            appendLine("Decoded: $formattedDecodeTime")
        }
    }

    /**
     * Checks if this decode result represents the same transmission as another.
     * Useful for duplicate detection across multiple decode attempts.
     *
     * @param other Another decode result to compare
     * @param timeToleranceMs Acceptable time difference for considering results as duplicates
     * @return true if the results likely represent the same transmission
     */
    fun isSameTransmissionAs(other: WSPRDecodeResult, timeToleranceMs: Long = 5000L): Boolean
    {
        val timeDifference = abs(decodeTimestamp - other.decodeTimestamp)
        val frequencyDifference = abs(frequencyOffsetHz - other.frequencyOffsetHz)

        return callsign == other.callsign &&
                gridSquare == other.gridSquare &&
                powerLevelDbm == other.powerLevelDbm &&
                timeDifference <= timeToleranceMs &&
                frequencyDifference < 5.0 // Within 5 Hz frequency tolerance
    }

    companion object
    {
        /** Placeholder for unknown or invalid callsigns */
        const val UNKNOWN_CALLSIGN = "UNKNOWN"

        /** Placeholder for unknown or invalid grid squares */
        const val UNKNOWN_GRID_SQUARE = "????"

        /** Placeholder for empty or corrupted messages */
        const val EMPTY_MESSAGE = ""

        /**
         * Creates a decode result representing a failed or corrupted decode.
         * Used when the decoder detects a signal but cannot extract valid information.
         *
         * @param partialMessage Any partial message content that was recovered
         * @param snr Signal-to-noise ratio if available
         * @param frequency Frequency offset if available
         * @return Decode result marked as invalid/incomplete
         */
        fun createCorruptedDecode(
            partialMessage: String = EMPTY_MESSAGE,
            snr: Float = Float.NaN,
            frequency: Double = Double.NaN
        ): WSPRDecodeResult
        {
            return WSPRDecodeResult(
                callsign = UNKNOWN_CALLSIGN,
                gridSquare = UNKNOWN_GRID_SQUARE,
                powerLevelDbm = 0,
                signalToNoiseRatioDb = snr,
                frequencyOffsetHz = frequency,
                completeMessage = partialMessage,
                decodeTimestamp = System.currentTimeMillis()
            )
        }
    }
}