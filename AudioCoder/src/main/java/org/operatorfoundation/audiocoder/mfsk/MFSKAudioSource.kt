package org.operatorfoundation.audiocoder.mfsk

import org.operatorfoundation.audiocoder.common.models.AudioSourceStatus

/**
 * Interface for providing audio data to an MFSK station.
 *
 * Mirrors [org.operatorfoundation.audiocoder.wspr.WSPRAudioSource] in structure.
 * Audio must be provided as 16-bit signed PCM, mono. Sample rate is specified
 * in [MFSKConfiguration] rather than fixed here — 12kHz is the recommended rate,
 * as it divides cleanly into all standard MFSK baud rates with no rounding error.
 *
 * If implementations need to signal unrecoverable errors with a typed exception,
 * use or extend [MFSKAudioSourceException] in this package rather than importing
 * WSPR-specific exception types.
 */
interface MFSKAudioSource
{
    /**
     * Initializes the audio source and prepares it for audio delivery.
     * The method should be idempotent — calling it multiple times must not cause
     * errors or resource leaks.
     *
     * @return Success if initialization completed without errors,
     *         Failure with descriptive error information otherwise.
     */
    suspend fun initialize(): Result<Unit>

    /**
     * Reads a chunk of audio data covering the specified time duration.
     *
     * @param durationMs Requested audio duration in milliseconds.
     * @return Array of 16-bit audio samples. May be shorter than requested
     *         if insufficient audio is available.
     */
    suspend fun readAudioChunk(durationMs: Long): ShortArray

    /**
     * Releases all resources and stops audio acquisition.
     * Safe to call multiple times. Must not throw.
     */
    suspend fun cleanup()

    /**
     * Discards all buffered audio samples. Call immediately before beginning
     * a decode window to ensure only time-aligned audio reaches the decoder.
     * Default implementation is a no-op for sources that do not buffer.
     */
    suspend fun flushBuffer() {}

    /**
     * Returns current status and diagnostic information about this audio source.
     */
    suspend fun getSourceStatus(): AudioSourceStatus
}