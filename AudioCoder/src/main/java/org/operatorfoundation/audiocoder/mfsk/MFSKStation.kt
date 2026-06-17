package org.operatorfoundation.audiocoder.mfsk

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.time.Instant
import kotlin.math.*

/**
 * Streaming MFSK-16 receive station.
 *
 * Consumes a [Flow<ShortArray>] of 16-bit PCM audio and emits [MFSKMessage] for each
 * complete text frame (CR STX CR ... data ... CR EOT CR) decoded from the signal.
 *
 * ## Receive pipeline (per audio sample, mirroring fldigi's rx_process)
 * ```
 * raw sample
 *   → HilbertFilter       — real → complex analytic signal
 *   → Mixer               — shift signal to fixed reference frequency
 *   → BandpassFilter      — remove out-of-band interference
 *   → SlidingDFT          — compute per-tone complex energies, fill pipe buffer
 *   → (every samplesPerSymbol samples):
 *       → harddecode       — pick highest-energy tone, update CWI and AFC metrics
 *       → softdecode       — form 4 soft-decision bytes, de-interleave
 *       → decodesymbol ×4 — dual Viterbi FEC decode, gated by symcounter
 *       → recvbit          — IZ8BLY Varicode shift-register decode
 *       → handleCharacter  — frame detection (STX/EOT), emit MFSKMessage on complete frame
 *       → synchronize      — adjust symbol clock using pipe buffer energy profile
 *       → afc              — track carrier frequency drift using complex phase
 * ```
 *
 * ## Threading
 * The receive loop runs on [Dispatchers.IO]. All state is confined to that coroutine.
 * [stationState] and [receivedMessages] are safe to observe from any coroutine context.
 *
 * ## Lifecycle
 * Idle → Listening (on [start]) → Idle (on [stop] or unrecoverable error).
 *
 * @param audioStream   Flow of raw PCM audio chunks at [MFSKConfiguration.sampleRate].
 * @param configuration Mode, base frequency, and timing parameters for this session.
 */
class MFSKStation(
    private val audioStream: Flow<ShortArray>,
    private val configuration: MFSKConfiguration
)
{
    // =========================================================================
    // Observable state
    // =========================================================================

    private val _stationState = MutableStateFlow<MFSKStationState>(MFSKStationState.Idle)
    val stationState: StateFlow<MFSKStationState> = _stationState.asStateFlow()

    private val _receivedMessages = MutableSharedFlow<MFSKMessage>(replay = 0)
    val receivedMessages: SharedFlow<MFSKMessage> = _receivedMessages.asSharedFlow()

    private var stationJob: Job? = null

    // =========================================================================
    // Derived configuration — computed once from MFSKConfiguration
    // =========================================================================

    private val mode               = configuration.mode
    private val sampleRate         = configuration.sampleRate
    private val samplesPerSymbol   = mode.samplesPerSymbol(sampleRate)
    private val toneCount          = mode.toneCount
    private val bitsPerSymbol      = mode.bitsPerSymbol

    // Tone spacing = sample rate / samples-per-symbol = baud rate (Hz).
    // Equals mode.baudRate but computed from the actual sample rate for precision.
    private val toneSpacingHz      = sampleRate.toDouble() / samplesPerSymbol

    // Base tone index: which DFT bin corresponds to tone 0.
    // Quantized so the tone frequencies fall exactly on DFT bin boundaries.
    private val baseToneIndex      = (configuration.baseFrequencyHz / toneSpacingHz).roundToInt()

    // Actual base frequency after quantization (may differ slightly from configured).
    private val quantizedBaseFreqHz = baseToneIndex * toneSpacingHz

    // Total occupied bandwidth of the MFSK signal (Hz).
    private val bandwidth          = (toneCount - 1) * toneSpacingHz

    // Bandpass filter cutoffs (normalized, fraction of sample rate).
    // Coverage: tone 0 down to 2 spacings below, tone N-1 up to 2 spacings above.
    // Matches fldigi's bpfilt computation in the mfsk constructor.
    private val centerFreqHz       = quantizedBaseFreqHz + bandwidth / 2.0
    private val bpFilterLowCutoff  = (centerFreqHz - bandwidth / 2.0 - 2.0 * toneSpacingHz) / sampleRate
    private val bpFilterHighCutoff = (centerFreqHz + bandwidth / 2.0 + 2.0 * toneSpacingHz) / sampleRate

    // =========================================================================
    // DSP pipeline components — stateful, reset on each session start
    // =========================================================================

    private val hilbertFilter  = HilbertFilter()
    private val bandpassFilter = BandpassFilter(bpFilterLowCutoff, bpFilterHighCutoff)
    private val slidingDFT     = SlidingDFT(samplesPerSymbol, baseToneIndex, baseToneIndex + toneCount)

    // Interleaver (receive direction — pre-filled with PUNCTURE = 128).
    private val rxInterleaver = MFSKInterleaver.createForReceive(
        size  = bitsPerSymbol,
        depth = MFSKEncoder.INTERLEAVER_DEPTH
    )

    // Viterbi decoders.
    // dec1 and dec2 mirror fldigi's dual-decoder scheme:
    //   - Even bitsPerSymbol (MFSK-16: 4): only dec2 is used (symcounter gate).
    //   - Odd bitsPerSymbol (MFSK-8: 3, MFSK-32: 5): both are used with metric comparison.
    private val viterbiDecoder1 = ViterbiDecoder(
        ConvolutionalEncoder.CONSTRAINT_LENGTH,
        ConvolutionalEncoder.GENERATOR_POLY_1,
        ConvolutionalEncoder.GENERATOR_POLY_2
    ).apply {
        setTraceback(ViterbiDecoder.MFSK_TRACEBACK)
        setChunkSize(ViterbiDecoder.MFSK_CHUNK_SIZE)
    }
    private val viterbiDecoder2 = ViterbiDecoder(
        ConvolutionalEncoder.CONSTRAINT_LENGTH,
        ConvolutionalEncoder.GENERATOR_POLY_1,
        ConvolutionalEncoder.GENERATOR_POLY_2
    ).apply {
        setTraceback(ViterbiDecoder.MFSK_TRACEBACK)
        setChunkSize(ViterbiDecoder.MFSK_CHUNK_SIZE)
    }

    private val varicodeDecoder = Varicode.Decoder()

    // Moving average filter for symbol sync (length 8, matches fldigi's Cmovavg(8)).
    private val syncFilter = MovingAverage(8)

    // =========================================================================
    // Per-session mutable state — reset on each session start
    // =========================================================================

    private var chunkCount = 0

    // Mixer: current carrier frequency (Hz) and accumulated phase.
    // Starts at the center of the MFSK band. AFC updates currentFrequencyHz over time.
    private var currentFrequencyHz = configuration.baseFrequencyHz + bandwidth / 2.0
    private var mixerPhaseAccumulator = 0.0

    // Symbol timing: counts samples until the next symbol decision.
    private var sampleCountdown = samplesPerSymbol

    // Current and previous tone decisions (indices into [0, toneCount)).
    private var currentSymbol  = 0
    private var prevSymbol1    = 0
    private var prevSymbol2    = 0

    // Complex DFT vector for the current and previous symbol (for AFC).
    // Indices [0]=real, [1]=imag.
    private val currentVectorBuf  = DoubleArray(2)
    private val prevVector1Buf    = DoubleArray(2)

    // Symbol counter for the dual-Viterbi gate.
    // Alternates 0 ↔ 1. For even bitsPerSymbol, only symcounter==0 invokes the decoder.
    private var symcounter = 0

    // Sliding symbol pair fed to the Viterbi decoders.
    // symbolPair[0] = previous soft byte, symbolPair[1] = current soft byte.
    private val symbolPair = ByteArray(2)

    // Exponential-decay Viterbi metric accumulators (for dual-decoder metric comparison).
    private var met1 = 0.0
    private var met2 = 0.0

    // Scaled signal quality metric (for squelch comparison).
    private var signalMetric = 0.0

    // AFC state.
    private var freqErr    = 0.0   // exponential average frequency error (Hz)
    private var afcMetric  = 0.0   // AFC confidence: only adjust freq when this is high enough

    // CWI (Continuous Wave Interference) detection counters — one per tone bin.
    // Tracks how many consecutive symbol periods each tone has been the dominant one.
    // Tones that repeatedly win are considered CW interferers and are soft-punctured.
    private val cwiCounters = IntArray(toneCount)

    // Whether the most recent symbol period showed a static noise burst (all tones active).
    private var staticBurst = false

    // Frame detection state.
    private val frameBuffer = StringBuilder()
    private var insideFrame = false

    // Reusable arrays for bin values (allocated once, reused every symbol).
    private val currentBinsReal = DoubleArray(toneCount)
    private val currentBinsImag = DoubleArray(toneCount)

    // Metric result array — single-element, passed to ViterbiDecoder.decode().
    private val metricResult = IntArray(1)

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /**
     * Starts the receive loop and begins decoding the audio stream.
     * Idempotent: calling while already running returns success immediately.
     */
    suspend fun start(): Result<Unit>
    {
        if (stationJob?.isActive == true) return Result.success(Unit)

        resetSessionState()
        _stationState.value = MFSKStationState.Listening

        stationJob = CoroutineScope(Dispatchers.IO + Job()).launch {
            try
            {
                audioStream.collect { chunk -> processChunk(chunk) }
            }
            catch (e: CancellationException)
            {
                throw e
            }
            catch (e: Exception)
            {
                _stationState.value = MFSKStationState.Error(e)
            }
            finally
            {
                _stationState.value = MFSKStationState.Idle
            }
        }

        return Result.success(Unit)
    }

    /**
     * Stops the receive loop. Blocks until the coroutine has fully stopped.
     */
    suspend fun stop()
    {
        stationJob?.cancel()
        stationJob?.join()
        stationJob = null
        _stationState.value = MFSKStationState.Idle
    }

    // =========================================================================
    // Audio chunk processing
    // =========================================================================

    /**
     * Processes one chunk of PCM audio. Each Short sample is fed individually
     * through the DSP pipeline. Symbol decisions are made every [samplesPerSymbol] samples.
     */
    private suspend fun processChunk(chunk: ShortArray)
    {
        chunkCount++
        if (chunkCount % 100 == 0) Timber.d("MFSKStation: processed $chunkCount chunks")

        for (sample in chunk)
        {
            processSample(sample.toDouble())
        }
    }

    /**
     * Processes one audio sample through the full DSP chain.
     *
     * Matches fldigi's per-sample body of rx_process():
     *   create analytic signal → mix → bandpass → update sliding DFT →
     *   (on symbol boundary) make symbol decision
     */
    private suspend fun processSample(rawSample: Double)
    {
        // Step 1: Hilbert filter — real input → complex analytic signal.
        val hilbertOutReal = DoubleArray(1)
        val hilbertOutImag = DoubleArray(1)
        hilbertFilter.run(rawSample, hilbertOutReal, hilbertOutImag)

        // Step 2: Mixer — frequency-shift to fixed reference position.
        // After mixing, tone[baseToneIndex] lands at (baseToneIndex * toneSpacingHz) Hz,
        // which is exactly where the SlidingDFT's first bin sits.
        val freqAdjustment = currentFrequencyHz - toneSpacingHz * baseToneIndex - bandwidth / 2.0
        val cosPhase = cos(mixerPhaseAccumulator)
        val sinPhase = sin(mixerPhaseAccumulator)
        val mixedReal = hilbertOutReal[0] * cosPhase - hilbertOutImag[0] * sinPhase
        val mixedImag = hilbertOutReal[0] * sinPhase + hilbertOutImag[0] * cosPhase
        mixerPhaseAccumulator -= 2.0 * PI * freqAdjustment / sampleRate
        if (mixerPhaseAccumulator < 0) mixerPhaseAccumulator += 2.0 * PI

        // Step 3: Bandpass filter — remove out-of-band energy.
        val bpOutReal = DoubleArray(1)
        val bpOutImag = DoubleArray(1)
        bandpassFilter.run(mixedReal, mixedImag, bpOutReal, bpOutImag)

        // Step 4: Sliding DFT — update all tone bin energies and the pipe buffer.
        slidingDFT.run(bpOutReal[0], bpOutImag[0])

        // Step 5: Symbol decision — triggered once per symbol period.
        if (--sampleCountdown <= 0)
        {
            sampleCountdown = samplesPerSymbol
            slidingDFT.copyCurrentBins(currentBinsReal, currentBinsImag)
            makeSymbolDecision()
        }
    }

    // =========================================================================
    // Symbol decision — called once per symbol period
    // =========================================================================

    /**
     * Performs the full symbol-level processing when the sample counter expires.
     *
     * Matches fldigi's block inside `if (--synccounter <= 0)` in rx_process().
     */
    private suspend fun makeSymbolDecision()
    {
        currentSymbol = harddecode()
        currentVectorBuf[0] = currentBinsReal[currentSymbol]
        currentVectorBuf[1] = currentBinsImag[currentSymbol]

        softdecode()
        synchronize()
        afc()

        // Rotate the symbol history (prev2 ← prev1 ← current).
        prevSymbol2    = prevSymbol1
        prevVector1Buf[0] = currentVectorBuf[0]
        prevVector1Buf[1] = currentVectorBuf[1]
        prevSymbol1    = currentSymbol
    }

    // =========================================================================
    // Hard decode — pick the highest-energy tone
    // =========================================================================

    /**
     * Returns the index of the tone bin with the greatest magnitude.
     * Also updates [afcMetric], [staticBurst], and [cwiCounters] for subsequent use.
     *
     * Direct translation of fldigi's mfsk::harddecode().
     */
    private fun harddecode(): Int
    {
        // Compute magnitudes for all bins.
        var magnitudeSum = 0.0
        for (i in 0 until toneCount)
        {
            val re = currentBinsReal[i]; val im = currentBinsImag[i]
            magnitudeSum += sqrt(re * re + im * im)
        }
        val averageMagnitude = maxOf(magnitudeSum / toneCount, 1e-20)

        var maxMagnitude = 0.0
        var winnerIndex  = 0
        var burstCount   = 0

        for (i in 0 until toneCount)
        {
            val re = currentBinsReal[i]; val im = currentBinsImag[i]
            val mag = sqrt(re * re + im * im)
            if (mag > maxMagnitude) { maxMagnitude = mag; winnerIndex = i }
            if (mag > 2.0 * averageMagnitude) burstCount++
        }

        staticBurst = (burstCount == toneCount)

        afcMetric = if (staticBurst) 0.0
        else decayAverage(afcMetric, 2.0 * maxMagnitude / averageMagnitude, weight = 20)

        return winnerIndex
    }

    // =========================================================================
    // Soft decode — form soft-decision bytes and feed into the FEC pipeline
    // =========================================================================

    /**
     * Forms [bitsPerSymbol] soft-decision bytes from the current tone bin magnitudes,
     * de-interleaves them, then feeds each through [decodesymbol].
     *
     * Soft byte value 0 = strong '0', 128 = uncertain, 255 = strong '1'.
     *
     * Includes CWI (Continuous Wave Interference) avoidance: tones that have
     * dominated for [CWI_MAX_COUNT] consecutive symbol periods are soft-punctured
     * (replaced by the average magnitude) so they don't corrupt the FEC input.
     *
     * Direct translation of fldigi's mfsk::softdecode().
     */
    private suspend fun softdecode()
    {
        // Compute total magnitude, excluding CWI-flagged tones.
        var magnitudeSum = 0.0
        for (i in 0 until toneCount)
        {
            if (cwiCounters[i] < CWI_MAX_COUNT)
            {
                val re = currentBinsReal[i]; val im = currentBinsImag[i]
                magnitudeSum += sqrt(re * re + im * im)
            }
        }
        val magnitudeSum2 = maxOf(magnitudeSum, 1e-10)
        val averageMagnitude = magnitudeSum / toneCount

        // Update CWI counters based on this symbol's hard-decode winner.
        // Tone 0 is excluded (cannot be a CWI tone) — same as fldigi.
        for (i in 1 until toneCount)
        {
            if (i == currentSymbol) cwiCounters[i]++
            else                    cwiCounters[i] = maxOf(0, cwiCounters[i] - 1)
            if (cwiCounters[i] > CWI_MAX_COUNT) cwiCounters[i] = CWI_MAX_COUNT + 1
        }

        // Accumulate per-bit soft evidence using Gray-decoded tone energies.
        // For each tone bin, Gray-decode its index to get the bit pattern it represents,
        // then add ±magnitude to each bit's accumulator.
        val bitAccumulators = DoubleArray(bitsPerSymbol)
        for (toneIndex in 0 until toneCount)
        {
            val grayDecoded = grayDecode(toneIndex)

            val re = currentBinsReal[toneIndex]; val im = currentBinsImag[toneIndex]
            val binMagnitude = when
            {
                cwiCounters[toneIndex] > CWI_MAX_COUNT ->
                    averageMagnitude  // puncture: replace CWI tone with average
                toneIndex == currentSymbol ->
                    2.0 * sqrt(re * re + im * im)  // give hard-decode winner extra weight
                else ->
                    sqrt(re * re + im * im)
            }

            for (bitPos in 0 until bitsPerSymbol)
            {
                val bitIsSet = (grayDecoded and (1 shl (bitsPerSymbol - 1 - bitPos))) != 0
                if (bitIsSet) bitAccumulators[bitPos] += binMagnitude
                else          bitAccumulators[bitPos] -= binMagnitude
            }
        }

        // Scale accumulators to [0, 255] soft bytes.
        // staticBurst → puncture (128). Normal → clamp(128 + accumulator/sum * 256, 0, 255).
        val softBytes = ByteArray(bitsPerSymbol) { bitPos ->
            if (staticBurst) 128.toByte()
            else (128.0 + bitAccumulators[bitPos] / magnitudeSum2 * 256.0)
                .coerceIn(0.0, 255.0).toInt().toByte()
        }

        // De-interleave the soft bytes in place.
        rxInterleaver.deinterleaveSymbols(softBytes)

        // Feed each de-interleaved soft byte into the FEC decoder.
        for (softByte in softBytes)
        {
            decodesymbol(softByte)
        }
    }

    // =========================================================================
    // FEC decoding — dual Viterbi with metric comparison
    // =========================================================================

    /**
     * Feeds one de-interleaved soft byte into the Viterbi decode pipeline.
     *
     * Uses the dual-decoder scheme from fldigi's mfsk::decodesymbol():
     * - symcounter alternates 0 ↔ 1 on each call.
     * - Even bitsPerSymbol (MFSK-16): decode only when symcounter == 0 (dec2 only).
     * - Odd bitsPerSymbol (MFSK-8, MFSK-32): both decoders run on alternate calls;
     *   the one with the higher metric wins and its decoded bit goes to [recvbit].
     *
     * The symbolPair sliding window accumulates pairs of consecutive soft bytes —
     * one pair per original input bit (R=1/2 rate means 2 soft bytes per decoded bit).
     */
    private suspend fun decodesymbol(softByte: Byte)
    {
        symbolPair[0] = symbolPair[1]
        symbolPair[1] = softByte
        symcounter    = if (symcounter != 0) 0 else 1

        val oddBitsPerSymbol = bitsPerSymbol == 3 || bitsPerSymbol == 5 || bitsPerSymbol == 7
        val decodedBit: Int

        if (oddBitsPerSymbol)
        {
            if (symcounter != 0)
            {
                // dec1 path (odd symcounter)
                val decoded = viterbiDecoder1.decode(symbolPair, metricResult)
                if (decoded == -1) return
                met1 = decayAverage(met1, metricResult[0].toDouble(), weight = 32)
                if (met1 < met2) return
                signalMetric = met1 / 1.5
                decodedBit = decoded
            }
            else
            {
                // dec2 path (even symcounter)
                val decoded = viterbiDecoder2.decode(symbolPair, metricResult)
                if (decoded == -1) return
                met2 = decayAverage(met2, metricResult[0].toDouble(), weight = 32)
                if (met2 < met1) return
                signalMetric = met2 / 1.5
                decodedBit = decoded
            }
        }
        else
        {
            // Even bitsPerSymbol — only dec2, only when symcounter == 0.
            if (symcounter != 0) return
            val decoded = viterbiDecoder2.decode(symbolPair, metricResult)
            if (decoded == -1) return
            met2         = decayAverage(met2, metricResult[0].toDouble(), weight = 32)
            signalMetric = met2 / 1.5
            decodedBit   = decoded
        }

        // Rescale metric to a useful display range (matches fldigi's post-decode scaling).
        signalMetric = maxOf(signalMetric - 32.0, 5.0)

        recvbit(decodedBit)
    }

    // =========================================================================
    // Varicode decode → frame detection
    // =========================================================================

    /**
     * Feeds one decoded bit into the IZ8BLY Varicode shift-register decoder.
     * A non-null return from the decoder means a complete character was decoded.
     *
     * Direct translation of fldigi's mfsk::recvbit():
     *   datashreg = (datashreg << 1) | bit
     *   if ((datashreg & 7) == 1): decode character, reset datashreg = 1
     */
    private suspend fun recvbit(decodedBit: Int)
    {
        val decodedChar = varicodeDecoder.feed(decodedBit) ?: return
        Timber.d("MFSKStation: recvbit decoded char ${decodedChar.code} ('$decodedChar')")
        handleCharacter(decodedChar)
    }

    /**
     * Handles one decoded character: detects STX/EOT frame markers and accumulates
     * the text payload between them. Emits an [MFSKMessage] on a complete frame.
     *
     * fldigi frame structure (TX_STATE_START/FLUSH in mfsk.cxx):
     *   CR STX CR <text> CR EOT CR [flush zeros]
     */
    private suspend fun handleCharacter(char: Char)
    {
        when
        {
            char == ASCII_STX ->
            {
                // Start of a new frame — discard any partial previous frame.
                Timber.d("MFSKStation: STX received — frame opened")
                frameBuffer.clear()
                insideFrame = true
            }

            char == ASCII_EOT && insideFrame ->
            {
                // End of frame — emit the accumulated text.
                val text = frameBuffer.toString()
                Timber.d("MFSKStation: EOT received — emitting frame, ${text.length} chars")
                frameBuffer.clear()
                insideFrame = false

                if (text.isNotEmpty())
                {
                    _receivedMessages.emit(
                        MFSKMessage(
                            text       = text,
                            receivedAt = Instant.now(),
                            mode       = mode
                        )
                    )
                }
            }

            insideFrame ->
            {
                // Accumulate payload character.
                // CR characters from the fldigi framing are included — callers can strip them.
                frameBuffer.append(char)
            }
        }
    }

    // =========================================================================
    // Symbol timing recovery
    // =========================================================================

    /**
     * Adjusts [sampleCountdown] to re-align the symbol clock with the incoming signal.
     *
     * Scans the SlidingDFT pipe buffer for the sample position of maximum energy in
     * the [prevSymbol1] bin. If that peak is offset from the expected symbol boundary,
     * the sample counter is nudged to compensate.
     *
     * Only runs when there was a tone transition (no sync signal in a steady state).
     * Direct translation of fldigi's mfsk::synchronize().
     */
    private fun synchronize()
    {
        // Only sync when there was a transition (current ≠ prev1, prev1 ≠ prev2).
        if (currentSymbol == prevSymbol1 || prevSymbol1 == prevSymbol2) return

        val pipeLength = 2 * samplesPerSymbol
        var maxMag     = 0.0
        var peakOffset = 0.0

        for (offset in 0 until pipeLength)
        {
            val mag = slidingDFT.getPipeEntryBinMagnitude(offset + 1, prevSymbol1)
            if (mag > maxMag) { maxMag = mag; peakOffset = offset.toDouble() }
        }

        // Smooth the sync estimate with an 8-point moving average.
        val smoothedOffset = syncFilter.run(peakOffset)

        // Adjust the sample countdown proportionally to the offset from center.
        sampleCountdown += floor(
            (smoothedOffset - samplesPerSymbol) / toneCount + 0.5
        ).toInt()
    }

    // =========================================================================
    // Automatic frequency control
    // =========================================================================

    /**
     * Tracks carrier frequency drift by comparing the complex phase of the current
     * symbol's DFT bin between consecutive sample periods.
     *
     * When the phase difference indicates a frequency error, [currentFrequencyHz] is
     * adjusted incrementally, which changes the mixer's phase increment and realigns
     * the signal with the SlidingDFT bins.
     *
     * Direct translation of fldigi's mfsk::afc().
     */
    private fun afc()
    {
        // AFC only runs when the signal is strong enough and the tone is stable.
        if (afcMetric < AFC_ACTIVATION_THRESHOLD) return
        if (currentSymbol != prevSymbol1) return

        // Retrieve the previous sample's complex vector for this tone bin.
        val prevVectorReal = DoubleArray(1)
        val prevVectorImag = DoubleArray(1)
        slidingDFT.getPipeEntryBin(2, currentSymbol, prevVectorReal, prevVectorImag)

        // Phase difference = conj(prev) * curr = (prevRe - j*prevIm) * (currRe + j*currIm)
        val conjMulReal = prevVectorReal[0] * currentVectorBuf[0] + prevVectorImag[0] * currentVectorBuf[1]
        val conjMulImag = prevVectorReal[0] * currentVectorBuf[1] - prevVectorImag[0] * currentVectorBuf[0]

        // Frequency offset inferred from the phase angle.
        val measuredFreq   = atan2(conjMulImag, conjMulReal) * sampleRate / (2.0 * PI)
        val expectedFreq   = toneSpacingHz * (baseToneIndex + currentSymbol)
        val halfToneSpacing = toneSpacingHz / 2.0

        if (abs(expectedFreq - measuredFreq) < halfToneSpacing)
        {
            freqErr           = decayAverage(freqErr, expectedFreq - measuredFreq, weight = 32)
            currentFrequencyHz -= freqErr
        }
    }

    // =========================================================================
    // Session state reset
    // =========================================================================

    private fun resetSessionState()
    {
        hilbertFilter.reset()
        bandpassFilter.reset()
        slidingDFT.reset()
        rxInterleaver.reset()
        viterbiDecoder1.reset()
        viterbiDecoder2.reset()
        varicodeDecoder.reset()
        syncFilter.reset()

        chunkCount = 0
        currentFrequencyHz   = configuration.baseFrequencyHz + bandwidth / 2.0
        mixerPhaseAccumulator = 0.0
        sampleCountdown      = samplesPerSymbol
        currentSymbol        = 0
        prevSymbol1          = 0
        prevSymbol2          = 0
        symcounter           = 0
        symbolPair.fill(0)
        met1                 = 0.0
        met2                 = 0.0
        signalMetric         = 0.0
        freqErr              = 0.0
        afcMetric            = 0.0
        cwiCounters.fill(0)
        staticBurst          = false
        frameBuffer.clear()
        insideFrame          = false
        currentBinsReal.fill(0.0)
        currentBinsImag.fill(0.0)
    }

    // =========================================================================
    // DSP utilities
    // =========================================================================

    private fun grayDecode(gray: Int): Int
    {
        return gray xor (gray ushr 1)
    }

    /**
     * Exponential decay moving average.
     * Equivalent to a one-pole IIR filter with time constant [weight].
     * From fldigi's misc.h: ((input - average) / weight) + average
     */
    private fun decayAverage(average: Double, input: Double, weight: Int): Double
    {
        if (weight <= 1) return input
        return (input - average) / weight + average
    }

    /**
     * 8-point unweighted moving average filter.
     * Used for smoothing the symbol timing sync estimate (fldigi's Cmovavg(8)).
     */
    private inner class MovingAverage(private val capacity: Int)
    {
        private val buffer  = DoubleArray(capacity)
        private var pointer = 0
        private var sum     = 0.0
        private var primed  = false

        fun run(value: Double): Double
        {
            if (!primed)
            {
                primed = true
                buffer.fill(value)
                sum     = value * capacity
                pointer = 0
            }
            else
            {
                sum -= buffer[pointer]
                sum += value
                buffer[pointer] = value
                if (++pointer >= capacity) pointer = 0
            }
            return sum / capacity
        }

        fun reset() { primed = false; sum = 0.0; pointer = 0; buffer.fill(0.0) }
    }

    // =========================================================================
    // Constants
    // =========================================================================

    companion object
    {
        // fldigi's ASCII_STX and ASCII_EOT control characters for frame detection.
        private const val ASCII_STX = 2.toChar()  // start of text
        private const val ASCII_EOT = 4.toChar()  // end of transmission

        /**
         * Maximum number of consecutive symbol periods a tone can dominate before
         * being treated as CW interference and soft-punctured.
         * Matches fldigi's CWI_MAXCOUNT = 6 in softdecode().
         */
        private const val CWI_MAX_COUNT = 6

        /**
         * Minimum AFC metric before automatic frequency correction is applied.
         * Below this threshold, the signal is too noisy or unstable for reliable
         * frequency tracking. Matches fldigi's check: `if (afcmetric < 3.0) return`.
         */
        private const val AFC_ACTIVATION_THRESHOLD = 3.0
    }
}