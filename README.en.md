# ChargeHud — 充电悬浮窗

**English** | [中文](README.md)

A tiny Android overlay that cares about exactly one thing: charging. It pins **live charging power (W)** and
**battery temperature (°C)** (voltage and current are optional) on top of everything on screen, so you can see how
fast the battery is taking a charge and how hot the device is getting — without unplugging and opening a settings page.

> Download: ready-to-install apks live in **[Releases](https://github.com/xx9926/chargehud/releases/latest)**.

> Note: the in-app UI is currently Chinese-only. Every label below is given as `English (中文)` so you can find it.
> Translating the UI itself is on the roadmap.

- The overlay shows two rows by default (power / temperature). In **Appearance (外观)** you can tick any of
  power / temperature / voltage / current — **the order you tick them is the order the rows appear in**
  (untick and re-tick moves a row to the bottom). The panel grows when all four are on and hides completely
  when none are ticked
- Colour, font size, backdrop and position are all adjustable. The settings page has four collapsible sections —
  **Appearance (外观)**, **Position (位置)**, **Archive (档案)** and **More (更多)**; **More** nests three
  sub-sections: **Theme (主题)** (a photo or short video as app background), **Alerts (提醒)** (four charging
  alerts) and **Advanced (高级)** (data-source and background-behaviour switches)
- **More → Theme (更多 → 主题)**: pick a photo or a short video straight from your gallery as the app background,
  with a background colour, 0–100 opacity and 0–100 gaussian blur, previewed live while you drag. The background
  runs all the way under the status bar (edge-to-edge), and the status bar icons flip light/dark to match it
- Drag it anywhere with one finger; tap it to jump back to settings. **Position** has a lock switch that freezes
  the drag
- A Quick Settings tile **充电悬浮 (Charge overlay)** toggles the overlay with one tap, and its colour changes
  instantly to reflect the state
- A persistent notification shows live power and temperature with **Close overlay (关闭悬浮窗)** and
  **Lock / Unlock (锁定 / 解锁)** buttons. If you don't want the notification, turn it off under
  **More → Advanced** — at the cost of the background service being reclaimed more eagerly
- On boot, the previous on/off state and position are restored automatically
- **More → Advanced → Hide from recents (隐藏后台)**: keeps the settings page out of the recent-apps list, so
  swiping "clear all" can no longer take the overlay service down with it. Reopen settings from the overlay or the
  launcher icon (the switch takes effect the next time you enter settings)
- **Archive (档案)** logs every charging session: duration, start/end battery level, peak and average power,
  mAh taken in, temperature peak — plus a **power and battery-level over time** line chart. Everything stays on
  the device
- Four charging alerts — overheat / slow charge / trickle / fully charged — on their own notification channel:
  **as long as any one of them is on, they are evaluated whenever the charger is plugged in, overlay or not**
- No network access at all, no ads, no third-party SDKs. The only dependencies are androidx
  core / appcompat / material / activity

## Overlay gestures

| Action | Result |
| --- | --- |
| Drag the panel | Move the overlay (when it sits against the status bar, start the drag on the transparent grab strip below it) |
| Tap the panel | Open settings (works while locked too) |

Position can also be nudged 5 px at a time with the ▲▼◀▶ buttons in settings (hold to repeat), which is far more
precise than dragging by hand.

## Theme background

In **More → Theme (更多 → 主题)**, tapping **Background image / video (背景图/视频)** opens **the gallery directly**
(the system photo picker, photos and videos side by side). Whatever you pick is copied into the app's private
storage right away (`files/bg_image.jpg` / `files/bg_video.mp4`), so **deleting the original from your gallery
afterwards does not remove your background**. Videos larger than 60 MB are rejected with a hint to pick a shorter one.

- Photos are drawn center-crop; videos play muted on a loop through a `TextureView` (also center-cropped) and both
  share the same two sliders
- Background opacity 0–100: 0 = background colour only, 100 = untouched
- Background blur 0–100: on Android 12+ this uses the GPU `RenderEffect`, so only the background layer is blurred
  and the slider is smooth while you drag — text and numbers stay sharp. Older versions fall back to a
  scale-down/scale-up approximation
- The background applies to the settings page and the archive page only; the overlay itself is unaffected. Both
  pages extend under the status bar, whose icons turn white or black according to the background's luminance
- Video decoding only runs while a page is visible and is released as soon as you leave it. If the background is
  too busy, raise the blur and lower the opacity

## Archive and alerts

**Archive**: one plug-in-to-unplug cycle is one record. The recorder samples every 5 seconds and thins out
automatically (doubling the interval) past 500 points. Each record stores duration, start/end level, peak and
average power, temperature peak, and the charge taken in **integrated from current over time** (mAh, which is finer
than back-calculating from 1% steps). The archive page shows a two-axis line chart for the selected session
(X = minutes, left = W, right = %) with the history list underneath — tap any row to switch curves. Records live in
the app's private `files/charge_sessions.jsonl`, the last 60 only, wiped on uninstall. **Nothing ever leaves the phone.**

Recording and the overlay are **independent**: the `ACTION_POWER_CONNECTED` broadcast starts a foreground service
that exists only while charging, and it records whether or not the overlay is visible; it stops itself one minute
after you unplug. The price is one silent notification while charging (the `充电记录` channel, minimum priority) —
turn **Background recording while charging (充电时后台记录)** off under **More → Advanced** if you don't want it.
The in-progress session is checkpointed to `charge_session_current.json` every 30 seconds, so a process kill costs
at most 30 seconds: if the device is still charging on the next start it resumes the same session, otherwise it
archives it, and you never get a half-written record. Plugs shorter than 30 seconds or fewer than 3 samples are dropped.

The same background service also evaluates the four alerts: with any of them enabled it is started on plug-in.
That silent notification cannot be removed — measured: calling `stopForeground` does make it disappear, but the
process then gets frozen about a minute into screen-off, the 5-second sampling loop stops, and no alert ever fires.
So when recording is off and only alerts remain, the notification text switches to **Charging alerts monitoring
(充电提醒监控中)**. The only way to have no notification at all is to turn off both recording and all four alerts.

**Alerts** (under **More → Alerts**, all on a separate `充电提醒` notification channel):

| Alert | Trigger | Default |
| --- | --- | --- |
| Overheat (高温提醒) | Battery temperature ≥ threshold (off / 36 / 38 / 40 / 42 / 46 / 48 / 50 °C); it may fire again only after falling back to threshold −2 °C | 42 °C |
| Slow charge (慢充提醒) | 5 minutes after plug-in, average power over the last minute < threshold (off / 2 / 4 / 6 / 8 / 10 W); not evaluated above 80% battery | off |
| Trickle (涓流提醒) | Battery ≥ 80% and average power over the last minute < threshold (off / 2 / 3 / 5 W) — fast charging has effectively ended | 3 W |
| Fully charged (充满提醒) | The framework reports `BATTERY_STATUS_FULL`, or the level reaches 100% | on |

Each of the four fires **at most once per plug-in cycle** (unplug and re-plug resets it). The checks ride along in
the same 5-second sampling loop as the recorder, so **alerts work with the overlay switched off** — though all four
are only evaluated while charging, since the service exits a minute after you unplug.

## Where the readings come from

Third-party apps cannot read the real charging current, so this app degrades through sources in order of reliability:

1. **FRAMEWORK** — `BatteryManager.getIntProperty(BATTERY_PROPERTY_CURRENT_NOW)`, averaged over the last few
   seconds of **current magnitude**. The sign is not taken from the HAL (on this Dimensity device, USB pulse
   charging makes the instantaneous current alternate between +0.47 A and −0.47 A by itself); it is stamped from the
   framework's charging state instead: **plugged in / charging shows positive, unplugged shows negative (the
   battery is discharging)**. The sampling window is cleared at the moment of plug/unplug so a reading from the
   previous direction never lingers. The averaging window has a side effect: hardware current reaches full scale in
   the very first frame after plugging in a powerful charger, but the average is dragged down by the small
   pre-plug samples and power appears to climb for ~6 seconds. So when the magnitude jumps (new sample ≥ 2× the
   mean, rising delta ≥ 0.3 A / falling delta ≥ 0.5 A, and the sample itself ≥ 0.3 A) it is treated as a
   plug/unplug or charger swap and the window keeps only that sample — measured: 7.6 W in the first frame after
   plugging in, and back to the true discharge value 1.3 s after unplugging. Samples below 0.3 A are never trusted,
   which keeps pulse troughs from dropping the reading to near zero.
2. **SYSTEM_FILE** — `/sys/class/power_supply/**/current_now`. Most ROMs have closed this to normal apps; it must be
   enabled manually in settings and falls back automatically on failure.
3. **CHARGE_COUNTER** — derives current by differencing the coulomb counter (`BATTERY_PROPERTY_CHARGE_COUNTER`).
4. **ESTIMATED** — battery capacity × rate of change of the charge level. Trend only.

Voltage comes from `EXTRA_VOLTAGE` (mV) and temperature from `EXTRA_TEMPERATURE` (0.1 °C). The design capacity is
read by reflecting `PowerProfile.getAveragePower("battery.capacity")`, and can be entered by hand if that fails.

Verified on: Redmi Note 10 Pro (M2104K10AC / chopin, Android 13, HyperOS V14.0.8.0.TKPCNXM), 1080×2400, 440 dpi.

## Build

Requirements: JDK 17+ (tested on JDK 21), Android SDK platform 34, and the Gradle Wrapper in the repo.

```sh
sh ./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk

sh ./gradlew assembleRelease
# output: app/build/outputs/apk/release/app-release.apk — R8 obfuscation + optimisation, about 2.0 MB
```

Release builds run R8 (`proguard-android-optimize.txt` + `app/proguard-rules.pro`). The four manifest components are
kept automatically by AGP and the only reflection target is the framework class `PowerProfile`, so no extra keep
rules are needed. Release is currently signed with the debug keystore — otherwise the artefact would be unsigned and
impossible to install; switch to a real keystore by creating one and configuring `signingConfig`.

Add `--offline` when building without network access (dependencies must already be in the local Gradle cache).

## Install and first run

If you don't want to build it yourself, grab the apk from Releases (about 2.0 MB):

- All releases: <https://github.com/xx9926/chargehud/releases/latest>
- Current v2.0 direct link: <https://github.com/xx9926/chargehud/releases/download/v2.0/chargehud-v2.0.apk>
  (2,072,036 bytes, MD5 `8c5278e07042248705c25c78bcfc57e3`)

The apk is signed with a **debug keystore** (a personal debug signature). Copy it to the phone and open it to
install; installing over an older version keeps your settings and archive.

```sh
adb install -r -t chargehud-v2.0.apk
```

After that, just switch on **Show overlay (显示悬浮窗)**. On MIUI / HyperOS three more things matter:

- **Notifications are off by default** for this app: App info → Notifications → enable **Allow notifications**,
  otherwise the persistent notification never appears and its buttons are unreachable.
- **The tile has to be added by hand**: pull down the control centre → edit → tap **充电悬浮** under
  "not added yet".
- Grant **Display over other apps** and **Autostart**, and set battery saver to "no restrictions", or the
  background service will get reclaimed.

## Changelog

| Version | Date | Notes |
| --- | --- | --- |
| v2.0 | 2026-10-01 | **Theme** added (More → Theme): pick a photo or short video straight from the gallery as the app background, with background colour, 0–100 opacity and 0–100 gaussian blur, live preview while dragging; media is copied into private storage so deleting the gallery original changes nothing; videos play muted on a loop, on the settings and archive pages only; edge-to-edge under the status bar with luminance-aware icon colour; blur moved to the GPU `RenderEffect` (Android 12+) so dragging no longer drops frames; the background notification text now follows the recording switch |
| v1.9 | 2026-10-01 | Settings re-layered: **More** now contains collapsible **Alerts** and **Advanced** sub-sections; removed the "service running / not running" text line at the top of settings |
| v1.8 | 2026-10-01 | Archive recording and all four alerts decoupled from the overlay, handled by a service that exists only while charging; 30-second checkpoints so a process kill costs at most 30 s; new **Hide from recents** switch; battery capacity became automatic/manual (no slider, manual cap 20000 mAh, committed on leaving the page); project URL shown at the bottom of **More**, tap to copy |
| v1.7 | 2026-10-01 | Added **Archive**: one record per charging session (duration, start/end level, peak and average power, mAh integrated from current, temperature peak) with power and battery-level line charts, stored on-device only; added four charging alerts (overheat / slow charge / trickle / full) on their own channel, at most once per plug-in; the archive page gained iOS-style horizontal slide transitions and a drag-anywhere swipe-back |
| v1.6 | 2026-09-30 | Fixed power crawling up for ~6 s after plugging in: magnitude jumps now pass straight through the sampling window — measured 7.3 W in the first frame with a high-wattage charger, and back to the true discharge value 1.3 s after unplugging, while small pulse jitter is still smoothed |
| v1.5 | 2026-09-30 | Overlay row order now follows the order you ticked the fields; text colour moved below backdrop opacity and renamed "overlay opacity"; ▲▼◀▶ buttons got visible outlines |
| v1.4 | 2026-09-30 | Fixed the power sign flipping with plug state: direction now derives from the charging state (positive while charging, negative once unplugged) and the sampling window clears at the transition |
| v1.3 | 2026-09-30 | Selectable overlay rows (power / temperature / voltage / current); persistent notification can be turned off; settings sections became collapsible; lock switch moved into **Position** (long-press gesture dropped); fixed the tile needing a second pull-down to recolour; switch colours and dialog corner radii unified |
| v1.2 | 2026-09-29 | Fixed third-party tiles not appearing in the control centre: `TileService` permission must be `BIND_QUICK_SETTINGS_TILE` |
| v1.1 | 2026-09-29 | R8 enabled for release builds; apk shrank from 6.4 MB to 1.9 MB |
| v1.0 | 2026-09-29 | First usable version: overlay, tile, persistent notification, boot restore |

Full notes for every version are on the [Releases](https://github.com/xx9926/chargehud/releases) page.

## Source layout

```
app/src/main/java/com/chargehud/app/
├── MainActivity.kt        Settings page (collapsible sections; More holds Theme / Alerts / Advanced) + threshold pickers
│                          SettingsShown / SettingsHidden are its two launcher entries, differing only in excludeFromRecents
├── ThemeBackground.kt     Theme background: gallery photo/video copied into private storage, centerCrop drawing, GPU blur, edge-to-edge status bar
├── RecentsEntry.kt        "Hide from recents": enables one entry and disables the other
├── HudService.kt          Overlay foreground service: window, gestures, dragging, locking, refresh loop host
├── BatteryReader.kt       Reading sources and the fallback chain
├── ChargeLog.kt           Archive: session open/close, sampling thin-out, checkpoints, JSON Lines I/O
├── ChargeRecorderService.kt Foreground service that exists only while charging: records the archive and evaluates alerts
├── ChargingReceiver.kt    Plug-in broadcast; starts the background service
├── ArchiveActivity.kt     Archive page: history list + drag-to-swipe-back
├── ChargeChartView.kt     Custom view: two-axis power / battery-level line chart
├── Alerts.kt              The four alerts: evaluation and notifications
├── Prefs.kt               SharedPreferences wrapper
├── ColorPickerDialog.kt   SV square + hue bar colour picker dialog
├── HudTileService.kt      Quick Settings tile
└── BootReceiver.kt        Boot restore
```

## Notes

This project is an independent implementation; all code is written against public Android APIs.

## License

Copyright (C) 2026 xx9926

This program is free software: you can redistribute it and/or modify it under the terms of the
**GNU General Public License as published by the Free Software Foundation**, either version 3 of the
License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but **without any warranty**; without even
the implied warranty of merchantability or fitness for a particular purpose. See the full text in
[LICENSE](LICENSE) at the root of this repository.

Since binaries are distributed as apks under Releases, the corresponding complete source required by section 6
of the GPL lives in this same repository: each Release's tag (for example `v2.0`) is the exact source for that
binary, and the source archive for it is one click away on the tag.
