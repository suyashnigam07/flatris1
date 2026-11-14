package com.home.intercom;

/** Generates a short two-note "ding-dong" so the listener knows a message is coming. */
final class Chime {
    private Chime() { }

    static short[] dingDong(int rate) {
        double[] freqs = {880.0, 660.0};
        int noteLen = (int) (rate * 0.45);
        short[] out = new short[noteLen * freqs.length + rate / 5];
        int pos = 0;
        for (double f : freqs) {
            for (int i = 0; i < noteLen; i++) {
                double t = (double) i / rate;
                double env = Math.min(1.0, i / (rate * 0.01)) * Math.exp(-4.0 * t);
                double v = Math.sin(2 * Math.PI * f * t) + 0.3 * Math.sin(2 * Math.PI * 2 * f * t);
                out[pos++] = (short) (v / 1.3 * env * 0.8 * 32767);
            }
        }
        return out; // trailing samples stay zero = short pause before speech
    }
}
