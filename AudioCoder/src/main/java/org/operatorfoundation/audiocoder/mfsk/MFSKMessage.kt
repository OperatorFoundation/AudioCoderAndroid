package org.operatorfoundation.audiocoder.mfsk

import java.time.Instant

/**
 * A message decoded by an [MFSKStation].
 *
 * Contains the raw decoded bytes and enough metadata for the caller to interpret
 * and log the result. Sender identity is not represented here — that association
 * is established by the encryption layer above, which links ciphertext to a peer
 * via key identity.
 *
 * @param data       Raw decoded bytes (typically ciphertext to be passed to the
 *                   decryption layer).
 * @param receivedAt Timestamp of when the message decode completed.
 * @param mode       The MFSK mode used to decode this message.
 */
data class MFSKMessage(
    val data: ByteArray,
    val receivedAt: Instant,
    val mode: MFSKMode
)
{
    // ByteArray requires manual equals/hashCode — the compiler-generated implementation
    // compares by reference, which is incorrect for a data class used in comparisons or sets.
    override fun equals(other: Any?): Boolean
    {
        if (this === other) return true
        if (other !is MFSKMessage) return false
        return data.contentEquals(other.data) &&
                receivedAt == other.receivedAt &&
                mode       == other.mode
    }

    override fun hashCode(): Int
    {
        var result = data.contentHashCode()
        result = 31 * result + receivedAt.hashCode()
        result = 31 * result + mode.hashCode()
        return result
    }
}