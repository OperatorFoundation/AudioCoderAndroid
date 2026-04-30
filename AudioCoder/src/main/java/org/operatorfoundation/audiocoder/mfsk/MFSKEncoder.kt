package org.operatorfoundation.audiocoder.mfsk

/**
 * Encodes text into a complete, fldigi-compatible MFSK-16 tone index sequence.
 *
 * The returned IntArray contains Gray-encoded tone indices (0..toneCount-1) in
 * transmission order. The caller converts each index to a frequency:
 *   `frequencyHz = baseFrequencyHz + toneIndex * mode.toneSpacingHz`
 *
 * ## Complete transmit pipeline (matches fldigi's mfsk.cxx exactly)
 * ```
 * text (String)
 *   → IZ8BLY Varicode bits, MSB-first, one character at a time
 *   → R=1/2 K=7 convolutional FEC encoder (2 output bits per input bit)
 *   → 4-bit nibble accumulator
 *   → MFSKInterleaver (forward, size=4, depth=10)
 *   → Gray encode nibble → tone index
 * ```
 *
 * ## Transmission structure
 * The encoder produces the complete symbol sequence, in order:
 *   1. Interleaver priming (clearbits): 107 zero bits through encoder+interleaver,
 *      no symbols emitted. Puts the interleaver in a known state.
 *   2. Preamble symbols: preamble/3 ≈ 35 zero bits through the full pipeline,
 *      symbols emitted (tone 0 — the tuning aid).
 *   3. Start frame: CR, STX (0x02), CR
 *   4. Data: all characters of [text]
 *   5. End frame: CR, EOT (0x04), CR
 *   6. Flush: one '1' bit (flushes the receiver's Varicode decoder), then
 *      107 zero bits (flushes the convolutional encoder and interleaver pipeline).
 *
 * ## Character support
 * Input must be pure ASCII (code points 0–127). The IZ8BLY Varicode table extends
 * to 255, but MFSK-16 text traffic is conventionally ASCII. Characters outside
 * [0, 255] are silently skipped — they have no Varicode code word.
 *
 * Direct translation of fldigi's mfsk TX pipeline: tx_init(), clearbits(),
 * sendbit(), sendchar(), flushtx() in src/mfsk/mfsk.cxx.
 */
object MFSKEncoder
{
    // -------------------------------------------------------------------------
    // Protocol constants — from fldigi mfsk.cxx (MODE_MFSK16 case)
    // -------------------------------------------------------------------------

    /**
     * Number of bits used for the interleaver-priming preamble (clearbits) and
     * the post-data flush (flushtx). From fldigi: `preamble = 107` for MFSK-16.
     */
    const val PREAMBLE_BIT_COUNT = 107

    /**
     * Interleaver depth. From fldigi: `depth = 10` for MFSK-16.
     * Both TX and RX interleavers must use the same depth to be compatible.
     */
    const val INTERLEAVER_DEPTH = 10

    // ASCII control characters used for fldigi-standard MFSK framing.
    private const val ASCII_CR  = '\r'  // carriage return — used to pad around STX/EOT
    private const val ASCII_STX = 2.toChar()  // start of text — marks start of data frame
    private const val ASCII_EOT = 4.toChar()  // end of transmission — marks end of data frame

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Encodes [text] as a complete fldigi-compatible MFSK tone index sequence.
     *
     * The returned array contains Gray-encoded tone indices in transmission order,
     * including preamble, start frame, data, end frame, and flush. The caller
     * converts each index to a centihertz frequency for Eden:
     *   `(baseFrequencyHz + toneIndex * mode.toneSpacingHz) * 100`
     *
     * @param text The text to encode. Characters outside [0, 255] are skipped.
     * @param mode The MFSK modulation mode (determines tone count and baud rate).
     * @return Gray-encoded tone indices, ready for transmission.
     */
    fun encodeToSymbols(text: String, mode: MFSKMode): IntArray
    {
        require(text.isNotEmpty()) { "text must not be empty" }
        require(text.all { it.code in 0..255 }) {
            "MFSK IZ8BLY Varicode supports code points [0, 255] only"
        }
        return EncoderState(mode).encode(text)
    }

    // -------------------------------------------------------------------------
    // Private stateful encoder
    // -------------------------------------------------------------------------

    /**
     * Holds all mutable state for one encoding run.
     *
     * A fresh instance is created per [encodeToSymbols] call, so the object
     * is inherently single-use and thread-safe by construction.
     */
    private class EncoderState(private val mode: MFSKMode)
    {
        private val outputSymbols      = mutableListOf<Int>()
        private val convolutionalEncoder = ConvolutionalEncoder()
        private val txInterleaver      = MFSKInterleaver.createForTransmit(
            size  = mode.bitsPerSymbol,
            depth = INTERLEAVER_DEPTH
        )

        // Nibble accumulator — mirrors fldigi's bitshreg and bitstate.
        // Accumulates [mode.bitsPerSymbol] encoded bits before interleaving and emitting.
        private var nibbleRegister = 0
        private var nibbleBitCount = 0

        fun encode(text: String): IntArray
        {
            primeInterleaver()           // clearbits()
            sendPreambleSymbols()        // ≈35 zero-bit symbols
            sendStartFrame()             // CR STX CR
            for (char in text) sendChar(char)
            sendEndFrame()               // CR EOT CR + flushtx
            return outputSymbols.toIntArray()
        }

        /**
         * Primes the TX interleaver with a predictable pattern — no symbols are emitted.
         *
         * Matches fldigi's clearbits() exactly:
         *   - [ConvolutionalEncoder.encode] is called ONCE outside the loop (not per bit).
         *     The same 2-bit output is reused for all [PREAMBLE_BIT_COUNT] iterations.
         *     This advances the encoder state by exactly one input bit, then freezes it.
         *   - The interleaver IS called [PREAMBLE_BIT_COUNT] * 2 / bitsPerSymbol times,
         *     advancing it to a known state. The interleaved nibbles are discarded.
         *   - [nibbleRegister] and [nibbleBitCount] retain their state after this call,
         *     carrying into subsequent sendBit calls.
         */
        private fun primeInterleaver()
        {
            // Call encode once and reuse the result — this is intentional, not a bug.
            // See fldigi's clearbits(): `int data = enc->encode(0);` before the k-loop.
            val reusedEncoderOutput = convolutionalEncoder.encode(0)

            for (k in 0 until PREAMBLE_BIT_COUNT)
            {
                for (bitPosition in 0..1)
                {
                    val bit = (reusedEncoderOutput ushr bitPosition) and 1
                    nibbleRegister = (nibbleRegister shl 1) or bit
                    nibbleBitCount++

                    if (nibbleBitCount == mode.bitsPerSymbol)
                    {
                        // Advance interleaver state — discard output (no symbol emitted).
                        txInterleaver.interleaveNibble(nibbleRegister)
                        nibbleRegister = 0
                        nibbleBitCount = 0
                    }
                }
            }
        }

        /**
         * Sends preamble/3 zero bits through the full pipeline, emitting symbols.
         * These produce lowest-tone (tone 0) symbols that serve as a tuning aid.
         * Matches fldigi's `for (int i = 0; i < preamble / 3; i++) sendbit(0);`
         */
        private fun sendPreambleSymbols()
        {
            repeat(PREAMBLE_BIT_COUNT / 3) { sendBit(0) }
        }

        /**
         * Sends the fldigi-standard start-of-transmission frame markers.
         * Matches fldigi's TX_STATE_START: CR, STX, CR.
         */
        private fun sendStartFrame()
        {
            sendChar(ASCII_CR)
            sendChar(ASCII_STX)
            sendChar(ASCII_CR)
        }

        /**
         * Sends the fldigi-standard end-of-transmission frame markers and flushes
         * the convolutional encoder and interleaver pipelines.
         *
         * Matches fldigi's TX_STATE_FLUSH + flushtx(preamble):
         *   - CR, EOT, CR
         *   - One '1' bit: flushes the receiver's IZ8BLY Varicode decoder by
         *     triggering the detection of the trailing EOT character
         *   - [PREAMBLE_BIT_COUNT] zero bits: flushes the convolutional encoder
         *     shift register and the interleaver pipeline
         *
         * Note: fldigi's flushtx() ends with `bitstate = 0`, discarding any partial
         * nibble. We replicate this by not processing remaining bits after the flush.
         */
        private fun sendEndFrame()
        {
            sendChar(ASCII_CR)
            sendChar(ASCII_EOT)
            sendChar(ASCII_CR)

            // One '1' bit acts as the trigger to decode the last character (CR before EOT)
            // and flush the receiver's Varicode decoder.
            sendBit(1)

            // Zero bits flush the convolutional encoder's shift register (K-1 = 6 bits)
            // and the interleaver's internal state (depth * bitsPerSymbol bits).
            repeat(PREAMBLE_BIT_COUNT) { sendBit(0) }
        }

        /**
         * Encodes one text character by looking up its IZ8BLY code word and feeding
         * each bit through the convolutional encoder pipeline.
         * Characters outside [0, 255] are skipped (no IZ8BLY code word exists).
         */
        private fun sendChar(char: Char)
        {
            val bits = Varicode.encodedBits(char) ?: return
            for (bit in bits) sendBit(bit)
        }

        /**
         * Encodes one input bit through the convolutional encoder, then accumulates
         * the two output bits into the nibble register.
         *
         * When the nibble register is full ([mode.bitsPerSymbol] bits), it is passed
         * through the interleaver and the resulting Gray-encoded tone index is emitted.
         *
         * Matches fldigi's sendbit():
         *   data = enc->encode(bit)
         *   for i in 0..1: extract bit i (POLY1 then POLY2), shift into bitshreg
         *   when full: interleave, Gray encode, send symbol
         *
         * Bit ordering: bit 0 of encoder output (POLY1) enters first and ends up at
         * the MSB of the nibble after accumulation. This matches fldigi's left-shift
         * accumulation, which the interleaver's bits() function expects MSB-first.
         */
        private fun sendBit(inputBit: Int)
        {
            val encoderOutput = convolutionalEncoder.encode(inputBit)

            // POLY1 output (bit 0) enters first, POLY2 output (bit 1) enters second.
            for (bitPosition in 0..1)
            {
                val bit = (encoderOutput ushr bitPosition) and 1
                nibbleRegister = (nibbleRegister shl 1) or bit
                nibbleBitCount++

                if (nibbleBitCount == mode.bitsPerSymbol)
                {
                    emitSymbol(nibbleRegister)
                    nibbleRegister = 0
                    nibbleBitCount = 0
                }
            }
        }

        /**
         * Passes the completed nibble through the interleaver, then Gray-encodes
         * the result and adds it to [outputSymbols].
         *
         * Gray encoding ensures that adjacent tones differ by only one bit, minimising
         * the Hamming distance between neighbouring tone errors. Matches fldigi's
         * sendsymbol(): `sym = grayencode(sym & (numtones - 1))`.
         */
        private fun emitSymbol(nibble: Int)
        {
            val interleaved = txInterleaver.interleaveNibble(nibble)
            val masked = interleaved and (mode.toneCount - 1)
            val grayEncoded = masked xor (masked ushr 1)
            outputSymbols.add(grayEncoded)
        }
    }
}