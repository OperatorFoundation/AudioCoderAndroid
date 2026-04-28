package org.operatorfoundation.audiocoder.mfsk

/**
 * PSK31 Varicode encoding and decoding for standard MFSK text transmission.
 *
 * Varicode is a variable-length binary encoding where each ASCII character maps to a
 * unique bit sequence. All code words consist only of 1-bits with at most one
 * consecutive 0-bit internally. Characters are separated by two consecutive 0-bits (`00`),
 * which cannot appear within any code word — making the boundary unambiguous.
 *
 * This is the standard encoding used by fldigi and other MFSK-16 implementations.
 * Transmissions encoded with this class are decodable by any compliant MFSK-16 receiver.
 *
 * ## References
 * PSK31 Varicode table as defined in the PSK31 specification by Peter Martinez G3PLX.
 */
object Varicode
{
    // -------------------------------------------------------------------------
    // PSK31 Varicode table — index is ASCII code point, value is the code word
    // as a binary string. All code words end in 1 and contain no "00" sequence.
    // -------------------------------------------------------------------------

    private val TABLE = arrayOf(
        "1010101011",  // 0  NUL
        "1011011011",  // 1  SOH
        "1011101101",  // 2  STX
        "1101110111",  // 3  ETX
        "1011101011",  // 4  EOT
        "1101011111",  // 5  ENQ
        "1011101111",  // 6  ACK
        "1011111101",  // 7  BEL
        "1011111111",  // 8  BS
        "11100111",    // 9  HT
        "11101",       // 10 LF
        "1101101111",  // 11 VT
        "1011011101",  // 12 FF
        "11111",       // 13 CR
        "1101110101",  // 14 SO
        "1110101011",  // 15 SI
        "1011110111",  // 16 DLE
        "1011110101",  // 17 DC1
        "1110101101",  // 18 DC2
        "1110101111",  // 19 DC3
        "1101011011",  // 20 DC4
        "1101101011",  // 21 NAK
        "1101101101",  // 22 SYN
        "1101010111",  // 23 ETB
        "1101111011",  // 24 CAN
        "1101111101",  // 25 EM
        "1110110111",  // 26 SUB
        "1101010101",  // 27 ESC
        "1101011101",  // 28 FS
        "1110111011",  // 29 GS
        "1011111011",  // 30 RS
        "1101111111",  // 31 US
        "1",           // 32 SP
        "111111111",   // 33 !
        "101011111",   // 34 "
        "111110101",   // 35 #
        "111011011",   // 36 $
        "1011010101",  // 37 %
        "1010111011",  // 38 &
        "101111111",   // 39 '
        "11111011",    // 40 (
        "11110111",    // 41 )
        "101101111",   // 42 *
        "111011111",   // 43 +
        "1110101",     // 44 ,
        "110101",      // 45 -
        "1010111",     // 46 .
        "110101111",   // 47 /
        "10110111",    // 48 0
        "10111101",    // 49 1
        "11101101",    // 50 2
        "11111111",    // 51 3
        "101110111",   // 52 4
        "101011011",   // 53 5
        "101101011",   // 54 6
        "110101101",   // 55 7
        "110101011",   // 56 8
        "110110111",   // 57 9
        "11110101",    // 58 :
        "110111101",   // 59 ;
        "111101101",   // 60 <
        "1010101",     // 61 =
        "111010111",   // 62 >
        "1010101111",  // 63 ?
        "1010111101",  // 64 @
        "1111101",     // 65 A
        "11101011",    // 66 B
        "10101101",    // 67 C
        "10110101",    // 68 D
        "1110111",     // 69 E
        "11011011",    // 70 F
        "11111101",    // 71 G
        "101010101",   // 72 H
        "1111111",     // 73 I
        "111111101",   // 74 J
        "101111101",   // 75 K
        "11010111",    // 76 L
        "10111011",    // 77 M
        "11011101",    // 78 N
        "10101011",    // 79 O
        "11010101",    // 80 P
        "111011101",   // 81 Q
        "10101111",    // 82 R
        "1101111",     // 83 S
        "1101101",     // 84 T
        "101010111",   // 85 U
        "110110101",   // 86 V
        "101011101",   // 87 W
        "101110101",   // 88 X
        "101111011",   // 89 Y
        "1010101101",  // 90 Z
        "111110111",   // 91 [
        "111101111",   // 92 \
        "111111011",   // 93 ]
        "1010111111",  // 94 ^
        "101101101",   // 95 _
        "1011011111",  // 96 `
        "1011",        // 97 a
        "1011111",     // 98 b
        "101111",      // 99 c
        "101101",      // 100 d
        "11",          // 101 e
        "111101",      // 102 f
        "1011011",     // 103 g
        "101011",      // 104 h
        "1101",        // 105 i
        "111101011",   // 106 j
        "10111111",    // 107 k
        "11011",       // 108 l
        "111011",      // 109 m
        "1111",        // 110 n
        "111",         // 111 o
        "111111",      // 112 p
        "110111111",   // 113 q
        "10101",       // 114 r
        "10111",       // 115 s
        "101",         // 116 t
        "110111",      // 117 u
        "1111011",     // 118 v
        "1101011",     // 119 w
        "11011111",    // 120 x
        "1011101",     // 121 y
        "111010101",   // 122 z
        "1010110111",  // 123 {
        "110111011",   // 124 |
        "1010110101",  // 125 }
        "1011010111",  // 126 ~
        "1110110101"   // 127 DEL
    )

    // -------------------------------------------------------------------------
    // Reverse lookup: code word string → ASCII char
    // Built once at class load time.
    // -------------------------------------------------------------------------

    private val REVERSE_TABLE: Map<String, Char> = buildMap {
        TABLE.forEachIndexed { index, code ->
            put(code, index.toChar())
        }
    }

    // -------------------------------------------------------------------------
    // Encoding
    // -------------------------------------------------------------------------

    /**
     * Encodes [text] as a PSK31 Varicode bit stream.
     *
     * Each character is encoded as its Varicode bit sequence followed by two
     * zero bits (`00`) as an inter-character separator. The final character
     * also receives the trailing `00` separator, which signals end-of-transmission
     * to the decoder.
     *
     * Characters outside the ASCII range (code points > 127) are silently skipped.
     *
     * @param text ASCII text to encode.
     * @return Bit stream as a [BooleanArray], `true` = 1, `false` = 0.
     */
    fun encode(text: String): BooleanArray
    {
        val bits = mutableListOf<Boolean>()

        for (char in text)
        {
            val codePoint = char.code
            if (codePoint > 127) continue  // Skip non-ASCII characters

            val codeWord = TABLE[codePoint]
            for (bit in codeWord)
            {
                bits.add(bit == '1')
            }

            // Inter-character separator: two zero bits.
            bits.add(false)
            bits.add(false)
        }

        return bits.toBooleanArray()
    }

    // -------------------------------------------------------------------------
    // Decoding
    // -------------------------------------------------------------------------

    /**
     * Streaming Varicode decoder.
     *
     * One instance per receive session. Feed bits one at a time via [feed];
     * a non-null return indicates a complete character has been decoded.
     * Call [reset] to clear accumulated state between messages.
     *
     * The decoder recognizes a complete code word when it sees two consecutive
     * zero bits (`00`) — the inter-character separator. The accumulated bits
     * before the `00` are looked up in the Varicode reverse table.
     *
     * Unknown code words (no table entry) are silently skipped.
     */
    class Decoder
    {
        // Bits accumulated since the last inter-character separator.
        private val accumulated = StringBuilder()

        // Whether the previous bit was zero — used to detect the `00` separator.
        private var previousBitWasZero = false

        /**
         * Feeds one bit into the decoder.
         *
         * @param bit `true` = 1, `false` = 0.
         * @return The decoded [Char] if a complete code word was just recognized,
         *         `null` if still accumulating.
         */
        fun feed(bit: Boolean): Char?
        {
            if (!bit && previousBitWasZero)
            {
                // Two consecutive zeros — end of code word.
                // Remove the first zero (which was appended in the previous call)
                // since it is part of the separator, not the code word.
                if (accumulated.isNotEmpty())
                {
                    accumulated.deleteCharAt(accumulated.length - 1)
                }

                val codeWord = accumulated.toString()
                accumulated.clear()
                previousBitWasZero = false

                return REVERSE_TABLE[codeWord]  // null if unknown code word
            }

            accumulated.append(if (bit) '1' else '0')
            previousBitWasZero = !bit
            return null
        }

        /**
         * Resets all accumulated state.
         * Call between messages or when synchronisation is lost.
         */
        fun reset()
        {
            accumulated.clear()
            previousBitWasZero = false
        }
    }
}