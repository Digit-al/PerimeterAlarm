# Work Log — Perimeter Alarm

> Journal de travail pour reprendre le contexte. Mis à jour à chaque session significative.
> L'agent (HAL) lit ce fichier en premier quand il repart sur un context exhausted.

---

## Current State (last updated: 2025-07-17)

**Branch**: `main`  
**Last commit**: `8f57ef6` — fix: disable HomeScreen GPS when no alarm is active in period  
**Build**: ✅ assembles successfully (debug APK)  
**User testing**: In progress — user validating that the blue location dot no longer blinks when no alarm is active.

### What's done
- [x] Core app (Compose UI, 4 screens: Home/Editor/Settings/Debug)
- [x] osmdroid map with marker, circle, tap, fit-to-radius
- [x] Dynamic interval logic (speed-based, min/max clamped)
- [x] Hysteresis triggering (15% margin)
- [x] Per-alarm sound settings (ringtone, volume, vibration)
- [x] App-level default sound settings
- [x] Validity periods (days + time range, midnight crossing, always-on)
- [x] SharedPreferences + Gson persistence
- [x] Foreground service with targeted sleep until next period
- [x] Boot receiver
- [x] i18n FR/EN with configurable language
- [x] Debug screen (30s polling, lifecycle-aware)
- [x] Map overflow fix (FrameLayout clipping wrapper)
- [x] **Single GPS fix per check** (battery optimization — replaces continuous tracking)

### What's pending / next
- [ ] User feedback on single-fix GPS behavior (accuracy, timing)
- [ ] Potential: release build configuration (keystore, signing)
- [ ] Potential: WorkManager/AlarmManager for more reliable wake in Doze
- [ ] Potential: Tile server configuration (OSM usage policy for heavy use)
- [ ] Potential: ProGuard rules for release
- [ ] README update (mention single-fix strategy instead of "updates every 5s")

---

## Session Log

### Session 2025-07-17 (latest)

**Context**: User reported the blue location dot STILL blinking (10s on/off) even with no active alarm, after the single-fix refactor.

**Diagnosis**: The `HomeScreen` had `observeCurrentLocation(enabled = true, intervalMs = 10_000)` which was ALWAYS active regardless of alarm state. This independent location request (separate from the service) was the true cause of the 10s blink pattern.

**Fix**: Changed to `enabled = anyActiveInPeriod` — GPS in the HomeScreen only activates when at least one alarm is both enabled AND within its validity period.

**Commit**: `8f57ef6`

---

### Session 2025-07-17 (single-fix refactor)

**Context**: User reported the blue location dot blinking on/off in a 10s cycle even when next check was 5 min away. Discussed battery implications.

**Decision**: Replace continuous GPS tracking with **single fix per check**.

**Changes**:
- `LocationMonitorService.kt`: Removed `startTracking()`/`stopTracking()` and the shared `locationListener`. Added `requestSingleFix()` using `suspendCancellableCoroutine` + `withContext(Dispatchers.Main)` + 15s timeout. The monitor loop now requests one fix per check cycle (shared across all alarms due simultaneously). Falls back to `latestLocation` (last known) if no fix in time.
- GPS now only on for ~5-15s per check instead of continuously.

**Commits this session**:
- `55016c1` — refactor: single GPS fix per check (battery optimization)
- `d8c9a14` — fix: GPS indicator blinking (reduce MIN_UPDATE_MS to 1s) [superseded by 55016c1]
- `c08e7b6` — fix: map overflow on zoom (osmdroid 6.1.20 ViewGroup clipping)

**Key architectural notes for future sessions**:
- The `requestSingleFix()` function iterates providers (GPS first, then network), uses `minTime=0, minDistance=0` for fastest possible fix.
- `invokeOnCancellation` removes the listener if the 15s timeout fires before a fix arrives.
- `latestLocation` is an `AtomicReference<Location?>` seeded with `getLastKnownLocation()` at service start.
- `publishStatuses()` is called after each check cycle (not on every location fix anymore).

---

### Session 2025-07-17 (earlier)

**Context**: User reported map overflowing its frame during zoom.

**Diagnosis**: osmdroid 6.1.20 `MapView` is a `ViewGroup` (not a simple `View`). Its internal scroll mechanism offsets children without clipping, causing content to draw outside the 280dp bounds during zoom/pan.

**Fix**: Wrap `MapView` in a `FrameLayout` with `clipChildren=true` + `clipToPadding=true`. The `AndroidView` factory now returns the `FrameLayout` wrapper; the `update` lambda gets the `MapView` from `mapRef` instead of the lambda parameter.

**Commit**: `c08e7b6`

---

### Session 2025-07-16 (i18n + debug)

**Changes**:
- Full i18n (FR/EN): all strings in `strings.xml` + `values-fr/strings.xml`
- Language selector in Settings (auto/EN/FR)
- `PerimetreApp` applies locale at startup
- Debug screen: 30s polling, lifecycle-aware (only while STARTED), 1s tick for countdown
- README bilingual (README.md + README.fr.md)

**Commit**: `c259b1d`

---

### Session 2025-07-15 (initial build)

**Changes**: Full app from scratch — all screens, service, persistence, map, sound, dynamic interval.

**Commit**: `b6cd9c4`

---

## Technical Decisions Log

| Date | Decision | Rationale |
|------|----------|-----------|
| 07-15 | SharedPreferences + Gson (not Room) | Simple data model, no queries needed, avoids migration headaches |
| 07-15 | Foreground service (not WorkManager) | Need real-time monitoring loop, WorkManager adds complexity for the dynamic interval |
| 07-15 | osmdroid (not Google Maps) | No API key, free tiles, offline cache support |
| 07-15 | Haversine for distance | Good enough for <10km perimeters, no OS dependency |
| 07-15 | 15% hysteresis on trigger | Prevents rapid re-triggering at the boundary |
| 07-17 | FrameLayout wrapper for MapView | osmdroid ViewGroup overflow bug on zoom |
| 07-17 | Single fix per check (not continuous) | Battery: GPS on 5-15s per 5min cycle vs 100% duty. Dynamic interval already handles detection granularity. |
| 07-17 | 15s fix timeout | Cold GPS acquisition can take up to 30s; 15s is a good balance. Fallback to last known location. |
| 07-17 | `suspendCancellableCoroutine` for fix | Clean structured concurrency, automatic cleanup on cancellation/timeout |

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
4. **Build** to verify the current state compiles: `JAVA_HOME=/home/user/toolchain/jdk-21 bash ./gradlew :app:assembleDebug`
5. **Check with the user** what they want to tackle next (or look at "What's pending" above).
6. When making changes: build → commit (with descriptive message) → push → update this log.

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
| `data/Alarm.kt` | Data models |
| `data/AlarmRepository.kt` | Persistence |
| `util/Geo.kt` | Haversine |
| `util/TimeUtils.kt` | Period logic |
| `util/AlarmSoundPlayer.kt` | Sound + vibration |
