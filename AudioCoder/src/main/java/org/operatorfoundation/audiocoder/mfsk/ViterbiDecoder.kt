package org.operatorfoundation.audiocoder.mfsk

/**
 * Soft-decision Viterbi decoder for the NASA R=1/2 K=7 convolutional code
 * used in MFSK-16 FEC.
 *
 * Accepts pairs of soft-decision bytes — one per convolutional encoder output —
 * and decodes them back to the original input bit stream. "Soft" means each
 * byte encodes both the bit decision and its reliability: 0 = strong 0,
 * 128 = uncertain, 255 = strong 1. This is the [0,255] range produced by
 * fldigi's softdecode().
 *
 * ## receive pipeline
 * After the de-interleaver produces soft bytes, [decode] is called once per
 * soft byte. It accumulates pairs of bytes into [symbolPair] (one pair per
 * original input bit, since R=1/2 doubled the bit rate). After [chunkSize]
 * symbol pairs, [traceback] walks the survivor path and returns a decoded bit.
 *
 * ## Dual-decoder usage
 * MFSKStation owns two instances of this class and implements the
 * symcounter-gated metric-comparison scheme from fldigi's decodesymbol().
 * This class is a single decoder instance with no knowledge of that scheme.
 *
 * ## Parameters (fixed for MFSK-16, matching mfsk.cxx)
 * - constraintLength = 7      (MFSK_K in mfsk.h)
 * - generatorPoly1   = 0x6d  (POLY1)
 * - generatorPoly2   = 0x4f  (POLY2)
 * - traceback        = 45    (tracepair.trace in mfsk.cxx)
 * - chunkSize        = 1     (setchunksize(1) in mfsk.cxx)
 *
 * Direct translation of fldigi's viterbi class (src/filters/viterbi.cxx).
 */
class ViterbiDecoder(
    private val constraintLength: Int,
    private val generatorPoly1: Int,
    private val generatorPoly2: Int
)
{
    companion object
    {
        // Depth of the path memory circular buffer. Must be a power of 2
        // to make modular indexing correct and efficient.
        const val PATH_MEMORY_DEPTH = 256

        // Traceback and chunk size as set by mfsk.cxx for MFSK decode.
        const val MFSK_TRACEBACK  = 45
        const val MFSK_CHUNK_SIZE = 1
    }

    // Number of encoder output states (2^constraintLength = 128 for K=7).
    private val encoderOutputCount = 1 shl constraintLength

    // Number of Viterbi trellis states (2^(constraintLength-1) = 64 for K=7).
    private val stateCount = 1 shl (constraintLength - 1)

    // Precomputed encoder output for each possible shift register state.
    // outputTable[state] = 2-bit value: bit0=poly1 output, bit1=poly2 output.
    private val outputTable = IntArray(encoderOutputCount)

    // Branch metric lookup: metricTable[expectedBit][softByteValue]
    // expectedBit=0: 128 - softByte (favors values near 0)
    // expectedBit=1: softByte - 128 (favors values near 255)
    private val metricTable = Array(2) { IntArray(256) }

    // Circular path metric and history buffers.
    // pathMetrics[timeStep][state] = accumulated path metric.
    // pathHistory[timeStep][state] = predecessor state at previous time step.
    private val pathMetrics = Array(PATH_MEMORY_DEPTH) { IntArray(stateCount) }
    private val pathHistory  = Array(PATH_MEMORY_DEPTH) { IntArray(stateCount) }

    // Survivor path sequence for traceback.
    private val survivorSequence = IntArray(PATH_MEMORY_DEPTH)

    // Current write position in the circular buffer.
    private var bufferPointer = 0

    // Configurable traceback depth and output chunk size.
    private var traceback  = MFSK_TRACEBACK
    private var chunkSize  = MFSK_CHUNK_SIZE

    init
    {
        buildOutputTable()
        buildMetricTable()
        reset()
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Sets the traceback depth. Must be in [1, PATH_MEMORY_DEPTH - 1].
     * Default is [MFSK_TRACEBACK] = 45, as set in fldigi for MFSK.
     */
    fun setTraceback(depth: Int)
    {
        require(depth in 1 until PATH_MEMORY_DEPTH) {
            "Traceback depth must be in [1, ${PATH_MEMORY_DEPTH - 1}], got $depth"
        }
        traceback = depth
    }

    /**
     * Sets the output chunk size — how many decoded bits to output per traceback.
     * Must be in [1, traceback]. Default is [MFSK_CHUNK_SIZE] = 1.
     */
    fun setChunkSize(size: Int)
    {
        require(size in 1..traceback) {
            "Chunk size must be in [1, $traceback], got $size"
        }
        chunkSize = size
    }

    /**
     * Feeds one pair of soft-decision bytes and advances the trellis by one step.
     *
     * [symbolPair] must contain exactly 2 bytes: the two convolutional encoder
     * outputs for one original input bit, scaled to [0, 255].
     *
     * Returns the decoded bit (0 or 1) after every [chunkSize] steps, or -1
     * while still accumulating.
     *
     * @param symbolPair  Two soft bytes: index 0 = poly1 output, index 1 = poly2 output.
     * @param metricOut   Single-element array; on non-null return, filled with the
     *                    path metric delta (confidence measure). Pass IntArray(1)
     *                    or null if the metric is not needed.
     */
    fun decode(symbolPair: ByteArray, metricOut: IntArray?): Int
    {
        require(symbolPair.size == 2) {
            "symbolPair must have exactly 2 bytes, got ${symbolPair.size}"
        }

        val currentPtr  = bufferPointer
        val previousPtr = (currentPtr - 1 + PATH_MEMORY_DEPTH) % PATH_MEMORY_DEPTH

        // Compute branch metrics for all four possible 2-bit encoder outputs.
        // sym values are unsigned bytes — and() with 0xff converts signed Byte to Int.
        val sym0 = symbolPair[0].toInt() and 0xff
        val sym1 = symbolPair[1].toInt() and 0xff

        val branchMetrics = intArrayOf(
            metricTable[0][sym1] + metricTable[0][sym0],  // output=0b00
            metricTable[0][sym1] + metricTable[1][sym0],  // output=0b01
            metricTable[1][sym1] + metricTable[0][sym0],  // output=0b10
            metricTable[1][sym1] + metricTable[1][sym0]   // output=0b11
        )

        // Update path metrics for all trellis states using the ACS (Add-Compare-Select) step.
        for (state in 0 until stateCount)
        {
            // The two encoder input states that lead to this output state.
            val predecessorState0 = state            // predecessor with input bit 0
            val predecessorState1 = state + stateCount  // predecessor with input bit 1

            // Corresponding previous (parent) states in the trellis.
            val parentState0 = predecessorState0 shr 1
            val parentState1 = predecessorState1 shr 1

            val metric0 = pathMetrics[previousPtr][parentState0] + branchMetrics[outputTable[predecessorState0]]
            val metric1 = pathMetrics[previousPtr][parentState1] + branchMetrics[outputTable[predecessorState1]]

            // Select the survivor (higher metric wins).
            if (metric0 > metric1)
            {
                pathMetrics[currentPtr][state] = metric0
                pathHistory[currentPtr][state] = parentState0
            }
            else
            {
                pathMetrics[currentPtr][state] = metric1
                pathHistory[currentPtr][state] = parentState1
            }
        }

        bufferPointer = (bufferPointer + 1) % PATH_MEMORY_DEPTH

        // Prevent metric overflow — shift all metrics when they drift too far from zero.
        normalizeMetricsIfNeeded(currentPtr)

        // Return decoded bits only after a full chunk has been processed.
        return if ((bufferPointer % chunkSize) == 0) performTraceback(metricOut) else -1
    }

    /**
     * Resets all path metrics and history to zero and returns the buffer pointer
     * to the start. Call before beginning a new receive session.
     */
    fun reset()
    {
        for (i in 0 until PATH_MEMORY_DEPTH)
        {
            pathMetrics[i].fill(0)
            pathHistory[i].fill(0)
        }
        survivorSequence.fill(0)
        bufferPointer = 0
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Traces back through the survivor path to decode [chunkSize] output bits.
     *
     * Finds the state with the highest metric at the current time, traces back
     * [traceback] steps through the history arrays, then reads [chunkSize] bits
     * from the survivor sequence (low bit of each state = decoded bit).
     */
    private fun performTraceback(metricOut: IntArray?): Int
    {
        var position = (bufferPointer - 1 + PATH_MEMORY_DEPTH) % PATH_MEMORY_DEPTH

        // Find the state with the best (highest) path metric at this time step.
        var bestMetric = Int.MIN_VALUE
        var bestState  = 0
        for (state in 0 until stateCount)
        {
            if (pathMetrics[position][state] > bestMetric)
            {
                bestMetric = pathMetrics[position][state]
                bestState  = state
            }
        }

        // Record the survivor and trace back through the history.
        survivorSequence[position] = bestState
        for (i in 0 until traceback)
        {
            val previousPosition = (position - 1 + PATH_MEMORY_DEPTH) % PATH_MEMORY_DEPTH
            survivorSequence[previousPosition] = pathHistory[position][survivorSequence[position]]
            position = previousPosition
        }

        // Capture the metric at the trace-back start point (before reading bits).
        if (metricOut != null)
            metricOut[0] = pathMetrics[position][survivorSequence[position]]

        // Read [chunkSize] decoded bits from the survivor sequence, LSB of each state.
        var decodedOutput = 0
        for (i in 0 until chunkSize)
        {
            decodedOutput = (decodedOutput shl 1) or (survivorSequence[position] and 1)
            position = (position + 1) % PATH_MEMORY_DEPTH
        }

        // Update metric to be the delta from start to end of decoded chunk.
        if (metricOut != null)
            metricOut[0] = pathMetrics[position][survivorSequence[position]] - metricOut[0]

        return decodedOutput
    }

    /**
     * Shifts all path metrics toward zero when the best metric exceeds Int.MAX_VALUE / 2
     * or falls below Int.MIN_VALUE / 2. Prevents integer overflow during long sessions
     * without changing any relative metric values. Matches fldigi's overflow guard.
     */
    private fun normalizeMetricsIfNeeded(currentPtr: Int)
    {
        val referenceMetric = pathMetrics[currentPtr][0]

        if (referenceMetric > Int.MAX_VALUE / 2)
        {
            for (time in 0 until PATH_MEMORY_DEPTH)
                for (state in 0 until stateCount)
                    pathMetrics[time][state] -= Int.MAX_VALUE / 2
        }
        else if (referenceMetric < Int.MIN_VALUE / 2)
        {
            for (time in 0 until PATH_MEMORY_DEPTH)
                for (state in 0 until stateCount)
                    pathMetrics[time][state] += Int.MIN_VALUE / 2
        }
    }

    /**
     * Precomputes the encoder output for all 2^constraintLength shift register states.
     * Both decoder and encoder use the same table
     * because the decoder needs to know what output each state transition would have produced.
     */
    private fun buildOutputTable()
    {
        for (state in 0 until encoderOutputCount)
        {
            val poly1Output = parity(generatorPoly1 and state)
            val poly2Output = parity(generatorPoly2 and state)
            outputTable[state] = poly1Output or (poly2Output shl 1)
        }
    }

    /**
     * Precomputes branch metrics for all 256 soft byte values.
     *
     * metricTable[0][softByte] = 128 - softByte  (reward for values near 0, i.e. bit=0)
     * metricTable[1][softByte] = softByte - 128  (reward for values near 255, i.e. bit=1)
     */
    private fun buildMetricTable()
    {
        for (softValue in 0 until 256)
        {
            metricTable[0][softValue] = 128 - softValue
            metricTable[1][softValue] = softValue - 128
        }
    }

    /**
     * Returns 1 if [value] has an odd number of set bits, 0 if even.
     */
    private fun parity(value: Int): Int
    {
        var x = value
        x = x xor (x ushr 16)
        x = x xor (x ushr 8)
        x = x xor (x ushr 4)
        x = x xor (x ushr 2)
        x = x xor (x ushr 1)
        return x and 1
    }
}