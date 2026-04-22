package org.operatorfoundation.audiocoder.wspr;

public class WSPRMessage
{
    public String out;
    public float snr;
    public float dt;
    public float drift;
    public double freq;
    public String message;
    public String call;
    int callhash;
    public String loc;
    public int power;

    public WSPRMessage(float snr, double freq, float dt, float drift, String message)
    {
        this.snr = snr;
        this.freq = freq;
        this.message = message;
        this.dt = dt;
        this.drift = drift;

        out = "" + snr + " -> " + freq + " -> " + message;
    }

    public float getSNR() {
        return snr;
    }

    public double getFREQ() {
        return freq;
    }

    public String getMSG() {
        return message;
    }

    public float getDT() {
        return this.dt;
    }

    public float getDRIFT() {
        return this.drift;
    }

    public String getCALLSIGN() { return this.call; }

    public int getPOWER() { return this.power; }

    public String getGRIDSQUARE() { return this.loc; }
}
