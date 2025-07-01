package org.operatorfoundation.audiocoder.extensions

// ========== Extension Functions for Formatting ==========

/**
 * Formats a floating-point number to the specified number of decimal places.
 */
internal fun Float.format(decimals: Int): String = "%.${decimals}f".format(this)

/**
 * Formats a double-precision number to the specified number of decimal places.
 */
internal fun Double.format(decimals: Int): String = "%.${decimals}f".format(this)

/**
 * Formats a frequency offset with appropriate sign and units.
 */
internal fun Double.formatOffset(): String
{
    val sign = if (this >= 0) "+" else ""
    return "$sign${format(1)}"
}