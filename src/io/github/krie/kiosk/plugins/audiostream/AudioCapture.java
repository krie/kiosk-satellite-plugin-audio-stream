// SPDX-License-Identifier: Apache-2.0
package io.github.krie.kiosk.plugins.audiostream;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

/**
 * Own microphone capture for the plugin.
 * <p>
 * This deliberately does not depend on Kiosk Satellite implementation classes.
 * Keep KS wake-word/clap/RTSP-audio capture disabled while this plugin is active
 * so there is exactly one AudioRecord session using the microphone.
 */
final class AudioCapture implements AutoCloseable {
    interface PcmConsumer {
        void onPcm(byte[] pcm, long timeUs);
    }

    static final int SAMPLE_RATE = 48000;
    static final int CHANNELS = 1;
    static final int BYTES_PER_SAMPLE = 2;
    static final int BYTES_PER_SECOND = SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE;

    private final PcmConsumer consumer;
    private volatile boolean running;
    private volatile String error;
    private volatile long chunks;
    private AudioRecord record;
    private Thread worker;

    AudioCapture(PcmConsumer consumer) {
        this.consumer = consumer;
    }

    synchronized void start() {
        if (running) return;

        int minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        if (minBuffer <= 0) {
            throw new IllegalStateException("AudioRecord does not support 48 kHz mono PCM16");
        }

        int bufferBytes = Math.max(minBuffer * 2, SAMPLE_RATE * BYTES_PER_SAMPLE / 5); // >= 200 ms
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();

        AudioRecord next = new AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes)
                .build();

        if (next.getState() != AudioRecord.STATE_INITIALIZED) {
            next.release();
            throw new IllegalStateException("AudioRecord initialization failed");
        }

        record = next;
        error = null;
        chunks = 0;
        running = true;
        next.startRecording();

        worker = new Thread(this::captureLoop, "audio-stream-capture");
        worker.setDaemon(true);
        worker.start();
    }

    private void captureLoop() {
        // 40 ms chunks: 48,000 samples/s * 2 bytes * 0.04 s = 3,840 bytes.
        byte[] buffer = new byte[3840];
        try {
            while (running) {
                AudioRecord current = record;
                if (current == null) break;

                int read = current.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                if (read > 0) {
                    // PCM16 must contain complete samples.
                    read &= ~1;
                    if (read == 0) continue;

                    byte[] pcm = new byte[read];
                    System.arraycopy(buffer, 0, pcm, 0, read);
                    long endUs = System.nanoTime() / 1000L;
                    long durationUs = read * 1_000_000L / (SAMPLE_RATE * BYTES_PER_SAMPLE);
                    consumer.onPcm(pcm, endUs - durationUs);
                    chunks++;
                } else if (read == AudioRecord.ERROR_INVALID_OPERATION) {
                    throw new IllegalStateException("AudioRecord invalid operation");
                } else if (read == AudioRecord.ERROR_BAD_VALUE) {
                    throw new IllegalStateException("AudioRecord bad value");
                } else if (read == AudioRecord.ERROR_DEAD_OBJECT) {
                    throw new IllegalStateException("AudioRecord audio service died");
                }
            }
        } catch (Throwable t) {
            if (running) error = rootMessage(t);
        } finally {
            running = false;
        }
    }

    boolean isRunning() {
        return running && error == null;
    }

    String error() {
        return error;
    }

    long chunks() {
        return chunks;
    }

    @Override
    public synchronized void close() {
        running = false;
        AudioRecord current = record;
        record = null;
        if (current != null) {
            try {
                current.stop();
            } catch (Throwable ignored) {
            }
            try {
                current.release();
            } catch (Throwable ignored) {
            }
        }
        Thread currentWorker = worker;
        worker = null;
        if (currentWorker != null) currentWorker.interrupt();
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current)
            current = current.getCause();
        String message = current.getMessage();
        return current.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : ": " + message);
    }
}
