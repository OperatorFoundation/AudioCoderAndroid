package org.operatorfoundation.audiocoder.models

import org.operatorfoundation.audiocoder.WSPRBandplan

/**
 * Configuration parameters for WSPR station operation.
 * Contains all user-configurable settings and operating parameters.
 */
data class WSPRStationConfiguration(
    /** WSPR operating frequency in MHz (e.g., 14.0956 for 20m band) */
    val operatingFrequencyMHz: Double,

    /** Whether to user Lower Sideband mode (LSB) instead of Upper Sideband mode (USB) */
    val useLowerSidebandMode: Boolean,

    /** Whether to use time-aligned decoding windows vs sliding windows */
    val useTimeAlignedDecoding: Boolean,

    /** Station callsign for identification (optional) */
    val stationCallsign: String?,

    /** Station Maidenhead grid square location (optional) */
    val stationGridSquare: String?
)
{
    companion object
    {
        /**
         * Creates a default configuration suitable for most WSPR operations.
         * Uses 20m band (most popular), USB mode, and time-aligned decoding.
         */
        fun createDefault(): WSPRStationConfiguration
        {
            return WSPRStationConfiguration(
                operatingFrequencyMHz = WSPRBandplan.getDefaultFrequency(),
                useLowerSidebandMode = false,
                useTimeAlignedDecoding = true,
                stationCallsign = null,
                stationGridSquare = null
            )
        }

        /**
         * Creates configuration for a specific WSPR band.
         *
         * @param bandName Band identifier (e.g., "20m", "40m", "80m")
         * @return Configuration for the specified band, or default if band not found
         */
        fun createForBand(bandName: String): WSPRStationConfiguration
        {
            val band = WSPRBandplan.ALL_BANDS.find { it.name.equals(bandName, ignoreCase = true) }
                ?: WSPRBandplan.ALL_BANDS.first { it.isPopular }

            return WSPRStationConfiguration(
                operatingFrequencyMHz = band.dialFrequencyMHz,
                useLowerSidebandMode = false,
                useTimeAlignedDecoding = true,
                stationCallsign = null,
                stationGridSquare = null
            )
        }
    }
}