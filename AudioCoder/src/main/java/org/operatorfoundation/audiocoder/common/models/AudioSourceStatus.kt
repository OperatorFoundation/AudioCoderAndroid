package org.operatorfoundation.audiocoder.common.models

/**
 * Status and diagnostic information for an audio source.
 *
 * This is a general-purpose status type shared across AudioCoder's audio source interfaces
 * ([org.operatorfoundation.audiocoder.wspr.WSPRAudioSource],
 * [org.operatorfoundation.audiocoder.mfsk.MFSKAudioSource], etc.).
 * Protocol-specific compatibility checks (e.g. WSPR sample rate requirements) are
 * implemented as extension functions in the relevant protocol package rather than here.
 */
data class AudioSourceStatus(
    /** Whether the audio source is currently operational. */
    val isOperational: Boolean,

    /** Current audio sample rate in Hz. */
    val currentSampleRateHz: Int,

    /** Number of audio channels. */
    val channelCount: Int,

    /** Audio bit depth. */
    val bitDepth: Int,

    /** Human-readable description of current source state. */
    val statusDescription: String,

    /** Optional error message if source is not operational. */
    val errorMessage: String? = null,

    /** Timestamp when status was last updated (milliseconds since epoch). */
    val lastUpdated: Long = System.currentTimeMillis()
)
{
    companion object
    {
        /**
         * Creates a status indicating the source is not operational.
         *
         * @param errorDescription Reason why the source is not working.
         */
        fun createNonOperationalStatus(errorDescription: String): AudioSourceStatus =
            AudioSourceStatus(
                isOperational       = false,
                currentSampleRateHz = 0,
                channelCount        = 0,
                bitDepth            = 0,
                statusDescription   = "Not operational",
                errorMessage        = errorDescription
            )

        /**
         * Creates a status indicating the source is working correctly.
         *
         * @param sampleRateHz Sample rate the source is currently providing.
         * @param channelCount Channel count the source is currently providing.
         * @param bitDepth     Bit depth the source is currently providing.
         * @param description  Optional description of current operation.
         */
        fun createOperationalStatus(
            sampleRateHz: Int,
            channelCount: Int,
            bitDepth: Int,
            description: String = "Operating normally"
        ): AudioSourceStatus =
            AudioSourceStatus(
                isOperational       = true,
                currentSampleRateHz = sampleRateHz,
                channelCount        = channelCount,
                bitDepth            = bitDepth,
                statusDescription   = description
            )
    }
}