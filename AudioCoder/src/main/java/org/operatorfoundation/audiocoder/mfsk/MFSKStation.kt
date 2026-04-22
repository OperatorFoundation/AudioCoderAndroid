package org.operatorfoundation.audiocoder.mfsk

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Instant
import kotlin.math.ceil

/**
 * Streaming MFSK receive/transmit station.
 *
 * Manages the full lifecycle of an MFSK audio session: initializing the audio source,
 * continuously decoding the incoming audio stream, and emitting completed messages.
 *
 * ## Framing
 * Every message is prefixed with a 2-byte big-endian unsigned length field, written and
 * read by [encode] and the receive loop respectively. This allows the decoder to know how
 * many payload bytes to accumulate before emitting a message. The maximum frameable payload
 * is 65,535 bytes — well above any practical MFSK message over HF or VHF.
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
 * @param audioSource     Audio source providing 16-bit PCM at [MFSKConfiguration.sampleRate].
 * @param configuration   Mode, frequency, and timing parameters for this session.
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

    // Samples needed to carry the 2-byte (UShort) length prefix.
    private val prefixSymbolCount = ceil(16.0 / configuration.mode.bitsPerSymbol).toInt()
    private val prefixSampleCount = prefixSymbolCount * samplesPerSymbol

    // Chunk size for readAudioChunk calls — one symbol period, at least 1ms.
    private val symbolDurationMs = (configuration.mode.symbolDurationSeconds * 1000)
        .toLong()
        .coerceAtLeast(1L)

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Initializes the audio source and starts the receive loop.
     *
     * Idempotent — calling [start] on an already-running station returns success immediately
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
     * Encodes [data] as MFSK audio with a 2-byte big-endian length prefix prepended.
     *
     * The returned PCM samples are ready for transmission. This method does not require
     * the station to be started — it is pure computation with no lifecycle dependency.
     *
     * @param data Payload bytes to encode. Must not exceed 65,535 bytes.
     * @return 16-bit PCM samples representing the framed MFSK signal.
     */
    fun encode(data: ByteArray): ShortArray
    {
        require(data.size <= UShort.MAX_VALUE.toInt()) {
            "data length ${data.size} exceeds maximum frameable size of ${UShort.MAX_VALUE}"
        }

        // Length prefix: 2 bytes, big-endian — consistent with MSB-first bit ordering used
        // throughout the encoder and decoder.
        val lengthPrefix = byteArrayOf(
            (data.size ushr 8).toByte(),
            (data.size and 0xFF).toByte()
        )

        return MFSKEncoder.encode(
            data            = lengthPrefix + data,
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
        try
        {
            // currentCoroutineContext().isActive is used rather than isActive because
            // isActive is an extension on CoroutineScope and is not directly accessible
            // inside a suspend function.
            while (currentCoroutineContext().isActive)
            {
                try
                {
                    withTimeout(configuration.timeoutMs)
                    {
                        // Phase 1: decode the 2-byte length prefix.
                        val prefixBytes = MFSKDecoder.decode(
                            samples         = accumulateSamples(prefixSampleCount),
                            mode            = configuration.mode,
                            baseFrequencyHz = configuration.baseFrequencyHz,
                            sampleRate      = configuration.sampleRate,
                            byteCount       = 2
                        )

                        // Reconstruct big-endian UShort. The `and 0xFF` mask is required
                        // because Kotlin Byte is signed — bytes >= 128 without the mask
                        // would sign-extend to negative Int values when shifted, corrupting
                        // the reconstructed length.
                        val messageLength = ((prefixBytes[0].toInt() and 0xFF) shl 8) or
                                (prefixBytes[1].toInt() and 0xFF)

                        // A zero-length decode indicates noise rather than a real transmission.
                        // Flush and retry rather than attempting to accumulate zero payload
                        // samples, which would busy-loop indefinitely.
                        if (messageLength == 0)
                        {
                            audioSource.flushBuffer()
                            return@withTimeout
                        }

                        // Phase 2: decode the payload.
                        val payloadSampleCount = ceil(
                            messageLength * 8.0 / configuration.mode.bitsPerSymbol
                        ).toInt() * samplesPerSymbol

                        val payloadBytes = MFSKDecoder.decode(
                            samples         = accumulateSamples(payloadSampleCount),
                            mode            = configuration.mode,
                            baseFrequencyHz = configuration.baseFrequencyHz,
                            sampleRate      = configuration.sampleRate,
                            byteCount       = messageLength
                        )

                        _messages.emit(MFSKMessage(
                            data       = payloadBytes,
                            receivedAt = Instant.now(),
                            mode       = configuration.mode
                        ))

                        // Flush before the next accumulation to prevent tail samples from
                        // the just-received transmission corrupting the next symbol boundary.
                        audioSource.flushBuffer()
                    }
                }
                catch (e: TimeoutCancellationException)
                {
                    // Timeout waiting for a complete message. Not fatal — flush and retry.
                    // The station remains in Listening state.
                    audioSource.flushBuffer()
                }
            }
        }
        catch (e: CancellationException)
        {
            // Normal shutdown via stop(). Rethrow so the coroutine machinery cleans up correctly.
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
     * Samples from oversized chunks are discarded to maintain symbol alignment. If the
     * audio source returns an empty chunk (buffer underrun), a short delay prevents
     * busy-waiting while the source catches up.
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
                // Source has nothing yet — yield briefly to avoid spinning.
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