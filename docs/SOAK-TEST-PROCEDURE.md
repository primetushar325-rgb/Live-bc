# LIVE VIP — Staged Soak Test Procedure (run on a real phone)

Goal: reach a **verified** 6–7 hour uninterrupted YouTube Live session.
This sandbox has **no Android device**, so every stage below must be run by you.
Do NOT call the target "passed" unless the checklist items are actually observed.

## Before every stage
1. Install the latest `VideoLive-release.apk` from the `apk-latest` release (uninstall the previous build first).
2. Fully charged phone or on charger; screen can be OFF (the app holds wake-locks and a foreground service).
3. Good 4G/Wi-Fi. Open YouTube Studio → Go Live and keep the **Control Room** page visible.
4. In the app: Settings → Diagnostics → copy the DIAG lines after each stage.

## Stage A — 10 minutes (sanity)
- Start stream, let it run 10 min.
- Checklist:
  - [ ] App status: **Streaming**; health shows **Healthy**.
  - [ ] Encoder line shows which encoder is actually in use (software x264 or hardware MediaCodec).
  - [ ] YouTube Control Room shows LIVE with data arriving.
  - [ ] After stop: no crash, notification clears.

## Stage B — 30 minutes (stability)
- Checklist from Stage A, plus:
  - [ ] Speed stays ~1.00x, encoded FPS matches target.
  - [ ] No reconnect events in the log.
  - [ ] Phone only mildly warm.

## Stage C — 90 minutes (the earlier failure window)
Your previous streams died between 30 s and 1 h 30 m. This stage targets that window.
- Checklist:
  - [ ] Stream still delivering at the 90-minute mark.
  - [ ] If the app shows **Performance warning**, note it — the app now stops honestly instead of looping restarts.
  - [ ] YouTube Control Room never shows "Stream ended" while the app is running.

## Stage D — 3 hours (endurance)
- Checklist:
  - [ ] No restarts/reconnects in Diagnostics.
  - [ ] Health stays **Healthy** (or warning noted with cause).
  - [ ] Loop boundary passes with no gap if video loops.
  - [ ] Screen off for the whole time.

## Stage E — 6–7 hours (target)
- Checklist:
  - [ ] Uninterrupted delivery for the full duration.
  - [ ] Reconnect count = 0.
  - [ ] YouTube still shows LIVE at the end; the app shows **Streaming** the whole time.

## What to report back
- Encoder line shown by the app (exact text).
- Health states seen (Healthy / Performance warning / Transport stalled).
- Diagnostics: longest run seconds, reconnect count, memory, stall events.
- Screenshot of YouTube Control Room at ~1 h and at the end.

## Important honesty note
Until Stage E passes on-device with that evidence, the 6–7 h target is an
**engineering goal, not a verified result** — per your instruction, nothing here
is claimed as tested.
