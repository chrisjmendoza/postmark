# Target SDK Review — Android 14 (API 34) & 15 (API 35) behavior changes

**Audit date:** July 24, 2026
**Branch:** `docs/target-sdk-review`
**Scope:** TODO → Play Store prep → "Target SDK review" (audit only — this is a
factual review, not a fix-everything pass).
**Sources:** developer.android.com behavior-change pages confirmed via web
(Android 15 FGS timeouts; Google Play 16 KB page-size requirement) plus the
auditor's own knowledge of the API 34/35 change lists. Where a verdict rests on
knowledge rather than a fetched page it is marked *(knowledge)*.

---

## 1. Current SDK levels (facts)

| Setting | Value | Source |
|---|---|---|
| `compileSdk` | **35** | `app/build.gradle.kts:41` |
| `targetSdk` | **35** | `app/build.gradle.kts:46` |
| `minSdk` | **26** (Android 8.0) | `app/build.gradle.kts:45` |
| AGP | 9.3.0 | `gradle/libs.versions.toml` |
| Kotlin | 2.2.10 | `gradle/libs.versions.toml` |
| WorkManager | 2.10.0 | `gradle/libs.versions.toml` |
| media3 (native `.so`) | 1.5.1 | `gradle/libs.versions.toml` |

The app already targets 35, so **both** the "apps targeting Android 14" and
"apps targeting Android 15" behavior-change sets apply in full, and the app is
already at/above the Google Play minimum-target requirement (API 35).

---

## 2. Behavior-change verdict table

Legend: **HANDLED** (with evidence), **GAP** (needs a change), **N-A** (does not
apply, with reason).

### Android 14 (API 34) — apps targeting 34+

| # | Behavior change | App exposure | Verdict |
|---|---|---|---|
| 1 | **Foreground services must declare a type** | 3 WorkManager workers go foreground (import, restore, export) | **HANDLED** — manifest declares `SystemForegroundService` with `foregroundServiceType="dataSync"` (`AndroidManifest.xml:84-87`); each worker builds `ForegroundInfo(..., FOREGROUND_SERVICE_TYPE_DATA_SYNC)` gated on API 29+ (`SmsHistoryImportWorker.kt:81-91`, `RestoreWorker.kt:490-498`, `ExportWorker.kt:126-134`) |
| 2 | **`FOREGROUND_SERVICE_<type>` permission required** | dataSync FGS above | **HANDLED** — `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` declared (`AndroidManifest.xml:36-37`) |
| 3 | **`SCHEDULE_EXACT_ALARM` denied by default** | — | **N-A** — no `AlarmManager`/`setExact`/`setAlarmClock`/`SCHEDULE_EXACT_ALARM` anywhere in the app (grep: 0 hits). Scheduling is WorkManager-only (periodic backup) |
| 4 | **Implicit intents to internal components blocked** | Notification + send/delivery PendingIntents | **HANDLED** — every PendingIntent target is an explicit `Intent(context, X::class.java)` (`IncomingNotifier.kt:82,97,114,158,235,355`; `SmsManagerWrapper.kt:108,135`; workers). No implicit-intent PendingIntents to own components |
| 5 | **Mutable PendingIntents must declare mutability** | 12 PendingIntent sites | **HANDLED** — all specify a flag; every one is `FLAG_IMMUTABLE` except the notification direct-reply intent which is `FLAG_MUTABLE` **by requirement** (RemoteInput must fill it in) (`IncomingNotifier.kt:101`). Correct per the RemoteInput contract |
| 6 | **Context-registered receivers need `RECEIVER_EXPORTED`/`NOT_EXPORTED`** | — | **N-A** — no `registerReceiver(...)` calls (grep: 0 hits). All 8 receivers are manifest-declared, each with an explicit `android:exported` (`AndroidManifest.xml:106-168`). The only runtime registration is a `ContentObserver` (`SmsContentObserver`), which is not a broadcast receiver and takes no export flag |
| 7 | **Full-screen intent permission (`USE_FULL_SCREEN_INTENT`) restricted to calling/alarm apps** | — | **N-A** — no `setFullScreenIntent`/`USE_FULL_SCREEN_INTENT` (grep: 0 hits). Incoming-SMS notifications use `PRIORITY_HIGH` heads-up only (`IncomingNotifier.kt:133`) |
| 8 | **Photo-picker / partial media access; `READ_MEDIA_VISUAL_USER_SELECTED`** | Attachment + background + avatar pickers | **HANDLED / N-A** — uses the Jetpack Photo Picker (`PickMultipleVisualMedia`/`PickVisualMedia`, `ThreadScreen.kt:3292`, `ContactDetailScreen.kt:113`, `AppearanceScreen.kt:89`); declares **no** `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO`, so the partial-access permission model does not apply. Audio still uses `GetContent` (picker has no audio) — also permissionless |
| 9 | **Minimum installable `targetSdk` (23)** | — | **N-A** — target 35 |
| 10 | **`WRITE_EXTERNAL_STORAGE` scoping** | Save-image-to-gallery | **HANDLED** — declared `maxSdkVersion="28"` (`AndroidManifest.xml:25-27`); API 29+ path uses scoped `MediaStore` insert with `RELATIVE_PATH`/`IS_PENDING` and no permission (`ThreadScreen.kt:5001-5035`) |
| 11 | **Health Connect** (named in the TODO as an "etc.") | — | **N-A** — no health/fitness data, no Health Connect dependency or permission. Listed only because the TODO copy names it |
| 12 | **`POST_NOTIFICATIONS` runtime permission (API 33)** | All notifications | **HANDLED** — declared (`AndroidManifest.xml:19`) and requested behind a `TIRAMISU` gate (`MainActivity.kt:125-127`) |

### Android 15 (API 35) — apps targeting 35+

| # | Behavior change | App exposure | Verdict |
|---|---|---|---|
| 13 | **Edge-to-edge enforced** (window fits system bars removed) | Whole UI | **HANDLED** — `enableEdgeToEdge()` in `MainActivity.onCreate()` (`MainActivity.kt:53`); no deprecated `setStatusBarColor`/`setNavigationBarColor`/`FLAG_LAYOUT_NO_LIMITS` used (grep: 0 hits). Dialogs opt into edge-to-edge via `WindowCompat.setDecorFitsSystemWindows(window, false)` (`ThreadScreen.kt:4700,5340`, `ContactDetailScreen.kt:806`). *Per-screen inset correctness is a device-verify item — see §4* |
| 14 | **dataSync FGS limited to 6 h / 24 h, then `Service.onTimeout()`** | Bulk import worker (can walk 51k SMS + 108k+ MMS) | **GAP (low–med)** — see Gap G1. WorkManager 2.10 is timeout-aware, and the importer already supports checkpoint-resume, so the realistic worst case is a truncated-then-resumed import, not a crash — but this is unverified on a large mailbox |
| 15 | **Deprecated window color APIs become no-ops** | — | **N-A** — none used (see #13) |
| 16 | **Predictive back on by default** | System back / Compose `BackHandler` | **HANDLED *(knowledge)*** — at target 35 predictive back is enabled by default and the `enableOnBackInvokedCallback` manifest flag is ignored; androidx.activity 1.10 `OnBackPressedDispatcher`/Compose `BackHandler` are predictive-back compatible. Gesture animation is a device-verify item (§4) |
| 17 | **FGS start-from-background tightened** (e.g. `BOOT_COMPLETED` can't start certain FGS types) | `RECEIVE_BOOT_COMPLETED` reschedules backup | **N-A** — the boot path only re-enqueues **WorkManager** work (`BackupScheduler`), it does not start a foreground service directly; WorkManager owns the FGS lifecycle and its start constraints |
| 18 | **Secure/elegant defaults (elegant text height, stricter TLS, etc.)** | — | **N-A** — cosmetic/managed; nothing app-specific |

### All apps (regardless of target)

| # | Behavior change | App exposure | Verdict |
|---|---|---|---|
| 19 | **Google Play 16 KB page-size compatibility** (native `.so` must be 16 KB-aligned; Play-enforced for apps targeting 35+, deadline extended to **May 31, 2026** in Play Console) | media3 1.5.1 ships native libraries (ExoPlayer/Transformer/effect) | **GAP / VERIFY** — see Gap G2. Pure-Kotlin/Java code is aligned automatically; only the media3 `.so` files are at issue. AGP 9.3.0 is well past the 8.5.1+ tooling floor, and media3 1.5.x is expected to ship aligned libs, but this **must be confirmed on the built APK/AAB** before Play submission |
| 20 | **Non-SDK interface restrictions / private API greylist** | — | **N-A** — no reflection into hidden framework APIs |

---

## 3. Prioritized gap list

**G1 — Android 15 dataSync FGS 6-hour timeout (severity: MEDIUM; mostly device-verify).**
On Android 15 (target 35), all of an app's `dataSync` foreground services share a
cumulative 6-hour budget per 24-hour window; on expiry the system calls
`Service.onTimeout()` and, if the service doesn't stop within seconds, throws
`RemoteServiceException`. Further `dataSync` starts are blocked until the user
foregrounds the app.
- *Exposure:* only `SmsHistoryImportWorker` can plausibly approach 6 hours (a
  first-run import of a very large mailbox — the app has already imported 51k SMS
  + 108k MMS on a Samsung S24 Ultra). `RestoreWorker`/`ExportWorker` are far
  shorter.
- *Mitigation already present:* WorkManager 2.10 is FGS-timeout-aware (it stops
  the worker rather than letting the raw exception escape), and the importer has
  checkpoint-resume (`resumeBeforeRawId`, two-phase import) so a truncated import
  resumes on next run.
- *Recommendation:* (a) confirm the WorkManager 2.10 timeout path stops the
  importer cleanly on device; (b) ensure the import surfaces a "resuming" state if
  it is cut at 6h (it already has "Resuming…" UI); (c) longer term, consider
  splitting the historical walk so no single FGS session runs anywhere near 6h.
  No code change is being made tonight — this needs a device/large-mailbox test to
  confirm whether any change is warranted at all.

**G2 — 16 KB page-size alignment of media3 native libraries (severity: MEDIUM, Play-blocking; APK-inspectable).**
Play requires 16 KB-aligned native code for apps targeting 35 (deadline extended
to May 31, 2026). The app's only native code comes from media3 1.5.1
(exoplayer/ui/transformer/effect). Room here uses framework SQLite (no bundled
native lib), Coil is pure-Kotlin — media3 is the sole `.so` source.
- *Recommendation:* build the release/staging AAB and check `.so` alignment (e.g.
  `zipalign -c -P 16`, the `check_elf_alignment.sh` script, or APK Analyzer). If
  any media3 `.so` is 4 KB-aligned, bump media3 to a 16 KB-aligned release and
  rebuild. This is inspectable from the artifact, not the source, so it is not
  fixable "blind" in this audit.

**Minor / watch-items (not standalone gaps):**
- **Per-screen window insets under enforced edge-to-edge (Android 15).** Code
  globally opts in correctly, but CLAUDE.md flags "bottom buttons behind the nav
  bar" as a recurring regression. Every screen/dialog still needs the four-edge
  inset check on a 15 device — device-verify, not code-inspectable here.
- **Predictive back animation.** Default-on at target 35; verify the back gesture
  animates and that custom `BackHandler` sites behave (device-verify).

---

## 4. Code-inspectable vs. needs-device

**Settled by code inspection (no device needed):**
- Foreground service type + permission (rows 1–2) — HANDLED.
- No exact alarms (row 3) — N-A, definitive.
- Explicit intents / PendingIntent mutability (rows 4–5) — HANDLED.
- No context-registered receivers; manifest receivers all carry `exported` (row 6) — HANDLED/N-A.
- No full-screen intents (row 7) — N-A.
- Photo Picker, no `READ_MEDIA_*`, scoped-storage save path (rows 8, 10) — HANDLED.
- `POST_NOTIFICATIONS` gating (row 12) — HANDLED.
- `enableEdgeToEdge`, no deprecated window-color APIs (rows 13, 15) — HANDLED.
- Boot path uses WorkManager, not a direct FGS start (row 17) — N-A.

**Requires on-device / artifact verification:**
- **G1** dataSync 6-hour timeout behavior on a large first-run import (Android 15 device).
- **G2** 16 KB `.so` alignment — inspect the built AAB/APK.
- Per-screen edge-to-edge insets on all four edges (Android 15 device).
- Predictive-back gesture animation (Android 15 device).

---

## 5. Summary

- **Verdicts:** 20 change areas reviewed — **11 HANDLED**, **2 GAP** (G1 dataSync
  timeout, G2 16 KB alignment), **7 N-A**.
- **No code changes were made in this branch.** The manifest, PendingIntents,
  receivers, foreground-service types, photo picker, and edge-to-edge setup are
  all already correct for target 35; there was no trivial zero-risk code gap to
  close (e.g. no missing receiver export flag, no implicit PendingIntent, no
  mutable-flag omission). Both open gaps require either a device test (G1) or an
  artifact inspection + possible dependency bump (G2), neither of which is a
  one-line safe edit.
- **The TODO item stays open** — it is only "done" when G1 and G2 are closed.
