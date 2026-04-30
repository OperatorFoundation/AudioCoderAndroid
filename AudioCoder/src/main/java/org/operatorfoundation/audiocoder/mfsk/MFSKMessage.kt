package org.operatorfoundation.audiocoder.mfsk

import java.time.Instant

/**
 * A text message decoded by an [MFSKStation].
 *
 * MFSK-16 is a text mode. [MFSKStation] delivers the text content between the
 * STX and EOT frame markers as a [String]. Callers are responsible for any
 * higher-level interpretation of that text (e.g. base64 decoding and decryption).
 *
 * @param text       The decoded text content, as transmitted between STX and EOT.
 * @param receivedAt Timestamp of when the message decode completed.
 * @param mode       The MFSK mode used to decode this message.
 */
data class MFSKMessage(
    val text: String,
    val receivedAt: Instant,
    val mode: MFSKMode
)