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
            if distToEntry <= 100 m (PROXIMITY_FAST_ZONE_M) → min interval (fast trigger near the boundary)
            elif speed <= 0.05 m/s → max interval
            else → (distToEntry / speed) / 2, clamped to [min, max]
        Check triggering:
            if distance <= radius AND not already triggered → trigger alarm
            if distance > radius * 1.15 AND was triggered → stop alarm (hysteresis 15%)

    Sleep until the earliest next-check time across all alarms
}
```

### Doze-Resistant Wake (long sleeps)

When the loop must sleep for a long time (no active alarm in period — hours/days until the next period start), a plain coroutine delay can be delayed if the device enters Android **Doze**. To make these wakes reliable:

- If the computed sleep exceeds **10 minutes** (`LONG_SLEEP_THRESHOLD_MS`), the service schedules a broadcast alarm with **`AlarmManager.setAlarmClock`** (API 23+): exact, **fires during Doze**, and shows a **clock icon in the status bar** while pending. **Caveat:** on Android 12+ (targeting SDK 31+) `setAlarmClock` requires the `SCHEDULE_EXACT_ALARM` permission or it throws `SecurityException`. This permission is **denied by default for apps targeting SDK 33+** (compat change `SCHEDULE_EXACT_ALARM_DENIED_BY_DEFAULT`, `@EnabledSince(TIRAMISU)`) — the user must explicitly grant it in the "Alarms & reminders" settings screen (older targets get it auto-granted at install). The service therefore checks `canScheduleExactAlarms()` before calling and wraps the call in try/catch: without the permission it degrades to a plain inexact coroutine sleep. The **Settings screen** has a card with the permission status + a request button (`Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`), and the Debug screen shows it too.
- A static receiver (`WakeReceiver`, manifest-registered for `ACTION_WAKE`) handles the broadcast:
  - service still running → `requestWake()` interrupts the loop's sleep via the wake `Channel`;
  - process killed → restarts the service if at least one alarm is enabled (same logic as the boot receiver).
- The loop re-evaluates after every wake: if it must still sleep, it reschedules on the recomputed target (self-correcting if a wake was delayed).
- The pending alarm is cancelled when active monitoring resumes, when the service is destroyed, and on service start (a `PendingIntent` survives process death).

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
- **Period**: day-of-week checkboxes (Mon–Sun), start/end time pickers, "Always on" toggle. Hidden when one-shot is active.
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

- `SCHEDULE_EXACT_ALARM` is **not** a runtime permission: it is declared in the manifest and managed via the system "Alarms & reminders" screen. On Android 12+ it is auto-granted at install for apps targeting 31–32, but **denied by default for apps targeting 33+** (we target 34) — the user must grant it explicitly (the Settings screen has a status card + request button for this). It is required by `AlarmManager.setAlarmClock` (Doze-resistant wake) — the service checks `canScheduleExactAlarms()` before calling and degrades gracefully without it.

Requested at runtime on first launch via `ActivityResultContracts.RequestMultiplePermissions` (runtime ones only).

---

## 9. Key Utility Classes

- **`Geo`**: Haversine distance (meters) between two lat/lon points.
- **`TimeUtils`**: `isWithinPeriod()` (handles midnight-crossing), `nextPeriodStart()` (scans 8 days ahead), `formatHourMinute()`, `formatDays()`.
- **`AlarmSoundPlayer`**: manages `MediaPlayer` (per-alarm, looping, volume, routed to `USAGE_ALARM`) and `Vibrator` (per-alarm, waveform pattern). Indexes by alarm ID for individual stop.
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

3. **Hysteresis**: 15% margin on the radius prevents rapid re-triggering when the user oscillates near the boundary.

4. **Midnight-crossing periods**: e.g., 22:00 → 06:00 works because the check is `current >= start || current <= end` when `start > end`.

5. **Service wake**: `AlarmRepository.saveAlarms()` calls `LocationMonitorService.requestWake()` which sends a signal on a `Channel`, waking the monitor loop's `withTimeoutOrNull` sleep immediately.

6. **Gson null safety**: Fields added after initial release (e.g., `language`, `sound`) may be `null` in old saved JSON. The repository normalizes them on load.

7. **osmdroid cache**: Tile cache goes to internal storage (`filesDir/osmdroid`) to avoid runtime storage permissions.

8. **First check**: The first check for each alarm always uses the minimum interval (30s by default) since there's no speed estimate yet.

9. **Doze mode**: Long sleeps (hours/days, no active alarm) are backed by an `AlarmManager.setAlarmClock` broadcast alarm — exact, fires during Doze, status-bar clock icon. On API 31+ / targetSdk 31+ this needs `SCHEDULE_EXACT_ALARM` (manifest-declared; **denied by default for targetSdk 33+** → the user grants it via the Settings-screen card or the "Alarms & reminders" system screen); the service guards with `canScheduleExactAlarms()` + try/catch and degrades to an inexact coroutine sleep when absent (a missing permission must never crash the app). `WakeReceiver` wakes the loop via `requestWake()`, or restarts the service if the process was killed. Only applies when the computed sleep exceeds 10 minutes.

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
