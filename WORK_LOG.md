# Work Log — Perimeter Alarm

> Journal de travail pour reprendre le contexte. Mis à jour à chaque session significative.
> L'agent (HAL) lit ce fichier en premier quand il repart sur un context exhausted.

---

## Current State (last updated: 2025-07-18)

**Branch**: `main`  
**Last commit**: `50e4f55` — feat: add ringtone reset button (back to system default)  
**Build**: ✅ assembles successfully (debug APK)  
**User testing**: In progress — user validating one-shot alarms + ringtone picker on Android 17 (Pixel).

### What's done
- [x] Core app (Compose UI, 4 screens: Home/Editor/Settings/Debug)
- [x] osmdroid map with marker, circle, tap, fit-to-radius
- [x] Dynamic interval logic (speed-based, min/max clamped)
- [x] Hysteresis triggering (15% margin)
- [x] Per-alarm sound settings (ringtone, volume, vibration)
- [x] App-level default sound settings
- [x] Validity periods (days + time range, midnight crossing, always-on)
- [x] **One-shot (ponctuelle) alarms** — manually activated, auto-deactivates after first trigger
- [x] SharedPreferences + Gson persistence
- [x] Foreground service with targeted sleep until next period
- [x] Boot receiver
- [x] i18n FR/EN with configurable language
- [x] Debug screen (30s polling, lifecycle-aware)
- [x] Map overflow fix (FrameLayout clipping wrapper)
- [x] **Single GPS fix per check** (battery optimization)
- [x] HomeScreen GPS lifecycle-aware (only when screen visible + alarm active)
- [x] **System ringtone picker** (ACTION_RINGTONE_PICKER — works on all Android versions)
- [x] Ringtone reset button (× to revert to system default)
- [x] App default ringtone title shown in alarm editor

### What's pending / next
- [ ] User feedback on one-shot alarm behavior
- [ ] Potential: release build configuration (keystore, signing)
- [ ] Potential: WorkManager/AlarmManager for more reliable wake in Doze
- [ ] Potential: Tile server configuration (OSM usage policy for heavy use)
- [ ] Potential: ProGuard rules for release
- [ ] Potential: notification with "dismiss" action already tested?

---

## Session Log

### Session 2025-07-18 (ringtone picker + one-shot)

**Context**: User testing on Pixel / Android 17. Two features requested:
1. One-shot alarm type
2. Ringtone picker not showing system ringtones

**One-shot implementation**:
- `Alarm.kt`: added `oneShot: Boolean` field
- `MonitorStatus.kt`: added `oneShotFired: SharedFlow<String>` (service → UI notification)
- `LocationMonitorService.kt`: `isAlarmInPeriod()` treats one-shot as always in-period; `deactivateOneShot()` after trigger (saves + notifies)
- `AppViewModel.kt`: collects `oneShotFired` to update alarm list in real-time
- `EditorScreen.kt`: "Ponctuelle" toggle above period section, hides days/hours when active
- `HomeScreen.kt`: shows "Ponctuelle" label, inPeriod logic adapted

**Ringtone picker evolution** (multiple attempts on Android 17):
1. `32bb608` — Added `READ_MEDIA_AUDIO` permission (Android 13+) / `READ_EXTERNAL_STORAGE` (< 13)
2. `83289ec` — Added permission prompt UI in picker when list is empty + logging
3. `bf74511` — Fallback: unfiltered MediaStore query (all audio files) when filtered query returns empty
4. `300ba4f` — **Final solution**: replaced custom MediaStore picker with system `ACTION_RINGTONE_PICKER` intent. On Pixel/Android 17, system ringtones are in a dedicated provider ("Sons du Pixel", collections) NOT exposed via `MediaStore.Audio.Media`.
5. `adc46c8` — Show app default ringtone title in alarm editor when `useDefault=true`
6. `50e4f55` — Added "×" reset button to revert ringtone to system default

**Commits this session**: `93d3f30`, `f0cf17b`, `dfda7f9`, `32bb608`, `83289ec`, `bf74511`, `300ba4f`, `adc46c8`, `50e4f55`

---

### Session 2025-07-17 (HomeScreen GPS lifecycle + toggle fix)

**Context**: User reported the blue location dot STILL blinking (10s on/off) even with no active alarm, after the single-fix refactor.

**Diagnosis**: The `HomeScreen` had `observeCurrentLocation(enabled = true, intervalMs = 10_000)` which was ALWAYS active regardless of alarm state.

**Fix 1** (`93d3f30`): Changed to lifecycle-aware — GPS in HomeScreen only activates when screen is ≥ STARTED AND at least one alarm is enabled + in period.

**Fix 2** (`f0cf17b`): Toggle switch was not reactive because the `Alarm` object was mutated in-place (StateFlow equality check failed). Fixed by using `copy()` to create a new object reference.

---

### Session 2025-07-17 (single-fix refactor)

**Context**: User reported the blue location dot blinking on/off in a 10s cycle even when next check was 5 min away.

**Decision**: Replace continuous GPS tracking with **single fix per check**.

**Changes**:
- `LocationMonitorService.kt`: `requestSingleFix()` using `suspendCancellableCoroutine` + 15s timeout. Monitor loop requests one fix per check cycle (shared across all alarms due simultaneously).

**Commits**: `55016c1`, `c08e7b6`

**Key architectural notes**:
- `requestSingleFix()` iterates providers (GPS first, then network), `minTime=0, minDistance=0` for fastest fix.
- `invokeOnCancellation` removes listener if 15s timeout fires.
- `latestLocation` is `AtomicReference<Location?>` seeded with `getLastKnownLocation()`.

---

### Session 2025-07-17 (earlier)

**Context**: Map overflow during zoom.

**Fix**: `c08e7b6` — FrameLayout wrapper with `clipChildren=true` + `clipToPadding=true` around osmdroid MapView.

---

### Session 2025-07-16 (i18n + debug)

Full i18n (FR/EN), language selector, Debug screen. Commit: `c259b1d`

---

### Session 2025-07-15 (initial build)

Full app from scratch. Commit: `b6cd9c4`

---

## Technical Decisions Log

| Date | Decision | Rationale |
|------|----------|-----------|
| 07-15 | SharedPreferences + Gson (not Room) | Simple data model, no queries needed |
| 07-15 | Foreground service (not WorkManager) | Real-time monitoring loop needed |
| 07-15 | osmdroid (not Google Maps) | No API key, free tiles |
| 07-15 | Haversine for distance | Good enough for <10km perimeters |
| 07-15 | 15% hysteresis on trigger | Prevents rapid re-triggering |
| 07-17 | FrameLayout wrapper for MapView | osmdroid ViewGroup overflow bug |
| 07-17 | Single fix per check (not continuous) | Battery: GPS on 5-15s per cycle vs 100% duty |
| 07-17 | 15s fix timeout | Balance between cold GPS acquisition and responsiveness |
| 07-17 | `suspendCancellableCoroutine` for fix | Clean structured concurrency + auto cleanup |
| 07-17 | `copy()` for StateFlow mutations | Data class equality requires new reference for StateFlow to emit |
| 07-18 | One-shot via `SharedFlow` (not callback) | Decouples service from UI; multiple collectors possible |
| 07-18 | System `ACTION_RINGTONE_PICKER` (not custom list) | On Android 14+/17, system ringtones are in a dedicated provider not in MediaStore. The system intent handles ALL versions + no permission needed. |
| 07-18 | `READ_MEDIA_AUDIO` still in manifest | May be needed in future if we add a custom picker again; harmless otherwise |

---

## Build Environment

- **JDK**: `/home/user/toolchain/jdk-21` (set `JAVA_HOME`)
- **Android SDK**: configured in `local.properties`
- **Build command**: `JAVA_HOME=/home/user/toolchain/jdk-21 bash ./gradlew :app:assembleDebug`
- **APK output**: `app/build/outputs/apk/debug/app-debug.apk`
- **Git remote**: `github.com:Digit-al/PerimeterAlarm.git` (SSH, key: `~/.ssh/id_ed25519_perimeter_alarm`)
- **Push command**: `GIT_SSH_COMMAND="ssh -i /home/user/.ssh/id_ed25519_perimeter_alarm -o IdentitiesOnly=yes" git push origin main`

---

## How to Resume (for the AI agent)

1. **Read this file** (`WORK_LOG.md`) to understand current state and pending items.
2. **Read `PROMPT.md`** for the full app specification.
3. **Check `git log --oneline -10`** to see recent commits.
4. **Build** to verify the current state compiles.
5. **Check with the user** what they want to tackle next (or look at "What's pending" above).
6. When making changes: build → commit → push → update this log.

---

## File Map (quick reference)

| File | Purpose |
|------|---------|
| `PROMPT.md` | Full spec to recreate the app from scratch |
| `WORK_LOG.md` | This file — session journal + decisions |
| `README.md` / `README.fr.md` | User-facing documentation (EN/FR) |
| `app/build.gradle` | Dependencies + build config |
| `service/LocationMonitorService.kt` | Core monitoring + alarm logic |
| `ui/components/OsmMap.kt` | Map composable (osmdroid) |
| `ui/components/Widgets.kt` | Reusable UI (RingtonePicker, SoundSettingsEditor, observeCurrentLocation) |
| `data/Alarm.kt` | Data models (Alarm, SoundSettings, AppSettings) |
| `data/AlarmRepository.kt` | Persistence (SharedPreferences + Gson) |
| `data/MonitorStatus.kt` | Service → UI status + oneShotFired flow |
| `util/Geo.kt` | Haversine |
| `util/TimeUtils.kt` | Period logic |
| `util/AlarmSoundPlayer.kt` | Sound + vibration |
| `util/RingtoneUtils.kt` | Ringtone helpers (title, permission check) |
