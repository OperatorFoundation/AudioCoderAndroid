package org.operatorfoundation.audiocoder

object WSPRConstants
{
    const val SAMPLE_RATE_HZ = 12000        // WSPR uses 12kHz sample rate
    const val CENTER_FREQUENCY_HZ = 1500
    const val SYMBOL_LENGTH = 8192
    const val SYMBOLS_PER_MESSAGE = 162 // WSPR messages have 162 symbols
}