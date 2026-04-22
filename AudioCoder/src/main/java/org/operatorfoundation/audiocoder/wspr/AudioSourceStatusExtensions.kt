package org.operatorfoundation.audiocoder.wspr

import org.operatorfoundation.audiocoder.common.models.AudioSourceStatus
import org.operatorfoundation.audiocoder.wspr.WSPRConstants.WSPR_REQUIRED_BIT_DEPTH
import org.operatorfoundation.audiocoder.wspr.WSPRConstants.WSPR_REQUIRED_CHANNELS
import org.operatorfoundation.audiocoder.wspr.WSPRConstants.WSPR_REQUIRED_SAMPLE_RATE

/**
 * Returns true if this audio source status reports format parameters compatible
 * with WSPR requirements (12kHz, mono, 16-bit).
 */
val AudioSourceStatus.isWSPRCompatible: Boolean
    get() = currentSampleRateHz == WSPR_REQUIRED_SAMPLE_RATE &&
            channelCount        == WSPR_REQUIRED_CHANNELS    &&
            bitDepth            == WSPR_REQUIRED_BIT_DEPTH