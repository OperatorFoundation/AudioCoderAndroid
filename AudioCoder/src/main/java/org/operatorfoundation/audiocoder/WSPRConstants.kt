package org.operatorfoundation.audiocoder

object WSPRConstants
{
    const val CENTER_FREQUENCY_HZ = 1500
    const val SYMBOL_LENGTH = 8192
    
    /** Duration of each WSPR symbol in milliseconds */
    const val SYMBOL_DURATION_MS = 683L
    const val SYMBOLS_PER_MESSAGE = 162 // WSPR messages have 162 symbols

    /** WSPR requires exactly 12kHz sample rate */
    const val WSPR_REQUIRED_SAMPLE_RATE = 12000

    /** WSPR requires mono audio (1 channel) */
    const val WSPR_REQUIRED_CHANNELS = 1

    /** WSPR requires 16-bit audio samples */
    const val WSPR_REQUIRED_BIT_DEPTH = 16
}

/**
 * WSPR timing constants used throughout the station implementation.
 */
object WSPRTimingConstants
{
    /** Standard WSPR cycle duration: 2 minutes */
    const val WSPR_CYCLE_DURATION_SECONDS = 120L

    /** WSPR transmission duration: approximately 110.g seconds */
    const val WSPR_TRANSMISSION_DURATION_SECONDS = 111L

    /** Native decoder audio collection requirement: exactly 114 seconds */
    const val AUDIO_COLLECTION_DURATION_SECONDS = 114L
    const val AUDIO_COLLECTION_DURATION_MILLISECONDS = AUDIO_COLLECTION_DURATION_SECONDS * 1000L

    /** Delay before starting decode after transmission begins: 2 seconds */
    const val DECODE_START_DELAY_SECONDS = 2L

    /** Duration of each audio chunk read during collection: 1 second */
    const val AUDIO_CHUNK_DURATION_MILLISECONDS = 1000L

    /** Pause between audio chunk reads: 100ms */
    const val AUDIO_COLLECTION_PAUSE_MILLISECONDS = 100L

    /** How often to update cycle information for UI: 1 second */
    const val CYCLE_INFORMATION_UPDATE_INTERVAL_MILLISECONDS = 1000L

    /** Brief pause between operations: 2 seconds */
    const val BRIEF_OPERATION_PAUSE_MILLISECONDS = 2000L

    /** Maximum delay for error backoff: 5 minutes */
    const val MAXIMUM_ERROR_BACKOFF_MILLISECONDS = 300_000L

    /** WSPR operates on 2-minute cycles */
    const val MINUTES_PER_WSPR_CYCLE = 2

    /** Standard calendar constants */
    const val MINUTES_PER_HOUR = 60
    const val SECONDS_PER_MINUTE = 60

    /**
     * Decode window closes at 116 seconds to ensure we have collected the required
     * 114 seconds of audio while staying within the transmission period
     */
    const val DECODE_WINDOW_END_SECOND = 116
}