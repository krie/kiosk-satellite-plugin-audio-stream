// SPDX-License-Identifier: Apache-2.0
package io.github.krie.kiosk.plugins.audiostream;

/**
 * Applies digital gain to little-endian signed PCM16 audio in place.
 */
final class PcmGain {
    private PcmGain() {
    }

    static void applyInPlace(byte[] pcm, double linearGain) {
        if (pcm == null || pcm.length < 2 || Math.abs(linearGain - 1.0) < 0.000001) return;

        int length = pcm.length & ~1;
        for (int i = 0; i < length; i += 2) {
            int sample = (short) (((pcm[i + 1] & 0xff) << 8) | (pcm[i] & 0xff));
            int scaled = (int) Math.round(sample * linearGain);

            if (scaled > 32767) scaled = 32767;
            else if (scaled < -32768) scaled = -32768;

            pcm[i] = (byte) (scaled & 0xff);
            pcm[i + 1] = (byte) ((scaled >>> 8) & 0xff);
        }
    }

    static double dbToLinear(double gainDb) {
        return Math.pow(10.0, gainDb / 20.0);
    }
}
