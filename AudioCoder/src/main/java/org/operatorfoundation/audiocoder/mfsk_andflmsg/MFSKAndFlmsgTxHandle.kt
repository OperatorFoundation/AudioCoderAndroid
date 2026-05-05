package org.operatorfoundation.audiocoder.mfsk_andflmsg

import com.AndFlmsg.Modem
import kotlinx.coroutines.flow.SharedFlow

/**
 * Exclusive token granting access to the FldigiAndroid transmit pipeline.
 *
 * Acquired via [MFSKAndFlmsgEngine.acquireForTx]. The engine guarantees that
 * only one handle (RX or TX) is live at a time across the entire process — the
 * underlying native code uses static globals and cannot support concurrent use.
 *
 * Lifecycle:
 *   1. Acquire from [MFSKAndFlmsgEngine.acquireForTx]
 *   2. Begin collecting from [tones] in a coroutine
 *   3. Call [transmit] one or more times to produce tones
 *   4. Call [close] to release the handle
 *
 * Tones for a [transmit] call are emitted into [tones] before [transmit] returns.
 * Callers should start collecting [tones] before calling [transmit] to avoid
 * missing emissions, since [tones] is a [SharedFlow] with no replay buffer.
 *
 * After [close], further calls to [transmit] throw [IllegalStateException].
 */
class MFSKAndFlmsgTxHandle internal constructor(
    private val engine: MFSKAndFlmsgEngine,
    /**
     * Stream of tone descriptors emitted during [transmit] calls.
     *
     * Each [ToneDescriptor] describes one tone: a frequency in hertz and a
     * duration in audio samples. Consumers convert these to whatever their
     * hardware expects (for example, USB serial commands to a radio, or PCM
     * audio samples).
     *
     * No replay buffer — collectors must be fully subscribed before [transmit]
     * is called or they will miss emissions. "Subscribed" means the collector
     * coroutine has reached the upstream of [collect][kotlinx.coroutines.flow.Flow.collect],
     * not merely that [launch][kotlinx.coroutines.launch] has returned a Job.
     * Use [onSubscription][kotlinx.coroutines.flow.onSubscription] with a
     * [CompletableDeferred][kotlinx.coroutines.CompletableDeferred] barrier
     * to synchronize, or check [subscriptionCount].
     *
     * Exposed as [SharedFlow] (rather than the more abstract [Flow]) so
     * consumers can use subscription-aware operations like
     * [subscriptionCount] and [onSubscription] for the synchronization
     * pattern this contract requires.
     */
    val tones: SharedFlow<ToneDescriptor>
)
{
    private var closed = false

    /**
     * Encodes [text] as MFSK tones and emits them via [tones].
     *
     * The native modem runs its full TX state machine (preamble, start frame,
     * data, end frame, flush) synchronously. Suspends while the engine's mutex
     * is held and during the encode itself. By the time this returns, all tones
     * for the message have been emitted into [tones].
     *
     * @param text Text to transmit. UTF-8 bytes are sent through the native
     *             modem; ASCII is the common case for Nahoft (base64 ciphertext).
     *             Characters above U+007F are sent as UTF-8 byte sequences.
     * @return True on success, false on native-side failure.
     * @throws IllegalStateException if the handle has been closed.
     */
    suspend fun transmit(text: String): Boolean
    {
        check(!closed) { "MFSKAndFlmsgTxHandle has been closed" }
        return engine.runTxProcess(text)
    }

    /**
     * Aborts an in-progress transmission by silencing the tone stream.
     *
     * Sets [Modem.stopTX] to true, which causes the native code's
     * `txToneDescriptors` callback to discard tones rather than forwarding
     * them. The native modem continues running its TX state machine until
     * the byte buffer is exhausted — there is no way to interrupt that from
     * the Java side — but the [tones] flow stops emitting immediately, so
     * from the consumer's perspective transmission ends now.
     *
     * Non-suspending and safe to call from any thread, including while
     * another coroutine is suspended inside [transmit]. Has no effect if no
     * transmission is in progress.
     *
     * After [abort], future calls to [transmit] on the same handle work
     * normally — the engine resets the abort flag at the start of each
     * transmission.
     */
    fun abort()
    {
        Modem.stopTX = true
    }

    /**
     * Releases the handle and frees the engine for another caller.
     *
     * Idempotent — calling [close] more than once is a no-op. After [close],
     * further calls to [transmit] throw [IllegalStateException].
     */
    suspend fun close()
    {
        if (closed) return
        closed = true
        engine.releaseTxHandle(this)
    }
}