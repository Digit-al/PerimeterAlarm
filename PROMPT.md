# Perimeter Alarm — Reconstruction Prompt

> This prompt describes everything needed to recreate the **Perimeter Alarm** Android app from scratch.
> Give it to a capable AI coding assistant (or a developer) along with access to an empty project folder.

---

## 1. Overview

Build an Android app that **triggers an alarm (sound + vibration + notification) when the user enters a circular perimeter** around a location they have defined. Multiple alarms can be defined, each with its own location, radius, validity period (days + time range, always-on, or **one-time**), and sound settings. One-time alarms are manually activated and automatically deactivate after the first trigger.

The app must work reliably in the background with minimal battery consumption.

---

## 2. Tech Stack

| Layer | Choice |
|-------|--------|
| Language | Kotlin |
| UI | Jetpack Compose (Material 3) |
| Map | osmdroid 6.1.20 (OpenStreetMap tiles, no API key) |
| Persistence | SharedPreferences + Gson (no Room) |
| Coroutines | kotlinx-coroutines 1.8.x |
| minSdk | 26 (Android 8.0) |
| targetSdk / compileSdk | 34 (Android 14) |
| Build | Gradle + AGP 8.x, JDK 17+ |
| Compose BOM | 2024.06.00 |
| Kotlin | 1.9.x, compiler extension 1.5.14 |

Dependencies:
- `androidx.core:core-ktx:1.13.1`
- `androidx.appcompat:appcompat:1.7.0`
- `androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4`
- `androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4`
- `androidx.lifecycle:lifecycle-runtime-ktx:2.8.4`
- `androidx.activity:activity-compose:1.8.1`
- Compose BOM (ui, ui-graphics, ui-util, material3, material-icons-extended)
- `org.osmdroid:osmdroid-android:6.1.20`
- `com.google.code.gson:gson:2.10.1`
- `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1`

---

## 3. Data Model

```kotlin
data class SoundSettings(
    var useDefault: Boolean = true,   // use app-level default sound
    var useVibration: Boolean = true,
    var volume: Float = 1.0f,         // 0..1
    var ringtoneUri: String? = null   // null = system default alarm tone
)

data class Alarm(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var latitude: Double = 48.8566,
    var longitude: Double = 2.3522,
    var radiusMeters: Int = 200,
    var oneShot: Boolean = false,       // "Ponctuelle": manual activation, auto-deactivates after 1st trigger
    var alwaysOn: Boolean = false,
    var daysOfWeek: Set<Int> = (1..7).toSet(),  // ISO: 1=Mon..7=Sun
    var startHour: Int = 8,
    var startMinute: Int = 0,
    var endHour: Int = 20,
    var endMinute: Int = 0,
    var enabled: Boolean = true,
    var retriggerable: Boolean? = true,   // nullable: Gson bypasses constructors → null for old saves; normalized to true in loadAlarms()
    var sound: SoundSettings = SoundSettings()
)

data class AppSettings(
    var minIntervalSeconds: Int = 30,
    var maxIntervalSeconds: Int = 300,
    var defaultSound: SoundSettings = SoundSettings(useDefault = false),
    var language: String = "auto"  // "auto" | "en" | "fr"
)

data class ConfigExport(
    val version: Int = 1,
    val settings: AppSettings,
    val alarms: List<Alarm>
)
```

Persistence: `AlarmRepository` serializes `List<Alarm>` and `AppSettings` as JSON strings in SharedPreferences. Every save triggers `LocationMonitorService.requestWake()` to notify the monitoring loop.

Export/Import: `ConfigExport` wraps settings + all alarms in a versioned JSON structure for backup/restore via file I/O (see Settings Screen).

---

## 4. Monitoring Service (Core Logic)

`LocationMonitorService` is a **foreground service** (type `FOREGROUND_SERVICE_TYPE_LOCATION`).

### GPS Strategy: Single Fix Per Check

To minimize battery usage, the service does **NOT** keep GPS continuously locked. Instead:

1. On each check cycle, request a **single location fix** via `LocationManager.requestLocationUpdates(provider, 0, 0f, listener, mainLooper)`.
2. The fix is obtained within a 15-second timeout (use `suspendCancellableCoroutine` + `withTimeoutOrNull`).
3. GPS tries `GPS_PROVIDER` first, falls back to `NETWORK_PROVIDER`.
4. If no fix is obtained in time, fall back to the last known location (`AtomicReference<Location?>`).
5. After the fix, remove the listener → GPS powers off.

This means: if the next check is 5 minutes away, GPS is only on for ~5-15 seconds during that 5-minute window. Duty cycle ≈ 5%.

### Monitor Loop (suspend, on `Dispatchers.Default`)

```
while (true) {
    Load alarms + settings
    Determine activeInPeriod (enabled AND within validity)

    if (activeInPeriod.isEmpty()) {
        Sleep until the next period start of any enabled alarm
        (or 15 min fallback if none found, capped at 8 days)
        Wake early if requestWake() is signaled (Channel)
        continue
    }

    For each active alarm that is due for a check:
        Get a single GPS fix (shared across all alarms due this cycle)
        Compute distance (Haversine)
        Compute speed (delta distance / delta time, from previous check)
        Update tracker state
        Compute next interval dynamically:
            distToEntry = max(0, distance_to_center - radius)  # distance to the ENTRY point, NOT the center
            cap = distToEntry / REFERENCE_SPEED_MPS (15 m/s, a car resuming in city traffic),
                  clamped to [min, max]
                  # distance-based cap: even STOPPED (traffic jam, chat, ...) the wait
                  # must not exceed the time to cover the remaining distance at the
                  # reference resume speed — the boundary could be crossed right
                  # after resuming. ≈30 s below 600 m, ≈1 min at 1 km, ≈2 min at
                  # 2 km, max beyond ~4.5 km.
            if distToEntry <= 100 m (PROXIMITY_FAST_ZONE_M) → min interval (fast trigger near the boundary)
            elif speed <= 0.05 m/s → cap
            else → (distToEntry / speed) / 2, clamped to [min, cap]
        Check triggering:
            if distance <= radius AND not already triggered → trigger alarm
            if distance > radius * 1.15 AND was triggered → stop alarm (hysteresis 15%)
                and if alarm.retriggerable → tracker.triggered = false (re-arms on re-entry);
                if retriggerable == false, keep triggered=true: the alarm rings ONCE per
                validity period (the tracker is dropped when the alarm leaves its period,
                so it re-arms automatically for the next period)
        While an alarm is ringing: soundPlayer.recheckOutput(alarmId) every cycle —
        if the connected output changed (headphones plugged/unplugged), the player
        is restarted on the appropriate stream (media vs alarm).

    Sleep until the earliest next-check time across all alarms
}
```

### Doze-Resistant Wake (long sleeps)

When the loop must sleep between periods (no active alarm in period — the next period start may be minutes away or hours/days away), a plain coroutine delay can be delayed if the device enters Android **Doze**, and the exact-alarm wake can be lost (e.g. the service is destroyed without the process dying). To make these wakes reliable:

- The service schedules a broadcast alarm with **`AlarmManager.setAlarmClock`** (API 23+) at the *exact* next period start — **for any sleep, not just long ones** (the old 10-minute floor left a gap where a period starting within 10 minutes relied on a plain coroutine delay). The alarm is exact, **fires during Doze**, and shows a **clock icon in the status bar** while pending. In addition, **each single sleep is capped at 2 hours** (`MAX_SINGLE_SLEEP_MS`): beyond that the loop wakes on a schedule, re-evaluates state, and re-arms the alarm — a delayed Doze wake (or a lost alarm) is thus caught at the next step and the exact alarm is refreshed. The wake is also **re-armed in `onDestroy`** so a killed service (whose process survives) does not orphan the sleep. **Caveat:** on Android 12+ (targeting SDK 31+) `setAlarmClock` requires the `SCHEDULE_EXACT_ALARM` permission or it throws `SecurityException`. This permission is **denied by default for apps targeting SDK 33+** (compat change `SCHEDULE_EXACT_ALARM_DENIED_BY_DEFAULT`, `@EnabledSince(TIRAMISU)`) — the user must explicitly grant it in the "Alarms & reminders" settings screen (older targets get it auto-granted at install). The service therefore checks `canScheduleExactAlarms()` before calling and wraps the call in try/catch: without the permission it degrades to the capped plain inexact sleep. The **Home screen** shows an automatic warning card (amber, dismissible for the session) whenever the permission is missing — one tap opens the system screen dedicated to our app (`Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM` with a `package:` data URI); the permission state is re-checked on every lifecycle resume so the card disappears as soon as it's granted. The **Settings screen** has the same status card + request button, and the Debug screen shows the status too.
- A static receiver (`WakeReceiver`, manifest-registered for `ACTION_WAKE`) handles the broadcast:
  - service still running → `requestWake()` interrupts the loop's sleep via the wake `Channel`;
  - process killed → restarts the service if at least one alarm is enabled (same logic as the boot receiver).
- The loop re-evaluates after every wake: if it must still sleep, it reschedules on the recomputed target (self-correcting if a wake was delayed).
- The pending alarm is cancelled when active monitoring resumes and on service start, and cancelled-then-re-armed when the service is destroyed (a `PendingIntent` survives process death).

### Tracker State (per alarm, in-memory only)

```kotlin
class Tracker {
    var lastCheckMs: Long = 0L
    var lastDistM: Double = Double.MAX_VALUE
    var lastSpeedMps: Double = 0.0
    var nextIntervalMs: Long = 0L
    var triggered: Boolean = false
}
```

### Alarm Triggering

When triggered:
- Show a **high-priority notification** (channel: alarm, `IMPORTANCE_HIGH`) with a "Stop" action.
- Play the ringtone via `MediaPlayer` (looping, volume from settings). Build the player manually and route it to the system alarm stream with `AudioAttributes` (`USAGE_ALARM`, `CONTENT_TYPE_SONIFICATION`) so the volume follows the alarm volume, not the media volume — `MediaPlayer.create()` routes to the media stream, avoid it.
- **Adaptive output routing** (so the alarm reaches connected headphones instead of the phone speaker): before playback, check `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` — if an **external output** is connected (wired/USB/BLE/Bluetooth A2DP headset, dock, …), play on the **media stream** (`USAGE_MEDIA`), which is always mixed to the active output; otherwise play on the **alarm stream** (`USAGE_ALARM`) (independent of media volume, audible in silent mode). Rationale: on real devices the alarm stream can stay pinned to the speaker even with audio focus held (verified on device). While ≥1 alarm is playing, request **audio focus** on the active stream (`AudioFocusRequest` built with `AUDIOFOCUS_GAIN_TRANSIENT` + matching usage, `setAcceptsDelayedFocusGain(true)`) — one shared request, re-requested if the stream changes mid-playback, abandoned via `abandonAudioFocusRequest` when the last alarm stops. `GAIN_TRANSIENT` (not `GAIN`) so the other player receives `LOSS_TRANSIENT` and **resumes automatically** when the focus is released (music resumes after the alarm). While an alarm is ringing, the service calls `soundPlayer.recheckOutput(alarmId)` on every monitoring cycle: if the connected output changed (headphones plugged/unplugged), the player is restarted on the appropriate stream.
- **Media volume boost while playing through headphones**: while ≥1 alarm plays on the external output (media stream) **and** the focus is actually held, the **system media volume is raised to its max** (`setStreamVolume(STREAM_MUSIC, getStreamMaxVolume(STREAM_MUSIC), 0)` — note the constant is `STREAM_MUSIC` = 3 in this stub, there is no `STREAM_MEDIA`) so the alarm slider corresponds exactly to the perceived level; the original volume (`getStreamVolume`) is saved once and restored when the last external alarm stops. **Ordering matters**: the boost is applied only **after** the focus is really granted (immediately if `requestAudioFocus` returns `AUDIOFOCUS_REQUEST_GRANTED`, otherwise on the `onAudioFocusChange(AUDIOFOCUS_GAIN)` callback when it returned DELAYED) — so the music that was playing is never made louder; the restore happens **before** the focus is released — so the music resumes at its original volume with no audible drop. On focus LOSS (e.g. incoming call): keep the alarm playing (it must ring no matter what) but restore the original media volume. Note: in this SDK stub, `AudioFocusRequest.Builder` has NO `AudioAttributes` constructor — use `Builder(int gain)` + `setAudioAttributes(...)`; and `requestAudioFocus(AudioFocusRequest)` (1-arg) is the available overload. `AudioManager.setPreferredDevice` is NOT available in this API surface.
- Vibrate via `VibratorManager` (pattern: 0-600ms on-400ms off, repeating).
- The sound/ringtone URI comes from the alarm's `SoundSettings`, or falls back to the app's `defaultSound`.

### Notification Channels

- `perimetre_monitor` — `IMPORTANCE_LOW`, shown while service is running.
- `perimetre_alarm` — `IMPORTANCE_HIGH`, alarm triggered.

### Boot Receiver

A `BOOT_COMPLETED` receiver restarts the service if at least one alarm is enabled.

---

## 5. UI Screens

### Navigation

Single-activity app with a `sealed class Screen` (Home, Editor, Settings, Debug). Navigation state is in the `AppViewModel` as a `StateFlow<Screen>`.

### Home Screen
- `LazyColumn` of alarms.
- Each row: green/gray dot (active + in-period), name, coordinates + radius, period label (includes "Ponctuelle" when one-shot), **distance to entry** (live, for active alarms), edit button, enable switch.
- FAB: add new alarm.
- Top bar: debug button (🐞), settings button (⚙️).
- Uses `observeCurrentLocation` (lifecycle-aware: only when screen ≥ STARTED AND at least one alarm is active + in-period) to display live distances.

### Editor Screen
- Name field.
- **Map** (osmdroid, 280dp height): marker at alarm location, blue polygon circle for radius, blue dot for current position. Tap to move location. "My location" and "Recenter" buttons.
- **Perimeter**: slider (10m–5km) + text field for exact radius, synced in real time.
- **One-time (Ponctuelle)** toggle: when active, shows a simple "Manually activate" message instead of days/times. Alarm auto-deactivates after first trigger.
- **Period**: day-of-week checkboxes (Mon–Sun), start/end time pickers, "Always on" toggle, and a **"Can ring several times per period"** toggle (`retriggerable`, default on): when off, the alarm rings only once per validity period (no re-arm on exit/re-entry). Hidden when one-shot is active.
- **Sound**: use-default toggle, vibration toggle, volume slider, ringtone picker (system `ACTION_RINGTONE_PICKER`), reset button (× to revert to system default).
  - When `useDefault=true`, the ringtone button displays "Défaut app : <ringtone title>" (the app-level default ringtone that will actually be played).
- **Enable** toggle.
- Save / Delete buttons in bottom bar.
- `TimePickerDialog` for start/end times.

### Settings Screen
- Min interval (seconds), Max interval (seconds).
- Language selector: Auto / English / Français.
- Default sound settings (vibration, volume, ringtone).
- **Backup & restore** section:
  - **Export button**: serializes `ConfigExport` (version, settings, alarms) to JSON, writes to a user-chosen file via `ActivityResultContracts.CreateDocument("application/json")` (SAF — no storage permission needed). Default filename: `perimetre_config.json`.
  - **Import button**: launches `ActivityResultContracts.OpenDocument()` (filter `application/json`), reads the JSON, deserializes `ConfigExport`, normalizes fields (same logic as repository load), then **replaces all** existing alarms and settings. Snackbar confirms success or reports invalid file.
  - The import uses `rememberCoroutineScope()` to handle I/O off the main thread, and a `mutableStateOf<String?>` + `LaunchedEffect` to display the snackbar (since the SAF callback is neither composable nor suspend).

### Debug Screen
- Polls `MonitorStatus.statuses` (a `StateFlow<Map<String, AlarmDebugStatus>>`) every 30 seconds.
- Logs timestamped lines: alarm name, enabled, in-period, distance to entry, speed, next check time.
- 1-second tick for the countdown display.
- Polling and ticking only run while the screen is in `STARTED` lifecycle state.
- Cap at 300 log lines. Clear button.

---

## 6. Map Component (osmdroid)

- `AndroidView` wrapping a `MapView` inside a **`FrameLayout`** (critical: osmdroid 6.1.20 `MapView` is a `ViewGroup` that overflows its bounds during zoom/pan; the `FrameLayout` with `clipChildren=true` and `clipToPadding=true` clips it).
- Tile source: `MAPNIK`.
- Multi-touch controls enabled.
- Zoom controller hidden.
- Overlays: `Marker` (alarm location), `Polygon` (circle, 64-point approximation via `Polygon.pointsAsCircle`), `Marker` with custom icon (current position blue dot).
- Tap detection: `setOnTouchListener` — if distance < 20dp and duration < 300ms, treat as a tap → callback with geo coordinates.
- Initial zoom: `zoomToBoundingBox` with 1.8× margin around the circle.
- `fitTrigger` parameter: incrementing it re-fits the map to the circle.
- osmdroid initialization: singleton, sets user agent and cache path to internal storage.

---

## 7. Internationalization (i18n)

- Languages: English (default) and French.
- All strings in `res/values/strings.xml` (English) and `res/values-fr/strings.xml` (French).
- `PerimetreApp` (custom `Application`) applies the chosen language at startup via `AppCompatDelegate.setApplicationLocales()`.
- "Auto" mode (default) follows the device language (empty locale list).
- Language can be changed from Settings; takes effect immediately (activity recreation).
- Day names are localized via `strings.xml` arrays.

---

## 8. Permissions

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.VIBRATE" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
```

- `SCHEDULE_EXACT_ALARM` is **not** a runtime permission: it is declared in the manifest and managed via the system "Alarms & reminders" screen. On Android 12+ it is auto-granted at install for apps targeting 31–32, but **denied by default for apps targeting 33+** (we target 34) — the user must grant it explicitly (an automatic warning card on the Home screen + a status card in Settings, both with a request button, make this a one-tap action). It is required by `AlarmManager.setAlarmClock` (Doze-resistant wake) — the service checks `canScheduleExactAlarms()` before calling and degrades gracefully without it.

Requested at runtime on first launch via `ActivityResultContracts.RequestMultiplePermissions` (runtime ones only).

---

## 9. Key Utility Classes

- **`Geo`**: Haversine distance (meters) between two lat/lon points.
- **`TimeUtils`**: `isWithinPeriod()` (handles midnight-crossing), `nextPeriodStart()` (scans 8 days ahead), `formatHourMinute()`, `formatDays()`.
- **`AlarmSoundPlayer`**: manages `MediaPlayer` (per-alarm, looping, volume, adaptive media/alarm stream routing + audio focus + `recheckOutput()` to follow output changes while ringing) and `Vibrator` (per-alarm, waveform pattern). Indexes by alarm ID for individual stop.
- **`LocationUtils`**: `lastKnownLocation()` — checks GPS, network, passive providers, returns the most recent.
- **`RingtoneUtils`**: `title(context, uri)` — returns the display name for a ringtone URI, or localized "Default (system)" if null. Also `hasMediaAudioPermission(context)` to check `READ_MEDIA_AUDIO` (API 33+) / `READ_EXTERNAL_STORAGE`.
- **`Format`**: distance formatting (m/km with appropriate precision).

---

## 10. Status Publishing (Service → UI)

`MonitorStatus` is a singleton holding a `MutableStateFlow<Map<String, AlarmDebugStatus>>`.

The service calls `publishStatuses(alarms)` after each check cycle. This updates:
- Distance to center and to entry (negative = inside)
- Speed estimate
- Next check timestamp
- Last check timestamp
- Triggered state

The Debug Screen and Home Screen observe this flow to display live data.

---

## 11. Project Structure

```
app/src/main/java/fr/rsgnl/perimetre/
├── MainActivity.kt
├── PerimetreApp.kt
├── data/
│   ├── Alarm.kt              # Alarm, SoundSettings, AppSettings, AppLanguage
│   ├── AlarmRepository.kt    # SharedPreferences + Gson
│   ├── ConfigExport.kt       # Versioned export/import structure
│   └── MonitorStatus.kt      # StateFlow publisher
├── ui/
│   ├── AppViewModel.kt       # State + navigation + service lifecycle
│   ├── HomeScreen.kt
│   ├── EditorScreen.kt
│   ├── SettingsScreen.kt
│   ├── DebugScreen.kt
│   ├── Strings.kt            # Localized day names
│   ├── theme/Theme.kt
│   └── components/
│       ├── OsmMap.kt         # osmdroid map component
│       └── Widgets.kt        # observeCurrentLocation, DaySelector, SoundSettingsEditor
├── service/
│   ├── LocationMonitorService.kt
│   ├── BootReceiver.kt
│   └── WakeReceiver.kt
└── util/
    ├── Geo.kt
    ├── TimeUtils.kt
    ├── LocationUtils.kt
    ├── AlarmSoundPlayer.kt
    ├── Format.kt
    └── RingtoneUtils.kt
```

---

## 12. Build Configuration

- `buildFeatures { compose = true }`
- `kotlinCompilerExtensionVersion = "1.5.14"`
- `packagingOptions { resources { excludes += ['META-INF/AL2.0', 'META-INF/LGPL2.1'] } }`
- `compileOptions`: Java 17 source/target
- `kotlinOptions`: jvmTarget 17

---

## 13. Edge Cases & Design Decisions

1. **Map overflow**: osmdroid 6.1.20's `MapView` is a `ViewGroup` whose internal scroll mechanism causes children to draw outside the view bounds during zoom. Fix: wrap in a `FrameLayout` with `clipChildren=true` + `clipToPadding=true`.

2. **Battery**: Single-fix-per-check (not continuous tracking) keeps GPS duty cycle proportional to check frequency. When far, checks are 5 min apart → GPS on only ~15s per 5 min.

3. **Hysteresis**: 15% margin on the radius prevents rapid re-triggering when the user oscillates near the boundary. On exit (distance > radius × 1.15) the sound always stops; `tracker.triggered` is cleared **only** if `alarm.retriggerable != false`, which is what controls whether the alarm can ring again within the same validity period (a one-shot alarm is simply disabled after its first trigger).

4. **Midnight-crossing periods**: e.g., 22:00 → 06:00 works because the check is `current >= start || current <= end` when `start > end`.

5. **Service wake**: `AlarmRepository.saveAlarms()` calls `LocationMonitorService.requestWake()` which sends a signal on a `Channel`, waking the monitor loop's `withTimeoutOrNull` sleep immediately.

6. **Gson null safety**: Fields added after initial release (e.g., `language`, `sound`, `retriggerable`) may be `null` in old saved JSON (Gson bypasses constructors). The repository normalizes them on load (`retriggerable` → `true` = original behavior).

7. **osmdroid cache**: Tile cache goes to internal storage (`filesDir/osmdroid`) to avoid runtime storage permissions.

8. **First check**: The first check for each alarm always uses the minimum interval (30s by default) since there's no speed estimate yet.

9. **Doze mode**: Long sleeps (hours/days, no active alarm) are backed by an `AlarmManager.setAlarmClock` broadcast alarm — exact, fires during Doze, status-bar clock icon. On API 31+ / targetSdk 31+ this needs `SCHEDULE_EXACT_ALARM` (manifest-declared; **denied by default for targetSdk 33+** → the user grants it via the automatic Home-screen warning card / the Settings-screen card, both opening the system screen dedicated to the app); the service guards with `canScheduleExactAlarms()` + try/catch and degrades to an inexact coroutine sleep when absent (a missing permission must never crash the app). `WakeReceiver` wakes the loop via `requestWake()`, or restarts the service if the process was killed. Armed for **any** between-periods sleep at the exact next period start, and each single sleep is capped at **2 h** so a delayed/lost wake is caught and re-armed at the next step.

10. **Foreground service type**: `FOREGROUND_SERVICE_TYPE_LOCATION` required on Android 10+ to declare location usage and show the system location indicator.

11. **One-shot alarms**: `oneShot=true` makes the alarm always "in period" (no day/time filter). After the first trigger, `enabled` is set to `false` and a `SharedFlow<String>` (`oneShotFired`) is emitted so the UI can show a snackbar and update the list in real-time.

12. **Ringtone picker**: uses `RingtoneManager.ACTION_RINGTONE_PICKER` (system intent) instead of a custom MediaStore list. On Android 14+/17, system ringtones live in a dedicated provider not exposed via `MediaStore.Audio.Media`. The system intent handles all versions + no permission required. `READ_MEDIA_AUDIO` is still in the manifest as a safety net.

13. **StateFlow + data classes**: when mutating an `Alarm` (e.g., toggling enabled), always use `copy()` to produce a new reference. In-place mutation won't trigger StateFlow emission because `equals()` sees the same reference.

14. **App default ringtone display**: in the alarm editor, when `useDefault=true`, the ringtone button shows "Défaut app : <title>" (the actual app-level ringtone name) instead of "Défaut (système)". This requires passing `appSettings.defaultSound.ringtoneUri` to the `SoundSettingsEditor` component.

15. **Config export/import (SAF)**: uses Storage Access Framework (`CreateDocument` / `OpenDocument`) instead of direct file I/O — no runtime permission needed, works on scoped storage (Android 10+). Import is a **full replace** (not merge): all alarms and settings from the file overwrite the current state. The JSON is versioned (`"version": 1`) for forward compatibility. The import normalizes fields exactly like `AlarmRepository.loadAlarms()` / `loadSettings()` to handle missing fields gracefully.

16. **Snackbar in non-composable callback**: the `rememberLauncherForActivityResult` callback is neither `@Composable` nor `suspend`, so `stringResource()` and `showSnackbar()` cannot be called directly. Solution: use `rememberCoroutineScope()` to launch a coroutine, store the message in a `mutableStateOf<String?>`, and display it via a `LaunchedEffect` that calls `snackbarHostState.showSnackbar()`.

---

## 14. Acceptance Criteria

- [ ] App builds successfully (debug APK)
- [ ] Can add an alarm by tapping the map, setting radius and period
- [ ] Entering the perimeter triggers: notification + sound + vibration
- [ ] Leaving the perimeter (with 15% margin) stops the alarm
- [ ] Multiple alarms work independently
- [ ] "Always on" mode works
- [ ] Day-of-week + time range filtering works (including midnight crossing)
- [ ] Dynamic interval: faster checks when approaching, slower when stationary/far
- [ ] Battery: GPS only active briefly for each check (not continuously)
- [ ] Service restarts after device reboot (if alarms enabled)
- [ ] Settings: min/max interval, language, default sound all functional
- [ ] Debug screen shows live state every 30s
- [ ] Map: tap to move, recenter, "my location", zoom without overflow
- [ ] i18n: all strings in EN + FR, language switchable from settings
- [ ] Export: produces a valid JSON file containing all settings and alarms
- [ ] Import: restores settings and alarms from a previously exported file
- [ ] Import of invalid/corrupt file shows error snackbar without crashing
