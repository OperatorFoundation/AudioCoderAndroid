package org.operatorfoundation.audiocoder

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.operatorfoundation.audiocoder.WSPRTimingConstants.AUDIO_CHUNK_DURATION_MILLISECONDS
import org.operatorfoundation.audiocoder.WSPRTimingConstants.AUDIO_COLLECTION_DURATION_MILLISECONDS
import org.operatorfoundation.audiocoder.WSPRTimingConstants.AUDIO_COLLECTION_PAUSE_MILLISECONDS
import org.operatorfoundation.audiocoder.WSPRTimingConstants.CYCLE_INFORMATION_UPDATE_INTERVAL_MILLISECONDS
import org.operatorfoundation.audiocoder.models.WSPRCycleInformation
import org.operatorfoundation.audiocoder.models.WSPRDecodeResult
import org.operatorfoundation.audiocoder.models.WSPRStationConfiguration
import org.operatorfoundation.audiocoder.models.WSPRStationState
import timber.log.Timber
import java.util.*

/**
 * WSPR station provides complete amateur radio WSPR (Weak Signal Propagation Reporter) functionality.
 *
 * This class handles the complete WSPR workflow:
 * - Automatic timing synchronization with the global WSPR schedule
 * - Audio collection during transmission windows
 * - Signal decoding using the native WSPR decoder
 * - Result management and reporting
 *
 * The WSPR protocol operates on a strict 2-minute cycle:
 * - Even minutes (00, 02, 04...): Transmission/reception windows
 * - Odd minutes (01, 03, 05...): Silent periods for frequency coordination
 *
 * Usage:
 * val audioSource = MyWSPRAudioSource()
 * val station = WSPRStation(audioSource)
 * station.start() // Begins automatic operation
 *
 * // Observe results
 * station.decodeResults.collect { results ->
 *     // Handle decoded WSPR messages
 * }
 *
 * @param audioSource Provider of audio data for WSPR processing
 * @param configuration Station operating parameters and preferences
 */
class WSPRStation(
    private val audioSource: WSPRAudioSource,
    private val configuration: WSPRStationConfiguration = WSPRStationConfiguration.createDefault()
)
{
    // ========== Core Components ==========

    /**
     * Processes raw audio data into WSPR messages using the native decoder.
     * Handles buffering and window management.
     */
    private val signalProcessor = WSPRProcessor()

    /**
     * Manages WSPR protocol timing and synchronization.
     * Ensures decode attempts align with global WSPR transmission schedule.
     */
    private val timingCoordinator = WSPRTimingCoordinator()

    /**
     * Controls the main station operation loop.
     * Cancelled when the station is stopped.
     */
    private var stationOperationJob: Job? = null

    // ========== State Management ==========

    /**
     * Current operational state of the WSPR station.
     *
     * States progress through the following lifecycle:
     * Stopped -> Starting -> Running -> (Collecting -> Decoding -> Complete) -> Running -> Stopping -> Stopped
     *
     * Error states can occur at any time and require manual intervention.
     */
    private val _stationState = MutableStateFlow<WSPRStationState>(WSPRStationState.Stopped)
    val stationState: StateFlow<WSPRStationState> = _stationState.asStateFlow()

    /**
     * Most recent WSPR decode results.
     * Updated after each successful decode cycle with all detected signals.
     */
    private val _decodeResults = MutableStateFlow<List<WSPRDecodeResult>>(emptyList())
    val decodeResults: StateFlow<List<WSPRDecodeResult>> = _decodeResults.asStateFlow()

    /**
     * Real-time WSPR cycle information for UI display.
     * Updates every second with current position in the 2-minute WSPR cycle.
     */
    private val _cycleInformation = MutableStateFlow(timingCoordinator.getCurrentCycleInformation())
    val cycleInformation: StateFlow<WSPRCycleInformation> = _cycleInformation.asStateFlow()

    // ========== Station Control ==========

    /**
     * Starts the WSPR station with automatic timing and decoding.
     *
     * The station will:
     * 1. Initialize the audio source
     * 2. Begin monitoring WSPR timing cycles
     * 3. Automatically collect audio during transmission windows
     * 4. Decode collected audio and report results
     * 5. Continue operation until stopped
     *
     * @return Success if station started successfully, Failure with details if initialization failed
     */
    suspend fun startStation(): Result<Unit>
    {
        return try
        {
            if (stationOperationJob?.isActive == true)
            {
                return Result.failure(
                    WSPRStationException("Station is already running. Stop the station before restarting.")
                )
            }

            _stationState.value = WSPRStationState.Starting

            // Initialize audio source and verify functionality
            val audioInitializationResult = audioSource.initialize()
            if (audioInitializationResult.isFailure)
            {
                _stationState.value = WSPRStationState.Error("Audio source initialization failed: ${audioInitializationResult.exceptionOrNull()?.message}")
                return  audioInitializationResult
            }

            // Start the main station operation loop
            stationOperationJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                executeStationOperationLoop()
            }

            // Start cycle information updates for UI
            startCycleInformationUpdates()

            _stationState.value = WSPRStationState.Running
            Result.success(Unit)
        }
        catch (exception: Exception)
        {
            val errorMessage = "Failed to start WSPR station: ${exception.message}."
            _stationState.value = WSPRStationState.Error(errorMessage)
            Result.failure(WSPRStationException(errorMessage, exception))
        }
    }

    /**
     * Stops the WSPR station and releases all resources.
     *
     * This method:
     * 1. Cancels any ongoing decode operations
     * 2. Stops audio source
     * 3. Cleans up all resources
     * 4. Returns station to stopped state
     *
     * It is safe to call this method multiple times.
     */
    suspend fun stopStation()
    {
        _stationState.value = WSPRStationState.Stopping

        try
        {
            // Cancel all ongoing operations
            stationOperationJob?.cancel()
            stationOperationJob?.join() // Wait for graceful shutdown

            // Clean up audio source
            audioSource.cleanup()

            // Clear any buffered data
            signalProcessor.clearBuffer()
        }
        catch (exception: Exception)
        {
            // Log error but continue shutdown
        }
        finally
        {
            _stationState.value = WSPRStationState.Stopped
        }
    }

    /**
     * Triggers an immediate WSPR decode attempt if timing conditions are favorable.
     *
     * This method respects WSPR timing constraints and will only attempt to decode
     * if we are currently in a valid WSPR transimission window.
     *
     * @return Success with the decode results, or Failure if timing is invalid or decode fails.
     */
    suspend fun requestImmediateDecode(): Result<List<WSPRDecodeResult>>
    {
        return try
        {
            if (!timingCoordinator.isCurrentlyInValidDecodeWindow())
            {
                val nextWindowInfo = timingCoordinator.getTimeUntilNextDecodeWindow()
                return Result.failure(
                    WSPRStationException("Not in valid WSPR decode window. Next window starts in ${nextWindowInfo.secondsUntilWindow} seconds.")
                )
            }

            val previousState = _stationState.value
            val decodeResults = performCompleteDecodeSequence()
            _stationState.value = previousState // Restore previous state

            Result.success(decodeResults)
        }
        catch (exception: Exception)
        {
            Result.failure(WSPRStationException("Manual decode failed: ${exception.message}", exception))
        }
    }

    // ========== Core Operation Logic ==========

    /**
     * Main station operation loop that runs continuously while the station is active.
     *
     * This loop:
     * 1. Calculates the next optimal decode window
     * 2. Waits until the window begins
     * 3. Performs audio collection and decoding
     * 4. Reports results
     * 5. Repeats indefinitely
     *
     * The loop handles exceptions gracefully and includes exponential backoff
     * for error recovery to prevent resource exhaustion.
     */
    private suspend fun executeStationOperationLoop()
    {
        var consecutiveErrorCount = 0
        val maximumConsecutiveErrors = 5
        val baseErrorDelayMilliseconds = 10_000L // 10 seconds

        while (stationOperationJob?.isActive == true)
        {
            try
            {
                Timber.d("=== WSPR Station Loop Iteration ===")
                val nextDecodeWindowInfo = timingCoordinator.getTimeUntilNextDecodeWindow()
                Timber.d("Next decode window: ${nextDecodeWindowInfo.secondsUntilWindow}s")
                Timber.d("Is currently in decode window: ${timingCoordinator.isCurrentlyInValidDecodeWindow()}")

                val millisecondsUntilDecodeWindow = nextDecodeWindowInfo.millisecondsUntilWindow

                if (millisecondsUntilDecodeWindow > 0)
                {
                    _stationState.value = WSPRStationState.WaitingForNextWindow(nextDecodeWindowInfo)
                    delay(millisecondsUntilDecodeWindow)
                }

                // Perform the complete decode sequence
                val decodedResults = performCompleteDecodeSequence()
                _stationState.value = WSPRStationState.DecodeCompleted(decodedResults.size)

                // Reset error counter on successful operation
                consecutiveErrorCount = 0

                // Brief pause before calculating the next window
                delay(WSPRTimingConstants.BRIEF_OPERATION_PAUSE_MILLISECONDS)
            }
            catch (exception: Exception)
            {
                consecutiveErrorCount++
                val errorMessage = "Station operation error (${consecutiveErrorCount}/${maximumConsecutiveErrors}): ${exception.message}"
                _stationState.value = WSPRStationState.Error(errorMessage)

                if (consecutiveErrorCount >= maximumConsecutiveErrors) {
                    // Too many consecutive errors - stop station
                    break
                }

                // Exponential backoff for error recovery
                val errorDelayMilliseconds = baseErrorDelayMilliseconds * (1L shl (consecutiveErrorCount - 1))
                delay(errorDelayMilliseconds.coerceAtMost(WSPRTimingConstants.MAXIMUM_ERROR_BACKOFF_MILLISECONDS))
            }
        }
    }

    /**
     * Performs the complete WSPR decode sequence: audio collection, processing, and result generation.
     *
     * This method implements the standard WSPR decode workflow:
     * 1. Clear any existing audio buffer
     * 2. Collect exactly 114 seconds of audio (native decoder requirement)
     * 3. Process collected audio through WSPR decoder
     * 4. Convert results to application format
     * 5. Update result state
     *
     * @return List of decoded WSPR messages found in the audio
     * @throws WSPRStationException if decode process fails
     */
    private suspend fun performCompleteDecodeSequence(): List<WSPRDecodeResult>
    {
        Timber.d(">>> DECODE SEQUENCE STARTING <<<")

        // Phase 1: Prepare for audio collection
        _stationState.value = WSPRStationState.PreparingForCollection
        signalProcessor.clearBuffer()

        // Phase 2: Collect audio for the required duration
        _stationState.value = WSPRStationState.CollectingAudio
        val audioCollectionStartTime = System.currentTimeMillis()
        var totalSamplesCollected = 0

        while (System.currentTimeMillis() - audioCollectionStartTime < AUDIO_COLLECTION_DURATION_MILLISECONDS)
        {
            val audioChunk = audioSource.readAudioChunk(AUDIO_CHUNK_DURATION_MILLISECONDS)
            signalProcessor.addSamples(audioChunk)
            totalSamplesCollected += audioChunk.size

            delay(AUDIO_COLLECTION_PAUSE_MILLISECONDS)
        }

        Timber.d(">>> COLLECTION DONE: ${totalSamplesCollected} samples in ${System.currentTimeMillis() - audioCollectionStartTime}ms")

        // Phase 3: Process collected audio through WSPR decoder
        _stationState.value = WSPRStationState.ProcessingAudio

        Timber.d("=== PRE-DECODE CHECK ===")
        Timber.d("Buffer ready: ${signalProcessor.isReadyForDecode()}")
        Timber.d("Buffer samples: ${signalProcessor.audioBuffer.size}")
        Timber.d("Buffer duration: ${signalProcessor.getBufferDurationSeconds()}s")
        Timber.d("Required samples: ${signalProcessor.getRequiredDecodeSamples()}")
        Timber.d("Config: freq=${configuration.operatingFrequencyMHz}, lsb=${configuration.useLowerSidebandMode}")

        val nativeDecodeResults = signalProcessor.decodeBufferedWSPR(
            dialFrequencyMHz = configuration.operatingFrequencyMHz,
            useLowerSideband = configuration.useLowerSidebandMode,
            useTimeAlignment = configuration.useTimeAlignedDecoding
        )

        Timber.d("Native decode returned: ${nativeDecodeResults?.size ?: "null"}")

        // Phase 4: Convert and store results
        val processedResults = convertNativeResultsToApplicationFormat(nativeDecodeResults)
        _decodeResults.value = processedResults

        return processedResults
    }

    /**
     * Converts native WSPR decoder results to application-friendly format.
     *
     * The native decoder returns WSPRMessage objects with specific field formats.
     * This method normalizes the data and adds application specific metadata.
     *
     * @param nativeResults Raw results from the native WSPR decoder
     * @return List of processed decode results with consistent formatting
     */
    private fun convertNativeResultsToApplicationFormat(nativeResults: Array<WSPRMessage>?): List<WSPRDecodeResult>
    {
        if (nativeResults == null) return emptyList()

        nativeResults.forEach { msg ->
            Timber.d("NATIVE-RAW: call='${msg.call}', loc='${msg.loc}', power=${msg.power}, snr=${msg.snr}, message='${msg.message}'")
        }

        return nativeResults.map { nativeMessage ->
            WSPRDecodeResult(
                callsign = nativeMessage.call?.trim() ?: WSPRDecodeResult.UNKNOWN_CALLSIGN,
                gridSquare = nativeMessage.loc?.trim() ?: WSPRDecodeResult.UNKNOWN_GRID_SQUARE,
                powerLevelDbm = nativeMessage.power,
                signalToNoiseRatioDb = nativeMessage.snr,
                frequencyOffsetHz = nativeMessage.freq,
                completeMessage = nativeMessage.message?.trim() ?: WSPRDecodeResult.EMPTY_MESSAGE,
                decodeTimestamp = System.currentTimeMillis()
            )
        }
    }

    /**
     * Starts background updates for cycle information display.
     * Updates cycle position and timing information every second for UI consumption.
     */
    private fun startCycleInformationUpdates()
    {
        CoroutineScope(Dispatchers.IO).launch {
            while (stationOperationJob?.isActive == true)
            {
                _cycleInformation.value = timingCoordinator.getCurrentCycleInformation()
                delay(CYCLE_INFORMATION_UPDATE_INTERVAL_MILLISECONDS)
            }
        }
    }

}

/**
 * Custom exception for WSPR station-specific errors.
 * Provides structured error reporting for station operation failures.
 */
class WSPRStationException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)