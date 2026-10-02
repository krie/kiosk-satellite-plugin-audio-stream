// SPDX-License-Identifier: Apache-2.0
package io.github.krie.kiosk.plugins.audiostream;

/**
 * Builds ADTS frames for AAC-LC audio.
 */
final class Adts {
    private Adts() {
    }

    static byte[] wrapAacLc(byte[] aac, int sampleRate, int channels) {
        if (aac == null) throw new IllegalArgumentException("AAC payload must not be null");
        if (channels < 1 || channels > 7)
            throw new IllegalArgumentException("ADTS channel count must be 1-7");

        int sampleIndex = sampleRateIndex(sampleRate);
        int profile = 2; // AAC LC
        int packetLength = aac.length + 7;
        if (packetLength > 0x1FFF)
            throw new IllegalArgumentException("AAC frame is too large for ADTS");

        byte[] packet = new byte[packetLength];
        packet[0] = (byte) 0xFF;
        packet[1] = (byte) 0xF1; // MPEG-4, layer 0, no CRC
        packet[2] = (byte) (((profile - 1) << 6) | (sampleIndex << 2) | (channels >> 2));
        packet[3] = (byte) (((channels & 3) << 6) | (packetLength >> 11));
        packet[4] = (byte) ((packetLength & 0x7FF) >> 3);
        packet[5] = (byte) (((packetLength & 7) << 5) | 0x1F);
        packet[6] = (byte) 0xFC;
        System.arraycopy(aac, 0, packet, 7, aac.length);
        return packet;
    }

    private static int sampleRateIndex(int sampleRate) {
        int[] rates = {
                96000, 88200, 64000, 48000, 44100, 32000, 24000,
                22050, 16000, 12000, 11025, 8000, 7350
        };
        for (int i = 0; i < rates.length; i++) {
            if (rates[i] == sampleRate) return i;
        }
        throw new IllegalArgumentException("Unsupported ADTS sample rate: " + sampleRate);
    }
}
