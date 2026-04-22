package org.operatorfoundation.audiocoder.mfsk

/**
 * Operational state of an [MFSKStation].
 *
 * Unlike WSPR, MFSK has no timing windows — the station transitions directly from
 * [Starting] to [Listening] and remains there until stopped or an error occurs.
 * There is no distinct "receiving" state because, without a preamble or frame-start
 * marker, the station cannot reliably detect whether it is mid-message or waiting
 * for one to begin.
 */
sealed class MFSKStationState
{
    /** Station is not running. Initial state and state after a clean stop. */
    object Idle : MFSKStationState()

    /** Station is initializing its audio source and preparing to decode. */
    object Starting : MFSKStationState()

    /** Station is active and processing incoming audio. */
    object Listening : MFSKStationState()

    /**
     * Station encountered an unrecoverable error.
     * @param cause The exception that caused the failure.
     */
    data class Error(val cause: Throwable) : MFSKStationState()
}