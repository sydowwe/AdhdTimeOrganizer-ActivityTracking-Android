# AdhdTimeOrganizer — Android usage tracker

Reads the phone's usage-event log (`UsageStatsManager`), builds per-app sessions, and sends them to the
AdhdTimeOrganizer server (`POST /api/tracker/android/sync`) every 15 minutes from WorkManager.
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
| `notify/Notifications.kt` | Channels, the permission check every poster makes, notification ids |
| `notify/SyncBlockedDecider.kt` | **Pure.** A run's facts + what was shown → post / clear / nothing |
| `notify/SyncBlockedNotifier.kt` | Runs the decider after every `SyncEngine.run`, builds the notification, keeps its state |
| `watch/ForegroundTracker.kt`, `watch/WatchReasons.kt` | **Pure.** Events → "came to the front / left" on top of `SessionBuilder`; who asked to watch until when |
| `watch/ForegroundWatcher.kt` | The API features use: `watch(reason, until)` / `stop(reason)`, listeners, `inFront` |
| `watch/ForegroundWatchService.kt` | The `specialUse` foreground service that polls the usage log while a reason is active |
| `review/EveningReviewDecider.kt`, `review/ReviewText.kt`, `review/RuleOutcome.kt` | **Pure.** When the evening review is due and when the moment is heard again; durations, the app line, the server's instants; a rule-create status → what the screen does |
| `review/EveningReviewRunner.kt`, `review/EveningReviewNotifier.kt`, `review/ReviewPreferences.kt` | The review after each sync run: hear the moment, ask `unmapped`, post the question; its switch and state |
| `review/ReviewActivity.kt` | The review screen: one card per unmapped app — map to the suggestion, choose an activity, ignore, skip |
| `worker/UsageSyncWorker.kt`, `TimeOrganizerApp.kt` | WorkManager entry point; periodic + "Sync Now" scheduling |
| `LoginActivity.kt`, `MainActivity.kt` | Password + 2FA sign-in; status screen (incl. the notification permission) |

`app/schemas/` holds Room's exported schema. Commit it with any entity change and write a real migration.

## Building and testing

```powershell
.\gradlew.bat testDebugUnitTest            # JVM tests of the pure classes
.\gradlew.bat assembleDebug                # debug APK
.\gradlew.bat assembleRelease              # R8-minified; catches missing keep rules
.\gradlew.bat testDebugUnitTest assembleDebug assembleRelease   # all three
```

- Results: `app/build/test-results/testDebugUnitTest/*.xml`. Gradle prints nothing per test.
- **Don't install on the user's phone or start an emulator without asking** — unless they just asked for it
  (`/test-release` or `scripts/test-release.ps1` is that ask). The phone runs their real tracker; a debug build
  signs with a different key, so installing it over a release build fails, and uninstalling to make room loses
  the outbox.
- AGP 9 runs Kotlin built in (no `kotlin-android` plugin). KSP must be one that registers its output through
  the Android source sets — 2.3.12 is; 2.3.1 failed with "Using kotlin.sourceSets DSL … is not allowed".
- **`SERVER_ROOT` in `util/Constants.kt` is the test server (`https://test.backend.adhdtimeorganizer.com/`) on
  `main`, in every build type** (since 2026-10-22). Production is switched in only for a real release, in its own
  commit, and switched back after; until `prod-release` exists nothing ships to production. Point it at
  `10.0.2.2:8081` (emulator → host) by hand for a local server; the debug build allows cleartext to it
  (`src/debug/res/xml/network_security_config.xml`), release does not. `scripts/test-release.ps1` prints the URL it
  built against and warns if it is production.

## Versioning

- **One place.** `versionCode` (an integer) and `versionName` (`"X.Y.Z"`) are literals in `app/build.gradle.kts`;
  nothing else carries a version. Never edit them by hand — `scripts/test-release.ps1` is what changes them (the
  `/test-release` command wraps it, so a session can call it too).
- **`versionName` is X.Y.Z; `versionCode` is +1 on every bump** — never reused, never lowered (Android refuses a
  downgrade with `INSTALL_FAILED_VERSION_DOWNGRADE`, and the next install over it would need an uninstall, which
  deletes the outbox).
  - **patch** (1.1.1 → 1.1.2): a fix, or a UI change with no new behaviour a user must learn.
  - **minor** (1.1.2 → 1.2.0): a new feature, a new permission or switch, a new server route the app calls.
  - **major** (1.2.0 → 2.0.0): the wire contract or the stored data changes incompatibly (a Room migration that
    drops data, a server that must be deployed first for the app to work at all).
- **A bump is one commit and one tag.** The commit is `chore: version X.Y.Z (code N)` and touches only
  `app/build.gradle.kts`; the annotated tag `vX.Y.Z` is on it. The tree is clean before it, so the tag is exactly
  what was built and installed. The script refuses a dirty tree (`-AllowDirty` overrides, and says the tag will not
  contain the dirt).
- **Test release** (`scripts/test-release.ps1`, for the phone on the desk, against `SERVER_ROOT` as it stands):
  bump → `testDebugUnitTest` + `assembleDebug` → commit → tag → `adb install -r` → read the installed version back.
  A failed build restores the version file and commits nothing. `-NoBump` rebuilds and reinstalls without a commit
  or tag (dev iteration — most installs are this); bump when a build is worth naming, e.g. a fix you will verify on
  the phone, or before handing a build to someone. `-Bump minor|major`, `-Version X.Y.Z`, `-NoInstall`, `-SkipTests`
  and `-Serial` are there when needed. It **never uninstalls**; a signature mismatch stops with a message.
- **Tags** are annotated, `v`-prefixed, and local. A tag that was never pushed may be moved (`git tag -f -a`); a
  pushed one never is. Push only when the owner asks, by name (`git push origin vX.Y.Z`, never `--tags`).
  `v1.0` is the first build, `v1.1` the durable-outbox rewrite.
- **Production release** is a separate script (`prod-release`, **not written yet**). When it is: switch
  `SERVER_ROOT` to production in its own commit; build and sign with the real release key (kept outside the repo —
  an unsigned release cannot be installed, and a different key forces an uninstall); **deploy the server first**
  (the auth lost-response recovery lives there); tag; switch `SERVER_ROOT` back to test.

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
- **A stop with no pause before it counts as the pause.** Samsung's Recents sends the app it leaves straight to
  `ACTIVITY_STOPPED` and never `ACTIVITY_PAUSED`; counting only pauses left that app "open" until the screen went
  off (a 22 s sitting stored as 125 s). A stop that follows its own pause is ignored (`SessionBuilder.onStopped`).
  `getInstanceId` is hidden API, so the match is by activity class, with a per-session count of pauses awaiting
  their stop.
- **The cursor rests at the start of the earliest still-open session**, never at "the latest event": the usage
  log's begin is inclusive, so the next read sees the resume again and builds the session whole. Moving it to
  the latest event of any kind lost the app in use at every sync.
- Sessions under 5 s are not queued (client policy, `SyncLimits.MIN_SESSION_MS`).
- **The home screen and Recents are not sent.** They are the launcher's activities, so the log counts them as an
  app in front (a 6 s `com.sec.android.app.launcher` row between every two apps). `UsageStatsCollector.collect`
  drops every installed home app's sessions after the build (`BuildResult.without`); the builder still sees their
  events, so the merge rule treats the launcher as "another app in between". Earlier rows already on the server stay.

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
  per client IP; phones share carrier IPs), 409 and 5xx keep the tokens and the sync waits.
- **A refresh whose answer may have been lost is replayed only after the server's 5 s grace.** The server
  rotates on every refresh. For a token client it exchanges a replay of the rotated token again, as long as the
  successor was never used and the latest rotation is 5 s – 72 h old (`RefreshTokenService`, lost-response
  recovery). Inside the 5 s it answers 409, "rotated moments ago" (a server from before 409 answers 401, which
  would sign us out). Past 72 h it ends only this phone's session.
  `RefreshPolicy`: a failure before the request was written keeps the tokens. A failure after it, a 409 or a 5xx
  leaves the token *uncertain*: `TokenRefresher` holds every send of it back to 6 s after that failure, retries
  once, and otherwise keeps the tokens for the next sync. A lost answer never signs the phone out.
  **Deploy the server first**: against one without the recovery, the replay revokes every session of the account.
- Tokens are AES-GCM-encrypted with a Keystore key (`EncryptedSharedPreferences` is deprecated) and written with
  `commit()` — a rotated pair lost to `apply()` and a kill is a replay. Backup and device transfer exclude
  everything (`data_extraction_rules.xml`): a restored token or device id on a second phone breaks both rules above.
- The authenticator refreshes at most once per request (`priorResponse`) and serializes refreshes on one lock.

### Notifications
- **One channel per purpose** (`Notifications.createChannels`, created on every app start): "Sync problems", the
  watcher's silent "Focus watch" and the "Evening review" question today.
  A feature that notifies adds its own channel and its own id, so people can mute nudges without losing the
  sync warnings. Never post on another feature's channel.
- **`POST_NOTIFICATIONS` (API 33+) is asked for only from the status screen's Notifications card**, which says
  in one line what it is for — never at launch. Once the system stops showing its dialog, the card opens the
  app's notification settings instead. Every post goes through `Notifications.post`, which checks the
  permission, the app switch and the channel.
- **"Sync blocked" is one notification, posted once per condition** (`SyncBlockedDecider`): usage access off
  (wins: it is the only one that loses data), signed out by the server, signed out by the user, or waiting
  with sessions pending for 24 h — the clock restarts whenever a run sends anything. A changed condition
  replaces it at once, a resolved one clears it, and while one persists it is posted again at most once a day.
  A phone that was never signed in is setting up, not blocked. A tap opens the fix: usage-access settings,
  sign-in, or the status screen.
- The decision runs at the end of `SyncEngine.run`, so a fixed condition clears on the next sync (≤ 15 min),
  not the moment it is fixed.

### Foreground watcher
- **It never records time.** Sessions come only from the sync's read of the usage log. What the watcher reports
  (`ForegroundChange`) is a cue for a nudge; nothing it sees goes into the outbox or to the server.
- **It runs only while some feature has asked**: `ForegroundWatcher.watch(context, reason, untilMs)` starts it or
  moves that reason's end, `stop(reason)` lets go, and the service stops itself when the last reason ends. Reasons
  are kept in prefs, so a sticky restart resumes them. Listeners get every change on a background thread; register
  them in `TimeOrganizerApp.onCreate` so a restarted process has them too.
- **Starting it from the background needs the battery-optimization exemption** (Android 12+). Without it, and
  outside the app, `watch` returns `false` — a feature must cope with that, not assume the watch began.
- **Mechanism (decided 2026-10-19):** a `specialUse` foreground service that polls `queryEvents` every 5 s while
  the screen is on. The SDK has no public "app came to the front" callback (`registerAppUsageObserver` is system
  only), an `AccessibilityService` is a permission Play restricts for non-accessibility use, and WorkManager can't
  run more often than every 15 min. `dataSync` doesn't fit and is capped at 6 h a day on Android 15. Play needs a
  `specialUse` declaration matching the manifest's `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`.
- **Same rules as the sessions, reused, not copied**: `ForegroundTracker` drives a `SessionBuilder` and reports
  changes to its `openSessions()`. A leave is held back for the 2 s merge gap (or until another app comes to the
  front), so switching screens inside one app is not a change. Feed each event exactly once: reads are contiguous
  (`[last end, now − 1 s)`) because a repeated resume leaves an app open for good.
- **Screen off stops the polling**; one read 3 s after screen-off settles everything as left, and the first read
  after unlock carries the dark stretch from the log. The first read looks back 6 h for the app already in front.
- **Battery, estimated, not measured:** a poll is one `queryEvents` call over ~5 s of log plus a few objects,
  ~1–5 ms of CPU. 720 polls a screen-on hour ≈ 1–4 s of CPU ≈ 0.1–0.3 mWh, under 0.01 % of a ~17 Wh battery
  per hour. The screen itself draws hundreds of times more. Nothing runs while the screen is off. Measure on the
  phone (`dumpsys batterystats`) only with the user's go-ahead.
- The ongoing notification ("Focus watch on until 14:30 · Notices which app is in front so nudges arrive on
  time. Records nothing.") is on the silent "Focus watch" channel. Without `POST_NOTIFICATIONS` it is hidden but
  the service still runs.

### Evening review of unmapped time
- **The server decides what is unmapped and worth asking** (`GET /tracker/android/unmapped`: today on
  the user's clock, resolved by `AndroidMappingRuleSet`; a package needs ≥ 5 min, at most 5, and under 15 min in all
  `apps` is empty). The phone repeats none of it — empty means say nothing. Mapping stays server-only: the phone
  sends a **package name and a target** (`POST …/rules`, `{packageName, activityId | isIgnored}`) and the
  server builds the exact-package rule, so a phone token can never author a matcher.
- **Mapping pays off tonight.** The rule create also attributes the package's sessions already stored today to the
  activity (the sync only attributes sessions it newly stores), and `attributedSeconds` is what the card reports.
  Earlier days are not backfilled.
- **When it runs:** from `UsageSyncWorker`, right after `SyncEngine.run`, so the sessions it asks about were just
  sent. It is due on the first run at or after the **server's evening-shutdown moment** (`GET tracker/evening-shutdown-moment`:
  bed time less the user's offset), for 3 h after; later than that the evening is skipped, not asked about at 2 a.m.
  The moment is heard again once it has been used or is 6 h old; reviews are at least 12 h apart
  (`EveningReviewDecider`). There is no alarm, so it lands up to ~15 min late.
- **A review is stamped only once the server has answered** — "nothing worth asking" included — so a failed call
  does not use the evening up; the next run tries again inside the window. A 404 (a server from before the routes)
  counts as answered. The run never throws into the sync.
- **Its own channel** (`evening_review`) and id; off with the switch on the status screen or by muting the channel.
  It needs `POST_NOTIFICATIONS` like every poster, and does nothing (state untouched) without it.
- The cards never log or keep package or activity names beyond the screen; the pick list is fetched on first use and
  dropped when the server says an activity is gone (404).

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
