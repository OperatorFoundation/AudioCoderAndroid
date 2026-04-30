package org.operatorfoundation.audiocoder.mfsk

/**
 * R=1/2 K=7 convolutional encoder using the NASA standard generator polynomials
 * required for MFSK-16 FEC.
 *
 * For every input bit, two output bits are produced — one per generator polynomial.
 * The output rate is therefore double the input rate. These output bits are
 * accumulated into 4-bit nibbles by the caller before being passed to the
 * interleaver.
 *
 * Generator polynomials (defined in fldigi mfsk.h as POLY1 and POLY2):
 *   GENERATOR_POLY_1 = 0x6d  (binary: 1101101)
 *   GENERATOR_POLY_2 = 0x4f  (binary: 1001111)
 *
 * Operation: the encoder maintains a 7-bit shift register representing the
 * last CONSTRAINT_LENGTH bits of input. On each call to [encode], the new bit
 * is shifted in and the two output bits are read from a precomputed lookup table.
 *
 * Return value of [encode]: an Int with the two output bits in the low two positions.
 *   bit 0 = poly1 output
 *   bit 1 = poly2 output
 *
 * The caller extracts both bits LSB-first:
 *   for i in 0..1: (encodedOutput shr i) and 1
 *
 * Direct translation of fldigi's encoder class (src/filters/viterbi.cxx).
 */
class ConvolutionalEncoder
{
    companion object
    {
        // NASA standard R=1/2 K=7 convolutional code parameters.
        // Source: fldigi src/include/mfsk.h — MFSK_K, POLY1, POLY2.
        const val CONSTRAINT_LENGTH   = 7
        const val GENERATOR_POLY_1    = 0x6d  // 109 decimal — 1101101 binary
        const val GENERATOR_POLY_2    = 0x4f  // 79 decimal  — 1001111 binary

        // Shift register has 2^CONSTRAINT_LENGTH = 128 possible states.
        private const val TABLE_SIZE          = 1 shl CONSTRAINT_LENGTH
        private const val SHIFT_REGISTER_MASK = TABLE_SIZE - 1  // 0x7f
    }

    // Precomputed lookup table: shift register state → 2-bit encoder output.
    // Indexed by shift register value, each entry encodes both polynomial outputs.
    private val outputTable = IntArray(TABLE_SIZE)

    // Current shift register state — holds the last CONSTRAINT_LENGTH input bits.
    private var shiftRegister = 0

    init
    {
        buildOutputTable()
    }

    /**
     * Encodes one input bit, returning two output bits packed into an Int.
     *
     * @param inputBit The bit to encode — any non-zero value is treated as 1.
     * @return Two-bit output: bit 0 = poly1 result, bit 1 = poly2 result.
     */
    fun encode(inputBit: Int): Int
    {
        shiftRegister = (shiftRegister shl 1) or (if (inputBit != 0) 1 else 0)
        return outputTable[shiftRegister and SHIFT_REGISTER_MASK]
    }

    /**
     * Resets the shift register to the all-zeros state.
     * Must be called before each new transmission to start from a known state.
     */
    fun reset()
    {
        shiftRegister = 0
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Precomputes the encoder output for all 128 possible shift register states.
     *
     * For each state i:
     *   output[i] = parity(POLY1 and i) or (parity(POLY2 and i) shl 1)
     *
     * This matches fldigi's encoder::init() exactly.
     */
    private fun buildOutputTable()
    {
        for (state in 0 until TABLE_SIZE)
        {
            val poly1Output = parity(GENERATOR_POLY_1 and state)
            val poly2Output = parity(GENERATOR_POLY_2 and state)
            outputTable[state] = poly1Output or (poly2Output shl 1)
        }
    }

    /**
     * Returns 1 if [value] has an odd number of set bits, 0 if even.
     *
     * This is the standard XOR parity used in convolutional encoder taps —
     * equivalent to popcount(value) mod 2. Matches fldigi's parity() in misc.cxx.
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