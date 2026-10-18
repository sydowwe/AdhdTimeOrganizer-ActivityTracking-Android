# AdhdTimeOrganizer — Android usage tracker

Reads the phone's usage-event log (`UsageStatsManager`), builds per-app sessions, and sends them to the
AdhdTimeOrganizer server (`POST /api/activity-tracking/android/sync`) every 15 minutes from WorkManager.
Kotlin, Views (no Compose), Retrofit + OkHttp + Gson, Room, no DI container. minSdk 29, target 35.

The server is `../../RiderProjects/AdhdTimeOrganizer` (Tracking slice: `AdhdTimeOrganizer.Tracking/`, start
with its `docs/summary.md`). The desktop tracker is the sibling `../../RiderProjects/AdhdTimeOrganizer.ActivityTracking.Desktop`
— same server, same rules, read its `CLAUDE.md` for the shared ones. Ideas for what to build next are in
[docs/ideas.md](docs/ideas.md); the follow-up work is planned as clean-context prompts in
`../../RiderProjects/AdhdTimeOrganizer/review/androidTrackerPrompts/` (start with its `00-README.md`).

## Layout

Under `app/src/main/java/com/timeorganizer/usagetracker/`:

| Path | What lives there |
|---|---|
| `usagestats/SessionBuilder.kt` | **Pure.** Events → sessions; where the next read starts (`resumeFromMs`) |
| `usagestats/UsageStatsCollector.kt` | Reads the usage log for a window, maps event types, resolves app labels |
| `sync/SyncLimits.kt` | **Pure.** The server's per-session rules, applied before a session is queued |
| `sync/BatchSender.kt` | **Pure.** Batching, `ResponseClassifier` (status → sent / wait / auth / rejected), bisecting a refused batch |
| `sync/SyncEngine.kt` | One run: usage log → outbox → server. Process-wide mutex. Writes the `SyncReport` the screen shows |
| `data/local/outbox/Outbox.kt` | Room: `pending_session` (the outbox) and `sync_cursor`, committed together |
| `data/local/AuthPreferences.kt` | Token pair, Keystore-encrypted, `commit()`; why the last session ended |
| `data/local/SyncPreferences.kt` | Device id, the account the outbox belongs to, the last `SyncReport` |
| `auth/RefreshPolicy.kt` | **Pure.** When a refresh saves, retries, keeps the tokens, or signs out |
| `auth/TokenRefresher.kt` | The refresh call (raw OkHttp, so it knows whether a failed request was written) |
| `auth/KeystoreCipher.kt`, `auth/JwtSubject.kt` | AES-GCM with a Keystore key; reading `sub` to notice an account switch |
| `data/api/` | Retrofit services, `RetrofitClient` (auth interceptor + authenticator), wire DTOs |
| `worker/UsageSyncWorker.kt`, `TimeOrganizerApp.kt` | WorkManager entry point; periodic + "Sync Now" scheduling |
| `LoginActivity.kt`, `MainActivity.kt` | Password + 2FA sign-in; status screen |

`app/schemas/` holds Room's exported schema. Commit it with any entity change and write a real migration.

## Building and testing

```powershell
.\gradlew.bat testDebugUnitTest            # JVM tests of the pure classes
.\gradlew.bat assembleDebug                # debug APK
.\gradlew.bat assembleRelease              # R8-minified; catches missing keep rules
.\gradlew.bat testDebugUnitTest assembleDebug assembleRelease   # all three
```

- Results: `app/build/test-results/testDebugUnitTest/*.xml`. Gradle prints nothing per test.
- **Don't install on the user's phone or start an emulator without asking.** The phone runs their real
  tracker; a debug build signs with a different key, so installing it over a release build fails, and
  uninstalling to make room loses the outbox.
- AGP 9 runs Kotlin built in (no `kotlin-android` plugin). KSP must be one that registers its output through
  the Android source sets — 2.3.12 is; 2.3.1 failed with "Using kotlin.sourceSets DSL … is not allowed".
- `API_BASE_URL` in `util/Constants.kt` is production in every build type. Point it at `10.0.2.2:8081`
  (emulator → host) by hand when testing against a local server; the debug build allows cleartext to it
  (`src/debug/res/xml/network_security_config.xml`), release does not.

## Rules that hold everywhere

### The wire contract
- **A refused batch is lost data.** The phone cannot repair a batch the server calls malformed, so every
  server rule for a session is mirrored in `SyncLimits` and applied *before* a session enters the outbox:
  package 1–255 characters, label 1–255 (blank → package name, over-wide → cut, never through a surrogate pair),
  duration ≥ 1 s and ≤ 24 h (longer sessions are split into consecutive pieces), at most 200 sessions per
  request (server: 1000), device id ≤ 100. When the server's `AndroidSyncValidator`, `AndroidSyncEndpoint` or
  `TrackingIngestLimits` change, change `SyncLimits` with them.
- **The client must work against both servers.** Since 2026-10-18 the server drops or repairs a bad session on
  its own and reports it in `dropped`; before that, the same session was a 400 for the whole sync. A user's
  phone may meet either, so `SyncLimits` stays as strict as the old validator.
- **Instants are UTC ISO-8601 with milliseconds; `durationSeconds` is the floored span** — exactly what the
  server recomputes from the two instants (±2 s tolerance). Don't round.
- **Dedup is the server's `(user, device, package, start)`.** Re-sending is always safe: a repeat counts as
  `duplicatesSkipped`. That is what lets the sender retry a batch whose answer was lost.
- **Response handling (`ResponseClassifier`)**: 2xx sent; 401 means the refresh could not help — stop;
  400/413/422 is a payload complaint — bisect the batch down to the bad session and drop only it; **every other
  status waits** (403, 404, 408, 409, 425, 429, 5xx). Never drop on a 404: a proxy or a moved route says
  nothing about the sessions.
- **Activity mapping happens only on the server** (`AndroidMappingRuleSet`, the one matcher; the grid's
  `coveredBy` chip and the sync's attribution both use it). The app sends package + label and never decides
  what activity a session is. If a feature here needs to know (a nudge, a widget), the server has to tell it.
- **Package names are identity.** Users' mapping rules match on them; never rewrite one.

### Sessions and the cursor
- All the accounting lives in `SessionBuilder`, which has no clock and no Android types. Keep it that way.
- A session is an **app**, from its first resumed activity until the last one pauses — not an activity. A
  return to the same app within 2 s with nothing else in between is the same sitting.
- Screen off, keyguard and shutdown close every session; after a reboot an open session ends at the last event
  before it. **Split-screen counts both visible apps; picture-in-picture counts nothing.** Both deliberate.
- **The cursor rests at the start of the earliest still-open session**, never at "the latest event": the usage
  log's begin is inclusive, so the next read sees the resume again and builds the session whole. Moving it to
  the latest event of any kind lost the app in use at every sync.
- Sessions under 5 s are not queued (client policy, `SyncLimits.MIN_SESSION_MS`).

### Durability and threads
- **Nothing is sent that is not in the outbox first**, and the outbox rows and the cursor commit in one Room
  transaction (`OutboxDao.record`). Recording needs only usage access — it runs offline and signed out.
- A row leaves the outbox only when the server answered for it (`markSent`), or when the server refused it
  alone (bisected to one). Sent rows stay until the cursor passes their start, so a re-read cannot queue them
  again. Unsent rows are bounded: 30 days, 50 000 rows, oldest first.
- One `SyncEngine.run` at a time (process-wide `Mutex`); "Sync Now" is unique work with `KEEP`.
- The periodic request has **no network or battery constraint** (recording needs neither) and is enqueued with
  `UPDATE` on every app start, so a force-stop is undone by opening the app.
- A different account signing in drops the outbox (`SyncEngine.onSignedIn`): those were someone else's sessions.

### Auth
- **Only the server refusing the refresh token (400/401/403) signs out.** Offline, 429 (the refresh is throttled
  per client IP; phones share carrier IPs) and 5xx keep the tokens and the sync waits.
- **Never replay a refresh token whose answer may have been lost.** The server rotates on every refresh and
  answers a rotated token replayed more than 5 s later by revoking *every* session of the account, web included.
  `RefreshPolicy`: a failure before the request was written keeps the tokens; one after it is retried at once
  only if it failed within 2.5 s (inside the grace), otherwise the phone signs itself out (`LOST_REFRESH`).
  Revisit once the framework tolerates a token client's lost response (handoff item 1 in the review).
- Tokens are AES-GCM-encrypted with a Keystore key (`EncryptedSharedPreferences` is deprecated) and written with
  `commit()` — a rotated pair lost to `apply()` and a kill is a replay. Backup and device transfer exclude
  everything (`data_extraction_rules.xml`): a restored token or device id on a second phone breaks both rules above.
- The authenticator refreshes at most once per request (`priorResponse`) and serializes refreshes on one lock.

### Privacy and logging
- No email, name, password or token in logs. The OkHttp logger is `BASIC` in debug and off in release, with
  `Authorization` redacted. **Never `BODY`**: the login body is the password, auth bodies are tokens, a sync body
  is every app the person used.
- Package names and app labels are personal (they say what someone does on their phone); don't log them either.

### Device identity
- `deviceId` is kept once stored. New installs use `"android-" + ANDROID_ID` (stable across reinstall, scoped to
  the signing key since Android 8), so a reinstall does not re-send its backlog as a new device and double the
  dashboards. Changing an existing install's id re-keys its dedup — don't.

## Shell

This is a Windows machine: use PowerShell. Never round-trip a file through the shell
(`Get-Content | Set-Content` and the like); edit files with the editor tools. .NET file APIs called from
PowerShell resolve relative paths against the process directory, not `Set-Location` — use absolute paths.
