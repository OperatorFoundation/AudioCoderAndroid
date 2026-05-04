package org.operatorfoundation.audiocoder.mfsk_andflmsg

/**
 * Describes a single tone to be emitted by a transmitter: a frequency held for
 * a fixed duration.
 *
 * Produced by [MFSKAndFlmsgTxHandle] during transmission. Consumers convert these
 * descriptors into whatever form their hardware expects — for example, USB serial
 * frequency commands to a software-defined radio, or PCM audio samples for an
 * audio output device.
 *
 * The native FldigiAndroid library emits frequencies as integers in millihertz
 * (Hz × 1000) interleaved with duration counts in an int array. The engine's
 * tone listener divides by 1000.0 to recover the original double-precision
 * frequency, preserving sub-hertz precision.
 *
 * @param frequencyHz     Tone frequency in hertz.
 * @param durationSamples Length of the tone in audio samples at the modem's
 *                        configured sample rate (8000 Hz for fldigi MFSK-16).
 */
data class ToneDescriptor(
    val frequencyHz: Double,
    val durationSamples: Int
)