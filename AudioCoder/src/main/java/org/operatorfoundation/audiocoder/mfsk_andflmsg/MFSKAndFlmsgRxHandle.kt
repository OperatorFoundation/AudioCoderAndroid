package org.operatorfoundation.audiocoder.mfsk_andflmsg

/**
 * Exclusive token granting access to the FldigiAndroid receive pipeline.
 *
 * Acquired via [MFSKAndFlmsgEngine.acquireForRx]. The engine guarantees that
 * only one handle (RX or TX) is live at a time across the entire process — the
 * underlying native code uses static globals and cannot support concurrent use.
 *
 * Lifecycle:
 *   1. Acquire from [MFSKAndFlmsgEngine.acquireForRx]
 *   2. Feed audio via [pushAudio] for as long as you want to receive
 *   3. Call [close] to release the handle, freeing the engine for another caller
 *
 * After [close], further calls to [pushAudio] throw [IllegalStateException].
 *
 * The decoded character stream returned by [pushAudio] is unframed — fldigi's
 * MFSK transmissions wrap data in `CR STX CR ... CR EOT CR` markers, but the
 * native code emits all characters including those markers. Callers that need
 * framed messages must reconstruct frames themselves; [MFSKAndFlmsgStation]
 * does this on top of this handle.
 */
class MFSKAndFlmsgRxHandle internal constructor(
    private val engine: MFSKAndFlmsgEngine
)
{
    private var closed = false

    /**
     * Feeds [samples] through the native modem and returns any newly decoded
     * characters.
     *
     * The samples must be 16-bit PCM at the sample rate the modem was configured
     * for (8000 Hz for MFSK-16 in fldigi). Suspends while the engine's mutex is
     * held by another caller, then runs the native decode synchronously.
     *
     * @param samples 16-bit PCM audio samples.
     * @return Newly decoded characters from this chunk. Empty if no characters
     *         were decoded. Includes raw fldigi framing characters (CR, STX, EOT)
     *         and any printable characters that crossed the decode threshold.
     * @throws IllegalStateException if the handle has been closed.
     */
    suspend fun pushAudio(samples: ShortArray): String
    {
        check(!closed) { "MFSKAndFlmsgRxHandle has been closed" }
        return engine.runRxProcess(samples)
    }

    /**
     * Releases the handle and frees the engine for another caller.
     *
     * Idempotent — calling [close] more than once is a no-op. After [close],
     * further calls to [pushAudio] throw [IllegalStateException].
     */
    suspend fun close()
    {
        if (closed) return
        closed = true
        engine.releaseRxHandle(this)
    }
}