// SPDX-License-Identifier: Apache-2.0
package io.github.krie.kiosk.plugins.audiostream;

import java.util.Arrays;

public final class AdtsTest {
    public static void main(String[] args) {
        byte[] payload = new byte[]{1, 2, 3, 4, 5};
        byte[] frame = Adts.wrapAacLc(payload, 48000, 1);

        if (frame.length != payload.length + 7) throw new AssertionError("wrong ADTS frame length");
        if ((frame[0] & 0xff) != 0xff || (frame[1] & 0xf0) != 0xf0) {
            throw new AssertionError("missing ADTS sync word");
        }
        int sampleRateIndex = (frame[2] >> 2) & 0x0f;
        if (sampleRateIndex != 3)
            throw new AssertionError("48 kHz must use ADTS frequency index 3");
        int channels = ((frame[2] & 1) << 2) | ((frame[3] >> 6) & 3);
        if (channels != 1) throw new AssertionError("expected mono ADTS header");
        if (!Arrays.equals(payload, Arrays.copyOfRange(frame, 7, frame.length))) {
            throw new AssertionError("AAC payload changed");
        }

        boolean rejected = false;
        try {
            Adts.wrapAacLc(payload, 12345, 1);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        if (!rejected) throw new AssertionError("unsupported sample rate should be rejected");

        System.out.println("AdtsTest passed");
    }
}
