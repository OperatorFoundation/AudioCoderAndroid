package org.operatorfoundation.audiocoder.mfsk

/**
 * Diagonal matrix interleaver/de-interleaver for MFSK-16 FEC.
 *
 * This is the IZ8BLY self-synchronizing diagonal interleaver described in the
 * MFSK-16 specification and implemented in fldigi. It operates on nibbles
 * (4-bit groups) to spread burst errors across the convolutional FEC decoder's
 * traceback window, dramatically improving decode performance under noise.
 *
 * ## Self-synchronizing property
 * The interleaver requires no explicit synchronization signal. Because the
 * de-interleaver is pre-filled with PUNCTURE values (128 = uncertain soft
 * decision), it produces neutral output before sufficient symbols have arrived,
 * and the Viterbi decoder handles punctured bits gracefully.
 *
 * ## Two roles, two instances
 * Create one instance per role using the factory functions:
 *   - [createForTransmit]: direction=FWD, table initialized to zeros.
 *   - [createForReceive]:  direction=REV, table initialized to PUNCTURE (128).
 *
 * ## Parameters for MFSK-16 (from fldigi mfsk.cxx)
 *   - size  = symbits = 4  (bits per MFSK symbol)
 *   - depth = 10           (interleaver depth L from the spec)
 *
 * ## TX usage
 * For each 4-bit nibble produced by the convolutional encoder accumulator:
 *   val interleavedNibble = txInterleaver.interleaveNibble(nibble)
 *
 * ## RX usage
 * For each set of [size] soft bytes produced by softdecode():
 *   rxInterleaver.deinterleaveSymbols(softBytes)  // modifies in place
 *
 * Direct translation of fldigi's interleave class (src/mfsk/interleave.cxx).
 */
class MFSKInterleaver private constructor(
    private val size: Int,
    private val depth: Int,
    private val direction: Direction
)
{
    enum class Direction { FORWARD, REVERSE }

    companion object
    {
        /**
         * Soft decision value used to fill the RX interleaver table on init and reset.
         * -128 as a signed Byte = 0x80 = 128 unsigned — the midpoint of [0, 255],
         * meaning "maximally uncertain". Matches fldigi's PUNCTURE = 128 (unsigned char).
         * When the Viterbi decoder converts with `byte.toInt() and 0xff`, it receives 128.
         */
        const val PUNCTURE_VALUE: Byte = -128  // 0x80 unsigned = 128 = maximally uncertain

        // MFSK-16 parameters as set in fldigi mfsk.cxx:
        //   txinlv = new interleave(symbits, depth, INTERLEAVE_FWD)  → size=4, depth=10
        //   rxinlv = new interleave(symbits, depth, INTERLEAVE_REV)  → size=4, depth=10
        const val MFSK_INTERLEAVER_SIZE  = 4
        const val MFSK_INTERLEAVER_DEPTH = 10

        fun createForTransmit(
            size: Int  = MFSK_INTERLEAVER_SIZE,
            depth: Int = MFSK_INTERLEAVER_DEPTH
        ): MFSKInterleaver = MFSKInterleaver(size, depth, Direction.FORWARD)

        fun createForReceive(
            size: Int  = MFSK_INTERLEAVER_SIZE,
            depth: Int = MFSK_INTERLEAVER_DEPTH
        ): MFSKInterleaver = MFSKInterleaver(size, depth, Direction.REVERSE)
    }

    // Flat 1D backing array for the 3D logical table [depth][size][size].
    // Access pattern: table[depth_index * size * size + row * size + col]
    private val table = ByteArray(depth * size * size)

    init
    {
        reset()
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Interleaves a 4-bit nibble for transmission (TX path only).
     *
     * Unpacks [nibble] into [size] single-bit values (MSB first), runs the
     * diagonal interleaver over them, then repacks into a new nibble.
     *
     * Matches fldigi's interleave::bits() with direction=INTERLEAVE_FWD.
     *
     * @param nibble A 4-bit value in [0, 15].
     * @return The interleaved 4-bit value.
     */
    fun interleaveNibble(nibble: Int): Int
    {
        // Unpack nibble into [size] single-bit bytes, MSB first.
        val bits = ByteArray(size) { index ->
            ((nibble ushr (size - index - 1)) and 1).toByte()
        }

        processSymbols(bits)

        // Repack the [size] bits into a nibble.
        var result = 0
        for (bit in bits)
        {
            result = (result shl 1) or (bit.toInt() and 1)
        }
        return result
    }

    /**
     * De-interleaves [size] soft-decision bytes in place (RX path only).
     *
     * [softBytes] must have exactly [size] elements, each in [0, 255], where
     * 0 = strong 0, 128 = uncertain (PUNCTURE), 255 = strong 1.
     *
     * Modifies [softBytes] in place — the caller's array contains the
     * de-interleaved output after this call returns.
     *
     * Matches fldigi's interleave::symbols() with direction=INTERLEAVE_REV.
     *
     * @param softBytes Soft-decision bytes from softdecode(). Modified in place.
     */
    fun deinterleaveSymbols(softBytes: ByteArray)
    {
        require(softBytes.size == size) {
            "softBytes must have exactly $size elements for this interleaver, got ${softBytes.size}"
        }
        processSymbols(softBytes)
    }

    /**
     * Resets the interleaver table to its initial state.
     * TX: filled with zeros. RX: filled with PUNCTURE (128).
     */
    fun reset()
    {
        val fillValue = if (direction == Direction.REVERSE) PUNCTURE_VALUE else 0
        table.fill(fillValue)
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Core diagonal interleave/de-interleave operation. Modifies [symbols] in place.
     *
     * For each of the [depth] interleaver layers:
     *   1. Shift each row left by one position (oldest element drops off).
     *   2. Insert the new input symbols at the right end of each row.
     *   3. Read out the diagonal: anti-diagonal for TX (FWD), main diagonal for RX (REV).
     *
     * Running all [depth] layers in sequence is what makes this a 10-deep interleaver.
     *
     * Direct translation of fldigi's interleave::symbols().
     */
    private fun processSymbols(symbols: ByteArray)
    {
        for (layer in 0 until depth)
        {
            // Step 1: Shift each row left — drop the oldest stored element.
            for (row in 0 until size)
                for (col in 0 until size - 1)
                    setTableValue(layer, row, col, getTableValue(layer, row, col + 1))

            // Step 2: Insert incoming symbols at the rightmost column of each row.
            for (row in 0 until size)
                setTableValue(layer, row, size - 1, symbols[row])

            // Step 3: Read out the diagonal element for each row.
            for (row in 0 until size)
            {
                symbols[row] = if (direction == Direction.FORWARD)
                    getTableValue(layer, row, size - row - 1)  // anti-diagonal (TX)
                else
                    getTableValue(layer, row, row)              // main diagonal (RX)
            }
        }
    }

    /** Reads one byte from the logical [depth][size][size] table. */
    private fun getTableValue(layer: Int, row: Int, col: Int): Byte =
        table[layer * size * size + row * size + col]

    /** Writes one byte to the logical [depth][size][size] table. */
    private fun setTableValue(layer: Int, row: Int, col: Int, value: Byte)
    {
        table[layer * size * size + row * size + col] = value
    }
}