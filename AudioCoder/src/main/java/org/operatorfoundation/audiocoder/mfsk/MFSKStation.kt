package org.operatorfoundation.audiocoder.mfsk

import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Instant

/**
 * Streaming MFSK receive/transmit station.
 *
 * Manages the full lifecycle of an MFSK audio session: initializing the audio source,
 * continuously decoding the incoming audio stream, and emitting completed messages.
 *
 * ## Framing
 * Messages are framed as `<base64(payload)>` and Varicode-encoded before transmission.
 * This produces standard MFSK-16 text traffic decodable by any compliant receiver.
 * The framing scheme is an implementation detail of this class —
 * callers receive and supply raw bytes only.
 *
 * Use [framePayload] to prepare bytes for transmission via external hardware TX paths.
 *
 * ## Threading
 * The receive loop runs on [Dispatchers.IO] in an internally owned coroutine. All state
 * transitions and message emissions are safe to observe from any coroutine context.
 * [start] and [stop] are suspend functions and must be called from a coroutine.
 *
 * ## Lifecycle
 * ```
 * Idle → Starting → Listening → Idle      (clean stop)
 *                 → Error                 (unrecoverable failure)
 * ```
 * [stop] is the only intended shutdown path. It cancels the internal coroutine and calls
 * [MFSKAudioSource.cleanup], guaranteeing the audio source is left in a clean state.
 *
 * @param audioSource   Audio source providing 16-bit PCM at [MFSKConfiguration.sampleRate].
 * @param configuration Mode, frequency, and timing parameters for this session.
 */
class MFSKStation(
    private val audioSource: MFSKAudioSource,
    private val configuration: MFSKConfiguration
)
{
    private val _stationState = MutableStateFlow<MFSKStationState>(MFSKStationState.Idle)
    val stationState: StateFlow<MFSKStationState> = _stationState.asStateFlow()

    private val _messages = MutableSharedFlow<MFSKMessage>(replay = 0)
    val messages: SharedFlow<MFSKMessage> = _messages.asSharedFlow()

    private var stationJob: Job? = null

    // Precomputed from configuration — constant for the lifetime of this station instance.
    private val samplesPerSymbol = configuration.mode.samplesPerSymbol(configuration.sampleRate)
    private val toneFrequencies  = DoubleArray(configuration.mode.toneCount) { i ->
        configuration.baseFrequencyHz + i * configuration.mode.toneSpacingHz
    }

    // Duration of one symbol in ms — minimum 1ms to avoid zero-length read requests.
    private val symbolDurationMs = (configuration.mode.symbolDurationSeconds * 1000)
        .toLong()
        .coerceAtLeast(1L)

    // -------------------------------------------------------------------------
    // Companion object
    // -------------------------------------------------------------------------

    companion object
    {
        private const val FRAME_START = '<'
        private const val FRAME_END   = '>'

        /**
         * Frames [data] for MFSK transmission as `<base64(data)>`.
         *
         * The returned string is pure ASCII and suitable for direct input to
         * [MFSKEncoder.encode] or [MFSKEncoder.encodeToSymbols]. Both the transmit
         * path through this station and external hardware TX paths must apply this
         * framing before encoding.
         *
         * @param data Raw bytes to frame (e.g. ciphertext).
         * @return Framed ASCII string ready for Varicode encoding.
         */
        fun framePayload(data: ByteArray): String
        {
            val base64 = Base64.getEncoder().encodeToString(data)
            return "$FRAME_START$base64$FRAME_END"
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Initializes the audio source and starts the receive loop.
     *
     * Idempotent - calling [start] on an already-running station returns success immediately
     * without reinitializing the audio source or restarting the loop.
     *
     * @return Success if the station started (or was already running),
     *         Failure if audio source initialization failed.
     */
    suspend fun start(): Result<Unit>
    {
        if (stationJob?.isActive == true) return Result.success(Unit)

        _stationState.value = MFSKStationState.Starting

        val initResult = audioSource.initialize()
        if (initResult.isFailure)
        {
            val cause = initResult.exceptionOrNull()
                ?: Exception("Audio source initialization failed")
            _stationState.value = MFSKStationState.Error(cause)
            return Result.failure(cause)
        }

        stationJob = CoroutineScope(Dispatchers.IO + Job()).launch {
            // State is set to Listening inside the coroutine rather than in start() to avoid
            // a race where a fast failure inside the coroutine sets Error, then start()
            // overwrites it with Listening after the fact.
            _stationState.value = MFSKStationState.Listening
            executeReceiveLoop()
        }

        return Result.success(Unit)
    }

    /**
     * Stops the receive loop and cleans up the audio source.
     *
     * Blocks until the coroutine has fully stopped before calling [MFSKAudioSource.cleanup],
     * ensuring the audio source is never cleaned up while a decode is in progress.
     */
    suspend fun stop()
    {
        stationJob?.cancel()
        stationJob?.join()
        stationJob = null
        audioSource.cleanup()
        _stationState.value = MFSKStationState.Idle
    }

    /**
     * Encodes [data] as a framed MFSK audio signal ready for transmission.
     *
     * Applies [framePayload] framing, Varicode-encodes the result, then modulates
     * as PCM audio. The returned samples are decodable by any compliant MFSK-16
     * receiver.
     *
     * This method does not require the station to be started — it is pure
     * computation with no lifecycle dependency.
     *
     * @param data Raw bytes to encode.
     * @return 16-bit PCM samples representing the framed MFSK signal.
     */
    fun encode(data: ByteArray): ShortArray
    {
        return MFSKEncoder.encode(
            text            = framePayload(data),
            mode            = configuration.mode,
            baseFrequencyHz = configuration.baseFrequencyHz,
            sampleRate      = configuration.sampleRate,
            amplitude       = configuration.amplitude
        )
    }

    // -------------------------------------------------------------------------
    // Receive loop
    // -------------------------------------------------------------------------

    private suspend fun executeReceiveLoop()
    {
        val varicodeDecoder = Varicode.Decoder()
        val payloadBuffer   = StringBuilder()
        var insideFrame     = false

        try
        {
            while (currentCoroutineContext().isActive)
            {
                try
                {
                    withTimeout(configuration.timeoutMs)
                    {
                        // Accumulate exactly one symbol period of audio.
                        val samples = accumulateSamples(samplesPerSymbol)

                        // Run Goertzel filter bank, pick the highest-energy tone.
                        val energies        = DoubleArray(configuration.mode.toneCount) { i ->
                            GoertzelFilter.energy(samples, toneFrequencies[i], configuration.sampleRate)
                        }
                        // maxByOrNull is safe, toneCount is always >= 8 by MFSKMode's design.
                        val winnerToneIndex = energies.indices.maxByOrNull { energies[it] }!!

                        // Extract bitsPerSymbol bits from the winner, MSB-first,
                        // and feed each into the Varicode decoder.
                        for (bitOffset in 0 until configuration.mode.bitsPerSymbol)
                        {
                            val bitInSymbol = configuration.mode.bitsPerSymbol - 1 - bitOffset
                            val bit         = ((winnerToneIndex ushr bitInSymbol) and 1) == 1

                            val decodedChar = varicodeDecoder.feed(bit) ?: continue

                            when
                            {
                                decodedChar == FRAME_START ->
                                {
                                    // Start of a new frame, reset any partial payload
                                    // from a previous incomplete or corrupted transmission.
                                    payloadBuffer.clear()
                                    insideFrame = true
                                }

                                decodedChar == FRAME_END && insideFrame ->
                                {
                                    // End of frame, attempt base64 decode and emit.
                                    insideFrame = false
                                    val base64   = payloadBuffer.toString()
                                    payloadBuffer.clear()

                                    try
                                    {
                                        val bytes = Base64.getDecoder().decode(base64)
                                        _messages.emit(
                                            MFSKMessage(
                                                data       = bytes,
                                                receivedAt = Instant.now(),
                                                mode       = configuration.mode
                                            )
                                        )
                                        audioSource.flushBuffer()
                                    }
                                    catch (e: IllegalArgumentException)
                                    {
                                        // Malformed base64, discard and continue listening.
                                        // This can happen if the signal was corrupted mid-frame.
                                    }
                                }

                                insideFrame ->
                                {
                                    payloadBuffer.append(decodedChar)
                                }
                            }
                        }
                    }
                }
                catch (e: TimeoutCancellationException)
                {
                    // Timeout with no complete message, reset state and retry.
                    varicodeDecoder.reset()
                    payloadBuffer.clear()
                    insideFrame = false
                    audioSource.flushBuffer()
                }
            }
        }
        catch (e: CancellationException)
        {
            // Normal shutdown via stop(). Rethrow so coroutine machinery cleans up correctly.
            throw e
        }
        catch (e: Exception)
        {
            _stationState.value = MFSKStationState.Error(e)
        }
    }

    // -------------------------------------------------------------------------
    // Audio accumulation
    // -------------------------------------------------------------------------

    /**
     * Reads from [audioSource] in symbol-sized chunks until exactly [sampleCount] samples
     * have been accumulated.
     *
     * If the audio source returns an empty chunk (buffer underrun), a short delay prevents
     * busy-waiting while the source catches up. Excess samples from oversized chunks are
     * discarded to maintain symbol alignment.
     *
     * @param sampleCount Exact number of samples to accumulate.
     * @return Fully populated [ShortArray] of length [sampleCount].
     */
    private suspend fun accumulateSamples(sampleCount: Int): ShortArray
    {
        val buffer      = ShortArray(sampleCount)
        var accumulated = 0

        while (accumulated < sampleCount)
        {
            val chunk = audioSource.readAudioChunk(symbolDurationMs)

            if (chunk.isEmpty())
            {
                delay(10)
                continue
            }

            val copyCount = minOf(chunk.size, sampleCount - accumulated)
            chunk.copyInto(buffer, accumulated, 0, copyCount)
            accumulated += copyCount
        }

        return buffer
    }
}