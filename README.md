# Perimeter Alarm 📍🔔

Android app that **triggers an alarm when you enter a perimeter** defined around a location you pick on an OpenStreetMap map.

- **Language / UI**: Kotlin + Jetpack Compose (Material 3)
- **Map**: [osmdroid](https://osmdroid.org/) (OpenStreetMap tiles, no API key required)
- **Persistence**: SharedPreferences + Gson (no database)
- **minSdk** 26 (Android 8.0) · **targetSdk** 34 (Android 14)

📄 Also available in French: [README.fr.md](README.fr.md)

---

## Features

### Home screen
- List of programmed alarms.
- For each alarm: an **edit button** ✏️ and an **enable toggle** (switch).
- A green dot indicates an alarm that is **active and within its validity period** right now.
- **Distance to the perimeter entry** shown live for alarms that are active and in their validity period ("X m to entry" or "inside zone").
- A **"+" button** at the bottom right to add an alarm, and a **🐞 Debug** button in the title bar.

### Editing an alarm
An alarm consists of:

1. **Location** — chosen on the OpenStreetMap map:
   - tap the map to move the marker,
   - **"My location"** button to center on your current position,
   - **"Recenter"** button to fit the zoom on the circle.

2. **Perimeter** (circle) — three elements **synchronized in real time**:
   - the **visual circle** around the location on the map,
   - a **slider** to grow/shrink the radius (10 m → 5 km),
   - a **text field** to type an exact value in meters.
   - Changing any of the three instantly updates the other two.

3. **Validity period**:
   - **checkboxes** for each **day of the week** (Mon → Sun),
   - a **start time** and an **end time** (time pickers, handles periods crossing midnight),
   - or a simple **"Always on"** toggle (24/7).

4. **Ringtone & vibration** (specific to the alarm):
   - "use default settings" toggle (otherwise custom settings),
   - **vibration** (on/off), **volume** (0–100 % slider), **ringtone** (choose from the device's alarm tones, or the system default).

### Dynamic check logic
When an alarm is **enabled** and **within its validity period**, the position is checked at dynamic intervals:

- **every 30 s** at first,
- then the interval is computed from the **distance** between you and the location and your **approach speed**:

  ```
  estimated time of arrival = distance / approach speed
  next interval             = estimated time of arrival / 2
  ```

  clamped between a **minimum** and a **maximum** (defaults: 30 s and 5 min):
  - if you are approaching fast → more frequent checks (down to the minimum, 30 s);
  - if you are not approaching → sparser checks (up to the maximum, 5 min).

- **Triggering**: when the distance becomes ≤ the radius, an alarm (high-priority notification + sound + vibration) is raised. A **15 % hysteresis** prevents re-triggering while you stay inside the zone.

### Battery optimization (targeted sleeping)
When no alarm is active and within its period, the service **sleeps until the next period start** (computed with the day-of-week + times, up to 8 days ahead) instead of polling every 30 s. Saving an alarm wakes the service immediately. Note: for sleeps of several days, Android Doze may delay the wake-up slightly (WorkManager/AlarmManager would be the next step).

### Settings
The **Settings** page lets you configure:
- the **minimum interval** (seconds, default 30),
- the **maximum interval** (seconds, default 300 = 5 min),
- the **language**: *Auto* (follows the device language), *English* or *Français*,
- the **default alarm**: vibration, volume and ringtone (applied to alarms that have no custom settings).

### Debug page
Accessible via the 🐞 button on the home screen. It **logs the state of all alarms every 30 seconds**, but only while the page is visible; each line starts with the **date/time** and reports: active, within the validity period, distance to the perimeter entry, approach speed, and the time (or delay) of the **next refresh** of the service. A countdown shows the time before the next update.

---

## Architecture

```
app/src/main/java/fr/rsgnl/perimetre/
├── MainActivity.kt                  # Single Compose activity + permissions + routing
├── PerimetreApp.kt                  # Application: applies the chosen language at startup
├── data/
│   ├── Alarm.kt                     # Alarm model + SoundSettings + AppSettings + AppLanguage
│   └── AlarmRepository.kt           # SharedPreferences + Gson persistence
├── ui/
│   ├── AppViewModel.kt              # State (alarms, settings, navigation, language)
│   ├── HomeScreen.kt                # List + toggle + edit + FAB
│   ├── EditorScreen.kt              # Map + perimeter + period + sound
│   ├── SettingsScreen.kt            # Intervals + language + default alarm
│   ├── DebugScreen.kt               # State logging (30 s, only while visible)
│   ├── Strings.kt                   # Localized day names helper
│   ├── theme/Theme.kt               # Material 3 theme
│   └── components/
│       ├── OsmMap.kt                # osmdroid map (marker, circle, tap)
│       └── Widgets.kt               # Location watcher, day selector, sound editor
├── service/
│   ├── LocationMonitorService.kt    # Foreground service: monitoring + alarm
│   └── BootReceiver.kt              # Restart after device reboot
└── util/
    ├── Geo.kt                       # Haversine (distance)
    ├── TimeUtils.kt                 # Validity period + next period start + formatting
    ├── LocationUtils.kt             # Last known location
    ├── Format.kt                    # Distance formatting
    └── RingtoneUtils.kt             # Device alarm tone listing
```

### Monitoring service
`LocationMonitorService` is a **foreground service** (type `location`) that:
1. requests a **single GPS fix** at each check (GPS only on ~5-15 s per cycle → minimal battery),
2. for each active and in-period alarm, keeps a **state** (last distance, speed, next interval, triggered state),
3. schedules the next check with the dynamic formula above,
4. triggers the alarm when entering the perimeter.

> **Battery**: the GPS is NOT continuously locked. When you are far and stationary (next check in 5 min), the GPS only wakes up for ~15 s to take a fix, then powers off. When you approach, checks become more frequent and the GPS is active more often — exactly when you need it.

The service starts automatically when at least one alarm is enabled (including after a device reboot, via `BootReceiver`), and stops when no alarm is active.

---

## Build & install

### A) With Android Studio (recommended)
1. Open the `PerimetreAlarm` folder in Android Studio.
2. Let the Gradle sync finish.
3. Press **Run ▶** (or `./gradlew installDebug`).

### B) From the command line
Prerequisites: JDK 17+ and the Android SDK (platform 34, build-tools 34.0.0).

```bash
# set the SDK path if needed
echo "sdk.dir=/path/to/the/android-sdk" > local.properties

# build the debug APK
./gradlew :app:assembleDebug

# produced APK:
# app/build/outputs/apk/debug/app-debug.apk

# or install directly on a connected device (adb)
./gradlew :app:installDebug
```

> The debug APK is signed with the debug key: enough for testing,
> but you'll need to configure a release keystore to publish.

### Permissions requested
- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` — location
- `POST_NOTIFICATIONS` (Android 13+) — alarm notifications
- `FOREGROUND_SERVICE(_LOCATION)` — monitoring service
- `VIBRATE`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `INTERNET`

---

## Notes
- **No API key**: tiles come from `tile.openstreetmap.org` (osmdroid attribution). For heavy use, consider respecting the OSM tile usage policy or hooking up your own tile server.
- **Battery** is spared thanks to the dynamic intervals (no continuous polling), the targeted sleeping until the next period, and the foreground service.
- The **map** always shows your **current position** (blue dot, refreshed) relative to the alarm's location and perimeter; the initial zoom frames the circle (on the current position for a new alarm, or on the configured point when editing).
- **Languages**: English (default) and French. "Auto" follows the device language; a specific language can be forced from Settings.
- To go **release**: create a keystore, then `./gradlew :app:bundleRelease` or `assembleRelease`.
