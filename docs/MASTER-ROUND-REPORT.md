# REPORT — "FINAL MASTER ENGINEERING PROMPT" round

Branch `arena/623b9178-live-bc` • green commits `d6e832e` (drift monitor)
and `4d92e8d` (projects) • checkpoints `v4.0-working`, `v5.0-projects`
APK (new): https://github.com/primetushar325-rgb/Live-bc/releases/download/apk-latest/VideoLive-release.apk
(VideoLive-release.apk, 65,420,889 bytes, universal 4-ABI)

---

## 1. The ~1 h 8 m lag — evidence-based diagnosis

Pipeline audited end to end before any change (Phase 1 honored: engine not
rewritten, checkpoint tags kept).

Eliminated with evidence:
- **Timestamps/loops:** ONE FFmpeg session, `-stream_loop -1` inside it, RTMP
  output opened once and never reopened → output PTS/DTS stay monotonic, no
  boundary reset, no duplicate decoders/encoders/sessions. int64 µs
  timestamps cannot wrap in hours. CFR `fps` filter removes VFR pacing jitter.
- **Unbounded app-side growth:** LogStore is a 600-line ring; cache is bounded
  and reused; MicMixer releases its AudioRecord on stop.
- **Memory/thermal:** thermal guard (warn/crit) already present and logged.

Most defensible mechanism for "fine at first, laggy after ~1 h":
**encode or transport running slightly under real time.** With `-re` pacing,
any sustained speed < 1.0x makes the output fall progressively behind wall
clock; the deficit is invisible for tens of minutes, then YouTube-side
buffering/stutter appears. Thermal throttling of a software encode after
20–60 min makes this worse over time. Your earlier logs (speed 0.6–0.8x,
18–23 fps of 30) prove this device's software encoder cannot hold 720p30 —
the same mechanism at a milder degree explains a later (~68 min) onset.

What this round adds:
- **Cumulative latency-drift monitor** — every stats tick measures
  `wall-clock elapsed − FFmpeg out_time`. Warns at 15 s drift; at 45 s posts
  an on-screen warning with the honest fix (stop, lower quality/FPS,
  restart). Drift appears in every periodic DIAG line.
- **Hardware encoder path (AUTO)** — the real long-term cure: MediaCodec
  fixed-function encoding does not throttle like a CPU encode. Actual encoder
  is verified from FFmpeg's own output, with automatic libx264 fallback on
  early failure.
- **Transport-stall + overload honesty** (previous stage) — frames advancing
  with frozen bytes ⇒ "Transport stalled"; sustained overload fails with a
  recommendation instead of restart-looping.

I cannot reproduce the 68-minute failure in this sandbox (no device, no
RTMPS endpoint). The instrumentation above is exactly what will catch it
on-device: run Stage C (90 min) of `SOAK-TEST-PROCEDURE.md` and read the
DIAG `drift=` values.

## 2. Features implemented this round

- Persistent **Stream Projects** home screen (now the launcher):
  create / open / rename / duplicate / delete (with confirmation).
  Each project durably stores video, playlist, destination, quality, fps,
  bitrate, volumes, mic, framing, encoder preference, and last-session
  status. Keys remain in EncryptedSharedPreferences — one secret per
  project, never in project files or logs.
- **Single-active-stream policy:** attempting a second session shows a
  dialog routing to the live dashboard; deleting/switching projects never
  stops a running stream; the service records Stopped/Failed/Ended + time
  back into the project.
- Per-project video/playlist isolation (each project remembers its own
  source via persisted SAF URI).

## 3. Already verified by code audit (no change needed)

- Screen-off/locked streaming: foreground service (dataSync type), partial
  wake-lock with 12 h renewals, notification with Stop/Open actions,
  `onTaskRemoved` handled, STOP_FOREGROUND_DETACH terminal notification.
- Mic OFF never touches video audio (independent command path).
- No duplicate sessions: service-level `isStreaming` guard + UI guard.

## 4. NOT verified / honest limitations

- **No device here**: the 68-minute failure was not reproduced; the drift
  monitor is the instrument to confirm the fix on your phone.
- Longest verified duration = CI build only. 30 min / 2 h / 6–7 h soaks:
  **not performed** — procedure in `SOAK-TEST-PROCEDURE.md`.
- Premium UI: themes + accents + project cards are in; extended animation
  passes and full icon-set replacement are not done this round and are
  documented as remaining (deliberately deferred behind streaming
  reliability, per your priority order).
- YouTube broadcast state is still not visible to the app (no fake statuses).

## 5. Rollback points

`baseline-v3.0-working` → `v4.0-working` → `v5.0-projects`. Any of these can
be re-released instantly if a regression appears on your phone.
