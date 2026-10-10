# Video Live — Local Video → YouTube Live (RTMP/RTMPS)

A production-oriented Android app that picks a local video, loops it forever through a
**single in-process FFmpeg session** (H.264 + AAC → FLV), and streams it to YouTube Live
over RTMP/RTMPS. The RTMP connection is opened **once** and is never reconnected at the
loop boundary — looping happens inside FFmpeg with `-stream_loop -1 -re`, so output
timestamps stay monotonic and YouTube sees one continuous broadcast.

## Pipeline

```
LOCAL VIDEO (SAF uri, zero-copy)
        │
        ▼
FFmpeg (ffmpeg-kit, in-process)
  -re -stream_loop -1 -i <video>
        │
  video decoder → libx264 (H.264, CBR, zerolatency)
  audio decoder → AAC 44.1 kHz stereo (or generated silence / mic mix)
        │
        ▼
FLV muxer → RTMP/RTMPS → YouTube Live
```

## Feature map

| Requirement | Implementation |
|---|---|
| Video selection | Storage Access Framework (`ACTION_OPEN_DOCUMENT`) + persistable Uri permission. No file copy: FFmpeg reads through ffmpeg-kit's SAF protocol; a cache copy is used only if the SAF descriptor is unreadable. |
| Continuous loop | `-stream_loop -1` inside one FFmpeg session. No stop/restart/reconnect at loop end. |
| Stable timestamps / A-V sync | Single session, single output clock; `-re` pacing; CFR output via `-r`. |
| Server URL + Stream Key | Separate fields combined internally, or advanced full-URL mode. Key stored in `EncryptedSharedPreferences` (Android Keystore), never logged, never in notifications — every log line passes a sanitizer. |
| Format 9:16 / 16:9 | Output resolution really changes (e.g. 1080x1920 vs 1920x1080) with scale+pad filters. |
| Quality / FPS / Bitrate | 360p–1080p, 24–60 fps, auto or manual CBR bitrate. 4K is intentionally not offered (honest software-encode limits). Device-adaptive FPS cap on weak CPUs. |
| Audio | Video volume 0–100%. Mic OFF keeps video audio. Mic ON mixes mic + video audio via FFmpeg `amix`, mic fed through an FFmpegKit named pipe (FIFO) written by `AudioRecord`. Mic mute writes silence so the pipeline never stalls. |
| Loop modes | Loop One (continuous single video). Loop All is presented but clearly marked as upcoming. |
| Preview | Native `VideoView` + thumbnail via `MediaMetadataRetriever`; metadata via real FFprobe. |
| Background streaming | Foreground service + persistent notification (chronometer, Open/Stop actions), partial WakeLock, battery-optimization guidance and exemption request. |
| Reconnect logic | Only on real network/RTMP failure or a 30 s watchdog stall. Exponential backoff 2s→30s, max 5 attempts, live status ("Reconnecting 2/5…"), budget reset after 60 s of healthy streaming. Auth/input errors fail fast with clear messages. |
| Error handling | FFmpeg stderr is classified into human messages ("Check your YouTube Stream Key.", "Internet connection lost…"). Advanced Logs screen shows sanitized logs. |

## Project structure

```
app/src/main/java/com/videolive/app/
├── MainActivity.kt            # UI: pick video, destination, format, quality, audio, loop, START LIVE
├── LiveActivity.kt            # LIVE screen: elapsed time, stats, mute mic, stop
├── SettingsActivity.kt        # Battery/help, engine info, security info
├── LogsActivity.kt            # Advanced (sanitized) logs
├── App.kt
├── model/StreamConfig.kt      # Config, quality presets, VideoInfo
├── data/SecurePrefs.kt        # Keystore-encrypted stream key
├── data/SettingsRepository.kt # UI settings persistence
├── data/VideoRepository.kt    # Shared loaded-video holder
├── media/InputSource.kt       # SAF zero-copy vs cache-copy source
├── media/MediaProbe.kt        # FFprobe metadata
├── media/VideoLoader.kt       # Resolve Uri → FFmpeg input + probe
├── ffmpeg/FFmpegCommandBuilder.kt  # Dynamic command generation
├── ffmpeg/FFmpegManager.kt    # Async session execution (suspend)
├── ffmpeg/ErrorClassifier.kt  # stderr → human readable errors
├── ffmpeg/LogStore.kt         # Sanitized ring buffer
├── stream/StreamService.kt    # Foreground service, reconnect loop, watchdog
├── stream/MicMixer.kt         # AudioRecord → FIFO → amix
├── stream/StreamUiState.kt
└── util/                      # Sanitizer, network, device caps, formatting
```

The streaming engine lives entirely in `StreamService` + `ffmpeg/*` — it is not coupled
to any Activity, so the stream survives Activity destruction and backgrounding.

**FFmpeg dependency:** FFmpegKit `full-gpl` 6.0-2 (GPL variant — bundles `libx264`
for H.264 plus TLS for RTMPS). The official `com.arthenica` artifacts were removed from
Maven Central in April 2025, so Gradle fetches a pinned community rebuild of that exact
package from a GitHub release and **verifies it by SHA-256 before building**
(`app/build.gradle.kts` → `downloadFfmpegKitAar`). This build ships **all 4 ABIs
(armeabi-v7a, arm64-v8a, x86, x86_64)** — the universal APK installs on both 32-bit and
64-bit phones. Requires Android 7.0 (API 24) or newer.

## Build

Requires JDK 17 and Android SDK 35 (the CI workflow sets these up automatically).

```bash
./gradlew :app:assembleDebug :app:assembleRelease
# Debug APK:   app/build/outputs/apk/debug/app-debug.apk
# Release APK: app/build/outputs/apk/release/app-release.apk (signed when
#              RELEASE_KEYSTORE_FILE etc. env vars are provided, as in CI)
```

The **Build APK** GitHub Actions workflow (`.github/workflows/build-apk.yml`) runs on every
push, builds **both** debug and signed release APKs, verifies that the FFmpeg native
libraries are actually packaged for all 4 ABIs (arm64-v8a, armeabi-v7a, x86, x86_64),
and publishes both APKs to the repository's **Releases** page (`apk-latest` tag).

## FFmpeg runtime verification

The app never assumes FFmpeg exists just because the library is bundled. `FFmpegRuntime`
detects the device ABI, inventories the native `.so` files packaged inside the installed
APK, loads the engine, and runs a real `ffmpeg -version` execution test — all logged to
the sanitized Advanced Logs screen. Architecture mismatches report
"FFmpeg is not available for this device architecture."; runtime failures report
"Streaming engine initialization failed." with the real reason in the logs.

## Testing checklist

1. Select MP4 → Start Live → YouTube receives the stream.
2. Video reaches the end → loops automatically (single FFmpeg session, watch log: no new session at the loop point).
3. Loop happens without RTMP reconnect (log shows continuous `time=` growth).
4. Mic OFF → video audio continues normally.
5. Mic ON → mic is mixed with the video audio (FFmpeg `amix`).
6. Screen locked → stream continues (foreground service + WakeLock).
7. App backgrounded → stream continues.
8. Airplane-mode toggle → "Network problem…" then "Reconnecting n/5…" and recovery.
9. Stop Live → confirmation → FFmpeg, mixer, WakeLock and service shut down cleanly.
10. Long-run a 30–60 min session; watch FPS/bitrate/speed on the LIVE screen and Advanced Logs.

## Security notes

- Stream keys are stored encrypted on-device and are only ever sent to the RTMP server.
- Every log line is masked (`rtmp(s)://host/<hidden-key>`), including FFmpeg's own stderr.
- No analytics, no crash upload, no network calls except the RTMP stream itself.
