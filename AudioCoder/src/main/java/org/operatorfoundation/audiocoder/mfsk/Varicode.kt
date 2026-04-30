package org.operatorfoundation.audiocoder.mfsk

/**
 * IZ8BLY Varicode encoding and decoding for standard MFSK-16 text transmission.
 *
 * This is NOT the G3PLX/PSK31 Varicode. The IZ8BLY Varicode is the standard for
 * MFSK-16 as specified in ZL1BPU's MFSK-16 spec and implemented in fldigi.
 * Transmissions encoded with this class are decodable by any compliant MFSK-16 receiver.
 *
 * ## Code design
 * Every code word:
 *   - Begins with '1'
 *   - Contains at least two trailing '0' bits (the built-in inter-character separator)
 *   - Never contains the bit pattern "001" internally (only at word boundaries)
 *
 * This means no explicit separator character is needed. The trailing '0' bits within
 * each code word and the leading '1' of the next word together form the "001" trigger.
 *
 * ## Encoding
 * Each character maps to its code word. Bits are sent MSB-first (leftmost character
 * of the code string is sent first). The leading '1' of each subsequent character
 * simultaneously serves as: the trigger that decodes the previous character, and
 * the new sentinel state for decoding the current character.
 *
 * ## Decoding mechanism
 * A 32-bit shift register accumulates incoming bits, initialized to 1 (the sentinel).
 * On each incoming bit:
 *   1. shiftRegister = (shiftRegister shl 1) or bit
 *   2. If (shiftRegister and 7) == 1 (bottom three bits = "001"):
 *      - (shiftRegister ushr 1) is the binary value of the completed code word
 *      - Look up in the reverse table to recover the character
 *      - Reset shiftRegister to 1 (sentinel — the just-received '1' is already the
 *        leading bit of the next character's accumulation)
 *
 * During the preamble (a stream of decoded zeros from the Viterbi decoder), the
 * sentinel '1' shifts out of the 32-bit register leaving 0. The first received '1'
 * then immediately triggers (0b001), varidec(0) returns null (not a valid code),
 * and the register resets to 1, ready to accumulate the first character normally.
 *
 * ushr (unsigned right shift) is mandatory — Kotlin's shr is arithmetic and would
 * sign-extend, corrupting the code word lookup for registers with bit 31 set.
 *
 * Reference: fldigi src/mfsk/mfskvaricode.cxx
 * Spec: http://www.qsl.net/zl1bpu/MFSK/Varicode.html
 */
object Varicode
{
    // -------------------------------------------------------------------------
    // IZ8BLY Varicode table — index is ASCII code point (0–255)
    // Source: fldigi src/mfsk/mfskvaricode.cxx
    // -------------------------------------------------------------------------

    private val TABLE = arrayOf(
        "11101011100",      //   0 NUL
        "11101100000",      //   1 SOH
        "11101101000",      //   2 STX
        "11101101100",      //   3 ETX
        "11101110000",      //   4 EOT
        "11101110100",      //   5 ENQ
        "11101111000",      //   6 ACK
        "11101111100",      //   7 BEL
        "10101000",         //   8 BS
        "11110000000",      //   9 HT
        "11110100000",      //  10 LF
        "11110101000",      //  11 VT
        "11110101100",      //  12 FF
        "10101100",         //  13 CR
        "11110110000",      //  14 SO
        "11110110100",      //  15 SI
        "11110111000",      //  16 DLE
        "11110111100",      //  17 DC1
        "11111000000",      //  18 DC2
        "11111010000",      //  19 DC3
        "11111010100",      //  20 DC4
        "11111011000",      //  21 NAK
        "11111011100",      //  22 SYN
        "11111100000",      //  23 ETB
        "11111101000",      //  24 CAN
        "11111101100",      //  25 EM
        "11111110000",      //  26 SUB
        "11111110100",      //  27 ESC
        "11111111000",      //  28 FS
        "11111111100",      //  29 GS
        "100000000000",     //  30 RS
        "101000000000",     //  31 US
        "100",              //  32 SP
        "111000000",        //  33 !
        "111111100",        //  34 "
        "1011011000",       //  35 #
        "1010101000",       //  36 $
        "1010100000",       //  37 %
        "1000000000",       //  38 &
        "110111100",        //  39 '
        "111110100",        //  40 (
        "111110000",        //  41 )
        "1010110100",       //  42 *
        "111100000",        //  43 +
        "10100000",         //  44 ,
        "111011000",        //  45 -
        "111010100",        //  46 .
        "111101000",        //  47 /
        "11100000",         //  48 0
        "11110000",         //  49 1
        "101000000",        //  50 2
        "101010100",        //  51 3
        "101110100",        //  52 4
        "101100000",        //  53 5
        "101101100",        //  54 6
        "110100000",        //  55 7
        "110000000",        //  56 8
        "110101100",        //  57 9
        "111101100",        //  58 :
        "111111000",        //  59 ;
        "1011000000",       //  60 <
        "111011100",        //  61 =
        "1010111100",       //  62 >
        "111010000",        //  63 ?
        "1010000000",       //  64 @
        "10111100",         //  65 A
        "100000000",        //  66 B
        "11010100",         //  67 C
        "11011100",         //  68 D
        "10111000",         //  69 E
        "11111000",         //  70 F
        "101010000",        //  71 G
        "101011000",        //  72 H
        "11000000",         //  73 I
        "110110100",        //  74 J
        "101111100",        //  75 K
        "11110100",         //  76 L
        "11101000",         //  77 M
        "11111100",         //  78 N
        "11010000",         //  79 O
        "11101100",         //  80 P
        "110110000",        //  81 Q
        "11011000",         //  82 R
        "10110100",         //  83 S
        "10110000",         //  84 T
        "101011100",        //  85 U
        "110101000",        //  86 V
        "101101000",        //  87 W
        "101110000",        //  88 X
        "101111000",        //  89 Y
        "110111000",        //  90 Z
        "1011101000",       //  91 [
        "1011010000",       //  92 \
        "1011101100",       //  93 ]
        "1011010100",       //  94 ^
        "1010110000",       //  95 _
        "1010101100",       //  96 `
        "10100",            //  97 a
        "1100000",          //  98 b
        "111000",           //  99 c
        "110100",           // 100 d
        "1000",             // 101 e
        "1010000",          // 102 f
        "1011000",          // 103 g
        "110000",           // 104 h
        "11000",            // 105 i
        "10000000",         // 106 j
        "1110000",          // 107 k
        "101100",           // 108 l
        "1000000",          // 109 m
        "11100",            // 110 n
        "10000",            // 111 o
        "1010100",          // 112 p
        "1111000",          // 113 q
        "100000",           // 114 r
        "101000",           // 115 s
        "1100",             // 116 t
        "111100",           // 117 u
        "1101100",          // 118 v
        "1101000",          // 119 w
        "1110100",          // 120 x
        "1011100",          // 121 y
        "1111100",          // 122 z
        "1011011100",       // 123 {
        "1010111000",       // 124 |
        "1011100000",       // 125 }
        "1011110000",       // 126 ~
        "101010000000",     // 127 DEL
        "101010100000",     // 128
        "101010101000",     // 129
        "101010101100",     // 130
        "101010110000",     // 131
        "101010110100",     // 132
        "101010111000",     // 133
        "101010111100",     // 134
        "101011000000",     // 135
        "101011010000",     // 136
        "101011010100",     // 137
        "101011011000",     // 138
        "101011011100",     // 139
        "101011100000",     // 140
        "101011101000",     // 141
        "101011101100",     // 142
        "101011110000",     // 143
        "101011110100",     // 144
        "101011111000",     // 145
        "101011111100",     // 146
        "101100000000",     // 147
        "101101000000",     // 148
        "101101010000",     // 149
        "101101010100",     // 150
        "101101011000",     // 151
        "101101011100",     // 152
        "101101100000",     // 153
        "101101101000",     // 154
        "101101101100",     // 155
        "101101110000",     // 156
        "101101110100",     // 157
        "101101111000",     // 158
        "101101111100",     // 159
        "1011110100",       // 160
        "1011111000",       // 161
        "1011111100",       // 162
        "1100000000",       // 163
        "1101000000",       // 164
        "1101010000",       // 165
        "1101010100",       // 166
        "1101011000",       // 167
        "1101011100",       // 168
        "1101100000",       // 169
        "1101101000",       // 170
        "1101101100",       // 171
        "1101110000",       // 172
        "1101110100",       // 173
        "1101111000",       // 174
        "1101111100",       // 175
        "1110000000",       // 176
        "1110100000",       // 177
        "1110101000",       // 178
        "1110101100",       // 179
        "1110110000",       // 180
        "1110110100",       // 181
        "1110111000",       // 182
        "1110111100",       // 183
        "1111000000",       // 184
        "1111010000",       // 185
        "1111010100",       // 186
        "1111011000",       // 187
        "1111011100",       // 188
        "1111100000",       // 189
        "1111101000",       // 190
        "1111101100",       // 191
        "1111110000",       // 192
        "1111110100",       // 193
        "1111111000",       // 194
        "1111111100",       // 195
        "10000000000",      // 196
        "10100000000",      // 197
        "10101000000",      // 198
        "10101010000",      // 199
        "10101010100",      // 200
        "10101011000",      // 201
        "10101011100",      // 202
        "10101100000",      // 203
        "10101101000",      // 204
        "10101101100",      // 205
        "10101110000",      // 206
        "10101110100",      // 207
        "10101111000",      // 208
        "10101111100",      // 209
        "10110000000",      // 210
        "10110100000",      // 211
        "10110101000",      // 212
        "10110101100",      // 213
        "10110110000",      // 214
        "10110110100",      // 215
        "10110111000",      // 216
        "10110111100",      // 217
        "10111000000",      // 218
        "10111010000",      // 219
        "10111010100",      // 220
        "10111011000",      // 221
        "10111011100",      // 222
        "10111100000",      // 223
        "10111101000",      // 224
        "10111101100",      // 225
        "10111110000",      // 226
        "10111110100",      // 227
        "10111111000",      // 228
        "10111111100",      // 229
        "11000000000",      // 230
        "11010000000",      // 231
        "11010100000",      // 232
        "11010101000",      // 233
        "11010101100",      // 234
        "11010110000",      // 235
        "11010110100",      // 236
        "11010111000",      // 237
        "11010111100",      // 238
        "11011000000",      // 239
        "11011010000",      // 240
        "11011010100",      // 241
        "11011011000",      // 242
        "11011011100",      // 243
        "11011100000",      // 244
        "11011101000",      // 245
        "11011101100",      // 246
        "11011110000",      // 247
        "11011110100",      // 248
        "11011111000",      // 249
        "11011111100",      // 250
        "11100000000",      // 251
        "11101000000",      // 252
        "11101010000",      // 253
        "11101010100",      // 254
        "11101011000"       // 255
    )

    // -------------------------------------------------------------------------
    // Reverse lookup table — binary code value → ASCII code point
    // Built once at class load time; avoids linear scan at decode time.
    // -------------------------------------------------------------------------

    private val REVERSE_TABLE = HashMap<Int, Int>(512)

    init
    {
        for (charCode in TABLE.indices)
        {
            // Convert the code string to its integer binary value (MSB at left).
            var binaryValue = 0
            for (bit in TABLE[charCode]) binaryValue = (binaryValue shl 1) or (if (bit == '1') 1 else 0)
            REVERSE_TABLE[binaryValue] = charCode
        }
    }

    // -------------------------------------------------------------------------
    // Encoding
    // -------------------------------------------------------------------------

    /**
     * Returns the IZ8BLY code bits for [char] as an IntArray of 0s and 1s, MSB first.
     *
     * Returns null for code points outside [0, 255] — the IZ8BLY table does not
     * cover Unicode. For Nahoft's use case (base64 text), all characters are ASCII.
     *
     * These bits are passed one at a time to [ConvolutionalEncoder] by the caller.
     * No explicit separator is appended — the trailing zeros already embedded in
     * each code word serve as the separator.
     */
    fun encodedBits(char: Char): IntArray?
    {
        val code = char.code
        if (code !in 0..255) return null
        val codeString = TABLE[code]
        return IntArray(codeString.length) { i -> if (codeString[i] == '1') 1 else 0 }
    }

    // -------------------------------------------------------------------------
    // Decoding
    // -------------------------------------------------------------------------

    /**
     * Streaming IZ8BLY Varicode decoder.
     *
     * Feed decoded bits one at a time from the Viterbi decoder output.
     * Each non-null return value is a decoded character.
     *
     * One instance per receive session. Call [reset] to clear state between sessions.
     */
    class Decoder
    {
        /**
         * Shift register initialized to 1 (the sentinel).
         *
         * The sentinel represents having "just received the leading '1' of the current
         * character." This state is set both at initialization and after each successful
         * decode — the leading '1' of each character that triggers the decode of the
         * previous character simultaneously becomes the new sentinel for the next character.
         *
         * Note: ushr (unsigned right shift) is used throughout. Kotlin's shr is arithmetic
         * and would sign-extend, corrupting the code word value for registers with bit 31 set.
         */
        private var shiftRegister = 1

        /**
         * Feeds one decoded bit into the shift register.
         *
         * @param bit The decoded bit (any non-zero value is treated as 1).
         * @return The decoded [Char] if a complete code word was recognized, null otherwise.
         *         A null return from [REVERSE_TABLE] (unrecognized code) also returns null
         *         rather than a garbage character — this handles the initial preamble trigger.
         */
        fun feed(bit: Int): Char?
        {
            shiftRegister = (shiftRegister shl 1) or (if (bit != 0) 1 else 0)

            // Bottom three bits == "001": the leading '1' of the next character has arrived,
            // which signals that the previous character's code word is complete.
            if ((shiftRegister and 7) == 1)
            {
                // Remove the trigger '1' from the bottom — the remaining value is the
                // binary representation of the completed code word (including its leading '1').
                val codeValue = shiftRegister ushr 1

                // Reset to sentinel: the trigger '1' is now the start of the next character.
                shiftRegister = 1

                // Look up and return the character, or null for unrecognized codes.
                val charCode = REVERSE_TABLE[codeValue] ?: return null
                return charCode.toChar()
            }

            return null
        }

        /**
         * Resets the shift register to the sentinel state.
         * Call at the start of each new receive session.
         */
        fun reset()
        {
            shiftRegister = 1
        }
    }
}