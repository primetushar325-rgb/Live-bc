# LIVE VIP — Background streaming & battery guide

## What the app already does (code, verified by build)
- Streaming runs in an Android **foreground service** (`dataSync` type) that
  is completely independent of any Activity/screen.
- **Partial WakeLock** with a 12-hour hard cap, renewed by a ticker — no
  endless/unbounded wake locks, no aggressive tricks.
- Persistent notification with chronometer + **Stop** action; a truthful
  terminal notification replaces it when the session ends.
- `onTaskRemoved` handled; process death is never faked as LIVE.
- targetSdk 34 on purpose: Android 15 (target 35) imposes a 6-hour limit on
  dataSync foreground services, which would kill 7–10 hour streams.

## What YOU must do on the phone (Android/OEM restrictions)
Android and phone makers can still kill background work. For 7–10 hour
streams do all of these once:

1. **Battery optimization → Unrestricted**
   Settings → Apps → Live VIP → Battery → choose **Unrestricted**
   (the app also asks for this on first launch — tap the banner).
2. **Disable adaptive battery for this app** if your phone has it.
3. **OEM autostart/background allowances** (names vary by brand):
   - Xiaomi/Redmi/POCO: Settings → Apps → Manage apps → Live VIP →
     **Autostart ON**; Security app → Battery saver → No restrictions.
   - Samsung: Settings → Battery → Background usage limits → remove Live VIP
     from "Deep sleeping apps"; add to "Never sleeping apps".
   - OnePlus/Oppo/Realme: Settings → Battery → App battery usage → Live VIP →
     **Allow background activity**; enable Autostart.
   - Huawei/Honor: Settings → Battery → App launch → Live VIP → Manage
     manually → keep all three switches ON.
4. **Keep the phone cool.** Thermal throttling reduces encode speed; the app
   warns after 3 minutes above the warning temperature and stops safely at
   the critical temperature. Remove thick cases while streaming; avoid
   charging + heavy use if the phone gets hot.
5. **Keep upload headroom.** The stream needs sustained upload above the
   configured bitrate (e.g. >3.5 Mbps for 720p/3500k). Prefer 5 GHz Wi-Fi or
   strong 4G/5G; airplane-mode toggles will end the broadcast.

## Honest limits
- No app can guarantee uninterrupted background streaming on every device;
  some OEM power managers kill foreground services regardless of settings.
- YouTube broadcast status cannot be confirmed by the app without authorized
  YouTube API access — the dashboard shows **YouTube broadcast: UNVERIFIED**
  until you verify in the YouTube Live Control Room.
