// SPDX-License-Identifier: Apache-2.0
package io.github.krie.kiosk.plugins.audiostream;

public final class PcmGainTest {
    public static void main(String[] args) {
        assertNear(PcmGain.dbToLinear(0), 1.0, 0.000001);
        assertNear(PcmGain.dbToLinear(6), 1.995262, 0.00001);
        assertNear(PcmGain.dbToLinear(-6), 0.501187, 0.00001);

        byte[] pcm = samples(1000, -1000, 30000, -30000);
        PcmGain.applyInPlace(pcm, 2.0);
        assertSample(pcm, 0, 2000);
        assertSample(pcm, 1, -2000);
        assertSample(pcm, 2, 32767);
        assertSample(pcm, 3, -32768);

        byte[] unchanged = samples(1234);
        PcmGain.applyInPlace(unchanged, 1.0);
        assertSample(unchanged, 0, 1234);

        System.out.println("PcmGainTest passed");
    }

    private static byte[] samples(int... values) {
        byte[] pcm = new byte[values.length * 2];
        for (int i = 0; i < values.length; i++) {
            int value = values[i];
            pcm[i * 2] = (byte) (value & 0xff);
            pcm[i * 2 + 1] = (byte) ((value >>> 8) & 0xff);
        }
        return pcm;
    }

    private static void assertSample(byte[] pcm, int index, int expected) {
        int actual = (short) (((pcm[index * 2 + 1] & 0xff) << 8) | (pcm[index * 2] & 0xff));
        if (actual != expected) {
            throw new AssertionError("sample " + index + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertNear(double actual, double expected, double tolerance) {
        if (Math.abs(actual - expected) > tolerance) {
            throw new AssertionError("expected " + expected + ", got " + actual);
        }
    }
}
