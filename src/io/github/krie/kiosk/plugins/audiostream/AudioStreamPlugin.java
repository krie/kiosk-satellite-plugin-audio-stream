// SPDX-License-Identifier: Apache-2.0
package io.github.krie.kiosk.plugins.audiostream;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Streams Android microphone audio as AAC/ADTS over HTTP.
 */
public final class AudioStreamPlugin implements KioskPlugin {
    private volatile PluginHost host;
    private volatile int port = PluginSettings.DEFAULT_PORT;
    private volatile double gainDb = PluginSettings.DEFAULT_GAIN_DB;
    private volatile double gainLinear = 1.0;
    private volatile AacHttpServer server;
    private volatile AacEncoder encoder;
    private volatile AudioCapture capture;
    private ScheduledExecutorService monitor;
    private volatile long lastPcmNs;
    private volatile String streamIp;

    @Override
    public synchronized void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        this.port = PluginSettings.readPort(settings);
        setGainDb(PluginSettings.readGainDb(settings));

        startRuntime();
        host.subscribe("device.network");
        refreshStreamUrl();

        monitor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "audio-stream-monitor");
            thread.setDaemon(true);
            return thread;
        });
        monitor.scheduleWithFixedDelay(this::monitor, 0, 2, TimeUnit.SECONDS);
        host.log(
                "Audio Stream plugin started on port " + port
                        + " with input gain " + PluginSettings.formatGain(gainDb)
        );
    }

    @Override
    public synchronized void configure(Map<String, Object> settings) {
        int nextPort = PluginSettings.readPort(settings);
        double nextGainDb = PluginSettings.readGainDb(settings);
        boolean portChanged = nextPort != port;

        port = nextPort;
        setGainDb(nextGainDb);

        if (host != null && portChanged) {
            restartServer();
            publishEndpoint();
        }
    }

    @Override
    public void execute(String command, Map<String, Object> arguments) {
        throw new IllegalArgumentException("Unknown command: " + command);
    }

    @Override
    public void onEvent(String event, Map<String, Object> payload) {
        if (!"ks.device.network".equals(event)) return;

        if (Boolean.TRUE.equals(payload.get("up"))) {
            refreshStreamUrl();
        } else {
            streamIp = null;
            publishEndpoint();
        }
    }

    private void refreshStreamUrl() {
        PluginHost currentHost = host;
        if (currentHost == null) return;

        currentHost.executeCommand("getDeviceInfo", new LinkedHashMap<>(), (ok, data, error) -> {
            if (host == null) return;

            String ip = null;
            if (ok && data instanceof Map) {
                Object value = ((Map<?, ?>) data).get("ip");
                if (value != null) {
                    String text = String.valueOf(value).trim();
                    if (!text.isEmpty()) ip = text;
                }
            }

            streamIp = ip;
            publishEndpoint();
        });
    }

    private void publishEndpoint() {
        PluginHost currentHost = host;
        if (currentHost == null) return;
        currentHost.publishTextSensor(
                "endpoint",
                "Stream URL",
                PluginSettings.streamUrl(streamIp, port)
        );
    }

    private synchronized void startRuntime() {
        encoder = new AacEncoder(frame -> {
            AacHttpServer current = server;
            if (current != null) current.broadcast(frame);
        });
        encoder.start();

        capture = new AudioCapture((pcm, timeUs) -> {
            lastPcmNs = System.nanoTime();
            PcmGain.applyInPlace(pcm, gainLinear);
            AacEncoder current = encoder;
            if (current != null) current.offer(pcm, timeUs);
        });

        try {
            capture.start();
        } catch (Throwable t) {
            if (host != null) host.status("Microphone capture failed: " + rootMessage(t), true);
        }

        startServer();
    }

    private synchronized void startServer() {
        AacHttpServer next = new AacHttpServer(port);
        try {
            next.start();
            server = next;
        } catch (Exception e) {
            next.close();
            server = null;
            if (host != null) host.status("HTTP server failed: " + rootMessage(e), true);
        }
    }

    private synchronized void restartServer() {
        AacHttpServer old = server;
        server = null;
        if (old != null) old.close();
        startServer();
    }

    private void monitor() {
        try {
            monitorOnce();
        } catch (Throwable ignored) {
            // The host may be revoked while stop() races with this background tick.
        }
    }

    private void monitorOnce() {
        PluginHost currentHost = host;
        AudioCapture currentCapture = capture;
        AacEncoder currentEncoder = encoder;
        AacHttpServer currentServer = server;
        if (currentHost == null || currentCapture == null || currentEncoder == null) return;

        long ageNs = lastPcmNs == 0 ? Long.MAX_VALUE : System.nanoTime() - lastPcmNs;
        boolean audioActive = currentCapture.isRunning()
                && ageNs < TimeUnit.SECONDS.toNanos(3);
        int clients = currentServer == null ? 0 : currentServer.clientCount();

        String status;
        boolean error = false;
        if (currentCapture.error() != null) {
            status = "Microphone capture failed: " + currentCapture.error();
            error = true;
        } else if (!currentCapture.isRunning()) {
            status = "Microphone capture is not running";
            error = true;
        } else if (currentEncoder.error() != null) {
            status = "AAC encoder failed: " + currentEncoder.error();
            error = true;
        } else if (currentServer == null) {
            status = "HTTP server is not running";
            error = true;
        } else if (currentServer.error() != null) {
            status = "HTTP server error: " + currentServer.error();
            error = true;
        } else if (!audioActive) {
            status = "Microphone is open, waiting for PCM audio";
        } else {
            status = "Streaming AAC-LC 48 kHz mono at "
                    + PluginSettings.formatGain(gainDb)
                    + "; " + clients + " client" + (clients == 1 ? "" : "s");
        }

        currentHost.status(status, error);
        currentHost.publishBinarySensor(
                "audio_active",
                "Microphone audio active",
                "",
                audioActive
        );

        Map<String, Object> countMetadata = new LinkedHashMap<>();
        countMetadata.put("accuracyDecimals", 0);
        currentHost.publishSensor(
                "clients",
                "Connected stream clients",
                countMetadata,
                (double) clients
        );

        Map<String, Object> gainMetadata = new LinkedHashMap<>();
        gainMetadata.put("unit", "dB");
        gainMetadata.put("stateClass", "measurement");
        gainMetadata.put("accuracyDecimals", 0);
        currentHost.publishSensor("input_gain", "Input gain", gainMetadata, gainDb);

        currentHost.publishTextSensor("stream_status", "Audio stream status", status);
        publishEndpoint();
    }

    private void setGainDb(double value) {
        gainDb = value;
        gainLinear = PcmGain.dbToLinear(value);
    }

    @Override
    public synchronized void stop() {
        ScheduledExecutorService currentMonitor = monitor;
        monitor = null;
        if (currentMonitor != null) currentMonitor.shutdownNow();

        AudioCapture currentCapture = capture;
        capture = null;
        if (currentCapture != null) currentCapture.close();

        AacEncoder currentEncoder = encoder;
        encoder = null;
        if (currentEncoder != null) currentEncoder.close();

        AacHttpServer currentServer = server;
        server = null;
        if (currentServer != null) currentServer.close();

        lastPcmNs = 0;
        streamIp = null;
        host = null;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : ": " + message);
    }
}
