package org.operatorfoundation.audiocoder

import org.operatorfoundation.audiocoder.models.WSPRAudioSourceStatus
import org.operatorfoundation.audiocoder.WSPRConstants.WSPR_REQUIRED_BIT_DEPTH
import org.operatorfoundation.audiocoder.WSPRConstants.WSPR_REQUIRED_CHANNELS
import org.operatorfoundation.audiocoder.WSPRConstants.WSPR_REQUIRED_SAMPLE_RATE

/**
 * Interface for providing audio data to WSPR station.
 *
 * Implementations must provide audio in the WSPR-required format:
 * - Sample rate: 12,000 Hz
 * - Bit depth: 16-bit signed integers
 * - Channels: Mono (single channel)
 * - Encoding: PCM (Pulse Code Modulation)
 *
 * The audio source should be designed for real-time operation and
 * handle buffering internally to provide smooth, continuous audio delivery.
 *
 * Example implementation:
 * class MyAudioSource : WSPRAudioSource {
 *     override suspend fun initialize(): Result<Unit> {
 *         // Set up audio hardware, open files, etc.
 *     }
 *
 *     override suspend fun readAudioChunk(durationMs: Long): ShortArray {
 *         // Return audio samples for the requested duration
 *     }
 * }
 */
interface WSPRAudioSource
{
    /**
     * Initializes the audio source and prepares it for audio delivery.
     *
     * This method should:
     * - Configure audio hardware or open audio files
     * - Verify that the source can provide WSPR-compatible audio
     * - Set up any necessary buffering or processing pipelines
     * - Perform connectivity or permission checks
     *
     * The method should be idempotent - calling it multiple times should
     * not cause errors or resource leaks.
     *
     * @return Success if initialization completed without errors,
     *         Failure with descriptive error information if initialization failed
     *
     * @throws WSPRAudioSourceException for unrecoverable initialization errors
     */
    suspend fun initialize(): Result<Unit>

    /**
     * Reads a chunk of audio data covering the specified time duration.
     *
     * This method provides audio samples in real-time for WSPR processing.
     * It should return approximately the number of samples corresponding
     * to the requested duration at 12kHz sample rate.
     *
     * Expected sample count calculation:
     * ```
     * sampleCount = (durationMs / 1000.0) * WSPR_SAMPLE_RATE_HZ
     * ```
     *
     * Behavior requirements:
     * - Returns audio promptly without excessive blocking
     * - Provides continuous audio stream (no gaps between calls)
     * - Handles timing variations gracefully
     * - Returns empty array if no audio is available
     *
     * @param durationMs Requested audio duration in milliseconds
     * @return Array of 16-bit audio samples covering the requested duration.
     *         May be shorter than requested if insufficient audio is available.
     *         Should not be longer than requested to prevent buffer overflow.
     *
     * @throws WSPRAudioSourceException for unrecoverable read errors
     */
    suspend fun readAudioChunk(durationMs: Long): ShortArray

    /**
     * Releases all resources and stops audio acquisition.
     *
     * This method should:
     * - Stop any active audio recording or streaming
     * - Release hardware resources (USB connections, audio devices)
     * - Close open files or network connections
     * - Free memory buffers
     * - Cancel any background processing tasks
     *
     * After calling this method, the audio source should not be used
     * until initialize() is called again.
     *
     * The method should be safe to call multiple times and should not
     * throw exceptions even if cleanup encounters errors.
     */
    suspend fun cleanup()

    /**
     * Gets current status and diagnostic information about the audio source.
     *
     * This method provides information useful for:
     * - Troubleshooting audio issues
     * - Monitoring source health and performance
     * - Displaying status in user interfaces
     *
     * @return Current status and diagnostic information
     */
    suspend fun getSourceStatus(): WSPRAudioSourceStatus
}

/**
 * Exception thrown by WSPR audio source implementations.
 */
class WSPRAudioSourceException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
{
    companion object
    {
        /**
         * Creates an exception for initialization failures.
         *
         * @param sourceDescription Type or name of the audio source
         * @param cause Underlying cause of the failure
         * @return Formatted exception with descriptive message
         */
        fun createInitializationFailure(sourceDescription: String, cause: Throwable? = null): WSPRAudioSourceException
        {
            return WSPRAudioSourceException(
                "Failed to initialize WSPR audio source: $sourceDescription. ${cause?.message ?: "Unknown error"}",
                cause
            )
        }

        /**
         * Creates an exception for audio reading failures.
         *
         * @param cause Underlying cause of the read failure
         * @return Formatted exception with descriptive message
         */
        fun createReadFailure(cause: Throwable? = null): WSPRAudioSourceException
        {
            return WSPRAudioSourceException(
                "Failed to read audio data from WSPR source. ${cause?.message ?: "Unknown error"}",
                cause
            )
        }

        /**
         * Creates an exception for audio format compatibility issues.
         *
         * @param actualSampleRate Actual sample rate provided by source
         * @param actualChannels Actual channel count provided by source
         * @param actualBitDepth Actual bit depth provided by source
         * @return Formatted exception describing the compatibility issue
         */
        fun createFormatIncompatibility(
            actualSampleRate: Int,
            actualChannels: Int,
            actualBitDepth: Int
        ): WSPRAudioSourceException
        {
            return WSPRAudioSourceException(
                "Audio source format incompatible with WSPR requirements. " +
                        "Required: ${WSPR_REQUIRED_SAMPLE_RATE}Hz, " +
                        "${WSPR_REQUIRED_CHANNELS} channel, " +
                        "${WSPR_REQUIRED_BIT_DEPTH}-bit. " +
                        "Actual: ${actualSampleRate}Hz, ${actualChannels} channels, ${actualBitDepth}-bit."
            )
        }
    }
}