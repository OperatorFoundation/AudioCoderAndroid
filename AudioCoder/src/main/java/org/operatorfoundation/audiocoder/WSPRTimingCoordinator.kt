package org.operatorfoundation.audiocoder

import android.icu.util.Calendar
import org.operatorfoundation.audiocoder.WSPRTimingConstants.DECODE_WINDOW_END_SECOND

import org.operatorfoundation.audiocoder.WSPRTimingConstants.MINUTES_PER_HOUR
import org.operatorfoundation.audiocoder.WSPRTimingConstants.MINUTES_PER_WSPR_CYCLE
import org.operatorfoundation.audiocoder.models.WSPRCycleInformation
import org.operatorfoundation.audiocoder.models.WSPRDecodeWindowInformation

/**
 * Manages WSPR protocol timing and synchronization with the global amateur radio schedule.
 *
 * The WSPR protocol operates on a precise2-minute cycle synchronized worldwide:
 * - Even minutes: Active transmission/reception window
 * - Odd minutes: Silent periods for frequency coordination
 *
 * This class provides:
 * - Calculation of optimal decode timing windows.
 * - Validation of current timing for decode attempts.
 * - Real-time cycle position information for UI display.
 * - Next window scheduling for automatic operation.
 *
 * All timing calculations are based on system clock and assume reasonable accuracy.
 * For professional WSPR operations, consider GPS synchronization for maximum precision.
 *
 * Example Usage:
 * val coordinator = WSPRTimingCoordinator()
 *
 * if (coordinator.isCurrentlyInValidDecodeWindow())
 * {
 *     // Safe to attempt decode
 *     performDecode()
 * }
 * else
 * {
 *     val nextWindow = coordinator.getTimeUntilNextDecodeWindow()
 *     println("Next decode window in ${nextWindow.secondsUntilWindow} seconds")
 * }
 *
 */
class WSPRTimingCoordinator
{
    /**
     * Calculates the absolute timestamp when the next WSPR decode window should begin.
     *
     * WSPR decode windows start 2 seconds after transmission begins to allow
     * the transmitting station to stabilize. This gives the best decoding conditions
     * while ensuring we capture the complete transmission.
     *
     * The calculation logic:
     * 1. Find the next even minute boundary.
     * 2. Add the standard decode start delay.
     * 3. Handl edge cases like hour/day boundaries.
     *
     * @return Absolute timestamp in milliseconds (epoch time) when next decode should start.
     */
    fun calculateNextDecodeWindowStartTime(): Long
    {
        val currentTime = System.currentTimeMillis()
        val currentTimeCalendar = Calendar.getInstance()
        currentTimeCalendar.timeInMillis = currentTime

        val currentMinuteInHour = currentTimeCalendar.get(Calendar.MINUTE)
        val currentSecondInMinute = currentTimeCalendar.get(Calendar.SECOND)

        // Determine the next even minute when we should start decoding
        val nextDecodeMinute = calculateNextEvenMinuteForDecode(currentMinuteInHour, currentSecondInMinute)


        // Build the target decode start time
        val decodeStartCalendar = Calendar.getInstance()
        decodeStartCalendar.timeInMillis = currentTime
        decodeStartCalendar.set(Calendar.MINUTE, nextDecodeMinute % MINUTES_PER_HOUR)
        decodeStartCalendar.set(Calendar.SECOND, WSPRTimingConstants.DECODE_START_DELAY_SECONDS.toInt())
        decodeStartCalendar.set(Calendar.MILLISECOND, 0)

        // Handle hour boundary crossing
        if (nextDecodeMinute >= MINUTES_PER_HOUR)
        {
            decodeStartCalendar.add(Calendar.HOUR_OF_DAY, 1)
        }

        return decodeStartCalendar.timeInMillis
    }

    /**
     * Determines if the current time falls within a valid WSPR decode window.
     *
     * A valid decode window exists when:
     * 1. We are in an even minute (transmission window).
     * 2. We are at least 2 seconds past the minute start (transmission has stabilized).
     * 3. We are not past the end of the decode collection period (116 seconds into the 2-minute cycle)
     *
     * This prevents decode attempts during:
     * - Silent periods (odd minutes)
     * - Transmission startup (first 2 seconds of even minutes)
     * - After the decode window has closed (beyond 116 seconds into the cycle)
     *
     * @return true if decode can be attempted now, false otherwise
     */
    fun isCurrentlyInValidDecodeWindow(): Boolean
    {
        val currentTimeCalendar = Calendar.getInstance()
        val currentMinuteInHour = currentTimeCalendar.get(Calendar.MINUTE)
        val currentSecondInMinute = currentTimeCalendar.get(Calendar.SECOND)

        // Cneck if we're in an even minute (transmission window)
        val isEvenMinute = (currentMinuteInHour % WSPRTimingConstants.MINUTES_PER_WSPR_CYCLE == 0)

        // Calculate position within the current 2-minute WSPR cycle (0-119 seconds)
        val cyclePositionSeconds = calculatePositionInCurrentWSPRCycle(currentMinuteInHour, currentSecondInMinute)

        // Check timing constraints within the full 2-minute cycle
        val isPastDecodeStartDelay = (cyclePositionSeconds >= WSPRTimingConstants.DECODE_START_DELAY_SECONDS)
        val isBeforeDecodeWindowEnd = (cyclePositionSeconds <= WSPRTimingConstants.DECODE_WINDOW_END_SECOND)

        return isEvenMinute && isPastDecodeStartDelay && isBeforeDecodeWindowEnd
    }

    /**
     * Calculates the current position within the active 2-minute WSPR cycle.
     *
     * WSPR cycles are 2 minutes long (120 seconds total):
     * - Even minute (0-59 seconds): Active transmission/reception window
     * - Odd minute (60-119 seconds): Silent period
     *
     * @param currentMinute Current minute (0-59)
     * @param currentSecond Current second (0-59)
     * @return Position in seconds (0-119) within the current 2-minute cycle
     */
    private fun calculatePositionInCurrentWSPRCycle(currentMinute: Int, currentSecond: Int): Int
    {
        val minuteInCycle = currentMinute % WSPRTimingConstants.MINUTES_PER_WSPR_CYCLE
        return minuteInCycle * WSPRTimingConstants.SECONDS_PER_MINUTE + currentSecond
    }

    /**
     * Alternative helper method to get cycle position for external use
     */
    fun getCurrentCyclePosition(): Int
    {
        val currentTimeCalendar = Calendar.getInstance()
        val currentMinuteInHour = currentTimeCalendar.get(Calendar.MINUTE)
        val currentSecondInMinute = currentTimeCalendar.get(Calendar.SECOND)
        return calculatePositionInCurrentWSPRCycle(currentMinuteInHour, currentSecondInMinute)
    }

    /**
     * Calculates detailed information about when the next decode window will occur.
     *
     * This method provides comprehensive timing information for:
     * - Scheduling automatic decode operations
     * - Displaying countdown timers in user interfaces
     * - Logging and diagnostic purposes
     *
     * @return Complete information about the upcoming decode window
     */
    fun getTimeUntilNextDecodeWindow(): WSPRDecodeWindowInformation
    {
        val currentTime = System.currentTimeMillis()
        val nextDecodeStartTime = calculateNextDecodeWindowStartTime()
        val millisecondsUntilWindow = nextDecodeStartTime - currentTime

        return WSPRDecodeWindowInformation(
            nextWindowStartTime = nextDecodeStartTime,
            millisecondsUntilWindow = millisecondsUntilWindow.coerceAtLeast(0L),
            secondsUntilWindow = (millisecondsUntilWindow / 1000L).coerceAtLeast(0L)
        )
    }

    /**
     * Provides real-time information about the current position within the WSPR cycle.
     *
     * This information is valuable for:
     * - User interface displays showing cycle progress
     * - Logging and debugging timing issues
     * - Monitoring station synchronization with the global WSPR schedule
     *
     * @return Current cycle position and status information
     */
    fun getCurrentCycleInformation(): WSPRCycleInformation
    {
        val currentTimeCalendar = Calendar.getInstance()
        val currentMinuteInHour = currentTimeCalendar.get(Calendar.MINUTE)
        val currentSecondInMinute = currentTimeCalendar.get(Calendar.SECOND)

        // Calculate position within the current 2-minute WSPR cycle
        val positionInCurrentCycle = calculatePositionInCurrentWSPRCycle(currentMinuteInHour, currentSecondInMinute)

        // Determine current transmission status
        val isCurrentlyTransmissionWindow = (currentMinuteInHour % MINUTES_PER_WSPR_CYCLE == 0)
        val isCurrentlyInTransmission = isCurrentlyTransmissionWindow &&
                (currentSecondInMinute <= WSPRTimingConstants.WSPR_TRANSMISSION_DURATION_SECONDS)

        // Get next decode window information
        val nextDecodeWindowInfo = getTimeUntilNextDecodeWindow()

        return WSPRCycleInformation(
            cyclePositionSeconds = positionInCurrentCycle,
            isInTransmissionWindow = isCurrentlyTransmissionWindow,
            isTransmissionActive = isCurrentlyInTransmission,
            isDecodeWindowOpen = isCurrentlyInValidDecodeWindow(),
            nextDecodeWindowInfo = nextDecodeWindowInfo
        )
    }

    // ========== Private Helper Methods ==========

    /**
     * Determines the next even minute suitable for starting a decode operation.
     *
     * Logic:
     * - If currently in an even minute and early enough, use current minute
     * - If currently in an even minute but too late, use next even minute
     * - If currently in an odd minute, use the next minute (which will be even)
     *
     * @param currentMinute Current minute (0-59)
     * @param currentSecond Current second (0-59)
     * @return Next suitable minute for decode start (may exceed 59 for hour boundary)
     */
    private fun calculateNextEvenMinuteForDecode(currentMinute: Int, currentSecond: Int): Int {
        return if (currentMinute % MINUTES_PER_WSPR_CYCLE == 0)
        {
            // Currently in an even minute (transmission window)
            if (currentSecond >= DECODE_WINDOW_END_SECOND)
            {
                // Too late in current window, use next even minute
                currentMinute + MINUTES_PER_WSPR_CYCLE
            }
            else
            {
                // Can still use current window
                currentMinute
            }
        }
        else
        {
            // Currently in odd minute (silent period), next minute will be even
            currentMinute + 1
        }
    }

}