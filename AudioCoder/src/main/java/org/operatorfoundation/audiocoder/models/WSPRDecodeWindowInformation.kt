package org.operatorfoundation.audiocoder.models

/**
 * Information about an upcoming WSPR decode window.
 * Provides timing details for scheduling and display purposes.
 */
data class WSPRDecodeWindowInformation(
    /** Absolute timestamp when the decode window will open (milliseconds since epoch) */
    val nextWindowStartTime: Long,

    /** Time remaining until the decode window opens (milliseconds) */
    val millisecondsUntilWindow: Long,

    /** Time remaining until the decode window opens (seconds) */
    val secondsUntilWindow: Long
)
{
    /** Human-readable description of when the next window will occur. */
    val humanReadableDescription: String
        get() = when {
            secondsUntilWindow == 0L -> "Decode window is open now."
            secondsUntilWindow < 60L -> "Next decode window in ${secondsUntilWindow} seconds."
            else -> {
                val minutes = secondsUntilWindow / 60L
                val seconds = secondsUntilWindow % 60L
                "Next decode window in ${minutes}m ${seconds}s."
            }
        }
}
