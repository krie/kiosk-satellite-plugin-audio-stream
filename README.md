# Kiosk Satellite Audio Stream

An unofficial [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite) plugin that captures
the Android microphone and exposes it as an AAC-LC/ADTS HTTP stream.

## Features

- 48 kHz mono microphone capture via Android `AudioRecord`
- AAC-LC encoding via Android `MediaCodec`
- ADTS over HTTP at `/audio.aac`
- Multiple simultaneous stream clients
- Adjustable digital input gain from `-12 dB` to `+24 dB`
- Runtime status and full stream URL in the plugin page
- No dependency on Kiosk Satellite implementation internals

## Installation

Kiosk Satellite installs published plugins from their public GitHub repository.

1. Open **Settings > Plugin Manager** on the kiosk or **Plugin Manager** in Remote Admin and enable
   **Enable Plugins**.
2. Choose **Add plugin**.
3. Paste this repository URL:

   `https://github.com/krie/kiosk-satellite-plugin-audio-stream`

4. Choose **Preview** and review the manifest, capabilities and README.
5. Choose **Trust and install**.
6. Enable **Audio Stream** from its entry row.

Kiosk Satellite uses the latest stable GitHub release and verifies the assets published by the
repository's GitHub Actions workflow. **Install from ZIP** is intended only for local development
builds.

## Settings

| Setting    | Default | Description                                     |
|------------|--------:|-------------------------------------------------|
| HTTP port  | `58585` | Port for `/audio.aac`; valid range `1024-65535` |
| Input gain |  `0 dB` | Digital PCM gain from `-12 dB` to `+24 dB`      |

Positive gain also raises background noise and can clip loud input. `+6 dB` is roughly 2x sample
amplitude.

The stream URL is shown on the plugin status page, for example:

`http://KIOSK_IP:58585/audio.aac`

## Usage

For go2rtc and Frigate examples, including an audio-only stream and a combined Kiosk Satellite
video + plugin audio stream, see [go2rtc and Frigate](docs/go2rtc-frigate.md).

## Microphone compatibility

This plugin opens its own Android `AudioRecord` session. Kiosk Satellite features that also capture
the microphone, such as built-in RTSP audio, wake word, clap detection, Voice Assist or intercom,
may compete with it depending on Android and the device.

For the most predictable setup, keep Kiosk Satellite's built-in **RTSP audio disabled** while this
plugin supplies audio. RTSP **video can remain enabled**.

## Build and test

Requirements:

- Python 3
- JDK 17 or newer
- Android SDK Platform 35
- Android SDK Build Tools

Set `ANDROID_HOME`, then run:

```sh
python3 tools/test.py
python3 tools/build.py --android-platform 35
```

The installable development package is written to `dist/audio-stream-1.0.0.zip`.

For a public release, publish a stable GitHub release tagged `v<version>`. The included release
workflow tests the source, builds the package with the tag version, and attaches the ZIP, checksum
and manifest required for repository installation.

## Security and privacy

The audio endpoint has **no authentication or TLS**. Anyone who can reach the configured port can
listen to the microphone stream. Use it only on a trusted network and do not expose the port to the
public internet.

The server accepts up to 16 simultaneous connections, including requests still reading headers.
Additional connections are closed immediately. Request headers are limited to 8 KiB and must be
complete within five seconds; this deadline does not limit the duration of an audio stream.

While enabled, the plugin continuously uses the device microphone. Android may show its microphone
privacy indicator accordingly.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
