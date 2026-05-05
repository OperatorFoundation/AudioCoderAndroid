package org.operatorfoundation.audiocoder.mfsk_andflmsg

import com.AndFlmsg.Modem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.operatorfoundation.audiocoder.mfsk.MFSKMode
import timber.log.Timber

// =============================================================================
// Engine
// =============================================================================

/**
 * Single-instance mediator for the FldigiAndroid MFSK modem.
 *
 * The native code (`AndFlmsg_Fldigi_Interface.cpp`) uses a static `active_modem`
 * pointer and a static `gEnv` JNI environment pointer. Two consequences:
 *   1. Only one modem can exist at a time across the whole process. Calling
 *      `Modem.createCModem` deletes any previous modem before installing the
 *      new one. This engine enforces a one-at-a-time acquire/release lifecycle
 *      so consumers never accidentally clobber each other.
 *   2. Concurrent JNI calls from different threads would race on the static
 *      globals. Every native call inside this engine runs under [mutex].
 *
 * ## Lifecycle
 * Consumers do not interact with this object directly except to acquire a
 * handle. A typical RX session looks like:
 * ```
 * when (val result = MFSKAndFlmsgEngine.acquireForRx(MFSKMode.MFSK16, 1500.0)) {
 *     is RxAcquisitionResult.Success -> {
 *         try { collectAudio { samples -> result.handle.pushAudio(samples) } }
 *         finally { result.handle.close() }
 *     }
 *     RxAcquisitionResult.Busy        -> retryLater()
 *     is RxAcquisitionResult.Failed   -> reportError(result.reason)
 * }
 * ```
 *
 * TX is symmetric, but the consumer also collects from `handle.tones` to
 * receive the encoded tone stream during [MFSKAndFlmsgTxHandle.transmit].
 *
 * ## Threading
 * All public methods are suspend functions and are safe to call from any
 * coroutine context. Internal native calls run on [Dispatchers.IO] under
 * [mutex] so they neither block the calling thread nor race each other.
 */
object MFSKAndFlmsgEngine
{
    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    /**
     * Mode code passed to [Modem.createCModem] for MFSK-16.
     *
     * This integer corresponds to the position of `MODE_MFSK16` in the enum
     * in fldigi's `globals.h`, as compiled into the FldigiAndroid build that
     * AudioCoder depends on. If FldigiAndroid's `globals.h` changes (modes
     * added, removed, or reordered before MFSK16), this constant must be
     * updated to match.
     *
     * Verified empirically by `MFSKAndFlmsgTests.createCModem_mfsk16_doesNotCrash`
     * — that test will fail if this value drifts.
     */
    private const val MODEM_CODE_MFSK16 = 22

    /**
     * Buffer capacity for the per-session tone SharedFlow.
     *
     * The native code flushes its `toneDescriptors` array at 2000 ints
     * (1000 [ToneDescriptor] pairs). 4096 gives comfortable headroom so a
     * normal Nahoft-sized message will never fill the buffer, even if the
     * consumer is briefly slow.
     */
    private const val TONE_FLOW_BUFFER_CAPACITY = 4096

    // -------------------------------------------------------------------------
    // Engine state — guarded by [mutex] except where noted
    // -------------------------------------------------------------------------

    /**
     * Serializes every JNI call. The native code is not thread-safe (see
     * class-level docs).
     */
    private val mutex = Mutex()

    /**
     * The currently-live handle, or null if the engine is free.
     * Either an [MFSKAndFlmsgRxHandle] or an [MFSKAndFlmsgTxHandle].
     * Mutated only inside [mutex].
     */
    private var currentHandle: Any? = null

    /**
     * Tone flow for the active TX session, or null when no TX session is live.
     *
     * Created fresh on each [acquireForTx] and discarded on [releaseTxHandle].
     * The C++ -> Java bridge calls into [Modem.toneDescriptorListener]; that
     * listener forwards into this flow.
     */
    private var activeTxToneFlow: MutableSharedFlow<ToneDescriptor>? = null

    // -------------------------------------------------------------------------
    // Public API — acquire
    // -------------------------------------------------------------------------

    /**
     * Acquires exclusive access to the modem for receiving.
     *
     * Internally:
     *   1. Checks the engine is free. If not, returns [RxAcquisitionResult.Busy].
     *   2. Resolves [mode] to a native mode code. Returns
     *      [RxAcquisitionResult.Failed] if the mode is unsupported.
     *   3. Calls `createCModem` then `initCModem` under the engine mutex.
     *      Returns [RxAcquisitionResult.Failed] if either reports an error.
     *   4. Stores the new handle and returns [RxAcquisitionResult.Success].
     *
     * @param mode        The MFSK mode to receive.
     * @param frequencyHz Audio center frequency in Hz (typically 1500.0 for
     *                    fldigi MFSK-16).
     */
    suspend fun acquireForRx(mode: MFSKMode, frequencyHz: Double): RxAcquisitionResult
    {
        val modeCode = modeCodeFor(mode)
            ?: return RxAcquisitionResult.Failed("Unsupported mode: ${mode.label}")

        return mutex.withLock {
            if (currentHandle != null)
            {
                return@withLock RxAcquisitionResult.Busy
            }

            // Run JNI calls on IO. Both calls return short status strings;
            // we treat anything starting with "ERROR" as failure.
            val initError = withContext(Dispatchers.IO) {
                val createResult = Modem.createCModem(modeCode)
                if (createResult.startsWith("ERROR"))
                {
                    return@withContext "createCModem failed: $createResult"
                }
                val initResult = Modem.initCModem(frequencyHz)
                if (initResult.startsWith("ERROR"))
                {
                    return@withContext "initCModem failed: $initResult"
                }
                null
            }

            if (initError != null)
            {
                Timber.w("MFSKAndFlmsgEngine: RX acquire failed — $initError")
                return@withLock RxAcquisitionResult.Failed(initError)
            }

            // No tone listener for RX — the listener is a TX-only callback.
            // Defensive: explicitly null it in case a previous TX session
            // left it set somehow.
            Modem.toneDescriptorListener = null

            val handle = MFSKAndFlmsgRxHandle(this)
            currentHandle = handle
            Timber.d("MFSKAndFlmsgEngine: RX acquired (mode=${mode.label}, freq=${frequencyHz}Hz)")
            RxAcquisitionResult.Success(handle)
        }
    }

    /**
     * Acquires exclusive access to the modem for transmitting.
     *
     * Internally:
     *   1. Checks the engine is free. If not, returns [TxAcquisitionResult.Busy].
     *   2. Resolves [mode] to a native mode code. Returns
     *      [TxAcquisitionResult.Failed] if the mode is unsupported.
     *   3. Calls `createCModem` then `txInit` under the engine mutex.
     *      Returns [TxAcquisitionResult.Failed] if either reports an error.
     *   4. Installs the tone listener that forwards into a fresh SharedFlow.
     *   5. Stores the new handle and returns [TxAcquisitionResult.Success].
     *
     * @param mode        The MFSK mode to transmit.
     * @param frequencyHz Audio center frequency in Hz (typically 1500.0 for
     *                    fldigi MFSK-16).
     */
    suspend fun acquireForTx(mode: MFSKMode, frequencyHz: Double): TxAcquisitionResult
    {
        val modeCode = modeCodeFor(mode)
            ?: return TxAcquisitionResult.Failed("Unsupported mode: ${mode.label}")

        return mutex.withLock {
            if (currentHandle != null)
            {
                return@withLock TxAcquisitionResult.Busy
            }

            val initError = withContext(Dispatchers.IO) {
                val createResult = Modem.createCModem(modeCode)
                if (createResult.startsWith("ERROR"))
                {
                    return@withContext "createCModem failed: $createResult"
                }
                val txInitResult = Modem.txInit(frequencyHz)
                if (txInitResult.startsWith("ERROR"))
                {
                    return@withContext "txInit failed: $txInitResult"
                }
                null
            }

            if (initError != null)
            {
                Timber.w("MFSKAndFlmsgEngine: TX acquire failed — $initError")
                return@withLock TxAcquisitionResult.Failed(initError)
            }

            // Create a fresh tone flow for this session and wire it up to
            // the C++ -> Java callback.
            val toneFlow = MutableSharedFlow<ToneDescriptor>(
                replay              = 0,
                extraBufferCapacity = TONE_FLOW_BUFFER_CAPACITY
            )
            activeTxToneFlow = toneFlow
            installToneListener(toneFlow)

            val handle = MFSKAndFlmsgTxHandle(
                engine = this,
                tones  = toneFlow.asSharedFlow()
            )
            currentHandle = handle
            Timber.d("MFSKAndFlmsgEngine: TX acquired (mode=${mode.label}, freq=${frequencyHz}Hz)")
            TxAcquisitionResult.Success(handle)
        }
    }

    // -------------------------------------------------------------------------
    // Internal API — called by handles
    // -------------------------------------------------------------------------

    /**
     * Drives one chunk of audio through the native receive pipeline.
     * Called by [MFSKAndFlmsgRxHandle.pushAudio].
     *
     * @return Newly decoded characters, or empty if none. If the native code
     *         reports a failure, returns empty and logs a warning rather than
     *         passing the error string upstream where it would be interpreted
     *         as decoded text.
     */
    internal suspend fun runRxProcess(samples: ShortArray): String
    {
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                val result = Modem.rxCProcess(samples, samples.size)
                if (result.startsWith("ERROR"))
                {
                    Timber.w("MFSKAndFlmsgEngine: rxCProcess error: $result")
                    ""
                }
                else
                {
                    result
                }
            }
        }
    }

    /**
     * Drives the full TX state machine for [text] through the native code.
     * Called by [MFSKAndFlmsgTxHandle.transmit].
     *
     * Tones are emitted into the active tone flow as a side effect of the
     * native call (via the registered tone listener).
     *
     * @return True on success, false if the native code reported a failure.
     */
    internal suspend fun runTxProcess(text: String): Boolean
    {
        // UTF-8 bytes are passed straight through. The native code reads one
        // byte at a time via get_tx_char(); ASCII works directly, and non-ASCII
        // characters become multi-byte UTF-8 sequences that the receiver
        // reassembles via the same UTF-8 logic in put_rx_char.
        val bytes = text.toByteArray(Charsets.UTF_8)

        return mutex.withLock {
            Timber.d("MFSKAndFlmsgEngine: runTxProcess about to call txCProcess (${bytes.size} bytes)")
            val result = withContext(Dispatchers.IO) {
                // Reset the abort flag in case a previous transmission was
                // aborted via [MFSKAndFlmsgTxHandle.abort]. Without this,
                // tones from this transmission would be silently discarded.
                Modem.stopTX = false

                Modem.txCProcess(bytes, bytes.size)
            }
            Timber.d("MFSKAndFlmsgEngine: runTxProcess returned $result")
            result
        }
    }

    /**
     * Releases an RX handle, freeing the engine for another caller.
     * Called by [MFSKAndFlmsgRxHandle.close]. Idempotent.
     *
     * Safe against double-close and against close-after-stale-handle: if
     * [handle] is not the current handle, this method is a warning-logged
     * no-op.
     */
    internal suspend fun releaseRxHandle(handle: MFSKAndFlmsgRxHandle)
    {
        mutex.withLock {
            if (currentHandle !== handle)
            {
                Timber.w("MFSKAndFlmsgEngine: releaseRxHandle called with stale or unknown handle — ignoring")
                return@withLock
            }
            currentHandle = null
            Timber.d("MFSKAndFlmsgEngine: RX released")
        }
    }

    /**
     * Releases a TX handle, freeing the engine for another caller.
     * Called by [MFSKAndFlmsgTxHandle.close]. Idempotent.
     *
     * Tears down the tone listener and the active tone flow. After this call,
     * any straggler `txToneDescriptors` callbacks from the C++ side find a
     * null listener and are no-ops.
     */
    internal suspend fun releaseTxHandle(handle: MFSKAndFlmsgTxHandle)
    {
        mutex.withLock {
            if (currentHandle !== handle)
            {
                Timber.w("MFSKAndFlmsgEngine: releaseTxHandle called with stale or unknown handle — ignoring")
                return@withLock
            }

            // Disable the listener path defensively. stopTX = true is the
            // C++'s "discard tones" gate; nulling the listener removes the
            // callback target entirely. Either alone is sufficient; both
            // together is belt-and-braces.
            Modem.stopTX = true
            Modem.toneDescriptorListener = null

            activeTxToneFlow = null
            currentHandle = null
            Timber.d("MFSKAndFlmsgEngine: TX released")
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the native mode code for [mode], or null if no MFSK-And-Flmsg
     * mapping exists.
     *
     * Currently only MFSK-16 is mapped because that's the only mode whose
     * code we have empirically verified against the FldigiAndroid build.
     * Adding other modes is a one-line change here plus a smoke test
     * verifying the new code.
     */
    private fun modeCodeFor(mode: MFSKMode): Int? = when (mode)
    {
        MFSKMode.MFSK16 -> MODEM_CODE_MFSK16
        else            -> null
    }

    /**
     * Installs a [Modem.ToneDescriptorListener] that decodes the int[] payload
     * from the C++ side and emits [ToneDescriptor] values into [toneFlow].
     *
     * Payload format (from `emitToneDescriptor` in `AndFlmsg_Fldigi_Interface.cpp`):
     *   `[freq_hz × 1000, duration_samples, freq_hz × 1000, duration_samples, ...]`
     * with `length` being the total int count, so pair count = length / 2.
     *
     * Uses [MutableSharedFlow.tryEmit] rather than the suspending `emit`. The
     * listener runs synchronously inside the JNI call, holding the engine
     * mutex; suspending here would deadlock anyone trying to acquire the
     * engine and would also stall the C++ TX state machine. With the
     * configured buffer capacity, [tryEmit] should always succeed for normal
     * Nahoft-sized messages — overflow logs a warning so we notice if it
     * ever happens.
     */
    private fun installToneListener(toneFlow: MutableSharedFlow<ToneDescriptor>)
    {
        Modem.stopTX = false
        Modem.toneDescriptorListener = object : Modem.ToneDescriptorListener
        {
            override fun onToneDescriptors(descriptors: IntArray, length: Int)
            {
                Timber.d("MFSKAndFlmsgEngine: onToneDescriptors fired, length=$length")

                // Defensive: length should always be even (interleaved pairs).
                // If it's not, truncate to the largest even count we can
                // safely read rather than risking an out-of-bounds read.
                val safeLength = length and 1.inv()
                if (safeLength != length)
                {
                    Timber.w("MFSKAndFlmsgEngine: odd tone descriptor length=$length — truncating to $safeLength")
                }

                var i = 0
                while (i < safeLength)
                {
                    val frequencyHz     = descriptors[i] / 1000.0
                    val durationSamples = descriptors[i + 1]
                    val tone = ToneDescriptor(frequencyHz, durationSamples)

                    if (!toneFlow.tryEmit(tone))
                    {
                        // Buffer full — consumer is too slow. Log once per
                        // overflow event; further drops in the same burst
                        // will be quiet to avoid log spam.
                        Timber.w("MFSKAndFlmsgEngine: tone flow buffer overflow — dropping tone $tone")
                    }
                    i += 2
                }
            }
        }
    }
}

// =============================================================================
// Acquisition result types
// =============================================================================

/**
 * Outcome of a call to [MFSKAndFlmsgEngine.acquireForRx].
 *
 * Distinct from [TxAcquisitionResult] so that pattern-matching at the call site
 * always works against a result whose [Success] branch contains an
 * [MFSKAndFlmsgRxHandle].
 */
sealed class RxAcquisitionResult
{
    /** Engine acquired successfully; the caller now owns [handle]. */
    data class Success(val handle: MFSKAndFlmsgRxHandle) : RxAcquisitionResult()

    /** Another caller already holds the engine. Try again later. */
    object Busy : RxAcquisitionResult()

    /**
     * The native modem could not be created or initialized.
     * @param reason Human-readable explanation, suitable for logging or a UI message.
     */
    data class Failed(val reason: String) : RxAcquisitionResult()
}

/**
 * Outcome of a call to [MFSKAndFlmsgEngine.acquireForTx].
 *
 * Distinct from [RxAcquisitionResult] so that pattern-matching at the call site
 * always works against a result whose [Success] branch contains an
 * [MFSKAndFlmsgTxHandle].
 */
sealed class TxAcquisitionResult
{
    /** Engine acquired successfully; the caller now owns [handle]. */
    data class Success(val handle: MFSKAndFlmsgTxHandle) : TxAcquisitionResult()

    /** Another caller already holds the engine. Try again later. */
    object Busy : TxAcquisitionResult()

    /**
     * The native modem could not be created or initialized.
     * @param reason Human-readable explanation, suitable for logging or a UI message.
     */
    data class Failed(val reason: String) : TxAcquisitionResult()
}