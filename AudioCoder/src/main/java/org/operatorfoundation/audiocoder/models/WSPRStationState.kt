package org.operatorfoundation.audiocoder.models

/**
 * Represents the current state of WSPR station operation.
 * Provides detailed information about what the station is currently doing.
 */
sealed class WSPRStationState
{
    /** Station is not running */
    object Stopped : WSPRStationState()

    /** Station is initializing and starting up */
    object Starting : WSPRStationState()

    /** Station is running and monitoring for decode opportunities */
    object Running : WSPRStationState()

    /** Station is shutting down */
    object Stopping : WSPRStationState()

    /**
     * Station is waiting for the next WSPR decode window to begin.
     * @param windowInfo Information about the upcoming decode window
     */
    data class WaitingForNextWindow(val windowInfo: WSPRDecodeWindowInformation) : WSPRStationState()

    /** Station is preparing to collect audio (clearing buffers, etc.) */
    object PreparingForCollection : WSPRStationState()

    /** Station is actively collecting audio for decode */
    object CollectingAudio : WSPRStationState()

    /** Station is processing collected audio through the WSPR decoder */
    object ProcessingAudio : WSPRStationState()

    /**
     * Decode cycle completed successfully.
     * @param decodedSignalCount Number of WSPR signals found in this cycle
     */
    data class DecodeCompleted(val decodedSignalCount: Int) : WSPRStationState()

    /**
     * Station encountered an error and requires attention.
     * @param errorDescription Human-readable description of the error
     */
    data class Error(val errorDescription: String) : WSPRStationState()
}
