package org.operatorfoundation.audiocoder

import android.util.Log

/**
 * WSPR (Weak Signal Propagation Reporter) encoder for Type 1 messages.
 *
 * Encodes callsign, 4-character Maidenhead locator, and power into 162 frequency values
 * suitable for WSPR transmission.
 *
 * Based on BD1ES's wspr_enc.c implementation.
 */
object WSPREncoder {

    private const val TAG = "WSPREncoder"
    private const val SYMBOL_COUNT = 162
    private const val BASE_FREQUENCY_HZ = 1500.0
    private const val SYMBOL_SPACING_HZ = 1.4648

    /**
     * WSPR message containing all transmission parameters.
     */
    data class WSPRMessage(
        val callsign: String,      // e.g., "W1ABC"
        val locator: String,       // 4-character grid square, e.g., "FN20"
        val powerDbm: Int,         // Transmit power in dBm
        val offsetHz: Int = 0,     // Frequency offset from base
        val lsbMode: Boolean = false  // Invert symbols for LSB
    )

    /**
     * Encodes a WSPR message and returns frequencies in centihertz (0.01 Hz precision).
     *
     * @param message WSPR message parameters
     * @return Array of 162 frequencies in centihertz
     */
    fun encodeToFrequencies(message: WSPRMessage): LongArray {
        Log.i(TAG, "Encoding WSPR: ${message.callsign} ${message.locator} ${message.powerDbm}dBm")

        require(message.locator.length == 4) {
            "Only 4-character grid locators supported (Type 1 messages)"
        }

        // Encode message to 162 symbols (values 0-3)
        val symbols = encodeToSymbols(message.callsign, message.locator, message.powerDbm)

        // Convert symbols to frequencies
        return symbols.mapIndexed { index, symbol ->
            // Apply LSB mode inversion if requested
            val adjustedSymbol = if (message.lsbMode) {
                (3 - symbol.toInt()).toUByte()
            } else {
                symbol
            }

            // Calculate frequency: base + offset + (symbol * spacing)
            val frequencyHz = BASE_FREQUENCY_HZ +
                    message.offsetHz +
                    (adjustedSymbol.toInt() * SYMBOL_SPACING_HZ)

            // Convert to centihertz (0.01 Hz precision)
            val centihertz = (frequencyHz * 100.0).toLong()

            if (index < 5) {
                Log.d(TAG, "Symbol[$index] = $adjustedSymbol → ${frequencyHz.format(4)} Hz → $centihertz cHz")
            }

            centihertz
        }.toLongArray()
    }

    // ==================== Private Implementation ====================

    /**
     * Character encoding table: '0'-'9' → 0-9, 'A'-'Z' → 10-35, space → 36
     */
    private val CHAR_ENCODING = IntArray(128) { index ->
        when (index) {
            in '0'.code..'9'.code -> index - '0'.code
            in 'A'.code..'Z'.code -> index - 'A'.code + 10
            in 'a'.code..'z'.code -> index - 'a'.code + 10
            ' '.code -> 36
            else -> 0
        }
    }

    /**
     * Byte parity lookup table at bit #1 (256 values)
     */
    private val BYTE_PARITY = byteArrayOf(
        0, 2, 2, 0, 2, 0, 0, 2, 2, 0, 0, 2, 0, 2, 2, 0, 2, 0, 0, 2, 0, 2, 2, 0,
        0, 2, 2, 0, 2, 0, 0, 2, 2, 0, 0, 2, 0, 2, 2, 0, 0, 2, 2, 0, 2, 0, 0, 2,
        0, 2, 2, 0, 2, 0, 0, 2, 2, 0, 0, 2, 0, 2, 2, 0, 2, 0, 0, 2, 0, 2, 2, 0,
        0, 2, 2, 0, 2, 0, 0, 2, 0, 2, 2, 0, 2, 0, 0, 2, 2, 0, 0, 2, 0, 2, 2, 0,
        0, 2, 2, 0, 2, 0, 0, 2, 2, 0, 0, 2, 0, 2, 2, 0, 2, 0, 0, 2, 0, 2, 2, 0,
        0, 2, 2, 0, 2, 0, 0, 2, 2, 0, 0, 2, 0, 2, 2, 0, 0, 2, 2, 0, 2, 0, 0, 2,
        0, 2, 2, 0, 2, 0, 0, 2, 2, 0, 0, 2, 0, 2, 2, 0, 0, 2, 2, 0, 2, 0, 0, 2,
        2, 0, 0, 2, 0, 2, 2, 0, 2, 0, 0, 2, 0, 2, 2, 0, 0, 2, 2, 0, 2, 0, 0, 2,
        0, 2, 2, 0, 2, 0, 0, 2, 2, 0, 0, 2, 0, 2, 2, 0, 2, 0, 0, 2, 0, 2, 2, 0,
        0, 2, 2, 0, 2, 0, 0, 2, 2, 0, 0, 2, 0, 2, 2, 0, 0, 2, 2, 0, 2, 0, 0, 2,
        0, 2, 2, 0, 2, 0, 0, 2, 2, 0, 0, 2, 0, 2, 2, 0
    )

    /**
     * Bit interleaving pattern (162 values)
     */
    private val INTERLEAVE = intArrayOf(
        0, 128, 64, 32, 160, 96, 16, 144, 80, 48, 112, 8, 136, 72,
        40, 104, 24, 152, 88, 56, 120, 4, 132, 68, 36, 100, 20, 148,
        84, 52, 116, 12, 140, 76, 44, 108, 28, 156, 92, 60, 124, 2,
        130, 66, 34, 98, 18, 146, 82, 50, 114, 10, 138, 74, 42, 106,
        26, 154, 90, 58, 122, 6, 134, 70, 38, 102, 22, 150, 86, 54,
        118, 14, 142, 78, 46, 110, 30, 158, 94, 62, 126, 1, 129, 65,
        33, 161, 97, 17, 145, 81, 49, 113, 9, 137, 73, 41, 105, 25,
        153, 89, 57, 121, 5, 133, 69, 37, 101, 21, 149, 85, 53, 117,
        13, 141, 77, 45, 109, 29, 157, 93, 61, 125, 3, 131, 67, 35,
        99, 19, 147, 83, 51, 115, 11, 139, 75, 43, 107, 27, 155, 91,
        59, 123, 7, 135, 71, 39, 103, 23, 151, 87, 55, 119, 15, 143,
        79, 47, 111, 31, 159, 95, 63, 127
    )

    /**
     * Channel synchronization vector (162 values)
     */
    private val SYNC = byteArrayOf(
        1, 1, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 1, 1, 0, 0, 0, 1, 0, 0, 1, 0, 1,
        1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 1, 0,
        1, 1, 0, 0, 1, 1, 0, 1, 0, 0, 0, 1, 1, 0, 1, 0, 0, 0, 0, 1, 1, 0, 1, 0,
        1, 0, 1, 0, 1, 0, 0, 1, 0, 0, 1, 0, 1, 1, 0, 0, 0, 1, 1, 0, 1, 0, 1, 0,
        0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, 1, 1, 1, 0, 1, 1, 0, 0, 1, 1,
        0, 1, 0, 0, 0, 1, 1, 1, 0, 0, 0, 0, 0, 1, 0, 1, 0, 0, 1, 1, 0, 0, 0, 0,
        0, 0, 0, 1, 1, 0, 1, 0, 1, 1, 0, 0, 0, 1, 1, 0, 0, 0
    )

    /**
     * Power correction table for rounding to valid WSPR power levels
     */
    private val POWER_CORRECTION = intArrayOf(0, -1, 1, 0, -1, 2, 1, 0, -1, 1)

    /**
     * Encodes WSPR Type 1 message to 162 symbols (values 0-3)
     */
    private fun encodeToSymbols(callsign: String, locator: String, powerDbm: Int): UByteArray {
        // Pack callsign, grid, and power into 50 bits
        val packed = packMessage(callsign, locator, powerDbm)

        // Apply convolutional encoding and interleaving
        return applyConvolutionalEncoding(packed)
    }

    /**
     * Packs callsign into 28-bit integer (Type 1 format only)
     */
    private fun packCallsign(call: String): Long {
        val c = call.uppercase()

        // Find position of digit in callsign (must be at position 1 or 2)
        val digitPos = when {
            c.length > 2 && c[2].isDigit() -> 2
            c.length > 1 && c[1].isDigit() -> 1
            else -> throw IllegalArgumentException("Invalid callsign format: $call")
        }

        // Number of suffix characters after the digit
        val suffixLen = c.length - digitPos - 1

        // Extract parts: prefix (0-2 chars), digit, suffix (0-3 chars)
        val prefix1 = if (digitPos >= 2) CHAR_ENCODING[c[digitPos - 2].code] else 36
        val prefix2 = if (digitPos >= 1) CHAR_ENCODING[c[digitPos - 1].code] else 36
        val digit = CHAR_ENCODING[c[digitPos].code]
        val suffix1 = if (suffixLen >= 1) CHAR_ENCODING[c[digitPos + 1].code] - 10 else 26
        val suffix2 = if (suffixLen >= 2) CHAR_ENCODING[c[digitPos + 2].code] - 10 else 26
        val suffix3 = if (suffixLen >= 3) CHAR_ENCODING[c[digitPos + 3].code] - 10 else 26

        // Pack into 28-bit value using radix encoding
        var n = prefix1.toLong()
        n = 36 * n + prefix2
        n = 10 * n + digit
        n = 27 * n + suffix1
        n = 27 * n + suffix2
        n = 27 * n + suffix3

        return n
    }

    /**
     * Packs 4-character grid locator into 15-bit integer
     */
    private fun packGridLocator(grid: String): Long {
        val g = grid.uppercase()
        require(g.length == 4) { "Grid locator must be 4 characters" }

        val field1 = CHAR_ENCODING[g[0].code] - 10  // First letter (A-R → 0-17)
        val field2 = CHAR_ENCODING[g[1].code] - 10  // Second letter
        val square1 = CHAR_ENCODING[g[2].code]      // First digit (0-9)
        val square2 = CHAR_ENCODING[g[3].code]      // Second digit

        return 180L * (179 - 10 * field1 - square1) + 10 * field2 + square2
    }

    /**
     * Packs complete WSPR Type 1 message into 11 bytes (88 bits, with 50 bits used)
     */
    private fun packMessage(callsign: String, locator: String, powerDbm: Int): ByteArray {
        val n1 = packCallsign(callsign)          // 28 bits: callsign
        var ng = packGridLocator(locator)        // 15 bits: grid locator

        // Correct power to nearest valid WSPR power level
        val correctedPower = (powerDbm.coerceIn(0, 60) +
                POWER_CORRECTION[powerDbm.coerceIn(0, 60) % 10])

        // Add power to grid field (Type 1: power + 0 for nadd=0)
        ng = 128 * ng + correctedPower + 64      // 22 bits total

        // Pack into 11 bytes (50 data bits + 38 zero bits for convolutional tail)
        return byteArrayOf(
            (n1 shr 20).toByte(),                           // Bits 27-20 of callsign
            (n1 shr 12).toByte(),                           // Bits 19-12
            (n1 shr 4).toByte(),                            // Bits 11-4
            (((n1 and 0x0F) shl 4) or ((ng shr 18) and 0x0F)).toByte(),  // Bits 3-0 + 21-18
            (ng shr 10).toByte(),                           // Bits 17-10 of grid+power
            (ng shr 2).toByte(),                            // Bits 9-2
            ((ng and 0x03) shl 6).toByte(),                 // Bits 1-0
            0, 0, 0, 0                                      // Zero tail for convolution
        )
    }

    /**
     * Applies convolutional encoding with interleaving to produce 162 symbols
     */
    private fun applyConvolutionalEncoding(packed: ByteArray): UByteArray {
        val symbols = UByteArray(SYMBOL_COUNT)
        var convPtr = 0
        var reg0 = 0L  // Shift register 0 (32 bits)
        var reg1 = 0L  // Shift register 1 (32 bits)

        // Process each of the 11 bytes, 8 bits each = 88 total bits
        for (byteIndex in packed.indices) {
            for (bitIndex in 0 until 8) {
                // Shift in next bit to both registers
                if ((packed[byteIndex].toInt() shl bitIndex) and 0x80 != 0) {
                    reg0 = reg0 or 1
                    reg1 = reg1 or 1
                }

                // Compute parity for register 0 with polynomial 0xF2D05351
                val parity0 = computeParity(reg0 and 0xF2D05351)
                val k0 = INTERLEAVE[convPtr++]
                symbols[k0] = (parity0 or SYNC[k0].toInt()).toUByte()

                // Compute parity for register 1 with polynomial 0xE4613C47
                val parity1 = computeParity(reg1 and 0xE4613C47)
                val k1 = INTERLEAVE[convPtr++]
                symbols[k1] = (parity1 or SYNC[k1].toInt()).toUByte()

                // Stop when we've generated all 162 symbols
                if (convPtr == SYMBOL_COUNT) {
                    return symbols
                }

                // Shift registers left for next bit
                reg0 = reg0 shl 1
                reg1 = reg1 shl 1
            }
        }

        return symbols
    }

    /**
     * Computes even parity of a 32-bit value by XORing byte parities
     */
    private fun computeParity(value: Long): Int {
        val byte0 = (value and 0xFF).toInt()
        val byte1 = ((value shr 8) and 0xFF).toInt()
        val byte2 = ((value shr 16) and 0xFF).toInt()
        val byte3 = ((value shr 24) and 0xFF).toInt()

        return BYTE_PARITY[byte0].toInt() xor
                BYTE_PARITY[byte1].toInt() xor
                BYTE_PARITY[byte2].toInt() xor
                BYTE_PARITY[byte3].toInt()
    }

    // Helper for double formatting
    private fun Double.format(decimals: Int) = "%.${decimals}f".format(this)
}