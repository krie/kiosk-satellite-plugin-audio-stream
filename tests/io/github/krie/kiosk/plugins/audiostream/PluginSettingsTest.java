// SPDX-License-Identifier: Apache-2.0
package io.github.krie.kiosk.plugins.audiostream;

import java.util.HashMap;
import java.util.Map;

public final class PluginSettingsTest {
    public static void main(String[] args) {
        Map<String, Object> settings = new HashMap<>();
        if (PluginSettings.readPort(settings) != 58585)
            throw new AssertionError("wrong default port");
        if (PluginSettings.readGainDb(settings) != 0.0)
            throw new AssertionError("wrong default gain");

        settings.put("port", "12345");
        settings.put("gainDb", 6);
        if (PluginSettings.readPort(settings) != 12345)
            throw new AssertionError("port parsing failed");
        if (PluginSettings.readGainDb(settings) != 6.0)
            throw new AssertionError("gain parsing failed");
        if (!"+6 dB".equals(PluginSettings.formatGain(6)))
            throw new AssertionError("gain formatting failed");
        if (!"http://192.168.1.5:12345/audio.aac".equals(
                PluginSettings.streamUrl("192.168.1.5", 12345)
        )) throw new AssertionError("stream URL formatting failed");

        expectFailure(() -> {
            Map<String, Object> invalid = new HashMap<>();
            invalid.put("port", "80");
            PluginSettings.readPort(invalid);
        });
        expectFailure(() -> {
            Map<String, Object> invalid = new HashMap<>();
            invalid.put("gainDb", 25);
            PluginSettings.readGainDb(invalid);
        });
        expectFailure(() -> {
            Map<String, Object> invalid = new HashMap<>();
            invalid.put("gainDb", 0.5);
            PluginSettings.readGainDb(invalid);
        });

        System.out.println("PluginSettingsTest passed");
    }

    private static void expectFailure(Runnable runnable) {
        try {
            runnable.run();
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
