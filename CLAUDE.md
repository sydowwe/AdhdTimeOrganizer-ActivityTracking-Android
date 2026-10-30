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
| `usagestats/Pickups.kt` | **Pure.** Events → pickups (screen on or unlock → screen off / keyguard), each unlocked or not. The loop check reads it, and so does `PickupCounter` |
| `usagestats/PickupCounter.kt` | **Pure.** The sync's pickups (prompt 14): the settled ones, where the next read starts (the earliest pickup still going), the no-lock-screen rule |
| `usagestats/DerailmentLinker.kt` | **Pure.** The notifications that interrupted and the derailments they started (prompt 15): `NOTIFIED` events joined to the sittings, where the next read starts |
| `sync/SyncLimits.kt` | **Pure.** The server's per-session rules, applied before a session is queued |
| `sync/SyncWire.kt` | **Pure.** The wire instant, the extras request (pickups and loops, prompt 14; interruptions and derailments, 15), whether an answer acknowledged each kind |
| `sync/BatchSender.kt` | **Pure.** Batching, `ResponseClassifier` (status → sent / wait / auth / rejected), bisecting a refused batch |
| `sync/SyncEngine.kt` | One run: usage log → outbox → server. Process-wide mutex. Writes the `SyncReport` the screen shows |
| `data/local/outbox/Outbox.kt` | Room: `pending_session` (the outbox), `pending_pickup` (prompt 14), `pending_interruption` / `pending_derailment` (15) and `sync_cursor` (all three cursors), committed together; `pause_range` (17) |
| `data/local/AuthPreferences.kt` | Token pair, Keystore-encrypted, `commit()`; why the last session ended |
| `data/local/SyncPreferences.kt` | Device id, the account the outbox belongs to, the last `SyncReport` |
| `auth/RefreshPolicy.kt` | **Pure.** When a refresh saves, retries, keeps the tokens, or signs out |
| `auth/TokenRefresher.kt` | The refresh call (raw OkHttp, so it knows whether a failed request was written) |
| `auth/KeystoreCipher.kt`, `auth/JwtSubject.kt` | AES-GCM with a Keystore key; reading `sub` to notice an account switch |
| `data/api/` | Retrofit services, `RetrofitClient` (version + auth interceptors, authenticator), wire DTOs; **pure** `ClientVersion` (the `X-Client-Version` header, the status screen's host) |
| `notify/Notifications.kt` | Channels, the permission check every poster makes, notification ids |
| `notify/SyncBlockedDecider.kt` | **Pure.** A run's facts + what was shown → post / clear / nothing |
| `notify/SyncBlockedNotifier.kt` | Runs the decider after every `SyncEngine.run`, builds the notification, keeps its state |
| `notify/PhoneSilence.kt` | The phone's own reasons for a nudge to keep quiet: a call (or ringing), any Do Not Disturb (every nudge); car mode (`driving`, the drift nudge only) |
| `notify/NudgeGap.kt` | Nudges of different kinds keep 10 min apart: when each family last posted (prefs) and **pure** `heldUntil` |
| `watch/ForegroundTracker.kt`, `watch/WatchReasons.kt` | **Pure.** Events → "came to the front / left" on top of `SessionBuilder`; who asked to watch until when |
| `watch/ForegroundWatcher.kt` | The API features use: `watch(reason, until)` / `stop(reason)`, listeners, `inFront` |
| `watch/ForegroundWatchService.kt` | The `specialUse` foreground service that polls the usage log while a reason is active |
| `review/EveningReviewDecider.kt`, `review/ReviewText.kt`, `review/RuleOutcome.kt` | **Pure.** When the evening review is due and when the moment is heard again; durations, the app line, the server's instants; a rule-create status → what the screen does |
| `review/EveningReviewRunner.kt`, `review/EveningReviewNotifier.kt`, `review/ReviewPreferences.kt` | The review after each sync run: hear the moment, ask `unmapped`, post the question; its switch and state |
| `review/ReviewActivity.kt`, `review/SnoozeState.kt` | The review screen: one card per unmapped app — map to the suggestion, choose an activity, ignore, snooze, skip; **pure** snooze expiry |
| `mapping/MappingRules.kt`, `mapping/MappingOutcome.kt`, `mapping/MappedAppsList.kt` | **Pure.** App categories, the "+ New activity" rules (name check, defaults, the three-way flag), the picker's filter; a mapping call's answer → what the screen does; the "Mapped apps" rows and "Add app"'s list |
| `mapping/MappingCalls.kt`, `mapping/MappingDialogs.kt` | The calls and the problem body; the one `ActivityPicker`, `NewActivityDialog`, `AppCategoryLinksDialog`, `BackfillConfirm` |
| `mapping/MappedAppsActivity.kt` | "Mapped apps": change / unlink / distraction per mapping, "Add app", "App categories" |
| `now/NowFacts.kt` | **Pure.** `GET tracker/android/now` as the nudges read it: silence, the blocks covering now (with their activity) and the next, the desk's last minute, idle, its busiest named work |
| `now/NowClient.kt` | The one `/now` reader: in-memory answer reused 30 s, then `If-None-Match` → `304` |
| `now/KeptDistractions.kt` | `/now`'s distraction packages kept in prefs for a restart offline; shared by the chime and the drift nudge |
| `drift/DriftPolicy.kt` | **Pure.** The drift nudge: the distraction apps' time in front inside a block (30 s grace, a minute's gap pauses it) → nudge / hold / withdraw; the buttons; how long to watch; sittings from the log |
| `drift/DriftMonitor.kt`, `drift/DriftNotifier.kt`, `drift/DriftActionReceiver.kt`, `drift/DriftActionActivity.kt`, `drift/DriftPreferences.kt` | Runs the policy (a distraction app to the front, a look-again, after each sync, a button), arms the watcher through blocks, posts the nudge; "5 more minutes" and the swipe (receiver), "Back to it" and "Switch plan" (an unseen activity, so it may open the home screen or the browser); its switch and state |
| `breaks/BreakPolicy.kt` | **Pure.** Breaks that don't end: `/now` + phone use from the log + silence + state → start / nudge / end; the buttons; how long to watch; `PhoneUse` from usage events |
| `breaks/BreakMonitor.kt`, `breaks/BreakNotifier.kt`, `breaks/BreakActionReceiver.kt`, `breaks/BreakPreferences.kt` | Runs the policy (after each sync, on a pickup, on a button, when it asks to look again), arms the watcher, posts the nudge; its switch, length and state |
| `chime/ChimeSchedule.kt`, `chime/ChimeApps.kt` | **Pure.** The time chime: the chosen apps' sittings from the log → the stretch (gaps ≤ 3 min paused, longer ones start over) → chime now / when the next falls due; which apps are chosen (distraction flags + ticks − unticks) and the screen's rows |
| `chime/ChimeMonitor.kt`, `chime/ChimeNotifier.kt`, `chime/ChimePreferences.kt`, `chime/ChimeAppsActivity.kt` | Runs the schedule (a chosen app to the front, the next chime due, after each sync), keeps the watcher on while the chime is, posts the chime; its switch, interval, choices, the kept distraction list and state; the "Time chime" screen |
| `loops/LoopDetector.kt`, `loops/LoopPolicy.kt`, `loops/LoopApps.kt` | **Pure.** The loop check: pickups + sittings → a rule holding now (6 unlocks in 10 min, or 6 apps hopped in 5 min); the loop's state from look to look, the buttons, the away timer, "Not today"; the "Apps that don't count" rows |
| `loops/LoopMonitor.kt`, `loops/LoopNotifier.kt`, `loops/LoopActionReceiver.kt`, `loops/LoopPreferences.kt`, `loops/LoopStore.kt`, `loops/LoopAppsActivity.kt` | Runs the check (any app to the front, after each sync, a button, the timer's alarm), keeps the watcher on while it is, posts the check-in and "Phone away … is up"; its switch, timer length, apps that don't count and state; the loops kept on the phone (`loops.db`); the "Apps that don't count" screen |
| `budget/BudgetTracker.kt` | **Pure.** Daily limits: the server's figures (`BudgetFacts`) + the budgeted apps' sittings from the log → the estimate, the stage due (80 % / the limit), holds, when the next crossing comes, whether to watch; `ClaimOutcome` (a claim's answer → post or stay quiet) |
| `budget/BudgetMonitor.kt`, `budget/BudgetNotifier.kt`, `budget/BudgetPreferences.kt` | Runs the tracker (a budgeted app to the front, the next crossing, after each sync), claims each stage before posting it, sends claims made offline before the next sync, keeps the watcher on while a limit is unreached; the switch, the figures, the settled stages, the unsent claims |
| `capture/CaptureRules.kt` | **Pure.** Quick capture: a typed line or a share → the thought (title + link for a browser share, cut to the server's limit); an answer's status → sent / refused / wait / auth |
| `capture/CaptureStore.kt`, `capture/CaptureSender.kt` | The queue (`capture.db`) and its sender: stored first, sent by a network-bound job and after every sync; a row leaves only when the server has it |
| `capture/CaptureActivity.kt`, `capture/CaptureTileService.kt` | The one-field dialog (tile, launcher shortcut) and the share target "Save to Inbox"; the "Capture" Quick Settings tile |
| `pause/PauseClipper.kt`, `pause/PausePolicy.kt` | **Pure.** Pause tracking (prompt 17): what a pause takes out of a read (sessions clipped, the rest dropped, no cursor moved), the daily limits' sittings without it; where each length ends, the morning, when the notification shows, when a paused phone sends an empty sync |
| `pause/Pauses.kt`, `pause/PauseNotifier.kt`, `pause/PauseActionReceiver.kt`, `pause/PausePreferences.kt` | The pauses in `outbox.db` (start, Resume, the morning from `/now`); "Tracking paused until …" with **Resume**; the kept morning, the notification's state, the last heartbeat |
| `ignore/IgnoredPackages.kt`, `ignore/IgnoredAppsList.kt` | **Pure.** `IgnoreState`: the server's rules + pending ignores → what the phone drops, every transition, the 7-day expiry; `IgnoreCallOutcome`; `OutboxPurge`; the screen's rows |
| `ignore/IgnoreStore.kt`, `ignore/IgnoreSync.kt` | The rules cache (body + ETag) and pending ops in prefs; `GET rules`, `PUT` / `DELETE ignored`, the outbox purge on an ignore |
| `ignore/IgnoredAppsActivity.kt`, `ignore/IgnoreDialogs.kt` | The "Ignored apps" screen (the week's apps + every ignored one, Ignore / Stop ignoring); the one-line confirmations it shares with the review |
| `util/InstalledApps.kt` | Labels, icons, launcher entries and the home apps from `PackageManager`, within what `<queries>` makes visible |
| `worker/UsageSyncWorker.kt`, `TimeOrganizerApp.kt` | WorkManager entry point; periodic + "Sync Now" scheduling |
| `LoginActivity.kt`, `MainActivity.kt` | Password or Google sign-in, then 2FA; status screen (red banner while usage access is off, amber while setup is unfinished) |
| `auth/GoogleSignIn.kt` | Credential Manager's "Sign in with Google" → the ID token the server verifies |
| `SettingsActivity.kt` | The set-once things: usage access, battery-optimization exemption, notification permission — a check and "Manage" (the system page; an app can't revoke these itself) once granted. Opens by itself on first run |

`app/schemas/` holds Room's exported schemas, one folder per database (`outbox.db`, `loops.db` since prompt 12, `capture.db` since 18). Commit
them with any entity change and write a real migration (its SQL copied from the exported `createSql`, as prompt 14's
`MIGRATION_1_2` in `outbox.db` and `loops.db`, 15's `MIGRATION_2_3` and 17's `MIGRATION_3_4` in `outbox.db` are). No test runs a migration: the JVM tests can't open Room, and there are
no instrumented tests. **The first build of a new `@Database`** — or of a new schema version — can fail
`kspReleaseKotlin` with `JsonDecodingException: Expected start of the object '{', but had 'EOF'`: the debug and release KSP
tasks race to write its new schema file. The file is fine once one of them has; run the build again.

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
- **Which servers a build talks to is its build type** (decision 25-1): **debug = the test environment, release =
  production**, set by one `environment(serverRoot, webAppRoot, googleServerClientId)` call per build type in
  `app/build.gradle.kts`. It writes `BuildConfig.SERVER_ROOT` / `WEB_APP_ROOT` / `GOOGLE_SERVER_CLIENT_ID` (read through
  `Constants`; `API_BASE_URL` and `HEALTH_URL` are derived) and generates the manifest's `asset_statements` string from
  the web app's root, so the four can't drift apart. Nothing is switched by hand for a release.
  - Test: `https://test.backend.adhdtimeorganizer.com/`, `https://test.adhdtimeorganizer.com/`, the test server's
    Google web client id. Production: `https://backend.adhdtimeorganizer.com/`, `https://adhdtimeorganizer.com/`, and
    **a blank Google client id** (the button hidden) until the production server's `OAUTH2_GOOGLE_CLIENT_ID` is filled
    into the release block.
  - **A local server**: the commented `environment(…10.0.2.2:8081…)` line in the debug block, used instead of the line
    above it. The debug build allows cleartext to it (`src/debug/res/xml/network_security_config.xml`), release does not.
  - `WEB_APP_ROOT` is the web app's own origin — the API is not the web app. The drift nudge's "Switch plan" opens its
    `day-planner`, the start ramp its `?track=` link.
  - `scripts/test-release.ps1` prints the server the debug build was generated with (its `BuildConfig.java`) and warns
    if it is production; `scripts/prod-release.ps1` refuses a release whose generated values aren't production's.

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
- **Release signing.** The release build is signed with the owner's key, kept outside the repo. `app/build.gradle.kts`
  reads its path, alias and passwords from the gitignored `keystore.properties` at the repo root (`storeFile`,
  `storePassword`, `keyAlias`, `keyPassword`; forward slashes in the path, a backslash is an escape there), or from
  `RELEASE_STORE_FILE` / `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD`. Without them, or with a
  path that doesn't exist (Gradle warns), the release comes out unsigned (`app-release-unsigned.apk`) and every other
  build is unaffected. **Never commit the key, its passwords or its path.** **Losing the key, or signing with a
  different one, means every installed copy must be uninstalled to update** — and an uninstall deletes the outbox —
  so a copy of the `.jks` lives off this PC. A new signing key also needs its own Android OAuth client and its SHA-256
  in the web's `assetlinks.json` (*Auth*). The owner's phone stays on debug builds against the test server
  (`test-release`); the release build is not installed on it (decision 25).
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
- **Each session also carries the app's declared category** (`appCategory`, the server's member name from
  `mapping/AppCategory`, read by `InstalledApps.appCategory` when the batch is **sent**, not stored in the outbox), and
  so does the ignore call (`IgnorePackageRequest.appCategory`): an ignored app is never synced again. Only the phone can
  read `ApplicationInfo.category`. The server keeps the last one per package (`AndroidReportedApp`) for the web's
  apps-by-category view, and applies no link with it; the picker filter stays the phone's. `null` is left out by Gson and
  read there as "declares none". So is an app uninstalled between its use and the send. A name the server doesn't know
  costs only that report, never the batch. A new Android `CATEGORY_*` goes into the server's enum first.
- **Package names are identity.** Users' mapping rules match on them; never rewrite one.
- **Every request names the build** (prompt 25): `X-Client-Version: android/<versionName> (<versionCode>)`, added by
  `RetrofitClient`'s first interceptor on `baseClient`, so login, refresh and sync all carry it. The server keeps the last
  one per source on `TrackerSourceStatus.ClientVersion` (stamped with `LastSeenAt`, so at most hourly) and calls a silent
  phone "out of date" below `TrackerMinimumClientVersion:Android`. The status screen's quiet line shows the same build and
  the server's host: `1.4.0 (7) · test.backend.adhdtimeorganizer.com`.
- **Pickups and loops ride the sync (prompt 14)** as two optional arrays (`pickups: [{startUtc, endUtc, unlocked}]`, `loops:
  [{startUtc, endUtc, rule, pickups, apps, answer}]`, rule / answer by the server's member names, `LoopRule.wire` /
  `LoopAnswer.wire`), in requests of their own with `sessions: []`, sent after every session is (`SyncEngine.sendExtras`,
  up to `SyncLimits.MAX_EXTRAS_PER_REQUEST`). A row leaves the "unsent" queue only on an **acknowledgement**: the answer's
  `pickupsStored` / `loopsStored` (`SyncWire.acknowledged`). A server from before 14 ignores the arrays and answers 200
  without them, so nothing is marked and the rest of the pass isn't sent to it; the rows wait for an updated server, 30
  days at most. Repeats are the server's `(user, device, start)`, skipped: re-sending is safe. A batch refused down to one
  row drops that row, as for sessions. Deploy order doesn't matter. The status screen's "sent" still means sessions.
- **Interruptions and derailments ride the sync too (prompt 15)**: `interruptions: [{atUtc, packageName}]` and `derailments:
  [{packageName, notifiedUtc, startUtc, endUtc, endedBy}]` (`DerailmentEnd.wire`: `Returned` / `ScreenOff` / `Capped`), **in
  requests of their own after the pickups-and-loops group** (`SyncEngine.sendExtraGroup`, up to
  `SyncLimits.MAX_NOTIFICATION_ROWS_PER_REQUEST`), acknowledged by `interruptionsStored` / `derailmentsStored`. Separate
  because the acknowledgement is per kind: a server from 14 acknowledges pickups and not these, and must keep taking pickups.
  A server that ignored pickups isn't sent these at all. Repeats: `(user, device, at, package)` / `(user, device, start)`.
  **Only the package and the instant, never the notification's text, title or channel** (decision 15-5; the log has no
  text, and the channel id is blanked for this app anyway). The server drops an ignored package's rows too and purges them
  on an ignore.
- **The sync, rules, ignore, mapping, `/now`, capture and budget answers** (`activities`, `activityCreated`, `problem`, `distraction`,
  `appCategoryLink`, `now`, `captured`, `budgets`, `budgetClaimed`), **and the requests the phone writes** (`syncRequest`, `ignoreRequest`,
  `startBlockRequest`, `captureRequest`, `budgetClaimRequest`, which `WireSampleTest` must write exactly), **are pinned by `app/src/test/resources/AndroidTrackerWire.samples.json`**, a
  byte-for-byte copy of the server's `AdhdTimeOrganizer.Tracking/application/dto/response/android/` file.
  `WireSampleTest` reads it through the DTOs; the server's `AndroidTrackerWireSampleTests` checks its live routes
  against it and that this copy is identical (it finds the copy by name anywhere in this checkout — keep exactly one).
  A renamed field reads as a default and nothing complains: change the file there, copy it here, follow it in the DTOs.

### Sessions and the cursor
- All the accounting lives in `SessionBuilder`, which has no clock and no Android types. Keep it that way.
- A session is an **app**, from its first resumed activity until the last one pauses — not an activity. A
  return to the same app within 2 s with nothing else in between is the same sitting.
- Screen off, keyguard and shutdown close every session; after a reboot an open session ends at the last event
  before it. Screen on and unlock (`EventKind.SCREEN_ON` / `UNLOCKED`, read since prompt 12 for `Pickups`) change no
  session: one starts with an app's resume, not with the screen (`PickupsTest.theSessionBuilder_ignoresScreenOnsAndUnlocks`). **Split-screen counts both visible apps; picture-in-picture counts nothing.** Both deliberate.
- **A stop with no pause before it counts as the pause.** Samsung's Recents sends the app it leaves straight to
  `ACTIVITY_STOPPED` and never `ACTIVITY_PAUSED`; counting only pauses left that app "open" until the screen went
  off (a 22 s sitting stored as 125 s). A stop that follows its own pause is ignored (`SessionBuilder.onStopped`).
  `getInstanceId` is hidden API, so the match is by activity class, with a per-session count of pauses awaiting
  their stop.
- **The cursor rests at the start of the earliest still-open session**, never at "the latest event": the usage
  log's begin is inclusive, so the next read sees the resume again and builds the session whole. Moving it to
  the latest event of any kind lost the app in use at every sync.
- **Pickups have a cursor of their own** (`sync_cursor.pickupCursorMs`, prompt 14), at the start of the earliest pickup still
  going: `Pickups` leaves out a pickup it sees no start of, and the sessions' cursor rests at an app's resume — after the
  screen came on. The collector reads the log once from the earlier cursor; `SessionBuilder` gets only the events from its
  own. A pickup that ended under 2 s before the read's end is not settled yet (Xiaomi's keyguard shown → hidden), so it is
  read again. Re-reading in pieces gives what one long read gives (`PickupCounterTest`). Every pickup is sent, glances
  included, with no 5 s floor: a 3 s glance at the clock is exactly what is counted. On a phone that never logged an unlock
  (no lock screen, `LoopPreferences.sawUnlock`, shared with the loop check and set by the sync's read too), a pickup that
  brought an app other than the home screen or this one to the front counts as unlocked. Sent pickups are pruned once the
  pickup cursor passes them, unsent ones after 30 days; an account switch drops them with the sessions.
- **Notifications have a cursor of their own** (`sync_cursor.derailmentCursorMs`, prompt 15), and the read reaches
  `DerailmentLinker.LOOKBACK_MS` (3 h) before it, so the app in front when a notification came is known. The event is
  `NOTIFICATION_INTERRUPTION` (12) — **`@SystemApi`, so the SDK hides the constant** (`UsageStatsCollector` names it
  itself), but it reaches an app with usage access with only its channel id blanked; **not yet seen on a device**. What
  counts (`DerailmentLinker`, pure, `DerailmentLinkerTest`):
  - **Every interruption is kept**, as soon as it is read, except this app's own nudges and the ignored apps'.
  - **A derailment** is the notifying app coming to the front within 60 s — not when it was in front already, not a pickup
    that didn't open it. It **ends** when the app in front at the notification is in front again (`Returned`), the screen
    goes off (`ScreenOff`), or 2 h after it opened (`Capped`). The home screen and this app are nothing to go back to.
  - **One at a time:** a notification whose app opens inside a derailment already going (a burst, Slack while still in
    WhatsApp's) starts none; the first one owns the stretch. Its length is phone time from the opening.
  - **The cursor** rests at the earliest notification still undecided (its minute not up, or its derailment still going) —
    and never inside a settled derailment a later notification depends on, or that one would be read again without it and
    start a derailment of its own. Re-reading in pieces gives what one long read gives.
  Sent rows are pruned once the cursor passes their notification, unsent ones after 30 days; an ignore deletes an app's
  unsent ones (`OutboxDao.deleteUnsentOf`); an account switch drops them with the sessions.
- Sessions under 5 s are not queued (client policy, `SyncLimits.MIN_SESSION_MS`).
- **The home screen and Recents are not sent.** They are the launcher's activities, so the log counts them as an
  app in front (a 6 s `com.sec.android.app.launcher` row between every two apps). `UsageStatsCollector.collect`
  drops every installed home app's sessions after the build (`BuildResult.forOutbox`, which drops the ignored apps
  too); the builder still sees their events, so the merge rule treats the launcher as "another app in between".
  Earlier rows already on the server stay.

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
- **"Sign in with Google" is native (prompt 23, 2026-10-29)**, under the password form: Credential Manager's
  `GetSignInWithGoogleOption` → an ID token → `POST auth/tracker/login/google` (`GoogleLoginRequest`: the token and the
  phone's IANA zone, used only if this creates the account — an unknown Google address registers, as on the web). The
  answer is the password login's: the token pair, or the 2FA hand-off to the same code step. The server accepts the token
  only when its `azp` is one of our Android OAuth clients (`OAUTH2_GOOGLE_ANDROID_CLIENT_IDS`), and always issues the
  phone's session for it. **Setup outside the code:** an Android OAuth client in the server's Google Cloud project for
  each signing key (package + SHA-1; Google refuses a build whose key has none), its id in the server's env var, and the
  server's web client id as the build type's `googleServerClientId` in `app/build.gradle.kts` (blank hides the button).
- **Google Password Manager offers the web's saved sign-ins** through a Digital Asset Link: the email and password
  fields carry `autofillHints`, the manifest's `asset_statements` (generated per build type from `WEB_APP_ROOT` by
  `environment(…)`) points at the web app's `/.well-known/assetlinks.json`, a static file in the
  frontend repo's `public/` listing this app's signing-key SHA-256s. **A new signing key (the release key, prompt 25)
  goes into that file and gets its own Android OAuth client**, or both features stop for builds signed with it.

### Notifications
- **One channel per purpose** (`Notifications.createChannels`, created on every app start): "Sync problems", the
  watcher's silent "Focus watch", the "Evening review" question, the high-importance "Breaks" nudge, the
  high-importance, vibrating, soundless "Time chime", "Loop check", "Drift nudge", "Start ramp" and "Daily limits", "Phone away"
  (default importance, the default sound), and the silent, low "Tracking paused" today. A channel's sound and vibration are fixed once it exists:
  getting them wrong means a new channel id.
  A feature that notifies adds its own channel and its own id, so people can mute nudges without losing the
  sync warnings. Never post on another feature's channel.
- **`POST_NOTIFICATIONS` (API 33+) is asked for only from the settings screen's Notifications card**, which says
  in one line what it is for — never as a system dialog at launch. The settings screen opens by itself once, on the
  first open of the status screen after install; after that, the gear and the status screen's amber setup banner
  (shown while the battery exemption or notifications are missing) lead to it. Once the system stops showing its
  dialog, the card opens the app's notification settings instead. Every post goes through `Notifications.post`, which checks the
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
  them in `TimeOrganizerApp.onCreate` so a restarted process has them too. `untilMs = ForegroundWatcher.INDEFINITELY`
  watches until `stop` (the time chime, the loop check); the ongoing notification then shows no end time.
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
- The notification ("Focus watch on until 14:30 · Notices which app is in front so nudges arrive on
  time. Records nothing.") is on the silent "Focus watch" channel. Without `POST_NOTIFICATIONS`, or with the channel
  off, it is hidden but the service still runs. **It is not ongoing** (since 1.2.1, the owner: "a bit annoying"): on
  Android 13+ a swipe hides it and the service runs on; it is posted again only when its line changes (a new end time),
  so a reason with no end (the chime, the loop check) never brings it back. **The channel stays low importance**: on a
  minimum one Android raises a foreground service's notification back to low and adds its own "running in the
  background" notice. Only the user can minimise or turn off the channel; the Time chime and Loop check cards have
  **Hide "Focus watch on"**, which opens its system page.

### `/now` — what every nudge asks first
- **`GET tracker/android/now`** is the server's view of right now: `silentReason` (an away day, quiet hours, absent — any
  value, an unknown one included, means silent), the open blocks covering now and the next (each with its `activityId`,
  read since prompt 08, and `isOptional` / `isCritical` since prompt 10), whether a timer runs (`timerRunning`, prompt 10;
  missing on an older server = unknown), the desk's last active minute
  (`lastActiveAt`), `isIdle` and what its last five minutes resolved to (`recent`, busiest first, each with `onPlan`), the
  distraction packages, and the user's next morning (`nextWakeUpAt`, prompt 17: the planner day's wake-up, else their
  default). The phone decides nothing the server decides there: no matcher, no planner, no idle rule.
- **Read through `NowClient` and `NowFacts`, nowhere else.** One answer is reused for 30 s (the server's `max-age`), then
  asked for again with its `ETag`: an unchanged day is a `304`. In memory only, cleared on an account switch. An unreadable
  field is absent, never guessed (an unparsable block is no plan). Pinned by the `now` entry of the wire sample
  (`NowFactsTest`).
- **The distraction packages are also kept in prefs** (`KeptDistractions`, since prompt 08; the chime kept its own copy
  before, taken over on the first read), so a restart offline still knows them. Refreshed after each sync while the chime
  is on, and by every drift evaluation that got an answer; cleared on an account switch.

### Break nudge (prompt 09, 2026-10-27)
- **What it is:** the desk goes quiet, the phone comes out, and once the break has run its length — *"Break's been 25 m.
  Thesis is still open on your desktop."* (or *"Email is planned until 11:30."*) — with **Back to it**, **5 more minutes**,
  **I'm done for now**. Its own channel (`breaks`, high importance so it peeks over the app in front) and id. On by default;
  the status screen's "Breaks" card has the switch and the length: 5 / 10 / 15 / 20 / **30**, default 10.
- **All of it is `BreakPolicy`** (pure, `BreakPolicyTest`). A break **starts** when `/now` says the desk is idle, the phone
  was in use no later than one break length after the desk's last minute (from 2 min before it, so a phone picked up at the
  desk counts), and there is something to come back to: the desk's busiest named work (unmapped and ignored time has no
  name), or a block covering now that had **begun before the desk stopped** (a lunch block that starts as the desk stops is
  not a break from anything). It is **counted from the desk's last minute**. The name is the desk's work when it was on the
  plan or nothing is planned, else the block's. A desk quiet for 2 h is not a break.
- **One nudge**, at the length, only while the phone is in use — a phone put down hears it at its next pickup, with the
  real length. Silenced it **waits**, never drops: the server's `silentReason`, a call (`AudioManager.mode`: in call,
  in communication, ringing, call screening) or any DND filter (`currentInterruptionFilter`); none needs a permission.
  Without an answer from the server nothing starts, ends or is posted.
- **The end** clears the nudge: the desk used again (a newer `lastActiveAt` — the end the owner asked for), the block a
  *planned* break names ends, or 2 h. **Back to it** and a swipe end the nudging for this break; **5 more minutes** is one
  more nudge 5 min on; **I'm done for now** is silent until another block is current — or, with no block, until the desk is
  used again — and a release never reopens on a pickup from before it. It lapses after 12 h whatever happens.
- **Phone use comes from the usage log**, through `SessionBuilder` like the sessions (`PhoneUse.from`), read from an hour
  before the desk's last minute: exact, survives a restart, works without the watcher. The watcher's own `inFront` counts
  as in use too (its first read reaches 6 h back).
- **When it runs (`BreakMonitor`):** after every sync run (the worker), on a pickup the watcher sees (an app to the front,
  at most every 20 s), on a button, and when the policy asks to look again (each minute while the phone is in use or a
  nudge is up) — that last one is an in-process delay, so it lives while the watcher's service keeps the process; the next
  sync catches up otherwise. **The watcher is armed** with reason `break` through any block covering now, for 30 min after
  desk use in the last 15 min, and while a break is open; an evaluation with no answer from the server leaves it as it was.
  Off, signed out, or nothing to post with (permission, app notifications or the channel off): no break, no watch.
- **Precision is the server's minute.** `isIdle` turns true 2–4 min after the user stands up (`DesktopIdleRule`); a phone
  out before then is looked at again a minute later. Idle, a shut laptop and a stopped desktop tracker read the same — so a
  phone used beside a dead desktop tracker can be nudged; without one, the desk is never recently active and nothing runs.
- **Never logged:** the subject is the user's activity name. It is kept in `break_nudge` prefs while a break is open, and
  an account switch resets it (`SyncEngine.onSignedIn`).

### Drift nudge (prompt 08, 2026-10-28)
- **What it is:** during a planned block, once a distraction app has been in front for 30 s — *"Planned: Thesis until
  11:30 · Instagram is open."* — with **Back to it** (the home screen), **Switch plan** (the web app's `day-planner`) and
  **5 more minutes**. Its own channel (`drift`: high importance, the chime's short vibration, no sound) and id. **Off by
  default**; the status screen's "Drift nudge" card has the switch.
- **All of it is `DriftPolicy`** (pure, `DriftPolicyTest`). **What counts** is `/now`'s distraction packages, less any
  whose rule maps it to the block's **own** activity (a YouTube-lectures block; the rules are the cached `GET rules`, exact
  packages). Unmapped apps and apps mapped to another activity never count: the phone has no per-minute "on plan" verdict
  like the desktop's, and unmapped time is neither on nor off the plan. **The grace:** time in front of any of them adds
  up; out of them for up to a minute pauses the count, longer starts it over; only time inside the block counts.
  Split-screen overlap once.
- **One nudge per block.** Back to it, Switch plan and a swipe end it for the block; 5 more minutes asks once more 5 min
  later if a distraction app is in front then (or once one has been for the grace again), and may be asked again. A new
  block starts everything again and withdraws the old block's nudge; the block ending withdraws it too.
- **Held, never dropped:** the server's `silentReason`, a call, DND, **car mode** (`PhoneSilence.driving`, Android Auto
  or a car dock — no permission), the loop check's "Phone away" timer (12-5's "nothing said on a pickup during it"), and
  another kind of nudge in the last 10 minutes (`NudgeGap`, below). A held nudge is said once the hold is over, if a
  distraction app is still in front. Without an answer from the server nothing starts.
- **The two buttons that leave the app in front** go through `DriftActionActivity` (`Theme.NoDisplay`, its own empty task,
  finishes in `onCreate`): since Android 12 a notification's broadcast receiver may not start an activity. "5 more minutes"
  and the swipe go to `DriftActionReceiver`.
- **When it runs (`DriftMonitor`):** a distraction app coming to the front (the watcher), the look-again the policy asks for
  (the grace running out, a hold or snooze ending, the block ending, the next block starting — an in-process delay), after
  each sync, a button, the switch, and once per process start. **The watcher** (reason `drift`) runs through every block
  covering now that isn't settled yet, and through the next block if it starts within 20 min — nothing wakes the phone at
  a block's start, so the sync before arms it. Its "Focus watch on until 11:30" notification shows meanwhile.
- **State** (`drift_nudge` prefs): the block's id and end, settled, snoozed until, showing — no names. An account switch
  resets it. Package and activity names are never logged.

### Start ramp (prompt 10, 2026-10-28)
- **What it is:** at a planned block's start, one notification — *"Thesis starts now · 1 h 30 min, until 11:30. Start the
  timer?"* (or *"Thesis started 20 min ago"*) — with **Start now**, **In 2 min** and **Not this one**. Its own channel
  (`start_ramp`: high importance, the chime's short vibration, no sound) and id 9. **On by default**; the status screen's
  "Start ramp" card has the switch and **which blocks**: *Every block* / *All but Optional* (default) / *Critical only*, from
  the planner's own importance (`/now`'s `isOptional` / `isCritical`, server-computed).
- **All of it is `StartRampPolicy`** (pure, `StartRampPolicyTest`), the desktop tracker's `StartRampPolicy` for the phone.
  **Late** it says how late, only while 10 min of the block are left (up to 3 min late always). **Settled without a word**
  when the block is already started, when `/now` says the desk is in use (the desktop's own ramp asks there), or too late.
  **Held, never dropped:** a running countdown or pomodoro (`/now`'s `timerRunning`; missing on an older server = unknown,
  not held), the server's silence, a call, DND, car mode, the "Phone away" timer, another nudge in the last 10 min.
- **The buttons.** **Start now** (`StartRampActionActivity`, unseen) posts `tracker/android/start-block` (the block goes in
  progress in the planner) and opens `WEB_APP_ROOT?track=<plannerTaskId>`, where the web app starts the countdown for the
  rest of the block (`useTrackLink`; the desktop opens the same). **In 2 min** asks exactly once more, without that button.
  **Not this one** also keeps the drift nudge quiet in that block (`DriftMonitor.quietBlock` → `DriftState.quietBlockId`,
  decision 10-6 — the one place one nudge writes another's state). The block is settled the moment the ramp is posted, so a
  swipe or no answer settles it too. Withdrawn when the block is started elsewhere, ends, or leaves the plan.
- **When it runs (`StartRampMonitor`):** an **alarm at the next block's start** (`setAndAllowWhileIdle`, inexact, no
  permission; re-planned after every evaluation from `/now`'s `next`; `StartRampActionReceiver.ACTION_WAKE`), an in-process
  look at the same moment (on time for a phone in use when the alarm is late), after each sync (catches a start missed while
  asleep, in the late form), on a pickup while a due ramp is held (the watcher, reason `ramp`, only then), a button, the
  card, and once per process start. Always **before the drift nudge** (`TimeOrganizerApp`, `UsageSyncWorker`).
- **State** (`start_ramp` prefs): the settled blocks as `id:end` pairs (forgotten a day after their end), the "In 2 min"
  block and time, the block on screen — no names. An account switch resets it and cancels the alarm.

### Daily limits (prompt 13, 2026-10-28)
- **What it is:** the daily limits the user set on the web (an activity's or a category's, P3-08), said on the phone while one
  of its apps is in front — *"Nearing your daily limit for YouTube · About 12 m left of the 1 h you set."* at 80 % (when the
  budget wants it), *"1 h of YouTube today · That's the daily limit you set."* at the limit. The server's own words: neutral,
  the user's number back, never "exceeded". No buttons, no tap target, no full-screen card (decision 13-2), nothing more on
  reopening the app past the limit (13-3). Its own channel (`daily_budget`: high importance, the chime's short vibration, no
  sound) and id 10, with the budget's id as the tag so an activity's and its category's limit can both show. **On by
  default** (making a budget is the opt-in, 13-4); the status screen's "Daily limits" card has the switch and each budget's
  "YouTube: 42 m of 1 h".
- **All of it is `BudgetTracker`** (pure, `BudgetTrackerTest`). **The estimate** is the server's `totalSeconds − thisPhoneSeconds`
  plus the phone's own time today in the budget's packages, from the usage log through `SessionBuilder` (the union of the
  sittings, split-screen once, sessions under the sync's 5 s floor left out). It is right whenever the last sync ran: a sync
  moves the same seconds into both server figures. **Packages are the server's** (`GET tracker/android/budgets`, exact,
  server-resolved: an activity budget's rules, a category budget's non-archived activities' rules); the phone matches nothing.
- **Who says it (decision 13-1): whoever sees the crossing first.** A due stage is claimed first
  (`POST tracker/android/budgets/{id}/claim`, `ClaimOutcome`): `claimed` → post, and the server never sends it; `false` → it
  was said (the server's push for desk time, another phone) → quiet; 404 → gone, quiet. **No answer → post anyway** and keep
  the claim in `pending_claims`; `UsageSyncWorker` sends them **before** `SyncEngine.run` (`BudgetMonitor.beforeSync`), so
  the sync carrying the crossing finds the mark set. A stage the figures report said (`warnedToday` / `reachedToday`) is never
  said here. **Only while an app of the budget is in front**: desk time reaching a limit is the server's, in the browser.
  Reaching the limit with the warning unsaid says only the limit, as the server does. After posting, a "Sync now" run is
  asked for (`TimeOrganizerApp.enqueueSyncNow`), so the web catches up.
- **Held, never dropped:** `/now`'s `silentReason` (asked only while a budgeted app is in front; unreachable is not silent), a
  call, DND, car mode, the "Phone away" timer, another nudge in the last 10 min (`NudgeFamily.BUDGET`). Looked at again when
  the hold ends (each minute for a call or silence), and said then if an app of the budget is still in front — otherwise the
  next sync's server push says it.
- **When it runs (`BudgetMonitor`):** a budgeted app coming to the front (the watcher), the next crossing while one is in front
  (an in-process delay to the moment the estimate reaches 80 % or the limit), after every sync (the figures read again), the
  card's switch, and once per process start. The figures are kept in prefs (`daily_budgets`) and read again when older than
  5 min or from another day; offline today's last figures still count. **The watcher** (reason `budget`, `INDEFINITELY`) runs
  while some budget with phone apps hasn't reached its limit today — the chime's and loop check's accepted cost — and is let
  go once all have, or the switch is off.
- **State:** the figures (with the user's activity names, like the break nudge's subject — never logged), the stages settled
  today, the unsent claims. An account switch clears all three and cancels what is showing (`SyncEngine.onSignedIn`); the
  switch stays.

### Nudges together (since prompt 08)
- **Kinds keep 10 minutes apart** (`NudgeGap`, decision 08-6, the desktop's `HyperfocusPolicy.MinGapBetweenKinds`): each of
  the chime, the loop check, the drift nudge, the start ramp and the daily limits records when it posted, and asks before
  posting whether another did within 10 min. **Held means each kind's own rule:** the drift nudge, the start ramp and the
  daily limits wait and are said after; the chime and the loop check **skip**, as they do for a call (a late "15 m" or "6 pick-ups" is wrong by then; the
  loop is still stored, `SILENCED`). **The break nudge is exempt**: it neither records nor asks.
- **The start ramp goes first** where several are evaluated together (a block's start doesn't come again, decision 10-8).
- Apart from that, the "Phone away" timer the drift nudge and the ramp read, and the ramp's **Not this one** quieting the
  drift nudge for its block, every nudge decides alone.

### Time chime (prompt 11, 2026-10-27)
- **What it is:** while you stay in the chosen apps, a short buzz and a heads-up that clears itself after 6 s — *"15 m on
  Reddit"* — at each interval: 5 / 10 / **15** / 20 / 30 min, the same interval every time (the growing number is the
  only escalation). No buttons, no tap target, no sound. Its own channel (`time_chime`) and id. **Off by default**; the
  status screen's "Time chime" card has the switch, the interval, "Choose apps" and "Vibration" (the channel's system
  page — there is no vibration-only mode and no in-app vibration switch).
- **All of it is `ChimeSchedule`** (pure, `ChimeScheduleTest`). **The chosen apps count as one stretch** (10 min of
  Reddit and 5 of Instagram is "15 m on Instagram", counted together with Reddit), split-screen overlap once. A gap out
  of them — another app, home, the screen off — **of up to 3 min pauses the count** and isn't counted; **longer starts
  over**. A chime only while one of them is in front. Missed marks are said once, for the latest. **A call or DND skips
  the chime** (`PhoneSilence`) — it is not saved for later, unlike the break nudge's — and so does another kind of nudge
  in the 10 minutes before (*Nudges together*). The server's quiet hours and away
  days do **not** mute it: it never asks the server at chime time.
- **Which apps (`ChimeChoices`):** "Chime my distraction apps" (on by default) follows the server's distraction packages
  (`/now`'s `distractionPackages`, exact names, kept in `KeptDistractions` so a restart offline still knows them, refreshed
  after each sync while on and when the screen opens), **plus** apps ticked by hand, **minus** distraction apps unticked
  by hand — an untick holds even when the server lists the app again. The screen is the week's apps (as "Ignored apps"
  lists them), distraction ones first; below, chosen apps on this phone not used this week. A distraction package not
  installed here (flagged from another device) is not shown or counted.
- **Phone use comes from the usage log**, through `SessionBuilder` (`ChimeSchedule.sittings`, 6 h back), like the break
  nudge: exact, survives a restart. The state kept is only the stretch's start and the time already chimed for.
- **The watcher runs the whole time the chime is on** (reason `chime`, `INDEFINITELY`), so the first chime lands on time;
  the cost is the silent "Focus watch on" notification all day. Armed again on every process start and after every
  sync (so a reboot or force-stop is undone); let go when the chime is off, signed out, nothing is chosen, or it has
  nothing to post with. `ChimeMonitor` runs on a chosen app coming to the front, at the next mark (an in-process delay,
  alive while the watcher's service keeps the process), after each sync and on a setting change.
- **Battery, estimated, not measured:** the chime adds no polling of its own — it is the watcher's ~0.1–0.3 mWh per
  screen-on hour (under 0.01 % of a ~17 Wh battery), now for every screen-on hour instead of only blocks and breaks, plus
  one read of 6 h of the log per chosen app coming to the front or per chime (milliseconds). Nothing runs with the
  screen off.
- **An account switch** clears the kept distraction list and the stretch; the ticks stay, they are this phone's.
  Package names are never logged.

### Loop check (prompt 12, 2026-10-27)
- **What it is:** one gentle check-in when the phone is being picked up in a loop — *"6 pick-ups in 10 minutes · Want to
  put the phone away for 20?"* (or *"6 apps in 5 minutes"*) — with **Put it away**, **I'm fine**, **Not today**. It knows
  nothing about what any app is (activity mapping stays the server's): only how the phone is used. Its own channel
  (`loop_check`: high importance so it peeks over the app, the chime's short vibration, no sound) and id. **Off by
  default**; the status screen's "Loop check" card has the switch, the away timer's length (10 / **20** / 30 min), "N loops
  today" (or "Phone away until 14:30") and "Apps that don't count".
- **The rules are `LoopDetector`** (pure, `LoopDetectorTest`, realistic event sequences), over a window ending now:
  **6 unlocks in 10 min** (`KEYGUARD_HIDDEN`; no length rule — six in ten minutes are short already), or **6 different
  apps in 5 min** in a run of sittings each ≤ 60 s going back from now (a longer one ends the run: that app was used for
  something). **Only unlocks are pickups**, so an alarm snoozed over the lock screen, a call (the proximity sensor turns the
  screen off and on), navigation showing over the lock screen and a glance at the clock never count. A phone with **no lock
  screen** never logs an unlock: until one has been seen (`LoopPreferences.sawUnlock`), a screen-on that brought an app to
  the front counts instead. The home screen, this app and apps with no launcher entry are never a loop's app; unlocking
  only to look at the home screen still counts as a pickup.
- **A chat looks exactly like a loop in the log** (six quick replies in ten minutes). **"Apps that don't count"** (ticked
  by hand, nothing ticked by default, this phone's — an account switch keeps them) fixes it: a pickup that opens only
  ticked apps is no pickup, and a hop to one is no hop (it still stays in the run). A pickup that also reaches a counted app
  counts.
- **One check-in per loop (`LoopPolicy`, `LoopPolicyTest`).** A loop begins when a rule holds and none is going on, goes on
  while one keeps holding, and **ends 15 min after the last time one did**; the next loop gets its own check-in, and nothing
  from before the last one's end counts. **A call or DND** when a loop begins skips its check-in (`PhoneSilence`) — not
  postponed, like the chime — and so does another kind of nudge in the 10 minutes before (*Nudges together*). The server's quiet hours and away days don't mute it (as 11-8): bedtime scrolling is the case.
- **The buttons.** **I'm fine** (and a swipe): silent until the loop ends. **Not today**: the loop ends, silent until the
  next local midnight; unlocks from before midnight don't count after it. **Put it away**: the loop ends and the **away
  timer** runs — no check-in meanwhile, nothing said if the phone is picked up during it, and when it is up *"20 min away is
  up"* on the `phone_away` channel **with the default sound** (the phone is away). It is an inexact
  `AlarmManager.setAndAllowWhileIdle` alarm: no permission (an exact one needs "Alarms & reminders" on Android 14), may come
  a little late in Doze, never early. A reboot clears the alarm; the timer is then let go silently 30 min after its end.
  Named "Phone away", never "chime": the time chime is another feature.
- **Phone use comes from the usage log**, through `SessionBuilder` (`LoopDetector.sittings`) and `Pickups`, 1 h back. The
  watcher is only the cue: **it runs the whole time the check is on** (reason `loop`, `INDEFINITELY`, as 11-4), and any app
  coming to the front — after an unlock that is at least the home screen — wakes `LoopMonitor`; changes arriving while a look
  is pending are one look. No look-again of its own: nothing changes without an app to the front, and a loop that is over is
  closed at the next look or sync. Re-armed on every process start and after every sync; let go when off, signed out, or the
  channel / notifications are off.
- **Loops are stored on the phone** (`loops.db`, its own Room database; `PickupLoopEntity`): start, end, rule, the most
  pickups and apps counted, and the answer (`ASKED` / `AWAY` / `FINE` / `NOT_TODAY` / `SILENCED`) — **no package names**.
  30 days. **Sent with the sync since prompt 14** (`sentAtMs` once acknowledged; *The wire contract*), for the web's android
  dashboard. An account switch clears them and the state.
- **With the other nudges:** each posts on its own channel. Since prompt 08 the check, the chime and the drift nudge keep
  10 minutes apart (*Nudges together*), and the drift nudge stays quiet while the "Phone away" timer runs; otherwise none
  reads another's state. A loop during a break from the desk can still bring a break nudge (it is exempt from the gap).
- **Battery, estimated, not measured:** the watcher's ~0.1–0.3 mWh per screen-on hour (as the chime's, none extra when both
  are on), plus one read of an hour of the log per app coming to the front (milliseconds).

### Quick capture (prompt 18, 2026-10-28)
- **What it is:** park a stray thought in two taps — the **"Capture" Quick Settings tile**, the app icon's **long-press
  shortcut** (both open a one-field dialog over the app in front; Enter or Save stores it) or **"Save to Inbox" in the share
  sheet** (`ACTION_SEND`, `text/plain`; stored at once, no dialog). It becomes a to-do on the user's capture list, "Inbox"
  until they point it elsewhere. No notification and no channel: a toast says it is saved. The status screen's "Quick
  capture" card shows what is waiting, opens the field, and on Android 13+ asks the system to add the tile
  (`StatusBarManager.requestAddTileService`).
- **The server does all of it** (`POST tracker/android/capture`, `{ text, url? }`, `[AllowAndroidTracker]` only — decision
  18-2): which activity the text names (reused, an archived one restored, or made), the list, the priority, the name cut and
  the note. The phone sends words and a link and decides nothing else, as with mapping.
- **A share (`CaptureRules.shared`):** a browser sends the link as the text and the page title as the subject, so a bare
  link takes the subject as its words; otherwise the text is the thought and its first link also goes as `url` (the server
  keeps one copy in the note). Text is cut to the server's 10 000 characters, never through a surrogate pair.
- **Never lost.** Stored in `capture.db` (its own Room database, like `loops.db`) before anything else, then sent: by
  `CaptureSendWorker` (unique work, `KEEP`, needs a network — at once when online) and after every sync run. Oldest first,
  one request each. **Only a 2xx removes a row** (`added`, `alreadyOpen`, `reopened` all mean it is on the list; a resend is
  `alreadyOpen`). 400 / 413 / 422 marks it refused, keeps it, and goes on to the next; 401 and everything else (offline,
  5xx, 404, 429, 409 `noPriority`) keep it and end the pass. Signed out, nothing is sent; signing in sends it. **An account
  switch drops the queue** (`SyncEngine.onSignedIn`): those thoughts were for the other account's to-dos.
- **The text is the user's own words**: kept only until the server has it, never logged.

### Pause tracking (prompt 17, 2026-10-29)
- **What it is:** "Pause…" on the status screen's first card — **15 minutes**, **1 hour**, **Until morning (07:00)**, **Until I
  resume** — and nothing the phone is used for meanwhile is recorded or sent, in any app. While paused the card says "Paused
  until 15:00" and offers **Resume**, and a silent "Tracking paused until 15:00 · Resume" sits on its own low channel (`pause`,
  id 11): swipeable, cleared by itself at a timed pause's end (`setTimeoutAfter`), shown again once a day while "until I
  resume" runs (`PausePolicy.notice`). Starting a pause from a notification or a tile is prompt 19's tile.
- **What it takes out (`PauseClipper`, pure, `PauseClipperTest`; decision 17-3)**, applied in `UsageStatsCollector.collect`
  after the ignored apps' drop: **sessions are clipped at the pause's edges** (one spanning it becomes two; a re-read clips the
  same way, so a piece's start — the dedup key — is stable), **pickups, interruptions and derailments touching it are dropped
  whole**, and **no cursor moves for it**: the log is read past the pause as usual and pausing never builds a backlog. The
  loop check still asks during a pause, but a loop touching one is not stored or sent (`LoopMonitor.apply`). The daily limits'
  estimate leaves paused time out too (`BudgetMonitor.sittings`), so it agrees with what the server is sent.
- **Nudges run on (decision 17-1).** None records anything; Do Not Disturb quiets them all, and a pause that also silenced the
  drift nudge and the limits would be a one-tap way round them.
- **The server keeps no pause (decision 17-2)**: a paused stretch is a gap on the dashboards, like an unused phone; a "paused"
  marker would itself say something private happened then. Its one part: `/now`'s **`nextWakeUpAt`**, the next morning from the
  planner (the calendar day's wake-up, else the user's default). "Until morning" ends there (decision 17-4). The card asks `/now`
  each time it is shown and keeps the answer (`PausePreferences.keptWakeUpMs`); the dialog uses the kept one, so what the button
  said is what happens. Offline it is the kept morning moved on by whole days (wall clock, so DST keeps 06:30 at 06:30), never
  heard: 07:00 (`PausePolicy.morning`).
- **A paused phone still says it's alive (decision 17-5).** The server calls a phone silent after 48 h without a sync (P2-04)
  and a pause sends nothing, so a paused run that sent nothing posts an empty sync (`sessions: []`) at most every 6 h
  (`PausePolicy.heartbeatDue`, `SyncEngine.heartbeatIfPaused`); the server stamps the phone as seen on any sync.
- **Stored in `outbox.db`** (`pause_range`, version 4): `[startMs, endMs)`, "until I resume" ending at `Long.MAX_VALUE` until
  Resume writes the real end. Read by the sync after it takes its `nowMs`, so a pause started before the read is seen. Kept until
  every cursor has passed it and two days more (the limits read the whole day). **An account switch keeps it**: it is this
  phone's, and the new account's time in it is as private.

### Evening review of unmapped time
- **The server decides what is unmapped and worth asking** (`GET /tracker/android/unmapped`: today on
  the user's clock, resolved by `AndroidMappingRuleSet`; a package needs ≥ 5 min, at most 5, and under 15 min in all
  `apps` is empty). The phone repeats none of it — empty means say nothing. Mapping stays server-only: the phone
  sends a **package name and an activity** (`POST …/rules`, `{packageName, activityId, backfillDays}`) and the
  server builds the exact-package rule, so a phone token can never author a matcher.
- **The one activity picker** (`ActivityPicker`, the card's "Choose activity" and every picker on "Mapped apps"):
  `GET tracker/android/activities` (activities, roles, categories, the "Phone app" role id, the app-category links) is
  read once per screen. An app whose own category (`ApplicationInfo.category`; Digital Wellbeing's can't be read) is
  linked opens filtered to that role / category, with "Show all". "+ New activity" (`POST tracker/android/activities`,
  optionally mapping the package in the same call) takes an **existing** role ("Phone app" by default — the server
  makes it on the first create if it's missing) and an optional existing category: the phone never makes roles or
  categories. A name an active activity has → "use it?"; an archived one's → "restore it on the web" (the phone never
  un-archives).
- **Snooze** ("ask again in a week") is the phone's alone (`SnoozeState` in `ReviewPreferences`): the active ones go as
  `unmapped?exclude=` from the screen *and* the runner, so a snoozed app is out of the list and of the 15-minute
  threshold. Mapping or ignoring an app clears its snooze.
- **Ignore is the one ignore** (*Ignored apps* below): the card asks first, in the same line the "Ignored apps" screen
  uses, then goes through `IgnoreSync.ignore` — the phone stops recording the app at once and the server is asked by
  `PUT ignored`. The route's `isIgnored` is not used any more. Offline, the card says it is ignored on the phone and
  the server hears with the next sync.
- **Mapping pays off tonight.** The rule create also attributes the package's sessions already stored today to the
  activity (the sync only attributes sessions it newly stores), and `attributedSeconds` is what the card reports.
  "Also add this week's time" (off by default; a box on the card, in "+ New activity", and in "Add app"'s confirm)
  sends `backfillDays = 6`, the server's `AndroidRuleBackfill.MaxDays`: today and the six days before, only sessions
  no earlier rule resolved.

### Mapped apps
- **`MappedAppsActivity`**, from the status screen beside "Review now": one row per **activity** rule of `GET rules`
  (ignored apps are the "Ignored apps" screen's), by label; icon and label from `PackageManager`, else the server's
  last label. Each row: the activity (· category, · archived) and its effective distraction flag, the server's.
- **Change** re-points the rule (`PATCH rules/{id}`), **Unlink** switches it off (`DELETE rules/{id}`; the app comes
  back in the evening review). Both apply from now on and say so: **time already in History stays where it is** —
  History rows carry no source, so there is nothing to move.
- **Distraction** sets the *activity's* own flag (`PATCH activities/{id}/distraction`: follow / yes / no). When
  other apps map to the same activity, it says so first ("also applies to Reddit, Instagram and 2 more"). Category
  flags stay a web job.
- **Add app**: the week's apps (as the "Ignored apps" screen lists them) minus the mapped, the ignored, the home apps
  and this app → the picker → "Map X to Y?" with the backfill box → `POST rules`.
- **App categories** edits the links (`PUT app-category-links/{appCategory}`; both "any" deletes one). A link only
  filters the picker and fills a new activity's defaults; it never attributes anything.
- A `404` body's `reason` tells `ruleGone` (another device unlinked or ignored it: reload) from `activityGone`; a
  `409`'s tells `nameTaken` / `nameArchived` / `alreadyMapped` (`MappingOutcome`). Every answer reloads the rules.
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

### Ignored apps
- **One kind of ignore** (decided 2026-10-22): the server's ordinary exact-package ignore rule. There is no phone-only
  ignore. Since 26a the server neither stores an ignored package's sessions (the sync's `ignored` count) nor keeps the
  ones it had (`PUT` purges them) — that is the failsafe, and what makes an ignore survive a reinstall. The phone's
  part is not to upload them at all.
- **The phone holds exact package names from the server, never a matcher.** `GET tracker/android/rules` is every
  active rule (`{packageName, appLabel, target: ignored | activity, targetName, ruleId, activityId, …}`); the ignored
  set is the `ignored` ones. Since prompt 27 an android rule has no role / category target. A cache written before it
  has no `ruleId` and reads as `0`; such a row isn't offered on "Mapped apps" until the next fetch replaces it. Cached with its `ETag` in prefs (`IgnoreStore`, not Room: two small blobs), refreshed at the
  start of every `SyncEngine.run` while signed in, before the usage log is read; a `304` keeps it.
- **What the phone drops** (`IgnoreState.ignored`): `server ignored ∪ pending ignores − pending stops`. A pending op
  is `(package, IGNORE | UNIGNORE, sinceMs)`. It clears when its call succeeds (the cached rules are patched at once,
  and the `ETag` dropped, so the package doesn't fall out of the set until the next fetch) or when a fetched list
  agrees. **A pending ignore older than 7 days stops counting** — the phone uploads the app again — and so does any op
  the server refused (400 / 422); both stay listed as "couldn't save" until retried or a fetch agrees. A failed call
  must not leave the phone dropping, forever, an app the server never heard about. A stop never expires: it already
  records the app.
- **The drop point** is `UsageStatsCollector.collect` → `BuildResult.forOutbox(home, ignored)`, after the build, like
  the home apps. **Rows queued before the phone knew** are deleted from the outbox: on every run before sending
  (`OutboxPurge` over `unsentPackages`, then `deleteUnsentOf`) and at once when the user ignores. Sent rows and the
  cursor need nothing.
- **Pending ops are sent by the sync run**, after the rules refresh. `IgnoreCallOutcome`: 2xx confirmed, 400 / 422
  rejected, 401 stops the loop (the normal auth path), **everything else keeps the op** — offline, 5xx, 429, the 409
  race on the server's unique index, and a 404 (a proxy, or a server from before 26a: deploy the server first). An
  ignore from the screen or the review also tries the call at once.
- **The "Ignored apps" screen** (`IgnoredAppsActivity`, from the status screen's card): the apps with ≥ 1 min in
  front in the last 7 days (`queryAndAggregateUsageStats`) and a launcher entry, minus home apps and this app,
  busiest first, top 30 then "Show all"; below them every ignored or pending app not in that list (unused lately or
  uninstalled, with the server's label), so every ignore can be undone. Each row: icon, label, time this week, what
  the rules make of it ("Mapped to Thesis", "Ignored", pending, couldn't save) and **Ignore** / **Stop ignoring**. No
  installed-app list is uploaded. Both ask first in one line (`IgnoreDialogs`); an ignore of a mapped app says the
  mapping stops.
- **An account switch clears the rules and the pending ops** with the outbox (`SyncEngine.onSignedIn`).
- **No `QUERY_ALL_PACKAGES`** (since 26c). Play restricts it to apps whose core purpose needs every package, and
  nothing here does: the manifest's `<queries>` for `MAIN`/`LAUNCHER` and `MAIN`/`HOME` make every app with a launcher
  entry and every home app visible, which is all the labels, icons, the screen and the home drop need. **The cost:**
  an app with no launcher entry (a separate in-call screen, a system dialog) is invisible, so its sessions go out with
  the package name as their label, and the screen never offers it (the evening review still can).

### Privacy and logging
- No email, name, password or token in logs. The OkHttp logger is `BASIC` in debug and off in release, with
  `Authorization` redacted. **Never `BODY`**: the login body is the password, auth bodies are tokens, a sync body
  is every app the person used.
- Package names and app labels are personal (they say what someone does on their phone); don't log them either. So is
  which apps notify and when (prompt 15): never logged, and a notification's text is never read at all.

### Device identity
- `deviceId` is kept once stored. New installs use `"android-" + ANDROID_ID` (stable across reinstall, scoped to
  the signing key since Android 8), so a reinstall does not re-send its backlog as a new device and double the
  dashboards. Changing an existing install's id re-keys its dedup — don't.

## Shell

This is a Windows machine: use PowerShell. Never round-trip a file through the shell
(`Get-Content | Set-Content` and the like); edit files with the editor tools. .NET file APIs called from
PowerShell resolve relative paths against the process directory, not `Set-Location` — use absolute paths.
