package org.operatorfoundation.audiocoder;

public class CJarInterface {
    static {
        System.loadLibrary("fftw3f");
        System.loadLibrary("QuietScream");
    }

    /**
     * Encodes WSPR messages into frequency data for direct radio hardware control.
     *
     * @param callsign Amateur radio callsign
     * @param locator Maidenhead grid square locator
     * @param power Power level in dBm (0-60)
     * @param offset Frequency offset in Hz (added to 1500 Hz base)
     * @param lsb LSB mode - inverts symbol order if true
     * @return long array containing 162 frequencies (Hz * 100 for 0.01 Hz precision)
     */
    public static native long[] WSPREncodeToFrequencies(String callsign, String locator, int power, int offset, boolean lsb);

    public static native byte[] WSPREncodeToPCM(String callsign, String locator, int power, int offset, boolean lsb);

    public static native WSPRMessage[] WSPRDecodeFromPcm(byte[] sound, double dialfreq, boolean lsb);

    public static native int WSPRNhash(String call);

    public static native double WSPRGetDistanceBetweenLocators(String a, String b);

    public static native String WSPRLatLonToGSQ(double lat, double lon);

    public static native int radioCheck(int testvar);
}
