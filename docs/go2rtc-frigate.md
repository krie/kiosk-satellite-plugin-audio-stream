# go2rtc and Frigate

The plugin exposes AAC-LC in ADTS format over HTTP:

`http://KIOSK_IP:58585/audio.aac`

Replace `KIOSK_IP` and the port if you changed it in the plugin settings.

## go2rtc: audio only

```yaml
go2rtc:
  streams:
    kiosk_audio:
      - http://KIOSK_IP:58585/audio.aac

  preload:
    kiosk_audio: "audio=aac"
```

`preload` keeps the audio source connected. This is useful for continuous audio detection.

## go2rtc: Kiosk Satellite video + plugin audio

Keep Kiosk Satellite's built-in RTSP audio disabled and combine its video track with the plugin
audio:

```yaml
go2rtc:
  streams:
    kiosk_camera:
      - rtsp://USER:PASS@KIOSK_IP:8554/camera#media=video
      - http://KIOSK_IP:58585/audio.aac
```

This gives go2rtc one stream with H.264 video from Kiosk Satellite and AAC audio from the plugin.

Do not preload this combined stream if you want the camera to remain on demand.

## Frigate: continuous audio detection

Use the audio-only go2rtc restream for Frigate's `audio` role so audio detection can run
continuously without requesting the camera stream.

Frigate still requires a video input with the `detect` role, even when object detection is disabled.
A generated dummy image can be used for an audio-only camera:

```yaml
cameras:
  kiosk_audio:
    enabled: true

    ffmpeg:
      hwaccel_args: [ ]
      inputs:
        # Dummy video because Frigate requires a detect stream
        - path: color=c=gray:s=320x240:r=1,drawtext=text='Audio detection':fontcolor=white:fontsize=28:x=(w-text_w)/2:y=(h-text_h)/2
          input_args: -re -f lavfi
          roles:
            - detect

        # Audio from the local go2rtc restream
        - path: rtsp://127.0.0.1:8554/kiosk_audio
          input_args: preset-rtsp-restream
          roles:
            - audio

    detect:
      enabled: false
      width: 320
      height: 240
      fps: 1

    motion:
      enabled: false

    record:
      enabled: false

    snapshots:
      enabled: false

    audio:
      enabled: true
      listen:
        - doorbell
        - bell
        - knock
```

The audio labels above are examples. Adjust them to match the sounds you want Frigate
to detect.

If go2rtc runs separately from Frigate instead of using Frigate's built-in go2rtc instance, replace
`127.0.0.1` with the address of the go2rtc host.