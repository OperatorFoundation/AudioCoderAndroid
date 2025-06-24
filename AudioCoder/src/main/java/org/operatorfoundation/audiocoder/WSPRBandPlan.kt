package org.operatorfoundation.audiocoder

/**
 * WSPR Band Plan containing official WSPR frequencies for amateur radio bands.
 *
 * These frequencies represent the dial frequencies where WSPR signals are transmitted,
 * based on the international WSPR band plan coordination.
 */
data class WSPRBand(
    val name: String,
    val dialFrequencyMHz: Double,
    val wavelengthMeters: String,
    val region: ITURegion = ITURegion.GLOBAL,
    val isPopular: Boolean = false
)
{
    /**
     * Gets a user-friendly display name for the band.
     */
    override fun toString(): String {
        return when (region)
        {
            ITURegion.GLOBAL -> name
            ITURegion.REGION_1 -> "$name (R1)"
            ITURegion.REGION_2 -> "$name (R2)"
            ITURegion.REGION_3 -> "$name (R3)"
        }
    }
}

/**
 * ITU Regions for amateur radio frequency coordination.
 */
enum class ITURegion
{
    GLOBAL,     // Used worldwide
    REGION_1,   // Europe, Africa, Middle East, Asia North of 40°N
    REGION_2,   // Americas,
    REGION_3,   // Asia south of 40°N, Oceania
}

/**
 * Official WSPR Band Plan
 *
 * This class provides access to the standardized WSPR frequencies used worldwide.
 * Frequencies are based on international amateur radio band plans and WSPR coordination.
 */
object WSPRBandplan
{
    /**
     * All official WSPR Bands with their dial frequencies.
     */
    val ALL_BANDS = listOf(
        // LF/MF Bands (requires special authorization in most countries)
        WSPRBand(name = "LF", dialFrequencyMHz = 0.136000, wavelengthMeters = "2200m", ITURegion.GLOBAL),
        WSPRBand(name = "MF", dialFrequencyMHz = 0.474200, wavelengthMeters = "630m", ITURegion.GLOBAL),

        // HF Bands (most popular for WSPR)
        WSPRBand(name = "160m", dialFrequencyMHz = 1.836600, wavelengthMeters = "160m", ITURegion.GLOBAL),
        WSPRBand(name = "80m", dialFrequencyMHz = 3.568600, wavelengthMeters = "80m", ITURegion.REGION_2),
        WSPRBand(name = "80m", dialFrequencyMHz = 3.592600, wavelengthMeters = "80m", ITURegion.REGION_1, isPopular = true),
        WSPRBand(name = "60m", dialFrequencyMHz = 5.287200, wavelengthMeters = "60m", ITURegion.REGION_2),
        WSPRBand(name = "60m", dialFrequencyMHz = 5.364700, wavelengthMeters = "60m", ITURegion.REGION_1),
        WSPRBand(name = "40m", dialFrequencyMHz = 7.038600, wavelengthMeters = "40m", ITURegion.GLOBAL, isPopular = true),
        WSPRBand(name = "30m", dialFrequencyMHz = 10.138700, wavelengthMeters = "30m", ITURegion.GLOBAL, isPopular = true),
        WSPRBand(name = "20m", dialFrequencyMHz = 14.095600, wavelengthMeters = "20m", ITURegion.GLOBAL, isPopular = true),
        WSPRBand(name = "17m", dialFrequencyMHz = 18.104600, wavelengthMeters = "17m", ITURegion.GLOBAL),
        WSPRBand(name = "15m", dialFrequencyMHz = 21.094600, wavelengthMeters = "15m", ITURegion.GLOBAL, isPopular = true),
        WSPRBand(name = "12m", dialFrequencyMHz = 24.924600, wavelengthMeters = "12m", ITURegion.GLOBAL),
        WSPRBand(name = "10m", dialFrequencyMHz = 28.124600, wavelengthMeters = "10m", ITURegion.GLOBAL, isPopular = true),

        // VHF/UHF/Microwave Bands
        WSPRBand(name = "6m", dialFrequencyMHz = 50.293000, wavelengthMeters = "6m", ITURegion.GLOBAL),
        WSPRBand(name = "4m", dialFrequencyMHz = 70.091000, wavelengthMeters = "4m", ITURegion.REGION_1), // Mostly Europe
        WSPRBand(name = "2m", dialFrequencyMHz = 144.489000, wavelengthMeters = "2m", ITURegion.GLOBAL),
        WSPRBand(name = "70cm", dialFrequencyMHz = 432.300000, wavelengthMeters = "70cm", ITURegion.GLOBAL),
        WSPRBand(name = "23cm", dialFrequencyMHz = 1296.500000, wavelengthMeters = "23cm", ITURegion.GLOBAL)
    )

    /**
     * Gets the most popular WSPR bands.
     */
    fun getPopularBands(): List<WSPRBand> = ALL_BANDS.filter { it.isPopular }

    /**
     * Gets WSPR bands for a specific ITU region
     */
    fun getBandsForRegion(region: ITURegion): List<WSPRBand> = ALL_BANDS.filter { it.region == region || it.region == ITURegion.GLOBAL }

    /**
     * Gets all available WSPR frequencies as a map of display name to frequency.
     */
    fun getFrequencyMap(): Map<String, Double> = ALL_BANDS.associate { it.toString() to it.dialFrequencyMHz}

    /**
     * Finds a WSPR band by frequency within a given tolerance (default tolerance is 1kHz).
     */
    fun findBandByFrequency(frequencyMHz: Double, toleranceKHz: Double = 1.0): WSPRBand?
    {
        val toleranceMHz = toleranceKHz / 1000.0
        return ALL_BANDS.find {
            kotlin.math.abs(it.dialFrequencyMHz - frequencyMHz) <= toleranceMHz
        }
    }

    /**
     * Gets the default WSPR frequency (20m)
     */
    fun getDefaultFrequency(): Double = ALL_BANDS.find { it.name == "20m" && it.region == ITURegion.GLOBAL }?.dialFrequencyMHz ?: 14.095600

    /**
     * Validates if a frequency is a valid WSPR frequency
     */
    fun isValidWSPRFRequency(frequencyMHz: Double): Boolean = findBandByFrequency(frequencyMHz) != null
}
