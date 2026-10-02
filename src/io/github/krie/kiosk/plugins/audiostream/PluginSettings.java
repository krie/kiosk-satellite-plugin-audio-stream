// SPDX-License-Identifier: Apache-2.0
package io.github.krie.kiosk.plugins.audiostream;

import java.util.Map;

/**
 * Parsing and formatting for user-visible plugin settings.
 */
final class PluginSettings {
    static final int DEFAULT_PORT = 58585;
    static final double DEFAULT_GAIN_DB = 0.0;
    static final double MIN_GAIN_DB = -12.0;
    static final double MAX_GAIN_DB = 24.0;

    private PluginSettings() {
    }

    static int readPort(Map<String, Object> settings) {
        Object value = settings == null ? null : settings.get("port");
        String text = value == null ? String.valueOf(DEFAULT_PORT) : String.valueOf(value).trim();
        final int port;
        try {
            port = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Port must be a number between 1024 and 65535");
        }
        if (port < 1024 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1024 and 65535");
        }
        return port;
    }

    static double readGainDb(Map<String, Object> settings) {
        Object value = settings == null ? null : settings.get("gainDb");
        if (value == null) return DEFAULT_GAIN_DB;

        final double gain;
        if (value instanceof Number) {
            gain = ((Number) value).doubleValue();
        } else {
            try {
                gain = Double.parseDouble(String.valueOf(value).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Input gain must be between -12 and +24 dB");
            }
        }

        if (!Double.isFinite(gain) || gain < MIN_GAIN_DB || gain > MAX_GAIN_DB || Math.rint(gain) != gain) {
            throw new IllegalArgumentException("Input gain must be a whole number between -12 and +24 dB");
        }
        return gain;
    }

    static String formatGain(double value) {
        long rounded = Math.round(value);
        return (rounded > 0 ? "+" : "") + rounded + " dB";
    }

    static String streamUrl(String ip, int port) {
        if (ip == null || ip.trim().isEmpty())
            return "Unavailable while network address is unknown";
        return "http://" + ip.trim() + ":" + port + "/audio.aac";
    }
}
