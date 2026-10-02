// SPDX-License-Identifier: Apache-2.0
package io.github.krie.kiosk.plugins.audiostream;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Encodes 48 kHz mono PCM16 to AAC-LC with ADTS headers.
 */
final class AacEncoder implements AutoCloseable {
    interface FrameConsumer {
        void onFrame(byte[] adtsFrame);
    }

    private static final int SAMPLE_RATE = AudioCapture.SAMPLE_RATE;
    private static final int CHANNELS = AudioCapture.CHANNELS;
    private static final int BIT_RATE = 32000;
    // Live audio should skip stale PCM rather than build an audible delay.
    private static final int QUEUE_CAPACITY = 8;

    private static final class Chunk {
        final byte[] pcm;
        final long timeUs;

        Chunk(byte[] pcm, long timeUs) {
            this.pcm = pcm;
            this.timeUs = timeUs;
        }
    }

    private final FrameConsumer consumer;
    private final ArrayBlockingQueue<Chunk> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private volatile boolean running;
    private volatile String error;
    private volatile String codecName;
    private volatile long encodedFrames;
    private volatile long droppedChunks;
    private Thread worker;

    AacEncoder(FrameConsumer consumer) {
        this.consumer = consumer;
    }

    synchronized void start() {
        if (running) return;
        error = null;
        running = true;
        worker = new Thread(this::runEncoder, "audio-stream-aac");
        worker.setDaemon(true);
        worker.start();
    }

    void offer(byte[] pcm, long timeUs) {
        if (!running || pcm == null || pcm.length == 0) return;
        Chunk chunk = new Chunk(pcm.clone(), timeUs);
        if (!queue.offer(chunk)) {
            queue.poll();
            queue.offer(chunk);
            droppedChunks++;
        }
    }

    String error() {
        return error;
    }

    String codecName() {
        return codecName;
    }

    long encodedFrames() {
        return encodedFrames;
    }

    long droppedChunks() {
        return droppedChunks;
    }

    private void runEncoder() {
        MediaCodec codec = null;
        try {
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            MediaFormat format = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS
            );
            format.setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
            );
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            codecName = codec.getName();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            Chunk pending = null;
            int offset = 0;

            while (running) {
                if (pending == null) {
                    try {
                        pending = queue.poll(80, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException interrupted) {
                        if (!running) break;
                        continue;
                    }
                    offset = 0;
                }

                if (pending != null) {
                    int inputIndex = codec.dequeueInputBuffer(0);
                    if (inputIndex >= 0) {
                        ByteBuffer input = codec.getInputBuffer(inputIndex);
                        if (input == null)
                            throw new IllegalStateException("AAC encoder returned no input buffer");
                        input.clear();
                        int remaining = pending.pcm.length - offset;
                        int size = Math.min(input.remaining(), remaining) & ~1;
                        if (size <= 0)
                            throw new IllegalStateException("AAC encoder input buffer is too small");
                        input.put(pending.pcm, offset, size);
                        long pts = pending.timeUs
                                + offset * 1_000_000L / AudioCapture.BYTES_PER_SECOND;
                        codec.queueInputBuffer(inputIndex, 0, size, pts, 0);
                        offset += size;
                        if (offset >= pending.pcm.length) pending = null;
                    }
                }

                int outputIndex = codec.dequeueOutputBuffer(info, 10_000);
                while (outputIndex >= 0) {
                    if (info.size > 0
                            && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        ByteBuffer output = codec.getOutputBuffer(outputIndex);
                        if (output == null)
                            throw new IllegalStateException("AAC encoder returned no output buffer");
                        output.position(info.offset);
                        output.limit(info.offset + info.size);
                        byte[] aac = new byte[info.size];
                        output.get(aac);
                        consumer.onFrame(Adts.wrapAacLc(aac, SAMPLE_RATE, CHANNELS));
                        encodedFrames++;
                    }
                    codec.releaseOutputBuffer(outputIndex, false);
                    outputIndex = codec.dequeueOutputBuffer(info, 0);
                }
            }
        } catch (Throwable t) {
            if (running) error = FailureMessages.rootCause(t);
        } finally {
            running = false;
            queue.clear();
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Throwable ignored) {
                }
                try {
                    codec.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    @Override
    public synchronized void close() {
        running = false;
        queue.clear();
        Thread thread = worker;
        worker = null;
        if (thread != null) thread.interrupt();
    }

}
