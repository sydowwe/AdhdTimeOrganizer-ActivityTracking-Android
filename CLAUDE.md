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
| `review/ReviewActivity.kt`, `review/SnoozeState.kt` | The review screen: one card per unmapped app — map to the suggestion, choose an activity, ignore, snooze, skip; **pure** snooze expiry |
| `mapping/MappingRules.kt`, `mapping/MappingOutcome.kt`, `mapping/MappedAppsList.kt` | **Pure.** App categories, the "+ New activity" rules (name check, defaults, the three-way flag), the picker's filter; a mapping call's answer → what the screen does; the "Mapped apps" rows and "Add app"'s list |
| `mapping/MappingCalls.kt`, `mapping/MappingDialogs.kt` | The calls and the problem body; the one `ActivityPicker`, `NewActivityDialog`, `AppCategoryLinksDialog`, `BackfillConfirm` |
| `mapping/MappedAppsActivity.kt` | "Mapped apps": change / unlink / distraction per mapping, "Add app", "App categories" |
| `ignore/IgnoredPackages.kt`, `ignore/IgnoredAppsList.kt` | **Pure.** `IgnoreState`: the server's rules + pending ignores → what the phone drops, every transition, the 7-day expiry; `IgnoreCallOutcome`; `OutboxPurge`; the screen's rows |
| `ignore/IgnoreStore.kt`, `ignore/IgnoreSync.kt` | The rules cache (body + ETag) and pending ops in prefs; `GET rules`, `PUT` / `DELETE ignored`, the outbox purge on an ignore |
| `ignore/IgnoredAppsActivity.kt`, `ignore/IgnoreDialogs.kt` | The "Ignored apps" screen (the week's apps + every ignored one, Ignore / Stop ignoring); the one-line confirmations it shares with the review |
| `util/InstalledApps.kt` | Labels, icons, launcher entries and the home apps from `PackageManager`, within what `<queries>` makes visible |
| `worker/UsageSyncWorker.kt`, `TimeOrganizerApp.kt` | WorkManager entry point; periodic + "Sync Now" scheduling |
| `LoginActivity.kt`, `MainActivity.kt` | Password + 2FA sign-in; status screen (red banner while usage access is off, amber while setup is unfinished) |
| `SettingsActivity.kt` | The set-once things: usage access, battery-optimization exemption, notification permission — a check and "Manage" (the system page; an app can't revoke these itself) once granted. Opens by itself on first run |

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
- **Each session also carries the app's declared category** (`appCategory`, the server's member name from
  `mapping/AppCategory`, read by `InstalledApps.appCategory` when the batch is **sent**, not stored in the outbox), and
  so does the ignore call (`IgnorePackageRequest.appCategory`): an ignored app is never synced again. Only the phone can
  read `ApplicationInfo.category`. The server keeps the last one per package (`AndroidReportedApp`) for the web's
  apps-by-category view, and applies no link with it; the picker filter stays the phone's. `null` is left out by Gson and
  read there as "declares none". So is an app uninstalled between its use and the send. A name the server doesn't know
  costs only that report, never the batch. A new Android `CATEGORY_*` goes into the server's enum first.
- **Package names are identity.** Users' mapping rules match on them; never rewrite one.
- **The sync, rules, ignore and mapping answers** (`activities`, `activityCreated`, `problem`, `distraction`,
  `appCategoryLink`), **and the two requests that carry a category** (`syncRequest`, `ignoreRequest`, which
  `WireSampleTest` must write exactly), **are pinned by `app/src/test/resources/AndroidTrackerWire.samples.json`**, a
  byte-for-byte copy of the server's `AdhdTimeOrganizer.Tracking/application/dto/response/android/` file.
  `WireSampleTest` reads it through the DTOs; the server's `AndroidTrackerWireSampleTests` checks its live routes
  against it and that this copy is identical (it finds the copy by name anywhere in this checkout — keep exactly one).
  A renamed field reads as a default and nothing complains: change the file there, copy it here, follow it in the DTOs.

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

### Notifications
- **One channel per purpose** (`Notifications.createChannels`, created on every app start): "Sync problems", the
  watcher's silent "Focus watch" and the "Evening review" question today.
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
- Package names and app labels are personal (they say what someone does on their phone); don't log them either.

### Device identity
- `deviceId` is kept once stored. New installs use `"android-" + ANDROID_ID` (stable across reinstall, scoped to
  the signing key since Android 8), so a reinstall does not re-send its backlog as a new device and double the
  dashboards. Changing an existing install's id re-keys its dedup — don't.

## Shell

This is a Windows machine: use PowerShell. Never round-trip a file through the shell
(`Get-Content | Set-Content` and the like); edit files with the editor tools. .NET file APIs called from
PowerShell resolve relative paths against the process directory, not `Set-Location` — use absolute paths.
