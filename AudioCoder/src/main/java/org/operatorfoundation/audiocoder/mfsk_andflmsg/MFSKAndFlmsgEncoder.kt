package org.operatorfoundation.audiocoder.mfsk_andflmsg

import com.AndFlmsg.ToneMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import timber.log.Timber

/**
 * Encodes text as a fldigi-compatible MFSK-16 frequency sequence using the
 * FldigiAndroid native modem.
 *
 * Acquires [MFSKAndFlmsgEngine] for the duration of a single encode call,
 * collects the emitted [ToneDescriptor] stream, converts each descriptor to
 * a centihertz value, then releases the engine.
 *
 * ## Centihertz conversion
 * Each [ToneDescriptor.frequencyHz] is an audio-band frequency produced by
 * the fldigi modem. The centihertz value is:
 *   `(frequencyHz * 100).toLong()`
 *
 * ## Symbol duration
 * All MFSK-16 data tones from the fldigi modem have a fixed symbol length of
 * 512 samples at 8000 Hz = 64 ms. This is a property of the fldigi modem
 * configuration for MFSK-16 and does not vary per tone.
 *
 * ## Threading
 * [encode] is a suspend function and is safe to call from any coroutine context.
 */
object MFSKAndFlmsgEncoder
{
    /**
     * Frequency in Hz passed to the fldigi modem's txInit, which sets tx_frequency
     * and determines where tones are placed by sendsymbol(). Uses the C++ modem's
     * own default from modem.cxx. Must be within the valid range [500, 2500] Hz.
     */
    private const val TX_FREQUENCY_HZ = 1000.0

    /**
     * Encodes [text] as an MFSK frequency sequence using the fldigi modem.
     *
     * Acquires the engine, subscribes to the tone flow, transmits [text],
     * collects all emitted tones, converts them to centihertz, derives symbol
     * duration from the first tone's [ToneDescriptor.durationSamples], and
     * releases the engine.
     *
     * Returns [MFSKAndFlmsgEncodeResult.Busy] if the engine is already acquired.
     * Returns [MFSKAndFlmsgEncodeResult.Failed] if the engine cannot be
     * initialized, the native modem reports an error, or no tones are emitted.
     *
     * @param text Text to encode. ASCII is the common case; characters above
     *             U+007F are sent as UTF-8 byte sequences.
     * @param mode The MFSK mode to use for encoding.
     * @return [MFSKAndFlmsgEncodeResult.Success] with the frequency array and
     *         symbol duration, or [MFSKAndFlmsgEncodeResult.Busy] /
     *         [MFSKAndFlmsgEncodeResult.Failed] on error.
     */
    suspend fun encode(text: String, mode: ToneMode): MFSKAndFlmsgEncodeResult = coroutineScope {
        val acquireResult = MFSKAndFlmsgEngine.acquireForTx(mode, TX_FREQUENCY_HZ)
        val handle = when (acquireResult)
        {
            is TxAcquisitionResult.Success -> acquireResult.handle
            is TxAcquisitionResult.Busy    -> return@coroutineScope MFSKAndFlmsgEncodeResult.Busy
            is TxAcquisitionResult.Failed  ->
                return@coroutineScope MFSKAndFlmsgEncodeResult.Failed(acquireResult.reason)
        }

        try
        {
            val collectedTones = mutableListOf<ToneDescriptor>()
            val collectorReady = CompletableDeferred<Unit>()

            val collectorJob = launch(Dispatchers.IO) {
                handle.tones
                    .onSubscription { collectorReady.complete(Unit) }
                    .collect { collectedTones.add(it) }
            }

            // Wait for the collector to be subscribed before transmitting.
            // The SharedFlow has no replay buffer — tones emitted before
            // subscription is established would be lost.
            collectorReady.await()

            val success = handle.transmit(text)

            if (!success)
            {
                collectorJob.cancel()
                return@coroutineScope MFSKAndFlmsgEncodeResult.Failed(
                    "Native modem reported a failure during transmission"
                )
            }

            collectorJob.cancel()

            if (collectedTones.isEmpty())
            {
                return@coroutineScope MFSKAndFlmsgEncodeResult.Failed(
                    "No tones emitted for mode ${mode.name}"
                )
            }

            val symbolFrequenciesCHz = collectedTones.map { tone ->
                (tone.frequencyHz * 100).toLong()
            }.toLongArray()

            val symbolDurationMs =
                (collectedTones.first().durationSamples.toDouble() / mode.sampleRate() * 1000).toLong()

            Timber.d("MFSKAndFlmsgEncoder: encoded ${collectedTones.size} tones " +
                    "at ${symbolDurationMs}ms each for mode ${mode.name}")

            MFSKAndFlmsgEncodeResult.Success(symbolFrequenciesCHz, symbolDurationMs)
        }
        finally
        {
            handle.close()
        }
    }
}