package org.operatorfoundation.audiocoder.models

import org.operatorfoundation.audiocoder.WSPRTimingConstants
import org.operatorfoundation.audiocoder.WSPRTimingConstants.WSPR_CYCLE_DURATION_SECONDS

/**
 * Real-time information about the current WSPR cycle state.
 */
data class WSPRCycleInformation(
    /** Current position within the 2-minute WSPR cycle (0-119 seconds) */
    val cyclePositionSeconds: Int,

    /** True if currently in an even minute (transmission window) */
    val isInTransmissionWindow: Boolean,

    /** True if a WSPR transmission is currently active */
    val isTransmissionActive: Boolean,

    /** True if decode operations are currently permitted. */
    val isDecodeWindowOpen: Boolean,

    /** Information about the next decode opportunity */
    val nextDecodeWindowInfo: WSPRDecodeWindowInformation
)
{
    /**
     * Human-readable description of current cycle state for UI dislay.
     */
    val currentStateDescription: String
        get() = when {
            isDecodeWindowOpen -> "🔍 Decode window open (${cyclePositionSeconds}s/120s)"
            isTransmissionActive -> "📡 Transmission active (${cyclePositionSeconds}s/120s)"
            isInTransmissionWindow -> "⏳ Transmission window, waiting for decode start (${cyclePositionSeconds}s/120s)"
            else -> "🔇 Silent period (${cyclePositionSeconds}s/120s)"
        }

    /**
     * Progress through current 2-minute cycle as a percentage (0.0 - 1.0).
     */
    val cycleProgressPercentage: Float
        get() = cyclePositionSeconds.toFloat() / WSPR_CYCLE_DURATION_SECONDS.toFloat()
}
