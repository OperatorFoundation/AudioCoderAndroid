package org.operatorfoundation.audiocoder

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.AndFlmsg.Modem
import com.AndFlmsg.ToneMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.operatorfoundation.audiocoder.mfsk.MFSKMode
import org.operatorfoundation.audiocoder.mfsk_andflmsg.MFSKAndFlmsgEncodeResult
import org.operatorfoundation.audiocoder.mfsk_andflmsg.MFSKAndFlmsgEncoder
import org.operatorfoundation.audiocoder.mfsk_andflmsg.MFSKAndFlmsgEngine
import org.operatorfoundation.audiocoder.mfsk_andflmsg.RxAcquisitionResult
import org.operatorfoundation.audiocoder.mfsk_andflmsg.ToneDescriptor
import org.operatorfoundation.audiocoder.mfsk_andflmsg.TxAcquisitionResult
import timber.log.Timber
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Instrumented tests for the FldigiAndroid-based MFSK implementation.
 *
 * Tests in this file require a device or emulator because they exercise the
 * JNI bridge into FldigiAndroid's native modem code. The pure-Kotlin
 * implementation's tests live in [MFSKTests] and run on the JVM.
 *
 * ## Scope
 *
 * These tests verify the wrapper at the JNI boundary:
 *   - The native libraries load and the JNI bridge is reachable.
 *   - The engine's acquire/release lifecycle works correctly.
 *   - TX produces tones in the expected frequency band with plausible durations.
 *   - RX accepts audio without crashing.
 *
 * They do *not* verify protocol correctness end-to-end — there is no fldigi
 * receiver in the test process to decode what we transmit. Round-trip
 * verification is a separate effort, ideally comparing against fldigi running
 * in a desktop environment.
 *
 * ## Test isolation
 *
 * Each test that acquires a handle releases it before returning. Because the
 * engine is a singleton tied to static native state, leaking a handle from
 * one test would cascade into failures in subsequent tests. Every acquire is
 * paired with a `try { ... } finally { handle.close() }` block.
 *
 * ## Test method shape
 *
 * Every `@Test` method uses block-bodied syntax — `fun foo() { runBlocking { ... } }`
 * rather than `fun foo() = runBlocking { ... }` — to guarantee a `void`-returning
 * compiled signature regardless of what the last expression in the block evaluates
 * to. JUnit 4 rejects test methods whose compiled return type is not `void`, and
 * the expression-bodied form silently inherits the type of its last expression
 * (which can be `IllegalStateException` after a call to `assertFailsWith`, for
 * example).
 */
@RunWith(AndroidJUnit4::class)
class MFSKAndFlmsgTests
{
    companion object
    {
        // Plant a DebugTree once per class so engine Timber logs are visible
        // even when a single test is run in isolation. Idempotent: a prior
        // test class may have already planted one.
        @JvmStatic
        @BeforeClass
        fun plantTimberOnce()
        {
            if (Timber.forest().none { it is Timber.DebugTree })
            {
                Timber.plant(Timber.DebugTree())
            }
        }

        private const val TX_FREQUENCY_HZ = 1000.0
    }
    // =========================================================================
    // Constants used across tests
    // =========================================================================

    /**
     * Standard fldigi MFSK-16 audio center frequency in Hz.
     * MFSK-16 occupies a 250 Hz band centered here (1375.0 to 1625.0 Hz).
     */
    private val MFSK16_CENTER_FREQUENCY_HZ = 1500.0

    /**
     * Half the MFSK-16 band width in Hz.
     * Used as the frequency-band assertion tolerance: tones must fall within
     * `MFSK16_CENTER_FREQUENCY_HZ ± MFSK16_BAND_HALF_WIDTH_HZ`.
     */
    private val MFSK16_BAND_HALF_WIDTH_HZ = 16 * 15.625  // toneCount × toneSpacingHz

    /**
     * Time to wait after a transmission for the tone collector to drain.
     * The native TX state machine completes synchronously, so by the time
     * `transmit()` returns, all tones have been pushed to the SharedFlow's
     * buffer. This brief delay covers the very last few emissions reaching
     * the collector coroutine.
     */
    private val TONE_DRAIN_DELAY_MS = 200L

    // =========================================================================
    // JNI bridge smoke test
    // =========================================================================

    @Test
    fun nativeBridge_createsMfsk16Modem()
    {
        val result = Modem.createCModem(ToneMode.MFSK16.code())
        assertTrue(
            result.startsWith("Modem created"),
            "Expected success, got: $result"
        )
    }

    // =========================================================================
    // Engine lifecycle and contract tests
    // =========================================================================

    /**
     * Verifies that releasing an RX handle frees the engine for re-acquisition.
     */
    @Test
    fun acquireForRx_thenRelease_freesEngine()
    {
        runBlocking {
            val first = MFSKAndFlmsgEngine.acquireForRx(ToneMode.MFSK16, MFSK16_CENTER_FREQUENCY_HZ)
            val firstHandle = (first as? RxAcquisitionResult.Success)?.handle
                ?: fail("First acquire failed: $first")
            firstHandle.close()

            val second = MFSKAndFlmsgEngine.acquireForRx(ToneMode.MFSK16, MFSK16_CENTER_FREQUENCY_HZ)
            val secondHandle = (second as? RxAcquisitionResult.Success)?.handle
                ?: fail("Second acquire failed after release: $second")
            secondHandle.close()
        }
    }

    /**
     * Verifies the busy path: a second acquire while the engine is held
     * returns [TxAcquisitionResult.Busy]. Uses RX-then-TX to also exercise
     * the cross-direction case (TX cannot start while RX is live).
     */
    @Test
    fun acquireForTx_whileRxLive_returnsBusy()
    {
        runBlocking {
            val rxResult = MFSKAndFlmsgEngine.acquireForRx(ToneMode.MFSK16, MFSK16_CENTER_FREQUENCY_HZ)
            val rxHandle = (rxResult as? RxAcquisitionResult.Success)?.handle
                ?: fail("RX acquire failed: $rxResult")

            try
            {
                val txResult = MFSKAndFlmsgEngine.acquireForTx(ToneMode.MFSK16, MFSK16_CENTER_FREQUENCY_HZ)
                assertEquals(
                    TxAcquisitionResult.Busy,
                    txResult,
                    "Expected Busy when RX is live, got: $txResult"
                )
            }
            finally
            {
                rxHandle.close()
            }
        }
    }

    /**
     * Verifies that calling [close] more than once is a silent no-op rather
     * than an error.
     */
    @Test
    fun closeAfterClose_isNoOp()
    {
        runBlocking {
            val result = MFSKAndFlmsgEngine.acquireForRx(ToneMode.MFSK16, MFSK16_CENTER_FREQUENCY_HZ)
            val handle = (result as? RxAcquisitionResult.Success)?.handle
                ?: fail("Acquire failed: $result")

            handle.close()
            handle.close()  // Should not throw.
        }
    }

    /**
     * Verifies that calling [MFSKAndFlmsgRxHandle.pushAudio] after
     * [MFSKAndFlmsgRxHandle.close] throws [IllegalStateException], catching
     * use-after-close bugs at the consumer.
     */
    @Test
    fun pushAudio_afterClose_throwsIllegalStateException()
    {
        runBlocking {
            val result = MFSKAndFlmsgEngine.acquireForRx(ToneMode.MFSK16, MFSK16_CENTER_FREQUENCY_HZ)
            val handle = (result as? RxAcquisitionResult.Success)?.handle
                ?: fail("Acquire failed: $result")

            handle.close()

            assertFailsWith<IllegalStateException> {
                handle.pushAudio(ShortArray(100))
            }
        }
    }

    /**
     * Verifies that calling [MFSKAndFlmsgTxHandle.transmit] after
     * [MFSKAndFlmsgTxHandle.close] throws [IllegalStateException].
     */
    @Test
    fun transmit_afterClose_throwsIllegalStateException()
    {
        runBlocking {
            val result = MFSKAndFlmsgEngine.acquireForTx(ToneMode.MFSK16, MFSK16_CENTER_FREQUENCY_HZ)
            val handle = (result as? TxAcquisitionResult.Success)?.handle
                ?: fail("Acquire failed: $result")

            handle.close()

            assertFailsWith<IllegalStateException> {
                handle.transmit("HELLO")
            }
        }
    }

    // =========================================================================
    // TX behavior tests
    // =========================================================================

    /**
     * Verifies that a transmission produces at least one tone descriptor.
     * Smoke check before the more detailed band/duration assertions.
     */
    @Test
    fun transmit_emitsTones()
    {
        runBlocking {
            val tones = transmitAndCollectTones("HELLO")
            assertTrue(tones.isNotEmpty(), "Expected at least one tone, got none")
        }
    }

    /**
     * Verifies that all emitted tones fall within the MFSK-16 frequency band
     * around the configured center.
     *
     * This catches a wide class of unit-conversion bugs:
     *   - Off-by-1000 errors in the millihertz unpacking
     *   - Wrong center frequency
     *   - Confusion between Hz and kHz
     *   - Wrong protocol selected (a different mode would have a different band)
     */
    @Test
    fun transmit_tonesFallInExpectedBand()
    {
        runBlocking {
            val tones = transmitAndCollectTones("HELLO")
            val bandLowHz  = MFSK16_CENTER_FREQUENCY_HZ - MFSK16_BAND_HALF_WIDTH_HZ
            val bandHighHz = MFSK16_CENTER_FREQUENCY_HZ + MFSK16_BAND_HALF_WIDTH_HZ

            val outOfBand = tones.filter { it.frequencyHz < bandLowHz || it.frequencyHz > bandHighHz }
            assertTrue(
                outOfBand.isEmpty(),
                "Expected all tones in [${bandLowHz}, ${bandHighHz}] Hz, " +
                        "but ${outOfBand.size} tones fell outside: ${outOfBand.take(5)}"
            )
        }
    }

    /**
     * Verifies that tone durations are positive and within an order of magnitude
     * of the expected samples-per-symbol at fldigi's 8 kHz sample rate.
     *
     * Expected: 8000 / 15.625 = 512 samples per symbol.
     * The test allows a generous range (100–2000) because preamble and flush
     * tones may be shorter or longer than data tones — the goal is to catch
     * obviously-wrong values like zero, negative, or millions, not to enforce
     * an exact value.
     */
    @Test
    fun transmit_durationSamplesPlausible()
    {
        runBlocking {
            val tones = transmitAndCollectTones("HELLO")

            val implausible = tones.filter { it.durationSamples !in 100..2000 }
            assertTrue(
                implausible.isEmpty(),
                "Expected all tone durations in [100, 2000] samples, " +
                        "but ${implausible.size} tones were outside: ${implausible.take(5)}"
            )
        }
    }

    /**
     * Verifies that [MFSKAndFlmsgTxHandle.abort] stops the tone stream.
     *
     * Strategy:
     *   1. Acquire TX, subscribe a collector (synchronizing via [CompletableDeferred]
     *      so we don't race against transmit).
     *   2. Start a long transmission in a background coroutine.
     *   3. Wait briefly for tones to start flowing.
     *   4. Verify some tones have been collected (otherwise the assertion
     *      below would pass trivially with `0 == 0`).
     *   5. Call abort() and record the tone count.
     *   6. Wait long enough for any in-flight emissions to land.
     *   7. Verify the tone count has not grown.
     *
     * The C++ TX state machine continues running until its byte buffer is
     * exhausted — abort() does not stop the state machine, only silences the
     * tone callback. This test verifies that silencing.
     */
    @Test
    fun abort_silencesToneStream()
    {
        runBlocking {
            val acquireResult = MFSKAndFlmsgEngine.acquireForTx(ToneMode.MFSK16, MFSK16_CENTER_FREQUENCY_HZ)
            val handle = (acquireResult as? TxAcquisitionResult.Success)?.handle
                ?: fail("Acquire failed: $acquireResult")

            try
            {
                val collectedTones = mutableListOf<ToneDescriptor>()
                val collectorReady = CompletableDeferred<Unit>()

                val collectorJob = launch(Dispatchers.IO) {
                    handle.tones
                        .onSubscription { collectorReady.complete(Unit) }
                        .collect { collectedTones.add(it) }
                }

                // Synchronize so the collector is subscribed before transmit
                // starts. Without this, the SharedFlow's replay=0 means
                // emissions before subscription are lost.
                collectorReady.await()

                // Long-ish payload so we have time to abort partway through.
                // The transmit call is synchronous on the engine thread; we run
                // it as a deferred so the test coroutine can call abort() while
                // it's in progress.
                val transmitDeferred = async(Dispatchers.IO) {
                    handle.transmit("THIS IS A LONGER MESSAGE FOR ABORT TESTING")
                }

                // Wait for some tones to flow before aborting.
                delay(100L)

                // Sanity check: if no tones came through, the rest of the test
                // is meaningless (countAtAbort == countAfterDrain == 0 would
                // pass trivially).
                assertTrue(
                    collectedTones.isNotEmpty(),
                    "Expected some tones before abort; the abort assertion below " +
                            "would otherwise pass trivially with no tones at all"
                )

                handle.abort()
                val countAtAbort = collectedTones.size

                // Let the in-flight emissions land. transmitDeferred will return
                // when the C++ TX state machine drains, which may be after this
                // delay — that's fine, we only care that no new tones reach the
                // collector after abort.
                delay(TONE_DRAIN_DELAY_MS)
                val countAfterDrain = collectedTones.size

                // Wait for the transmit call to fully complete before tearing down.
                transmitDeferred.await()
                collectorJob.cancelAndJoin()

                assertEquals(
                    countAtAbort,
                    countAfterDrain,
                    "Expected no new tones after abort, but count grew from " +
                            "$countAtAbort to $countAfterDrain"
                )
            }
            finally
            {
                handle.close()
            }
        }
    }

    // =========================================================================
    // RX behavior tests
    // =========================================================================

    /**
     * Verifies that pushing silence through the RX pipeline does not crash.
     *
     * Without a real signal we can't assert anything meaningful about the
     * decoded text — the decoder may emit garbage from internal state — but
     * "did not crash" and "returned a string of any kind" are real guarantees
     * worth verifying before the wrapper sees real audio.
     *
     * The buffer size is one second of silence at fldigi's 8 kHz sample rate,
     * a realistic chunk size for the production audio pipeline.
     */
    @Test
    fun pushAudio_silence_doesNotCrash()
    {
        runBlocking {
            val acquireResult = MFSKAndFlmsgEngine.acquireForRx(ToneMode.MFSK16, MFSK16_CENTER_FREQUENCY_HZ)
            val handle = (acquireResult as? RxAcquisitionResult.Success)?.handle
                ?: fail("Acquire failed: $acquireResult")

            try
            {
                val oneSecondOfSilence = ShortArray(8000)
                val decoded = handle.pushAudio(oneSecondOfSilence)

                // We don't assert anything about the contents — just that we got
                // a non-null string back without an exception.
                @Suppress("UNUSED_VARIABLE")
                val length = decoded.length
            }
            finally
            {
                handle.close()
            }
        }
    }

    // =========================================================================
    // MFSKAndFlmsgEncoder tests
    // =========================================================================

    /**
     * Verifies that [MFSKAndFlmsgEncoder.encode] returns [MFSKAndFlmsgEncodeResult.Success]
     * with a non-empty frequency array and a positive symbol duration.
     */
    @Test
    fun encoder_encode_returnsSuccess()
    {
        runBlocking {
            val result = MFSKAndFlmsgEncoder.encode("HELLO", MFSKMode.MFSK16)

            val success = result as? MFSKAndFlmsgEncodeResult.Success
                ?: fail("Expected Success, got: $result")

            assertTrue(
                success.symbolFrequenciesCHz.isNotEmpty(),
                "Expected non-empty frequency array"
            )
            assertTrue(
                success.symbolDurationMs > 0,
                "Expected positive symbol duration, got ${success.symbolDurationMs}ms"
            )
        }
    }

    /**
     * Verifies that [MFSKAndFlmsgEncoder.encode] returns [MFSKAndFlmsgEncodeResult.Busy]
     * when the engine is already acquired by another caller.
     */
    @Test
    fun encoder_encode_whileEngineBusy_returnsBusy()
    {
        runBlocking {
            val acquireResult = MFSKAndFlmsgEngine.acquireForTx(
                ToneMode.MFSK16,
                TX_FREQUENCY_HZ
            )
            val handle = (acquireResult as? TxAcquisitionResult.Success)?.handle
                ?: fail("Engine acquire failed: $acquireResult")

            try
            {
                val result = MFSKAndFlmsgEncoder.encode("HELLO", MFSKMode.MFSK16)
                assertEquals(
                    MFSKAndFlmsgEncodeResult.Busy,
                    result,
                    "Expected Busy while engine is held"
                )
            }
            finally
            {
                handle.close()
            }
        }
    }

    /**
     * Verifies that [MFSKAndFlmsgEncoder.encode] derives [MFSKAndFlmsgEncodeResult.Success.symbolDurationMs]
     * correctly from the first tone's [ToneDescriptor.durationSamples] and [ToneMode.sampleRate].
     *
     * The expected duration is derived independently via a direct engine encode,
     * so no symlen value is hardcoded in this test.
     */
    @Test
    fun encoder_encode_symbolDurationMatchesToneDescriptor()
    {
        runBlocking {
            // Derive expected duration from a direct engine call
            val expectedDurationMs = transmitAndCollectTones("HELLO").let { tones ->
                val firstTone = tones.firstOrNull()
                    ?: fail("Direct engine encode produced no tones")
                (firstTone.durationSamples.toDouble() / ToneMode.MFSK16.sampleRate() * 1000).toLong()
            }

            val result = MFSKAndFlmsgEncoder.encode("HELLO", MFSKMode.MFSK16)
            val success = result as? MFSKAndFlmsgEncodeResult.Success
                ?: fail("Expected Success, got: $result")

            assertEquals(
                expectedDurationMs,
                success.symbolDurationMs,
                "symbolDurationMs should match durationSamples / sampleRate * 1000"
            )
        }
    }

    // =========================================================================
    // Test helpers
    // =========================================================================

    /**
     * Acquires a TX handle, transmits [text], collects all emitted tones, and
     * releases the handle. Used by the TX behavior tests that share the same
     * setup pattern.
     *
     * Subscription ordering matters: the underlying [MutableSharedFlow] uses
     * `replay = 0`, so any tones emitted before a collector is subscribed are
     * lost to that collector. This helper uses [onSubscription] plus a
     * [CompletableDeferred] barrier to guarantee the collector is fully
     * subscribed before [text] is transmitted.
     *
     * @return All tones emitted during the transmission, in order.
     */
    private suspend fun transmitAndCollectTones(text: String): List<ToneDescriptor> = coroutineScope {
        val acquireResult = MFSKAndFlmsgEngine.acquireForTx(ToneMode.MFSK16, MFSK16_CENTER_FREQUENCY_HZ)
        val handle = (acquireResult as? TxAcquisitionResult.Success)?.handle
            ?: fail("Acquire failed: $acquireResult")

        try
        {
            val collectedTones = mutableListOf<ToneDescriptor>()

            // Barrier: completed by the collector when it actually subscribes.
            // Without this, the collector launch returns immediately but the
            // coroutine itself is still queued on the dispatcher when transmit
            // begins. The native TX runs synchronously and emits all tones
            // before the collector ever attaches, and with replay=0 those
            // tones are lost to a late subscriber.
            val collectorReady = CompletableDeferred<Unit>()

            val collectorJob: Job = launch(Dispatchers.IO) {
                handle.tones
                    .onSubscription { collectorReady.complete(Unit) }
                    .collect {
                        Timber.d("TEST collector received tone: $it")
                        collectedTones.add(it) }
            }

            // Wait for the collector to be a registered subscriber before
            // transmitting. onSubscription runs after the upstream flow has
            // accepted us as a subscriber, so once this await returns,
            // tryEmit calls into the SharedFlow will reach our collector.
            collectorReady.await()

            handle.transmit(text)

            // Brief settle so the very last emissions reach the collector.
            // transmit() is synchronous-on-engine-thread so by the time it
            // returns, all tones have been pushed to the SharedFlow buffer;
            // this delay covers the buffer-to-collector handoff.
            delay(TONE_DRAIN_DELAY_MS)

            // cancelAndJoin (rather than cancel) so the collector is fully
            // torn down before we return to the test. A bare cancel() leaves
            // the collector coroutine alive on Dispatchers.IO until the
            // cancellation propagates, which can wedge subsequent tests
            // that share the IO thread pool.
            collectorJob.cancelAndJoin()

            collectedTones.toList()
        }
        finally
        {
            handle.close()
        }
    }
}