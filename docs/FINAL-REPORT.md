# FINAL REPORT — LIVE VIP "FINAL LIVE VIP APK" round

Branch `arena/623b9178-live-bc` • last green commit `c5babdd` • tag `v4.0-working`
APK: https://github.com/primetushar325-rgb/Live-bc/releases/download/apk-latest/VideoLive-release.apk
(Release `apk-latest`, file `VideoLive-release.apk`, universal 4-ABI.)

---

## 1. Why YouTube stopped receiving while the app still showed LIVE

**Root cause (from your own device logs):** the software encoder (libx264)
could not sustain the chosen 720p30 vertical encode. Your logs show
`speed=0.6-0.8x` and encoded `fps=18-23` against a 30 fps target.

Chain of failure:
1. Encoder falls behind → frames queue up.
2. FFmpeg's RTMPS output writes start blocking → upload to YouTube
   effectively stops or crawls.
3. YouTube ingest declares the stream dead (ingest timeout) and ends it —
   from 30 s up to ~1 h 30 m in, matching your reports.
4. The app still looked "LIVE" because the old watchdog only reacted to
   **zero** progress. Slow-but-continuing progress never triggered it, and
   nothing ever verified that YouTube was still receiving.

There was no crash, no loop bug, no timestamp jump: it was ingest starvation
from an overloaded software encode. This was stated before as a hypothesis;
with the speed/fps evidence it is the confirmed explanation.

## 2. What changed in this final round (engine untouched)

- **Hardware encoder path (AUTO default).** FFmpeg's `-encoders` list is
  probed once; `h264_mediacodec` is used only when proven present
  (your device's list confirms it is packaged). Settings → Encoder offers
  Auto / Hardware / Software.
- **Verification, not assumption.** The encoder actually in use is parsed
  from FFmpeg's own output mapping line and shown on the Live screen +
  diagnostics. Detecting availability alone is never treated as proof.
- **Automatic fallback.** If the hardware encoder fails or stalls within the
  first 20 s, the session restarts once on tested libx264. Encoder choice,
  probe result and fallback events are all logged.
- **Honest overload behavior.** If the device is overloaded and software was
  already in use, the app now **fails with a clear recommendation** (drop to
  480p / lower fps) instead of restart-looping the same doomed config.
- **Transport-stall signal.** If encoded frames advance but output bytes stop
  growing, the network write is blocked → health shows **Transport stalled**
  well before the watchdog, and the event is logged.
- **Health monitor.** Live screen + diagnostics show:
  Healthy / Performance warning / Transport stalled / Recovering / Failed /
  Stopped, plus the verified encoder name. Reconnect counters and longest-run
  seconds already existed.
- **Appearance (from the Master Upgrade prompt).** Settings now has a theme
  mode picker (System default / Light / Graphite) and 6 accent colors.
  Choices persist and changing them NEVER restarts or touches the stream.

## 3. Actual encoder used

Cannot be answered from this sandbox (no device). What IS guaranteed:
- The app logs `Encoder preference: … | hardware H.264 present: true/false`.
- It logs which encoder was selected before each attempt.
- The Live screen shows the **verified** encoder parsed from FFmpeg output.
Report the on-screen "Encoder" line back and that closes the loop.

## 4. Before / after numbers

| Measure | Before (your logs) | After |
|---|---|---|
| Speed | 0.6–0.8x | **Not measured here** — expect ~1.0x if the HW encoder carries 720p30; verify via the Diagnostics page |
| Encoded FPS | 18–23 of 30 | Same: verify on device |
| Failure mode | Silent YouTube timeout | Warns / fails honestly with cause |

## 5. 6–7 hour soak

**Not performed.** There is no Android device or emulator in this environment;
a real 6–7 h RTMPS soak cannot be run here and is not claimed.
A staged procedure (10 min → 30 min → 90 min → 3 h → 6–7 h) with checklists
is in `docs/SOAK-TEST-PROCEDURE.md`. The 90-minute stage directly targets
your previous failure window.

## 6. Verified vs not verified

- **Verified:** everything compiles, assembles, signs and dexes in GitHub
  Actions at `c5babdd`; APK published to `apk-latest`.
- **NOT verified (needs your phone):** hardware encoder behavior on your
  chipset, real RTMPS to YouTube, thermal behavior, reconnects on real
  network drops, theme/accent rendering, any soak duration.

## 7. Deferred from the Master Upgrade prompt (documented, not silently dropped)

- Library grid projects / scenes, custom overlay widgets, YouTube analytics
  integration — deferred by your own instruction ("if you cannot finish all
  features, document them and build the working app first").
- Everything already shipped (streaming engine, mic mixing, SAF cache,
  playlists, destinations, diagnostics, themes, overlays) is intact.

## 8. Rollback points

- `baseline-v3.0-working` (97dafc9) — last known-good before this round.
- `v4.0-working` (c5babdd) — this round, CI green.
If a regression appears on your phone, reinstall from the older tag's release
or ask to revert.
