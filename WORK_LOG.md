# Work Log — Perimeter Alarm

> Journal de travail pour reprendre le contexte. Mis à jour à chaque session significative.
> L'agent (HAL) lit ce fichier en premier quand il repart sur un context exhausted.

---

## Current State (last updated: 2026-09-21)

**Branch**: `main`  
**Last commit**: `73ae18e` — fix: distance-based interval cap for stopped/slow users near the perimeter  
**Build**: ✅ assembles successfully (debug + release APK)  
**Release 1.0.0**: tag `1.0.0` = `a5c7200` (the SCHEDULE_EXACT_ALARM crash fix) — **outdated**: `7f40c5b` (interval fix + exact-alarm card) and the distance-cap fix are not in it. GitHub asset + F-Droid ref must be refreshed once validated on device.  
**User testing**: Doze crash validated fixed (2026-09-21). Issues found on device and fixed: (1) interval pinned at max near the perimeter → `7f40c5b` (ETA from entry point + 100 m fast zone); (2) SCHEDULE_EXACT_ALARM denied by default for targetSdk 33+ → `7f40c5b` (Settings card); (3) **stopped/slow user near the perimeter** (traffic jam, chat on foot) still got the 5-min max interval → new distance-based cap (interval ≤ time to cover the remaining distance at a 15 m/s reference resume speed; ≈30 s below 600 m, ≈1 min at 1 km, ≈2 min at 2 km). Awaiting on-device validation of the interval hotfixes + exact-alarm permission grant.

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
- [x] **Release build 1.0.0** — signing config, LGPL 3.0, tag `1.0.0`, F-Droid metadata (`fdroid/app.yml`) (`86c14b9`, `903d1b0`)
- [x] **Alarm volume fix** — sound routed to `USAGE_ALARM` stream; per-alarm slider no longer a % of the media volume (`18f2653`)
- [x] **Config export/import** — JSON file (SAF) containing all settings + alarms; buttons in Settings page
- [x] **Doze-resistant wake** — `AlarmManager.setAlarmClock` + `WakeReceiver` for long sleeps (hours/days)

### What's pending / next
- [ ] User validation of the alarm volume fix (alarm at full volume with low media volume)
- [ ] User validation of export/import on device
- [ ] User feedback on one-shot alarm behavior
- [x] User validation of Doze-resistant wake on device with the **hotfix APK** (no crash after importing settings outside active periods, app stays open during the long sleep)
- [x] Rebuild the GitHub release 1.0.0 APK with the hotfix — tag `1.0.0` moved to `a5c7200`, asset replaced, F-Droid ref updated (`5ad6089`)
- [ ] User validation of `7f40c5b` on device: alarm rings on zone entry (fast checks near the perimeter) + user grants the exact-alarm permission from the Settings card
- [ ] User validation of the morning alarm (Boulot, 07:00–08:15) triggering on time after the Doze wake (first full cycle test)
- [ ] Refresh GitHub release asset + tag `1.0.0` + F-Droid ref to `7f40c5b` once validated
- [ ] Push commits to `origin/main` after validation (see Build Environment)
- [ ] Potential: Tile server configuration (OSM usage policy for heavy use)
- [ ] Potential: ProGuard rules for release
- [ ] Potential: notification with "dismiss" action already tested?

---

## Session Log

### Session 2026-09-21 (distance-based interval cap — stopped user near the perimeter)

**Context**: David's report — "si je suis pris dans un bouchon juste avant l'entrée dans la zone (ou si je m'arrête pour discuter si je suis à pied), on repart sur 5 minutes d'attente à tord alors que je suis tout près."

**Analysis**: after the `7f40c5b` fix, the 100 m proximity fast zone covers "right at the boundary", but a user stopped (speed ≈ 0) a few hundred meters away fell into the `speed <= SPEED_EPS → maxMs` branch → 5-min wait while the jam could clear any second. Also: at low speeds the GPS speed estimate is noisy (0.2–0.5 m/s in a traffic jam), inflating the ETA and pinning the interval at the max even while technically "moving".

**Fix**: distance-based **cap** on the interval: `cap = distToEntry / REFERENCE_SPEED_MPS (15 m/s ≈ 54 km/h, a car resuming in city traffic)`, clamped to [min, max]. Both branches now respect it: stopped → `cap`; moving → `clamp(eta/2, min, cap)`. Result: ≈30 s below ~600 m (falls to the min), ≈40 s at 600 m, ≈1 min at 1 km, ≈2 min at 2 km, max beyond ~4.5 km (15 m/s × 300 s). Far-away behavior unchanged. `REFERENCE_SPEED_MPS` is a named constant (could become a setting later).

**Docs**: PROMPT.md (interval pseudocode), README.md + README.fr.md (dynamic check logic bullets).

**Next**: build, share hotfix APK, on-device validation (traffic-jam scenario at Metzange), then refresh GitHub release asset + tag `1.0.0` + F-Droid ref.

---

### Session 2026-09-21 (interval fix + exact-alarm permission card)

**Context**: On-device testing in the evening (17:48, near Metzange): "l'alarme n'a pas sonné à l'entrée dans la zone" — Debug showed `dist.entrée=60 m`, `vitesse=0.3 m/s`, `prochain=+264 s` (unreasonable so close to the perimeter). The Debug screen also showed `Alarme exacte : non accordée`.

**Bug 1 — interval pinned at max near the perimeter**: `computeInterval()` computed the ETA from the distance to the **center** of the circle. With a 200 m radius, ETA = (dist_to_entry + 200)/speed — overestimated by 200/0.3 = 666 s → interval clamped to the 300 s max even 60 m from the boundary. Repro from the screenshot: at the last check, dist to center ≈ 271 m → ETA ≈ 903 s → interval 300 s → next check 17:53:03 (+264 s after the snapshot). The alarm would have rung ~3 min after actual entry — "not on entry" from the user's point of view.
**Fix** (`7f40c5b`): ETA now uses the **distance to the entry point** `max(0, dist_to_center - radius)`; within **100 m** of the entry (`PROXIMITY_FAST_ZONE_M`) — or inside the perimeter — checks run at the **minimum** interval (fast trigger near the boundary + fast exit detection via hysteresis).

**Bug 2 — SCHEDULE_EXACT_ALARM "non accordée"**: verified in AOSP that the compat change `SCHEDULE_EXACT_ALARM_DENIED_BY_DEFAULT` is `@EnabledSince(targetSdkVersion = TIRAMISU)` — for apps targeting SDK 33+ (we target 34) the permission is **denied by default** even when declared; the user must allow it explicitly ("Alarms & reminders"). The crash guard degraded gracefully (no crash — the guard worked), but the Doze wake was inexact. (The "auto-granted at install" doc applies to older targets / is overridden by this change.)
**Fix** (`7f40c5b`): new card on the **Settings screen** — "Alarmes exactes (réveil Doze)" with the live permission status + a request button (`Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`). Debug-screen line already existed.

**Docs**: PROMPT.md (interval logic, permissions section, Doze sections, edge case #9), README.md + README.fr.md (dynamic check logic + Doze paragraph).

**Build**: ✅ debug + release BUILD SUCCESSFUL. Release APK verified: versionName 1.0.0, versionCode 1, signature David R. Hotfix APK shared in chat.

**Next**: user installs the hotfix, grants the exact-alarm permission from the Settings card, re-tests zone entry at Metzange. Then refresh the GitHub release asset + tag `1.0.0` + F-Droid ref to `7f40c5b`.

---

### Session 2026-09-21 (hotfix: SCHEDULE_EXACT_ALARM crash on Doze wake)

**Context**: User reported: "la dernière APK release se ferme systématiquement" (the previous one, with alarm volume + import/export, worked). Deep dive with the user: **fresh install works, crash as soon as the old settings are reimported** (dialog "Périmètre Alarme s'arrête systématiquement", no clock icon in the status bar).

**Root cause** (verified against AOSP `AlarmManagerService`, Android 17): `setAlarmClock` — used by the new Doze-resistant wake — **requires the `SCHEDULE_EXACT_ALARM` permission** for apps targeting SDK 31+ (we target 34). Without it, the system throws `SecurityException: Caller fr.rsgnl.perimetre needs to hold android.permission.SCHEDULE_EXACT_ALARM or android.permission.USE_EXACT_ALARM to set exact alarms.` Our manifest **did not declare it**, and the call ran in the monitor coroutine without a try/catch → unhandled exception → process death. The crash only manifested when the loop took the long-sleep branch (no alarm in its period, next start > 10 min away) — exactly the state after importing the user's config at 16:18 (Metzange 16:50–19:25 not started yet, Boulot 07:00–08:15 finished). A fresh install has no alarms → service never starts → no crash. The old APK (pre-Doze) never called `setAlarmClock` → worked with the same config.

**Fix**:
- `AndroidManifest.xml`: declares `android.permission.SCHEDULE_EXACT_ALARM` (not a runtime permission: on Android 12+ it is auto-granted at install when declared; user can revoke it via "Alarms & reminders").
- `LocationMonitorService.scheduleWakeAlarm()`: checks `canScheduleExactAlarms()` (API 31+) before calling, and wraps the whole call in try/catch → without the permission the app degrades gracefully to a plain inexact coroutine sleep (previous behavior). `Log` statements + `TAG` added. Fixed the wrong doc comment ("no extra permission").
- `DebugScreen`: new line showing the exact-alarm permission status (granted / not granted — tap to request via `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM` / not required pre-Android 12). Recomputed on every recomposition (1 s tick) so a revocation + return is reflected. New strings EN/FR.
- Docs: README.md, README.fr.md, PROMPT.md (permissions section + Doze section + edge case #9) corrected.

**Build**: ✅ `:app:assembleDebug` + `:app:assembleRelease` BUILD SUCCESSFUL (JDK 21). Release APK verified: `SCHEDULE_EXACT_ALARM` in merged manifest, signature `CN=David R, O=RSGNL, C=FR` (same keystore → installs over the old version). APK: `app/build/outputs/apk/release/PerimeterAlarm-release.apk` (11.6 MB, versionName 1.0.0, versionCode 1).

**Next**: user installs the hotfix APK (same signature → overwrite OK), reimports the config → should stay open. Then rebuild the GitHub release asset + move tag `1.0.0` if needed.

**Validation + release update (same day, evening)**: user confirmed the crash is gone (deactivated the afternoon alarm which was in its active period, kept the morning one — app stays open). Release 1.0.0 then finalized: tag `1.0.0` moved `2261c13` → `a5c7200`, GitHub asset replaced (new asset id 579278040), release notes updated, `fdroid/app.yml` → `a5c7200` (commit `5ad6089`).

---

### Session 2026-09-21 (release 1.0.0 APK update)

**Context**: User asked: "mets à jour l'APK dans le tag 1.0.0". The GitHub release 1.0.0 carried an APK built from the old tag commit `87873aa` — while the code had since evolved (alarm volume fix, config export/import, Doze-resistant wake). The release notes even advertised export/import, which the APK didn't contain.

**Actions**:
- `2261c13` — `versionName "1.0"` → `"1.0.0"` (consistency between the APK and the tag/release name). `versionCode` kept at 1 (in-place update of the same release; a direct install with the same signature overwrites fine).
- Tag `1.0.0` force-moved `87873aa` → `2261c13` and pushed.
- Release APK rebuilt (`:app:assembleRelease`) and verified: `versionName=1.0.0`, `versionCode=1`, signature OK (APK Signature Scheme v2, `CN=David R, O=RSGNL, C=FR`).
- GitHub release: old asset deleted, new APK uploaded (same download URL), release notes updated (added Doze-resistant wake + build info).
- `fdroid/app.yml`: fixed stale commit reference (`903d1b0` — no longer existed in the repo) → `2261c13` (tag 1.0.0).

**Build**: ✅ `:app:assembleRelease` BUILD SUCCESSFUL (JDK 21). APK: `app/build/outputs/apk/release/PerimeterAlarm-release.apk` (11.6 MB).

---

### Session 2026-09-21 (Doze-resistant wake for long sleeps)

**Context**: PROMPT.md edge case #9 said: "For very long sleeps (hours/days), Android Doze may delay the wake. Acceptable for now; WorkManager/AlarmManager would be the next evolution." User asked to implement this evolution.

**Implementation**:
- `service/WakeReceiver.kt` (new): static receiver (`ACTION_WAKE`). If the service is running → `requestWake()` (cuts the loop's sleep via the wake channel). Otherwise (process killed) → restarts the service if at least one alarm is enabled (same logic as BootReceiver).
- `LocationMonitorService.kt`:
  - `LONG_SLEEP_THRESHOLD_MS = 10 min` — below that, a plain coroutine delay is fine (standard Doze starts ~30 min after inactivity; "moderate Doze" can start earlier, so 10 min is a conservative margin).
  - `scheduleWakeAlarm(fireAtMs)`: `AlarmManager.setAlarmClock(AlarmClockInfo, pendingIntent)` — exact, fires while in Doze, clock icon in the status bar, **no extra permission**.
  - `cancelWakeAlarm()`: called when active monitoring resumes, in `onDestroy()`, and in `onCreate()` (a PendingIntent survives process death).
  - "No active alarm" branch: when the computed sleep > threshold, schedules the alarm at the wake time.
- `AndroidManifest.xml`: declares `WakeReceiver` (`exported=false`, action `fr.rsgnl.perimetre.ACTION_WAKE`).

**Design decisions**:
- Chose `setAlarmClock` over `setExactAndAllowWhileIdle`: same Doze-resistance and precision, but `SCHEDULE_EXACT_ALARM` is **not** required (auto-granted on API 31+ but user-revocable; must be explicitly requested via Settings on API 33+ for apps targeting 33+). The status-bar clock icon is a side effect that is actually useful (the user sees a wake is scheduled).
- WorkManager rejected: overkill for a single scheduled wake signal (there is no work to run — just interrupt the loop).
- Self-correcting: if a wake is delayed or the process was killed, the loop re-evaluates state and reschedules on the recomputed target.

**Build**: ✅ `:app:assembleDebug` BUILD SUCCESSFUL (JDK 21). Runtime validation pending (device: long sleep with screen off → clock icon appears, wake at the right time).

**Docs updated**: PROMPT.md (new "Doze-Resistant Wake" subsection, edge case #9, project structure), README.md + README.fr.md (battery section, service list, file tree), WORK_LOG.md.

---

### Session 2026-09-21 (config export/import)

**Context**: User requested a way to backup/restore the full app configuration (settings + alarms) from the Settings page.

**Implementation**:
- `data/ConfigExport.kt` (new): `ConfigExport(version, settings, alarms)` data class for JSON serialization.
- `AppViewModel.kt`: added `exportConfigJson(): String?` and `importConfigJson(json: String): Boolean`.
  - Import normalizes fields same as `AlarmRepository` (null-safe sound, clamped intervals, validated language).
  - Import replaces all existing alarms (full restore, not merge).
- `SettingsScreen.kt`: new card « Backup & restore » with two `OutlinedButton`s:
  - **Export**: launches SAF `CreateDocument("application/json")` → writes JSON to user-chosen file.
  - **Import**: launches SAF `OpenDocument()` (filter `application/json`) → reads + applies, snackbar feedback.
- `strings.xml` (EN + FR): 6 new strings for the section title, hint, buttons, and snackbar messages.

**Decisions**:
- SAF (Storage Access Framework) instead of direct file I/O → no storage permission needed, works on Android 10+ scoped storage.
- Full replace on import (not merge) → simpler UX, predictable result. User can keep a backup before importing.
- Version field in JSON (`version: 1`) → forward-compatible format.
- Snackbar (not Toast) for import feedback → consistent with Material 3.

**Build**: ✅ `:app:assembleDebug` BUILD SUCCESSFUL (JDK 21).

---

### Session 2026-09-21 (alarm volume fix)

**Context**: User reported the alarm volume slider "doesn't work": at 100 % the alarm is barely audible when the phone volume is low, and gets louder when the phone volume is raised manually → the alarm volume was a percentage of the *current* volume.

**Diagnosis**: `AlarmSoundPlayer.play()` used `MediaPlayer.create(context, uri)`, which routes audio to the **media stream**. `setVolume(v, v)` then applied the app slider as a multiplier on top of the media volume.

**Fix** (`18f2653`): build the `MediaPlayer` manually and set `AudioAttributes` (`USAGE_ALARM` + `CONTENT_TYPE_SONIFICATION`) before `setDataSource`. The alarm now follows the system **alarm volume** stream, independent of media volume (and audible on silent mode on most devices). Also release the player on creation failure (leak prevention).

**Docs updated**: README.md + README.fr.md (alarm stream note), PROMPT.md (player construction detail), WORK_LOG.md.

**Verification**: `:app:assembleDebug` BUILD SUCCESSFUL (JDK 21). Runtime validation pending (user, Android 17/Pixel).

---

### Session 2026-09-18 (release build + F-Droid — catch-up entry, log was not updated at the time)

**Context**: Release preparation for v1.0.0.

**Changes**:
- `73b5b50`, `fc1c01e`, `1c4152b` — PROMPT.md upkeep (one-shot, ringtone picker, decisions; pure English)
- `27f8ac7` — docs sync (WORK_LOG, README.md, README.fr.md)
- `86c14b9` — debug APK renamed to `PerimeterAlarm-debug.apk` (`archivesBaseName`)
- `903d1b0` — release signing config (`david-release.keystore` at repo root, now git-ignored) + **LGPL 3.0** license, tag **`1.0.0`**
- Untracked at the time: `fdroid/app.yml` (F-Droid metadata, build 1.0.0 @ `903d1b0`), `AGENT.md` (agent session protocol)

**Note**: commits above `27f8ac7` never got a WORK_LOG update — this entry is the catch-up.

---

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
| 09-18 | LGPL-3.0 + F-Droid metadata (`fdroid/app.yml`) | Publish on F-Droid requires a free license + declarative build |
| 09-21 | `AudioAttributes` `USAGE_ALARM` instead of `MediaPlayer.create()` | `create()` routes to the media stream: the app volume slider was a % of the *current media volume*, making the alarm barely audible at low media volume |
| 09-21 | SAF for export/import (not direct file I/O) | No storage permission needed, works on scoped storage (Android 10+), user picks location |
| 09-21 | Full replace on import (not merge) | Simpler UX, predictable result — user can keep a backup before importing |
| 09-21 | Doze-resistant long-sleep wake via `setAlarmClock` (not WorkManager / `setExactAndAllowWhileIdle`) | Exact + fires while idle + **no extra permission** (avoids `SCHEDULE_EXACT_ALARM` on API 31+/targetSdk 34); status-bar clock icon is a welcome side effect. WorkManager = overkill for a single wake signal |

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
| `service/WakeReceiver.kt` | Doze-resistant wake for long sleeps |
| `ui/components/OsmMap.kt` | Map composable (osmdroid) |
| `ui/components/Widgets.kt` | Reusable UI (RingtonePicker, SoundSettingsEditor, observeCurrentLocation) |
| `data/Alarm.kt` | Data models (Alarm, SoundSettings, AppSettings) |
| `data/AlarmRepository.kt` | Persistence (SharedPreferences + Gson) |
| `data/ConfigExport.kt` | Export/import JSON structure (version, settings, alarms) |
| `data/MonitorStatus.kt` | Service → UI status + oneShotFired flow |
| `util/Geo.kt` | Haversine |
| `util/TimeUtils.kt` | Period logic |
| `util/AlarmSoundPlayer.kt` | Sound + vibration |
| `util/RingtoneUtils.kt` | Ringtone helpers (title, permission check) |
