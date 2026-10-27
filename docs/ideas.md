# Android tracker — ideas

What could come next for the phone tracker, beyond the 2026-10-18 fixes (session building per app, the
open-session cursor, the Room outbox, server-rule mirroring, auth that survives a bad network, 2FA, the status
card). The desktop tracker's list is `../../RiderProjects/AdhdTimeOrganizer/review/ideas.md`; the ones that
carry over are restated here with what changes on a phone.

Each idea lists **why** it matters for an ADHD time organizer, **how** it could be built on what exists on both
sides, and the **catches** worth deciding up front. Rough order: value first.

Two constraints shape almost every idea below:

- **The phone does not know what an app *means*.** Mapping a package to an activity happens only on the server
  (`AndroidMappingRuleSet`). Any feature that needs "this is YouTube = Distraction" asks the server, or gets it
  pushed down — it never re-implements the matcher.
- **The phone has no push channel.** The server's notification pipeline delivers Web Push and email
  (`Sydowwe.Notifications`, `PushSubscription`); nothing reaches this app. Phone-side nudges are local
  notifications decided on the phone, or a new FCM channel on the server (a real project — see #10).

---

## 1. Focus-block nudge — "you planned Thesis until 11:30, Instagram is open"

**Why.** The planner knows what the user meant to do right now; the phone sees them open the app that derails
it. A phone is where drift happens fastest — one notification, one tap, twenty minutes. Catching it at the
moment of opening is worth far more than seeing it on tomorrow's dashboard.

**How.**
- **Server:** `GET /api/tracker/android/now` returning the planner block covering *now* (activity,
  end time) and the user's **distraction list resolved to packages**: every exact-package android rule whose
  target activity/category the user flagged as a distraction. Exact packages only — that is a set lookup, not a
  matcher, so the single-matcher rule holds. Pattern rules stay server-side; the phone simply doesn't nudge on
  them.
- **Phone:** fetch it at block start (WorkManager one-off scheduled at the block's start) and cache it. Detecting
  the open needs either polling `queryEvents` for the last few seconds every ~10 s while a block is active (a
  short-lived foreground service with a "Focus block running" notification — honest and allowed), or an
  `AccessibilityService` (instant, but a heavy permission that Play scrutinises — avoid).
- Show a local notification: *"Planned: Thesis (until 11:30). You opened Instagram."* Actions: **Back to it**
  (go home), **Switch plan** (deep link to the planner), **5 minutes** (snooze this package for the block).

**Catches.**
- Off by default; at most one nudge per package per block; never during a call (`AudioManager.mode`), never
  while driving (Android Auto projection), respect DND.
- The foreground service needs `FOREGROUND_SERVICE_SPECIAL_USE` (or `_DATA_SYNC`) on Android 14+ with a
  declared reason — decide which before building.
- Must not count time itself. The service only *watches*; sessions still come from the usage log.

## 2. App-time budgets on the phone — "45 min of TikTok today, 15 left"

**Why.** Budgets already exist server-side (P3-08, `ActivityDailyBudget`): a daily limit per activity or
category, 80 % warning and limit notification, fed by every source including this sync. But the phone syncs
every 15 minutes, so the "limit reached" push lands up to 15 minutes late — and as Web Push, not on the phone
where the scrolling is happening.

**How.**
- **Server:** `GET /api/activity-tracking/android/budgets` — for each budget, the exact packages that resolve to
  its activity/category (same exact-package restriction as #1), the limit, and today's total *excluding* the
  phone's not-yet-synced time (the server can't know it).
- **Phone:** add today's local, unsynced time per package from the outbox to the server's total, so the phone's
  number is ahead of the server's. Warn locally at 80 % and 100 %. Optional: a gentle full-screen "you're over"
  card when a budgeted app is opened past its limit (the same open detection as #1).
- **Sync sooner near a limit:** when a budgeted app has been foreground for a while, request an expedited sync so
  the server's budget notifications and the web dashboard catch up.

**Catches.**
- Double notification: the server also sends its budget push. Either the phone suppresses its own when it knows
  the user has Web Push on the phone's browser, or the server learns that the phone handles budgets for
  phone-only targets. Decide which one owns the notification.
- Overlaps Digital Wellbeing's app timers (see #8); ours count by *activity* across devices, theirs by app on one
  device. Say that in the UI.

## 3. Home-screen widget and Quick Settings tile

**Why.** The lowest-effort feedback loop on a phone. "Phone today: 2 h 10 m · Planned now: Thesis" next to the
clock keeps the data in sight without opening anything. A tile gives one-tap pause (#6) and "Sync now".

**How.**
- **Widget (Glance):** today's phone total from the outbox (local, instant), today's top 3 apps, the current
  planner block from the cached `/now` (#1). A widget's own update period is at least 30 minutes, so drive
  updates from the sync worker instead.
- **Tile (`TileService`):** state = tracking / paused; tap toggles pause; long-press opens the app.
- **Server (optional):** a unified "today across devices" number from the unified dashboard endpoints, so the
  widget can say "today: 6 h 40 m (all devices)".

**Catches.** Local totals cover this phone only — label them. Glance adds Compose to the build; fine, but it is
the first Compose in the project.

## 4. Unlock count and first-pickup time

**Why.** How often someone *picks up* the phone is a stronger compulsion signal than total minutes — 90 unlocks
of 30 seconds each is a different day from one long video. "First pickup at 06:12, before getting up" is a
morning-routine signal the morning briefing could use.

**How.**
- **Phone:** `KEYGUARD_HIDDEN` (18) is an unlock; `SCREEN_INTERACTIVE` (15) is a screen-on without unlock
  (checking the time). Count both per hour in `SessionBuilder`'s pass (it already walks every event — add a
  pure `PickupCounter` beside it) and store per-hour rows in the outbox DB. **Since 12 (2026-10-27) the reading exists:**
  `UsageStatsCollector` maps both (`EventKind.UNLOCKED` / `SCREEN_ON`) and the pure `usagestats/Pickups` turns them into
  pickups, each unlocked or not; a `PickupCounter` only bins them per hour (mind its rule: a read that starts mid-pickup
  leaves that pickup out). Send the loop check's stored loops in the same change (#16).
- **Server:** a new ingest shape — either a `pickups` array on the sync request (`{hourStartUtc, unlocks,
  screenOns}`), stored in a small `android_pickup_hour` table in the `tracking` schema with the same retention
  purge, or a nullable count on `TrackerSourceStatus`-style daily rows. The android focus-metrics dashboard gets
  a pickups line; the morning briefing can say "first pickup 06:12".

**Catches.**
- Adding a field to the sync request is backwards compatible only if the server ignores unknown fields (it does,
  System.Text.Json) and the old server is never sent anything that changes its validation — a new top-level
  array is fine.
- Dedup: hour rows must be idempotent on `(user, device, hourStart)` — upsert, not insert.

## 5. Screen time before bed

**Why.** ADHD and sleep are tightly linked, and late phone use is the most common delay. The server already knows
the user's `DefaultBedTime` (it uses it for quiet hours and tracker-silence). "You used the phone 48 minutes
after your bedtime last night" is a fact the user can't easily see anywhere else.

**How.**
- **Server only, mostly:** the android ledger already has the sessions. A daily computation of minutes of phone
  use between the user's `DefaultBedTime` and the next `DefaultWakeUpTime` (already read by the tracker-silence
  sweep), plus the last session's end — a line in the morning briefing and a weekly trend on the android
  dashboard.
- **Phone (optional):** a wind-down nudge at bedtime − 15 min if a budgeted/distraction app is open (reuse #1's
  detection).

**Catches.** Night-shift users: use their configured window, never a fixed 23:00. Alarm-clock / white-noise apps
should not count — let ignore rules cover them (they already drop to `IsIgnored` on the server).

## 6. Pause and privacy before upload

**Why.** (From the desktop list.) A phone carries the most private usage there is — dating, health, banking,
messaging. "It never leaves the phone" beats "it's deleted on arrival".

**How.**
- **Pause for…** 15 min / 1 h / until tomorrow, from the status screen, the tile (#3) and a notification action.
  Implementation: a paused range in the outbox DB; `SyncEngine.record` drops sessions inside it (clip the ones
  crossing its edges). The cursor still advances, so pausing never builds a backlog.
- ~~**Never-send list**~~ and ~~**sync server ignore rules down**~~ — **done (prompt 26c, 2026-10-25)** as one
  thing, decided with the owner: there is no phone-only kind of ignore. Ignoring is the server's ordinary rule
  (`PUT tracker/android/ignored/{packageName}`), the phone downloads every rule (`GET tracker/android/rules`, exact
  packages only since 26a) and drops the ignored packages before the outbox. See *Ignored apps* in `CLAUDE.md`. The
  generic `"private"` package was not built.

**Catches.** Dropping sessions silently changes totals; make "private" vs "drop" an explicit choice. Paused time
must not look like the tracker went silent — send an empty sync anyway so `TrackerSourceStatus` stays fresh.

## 7. Notification-interruption counts

**Why.** Notifications are the main way a phone pulls someone out of focus. "Slack interrupted you 34 times
today, 9 of them during your Thesis block" is directly actionable (mute it during blocks).

**How.**
- **Phone:** `NOTIFICATION_INTERRUPTION` (12) events carry the posting package. **Verify on real devices first**
  that a non-system caller with usage access receives them — the constant is public but some event types are
  filtered for third-party callers. The alternative is a `NotificationListenerService`, which sees every
  notification *including its text* — a much bigger privacy ask; if used, read only the package and timestamp.
- **Server:** per-hour per-package counts, same shape and dedup as #4. Overlay on the timeline: interruptions
  during planned blocks.

**Catches.** Never store notification content. A listener service is also a Play-policy-sensitive permission.

## 8. Digital Wellbeing overlap

**Why.** Users will compare our phone total with Digital Wellbeing's and they will differ. If we don't explain
why, they'll distrust both.

**How.**
- Document the differences on the android dashboard's info tooltip and in the app: we drop glances under 5 s,
  merge returns within 2 s, count split-screen apps both, don't count PiP, and end sessions at screen-off; DW has
  its own rules and counts some system UI.
- Offer "open Digital Wellbeing app timers" as a shortcut next to our budgets (#2) — DW's timers *block* the app,
  ours nudge. They complement.
- Optional: `UsageStatsManager.queryAndAggregateUsageStats` gives the OS's own per-app totals for a day; showing
  "OS says 2 h 31 m, we counted 2 h 18 m" on the status screen is a cheap accuracy check (and a good bug detector).

**Catches.** DW can't be read directly; aggregated stats are coarse (bucketed, sometimes off by an hour around
day boundaries). Use them as a sanity check, never as data.

## 9. Distribution, updates and client version

**Why.** (From the desktop list.) Every fix only reaches a phone that gets the new APK. Today that means building
it from Android Studio.

**How.**
- **Play Store (internal testing track)** is the easy update path, but `QUERY_ALL_PACKAGES` and
  `PACKAGE_USAGE_STATS` need a declaration and justification; `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` too. A
  self-hosted APK with an in-app "update available" check (version JSON on the server) avoids the review but
  needs "install unknown apps".
- **Send the client version** on every request (`X-Client-Version: android/1.1 (2)`). **Server:** store it on
  `TrackerSourceStatus` next to `LastSeenAt` (the desktop idea, same column), show it in the sources panel, and
  let the tracker-silence notification say "your phone tracker is out of date" when that's likely.
- A minimum-version gate (426) for wire-contract changes.

**Catches.** The release build needs a real signing key kept outside the repo; losing it means users must
uninstall (and lose the outbox) to update.

## 10. A push channel to the phone (FCM)

**Why.** #1, #2 and the tracker-silence notification all want to reach *this* app. Today the server's pipeline
can only reach a browser.

**How.**
- **Server:** a second delivery channel in `Sydowwe.Notifications` beside Web Push: an `FcmRegistration` entity
  (token, device id, user), an FCM sender, and per-kind channel preferences. Budget and drift notifications could
  then be delivered to the phone app instead of the browser.
- **Phone:** Firebase Messaging, register the token after sign-in, show notifications with actions.

**Catches.** A Google dependency in a framework module; a server credential to manage. Worth it only once two or
more of #1/#2/#5 exist.

## 11. "Sync blocked" notification

**Why.** The status card explains a stall only when the user opens the app. Usage access revoked, signed out,
or nothing sent for a day — each silently stops the data, and the server's
tracker-silence push needs 48 h and a browser.

**How.** Local notification from `SyncEngine.run` when the report's state is `SIGNED_OUT` or `NO_USAGE_ACCESS`,
or `WAITING` for more than 24 h with sessions pending. Once per condition, cleared when it resolves. Needs the
`POST_NOTIFICATIONS` runtime permission (Android 13+), requested from the status screen with an explanation.

**Catches.** Don't nag: once per condition per day at most.

## 12. Browser-based sign-in

**Why.** (From the desktop list.) Accounts created with Google have no password. The phone can't sign them in.

**How.** Custom Tabs + an app-link redirect (`https://…/auth/android/callback`) or a custom scheme, PKCE, and a
server endpoint pair to issue and exchange a one-time code for the usual tracker token pair. Same server work
as the desktop's loopback flow — build them together.

**Catches.** Revisits the 2026-10-12 decision against pairing codes; decide it explicitly. App links need
`assetlinks.json` on the server's domain.

## 13. Evening review of unmapped phone time — built 2026-10-19

**Why.** Every nudge, budget and attribution depends on packages being mapped to activities, and unmapped phone
time is invisible in History. A two-minute evening ritual — "1 h 40 m on your phone today isn't mapped: Reddit
50 m, Chrome 30 m" — raises coverage where the time actually went, one tap per app.

**What was built.** A local notification at the user's evening-shutdown moment opens a review screen: one card per
unmapped app, mapped with a tap to the activity the user's timers suggest, to another they pick, ignored,
snoozed or skipped. See `CLAUDE.md` → "Evening review" for the rules; the server side is the Tracking slice's
`AndroidTrackerUnmappedEndpoint` / `CreateAndroidTrackerRuleEndpoint`.

**Built since (prompt 27, 2026-10-26).** Mapping is managed from the phone. "+ New activity" in every picker (an
existing role, "Phone app" by default, an optional existing category, the distraction flag as follow / yes / no). A
"Mapped apps" screen to change, unlink or flag each mapping, and to map a recently used app before it is ever unmapped.
App-category links filter the picker. "Also add this week's time" when mapping (today + 6 days before). Snooze on a
review card. The review and "Mapped apps" are both on the status screen.

**Not built, still open.** A line about it in the web evening-shutdown push (the push would have to stop being
"tomorrow only"; a web push also cannot deep-link into this app). Mapping by app *label* or pattern stays a web-app
thing on purpose, and so do roles, categories and un-archiving an activity.

## 14. Breaks that don't end — built 2026-10-27

**Why.** A "quick break" from the desk turns into 40 minutes on the phone. Digital Wellbeing doesn't know about the desk
and the desktop tracker doesn't know about the phone; the server sees both. The desk going quiet and the phone coming out
is a break starting, with a known start and a known thing being left.

**What was built.** The first phone reader of `/now` (`now/NowClient`, `NowFacts`) and a pure `BreakPolicy`: once the
break has run its length (5–30 min, default 10, counted from the desk's last minute), one nudge — "Break's been 25 m.
Thesis is still open on your desktop." — with Back to it / 5 more minutes / I'm done for now; silent during calls, DND,
quiet hours, away days; cleared when the desk is used again. The watcher runs through planned blocks and for 30 min after
desk use so a pickup is seen within seconds. See `CLAUDE.md` → "Break nudge". The server needed no new field: `/now`'s
desk part (prompt 06) already said when the desk stopped, what it was and whether it was on the plan.

**Not built, still open.**
- A *planned* break. The planner has no break concept; a gap between two blocks could be one ("break until 11:00"), which
  would set the length and the subject (the next block) instead of the setting.
- Acting on "Back to it" beyond ending the nudging — e.g. opening the web app's timer for the block (`?track=<id>`), the
  desktop start ramp's link.
- Telling a dead desktop tracker from a break. `/now` can't (idle, a shut laptop and a stopped tracker look the same);
  `TrackerSourceStatus` is hourly. A desk whose tracker stopped mid-block reads as a break that never ends.
- A sooner end: the desk's return is seen within a minute while the phone is in use or a nudge is up, otherwise at the next
  sync.

## 15. Time chime in distracting apps — built 2026-10-27

**Why.** Time blindness: twenty minutes of scrolling feels like three. A budget speaks once, at the limit; a soft chime at
each interval of staying in the app keeps time visible without blocking anything.

**What was built.** A short buzz and a self-clearing heads-up — "15 m on Reddit" — at each interval (5–30 min, default 15,
never escalating) while the chosen apps stay in front; the chosen apps count as one stretch, a gap of up to 3 min pauses
it, a longer one starts over; skipped during calls and DND. The apps are the server's distraction apps (followed as the
flags change, each one can be unticked) plus any ticked by hand. See `CLAUDE.md` → "Time chime".

**Not built, still open.** Everything is one setting for all chosen apps today; each of these could become per app:
- **A separate count per app** instead of one stretch across the chosen apps (Reddit and Instagram each from zero).
- **An interval per app** (5 min for TikTok, 30 for YouTube).
- **The notification style per app** (heads-up or only a buzz). A vibration-only mode was considered and left out
  (2026-10-27): it needs `VIBRATE` and its own checks of silent mode and the system's notification-vibration setting.
- **Escalation**, either kind: chimes closer together the longer the stretch runs (15, then +10, then every 5), or a heavier
  chime after an hour (one that stays until dismissed, a longer buzz). Left out because a chime that nags gets its channel
  muted, and then it does nothing.
- **The server's silence.** Quiet hours and away days don't mute the chime today (it never asks the server at chime time,
  and scrolling past bedtime is exactly the time-blind stretch). Options: honour away days; or *strengthen* the chime after
  the user's bedtime rather than mute it (#5's wind-down nudge).
- **The reset gap as a setting** (3 min today, a constant).

## 16. Pick-up loop check — built 2026-10-27

**Why.** Per-app totals hide the loop: pick up → feed → put down → pick up again 90 s later, six times in ten minutes, or a
chain of apps hopped through without purpose. That pattern *is* the derailment, and spotting it needs no app→activity
mapping at all.

**What was built.** One check-in per loop — "6 pick-ups in 10 minutes · Want to put the phone away for 20?" with Put it
away / I'm fine / Not today — when the phone is unlocked 6 times in 10 min, or 6 apps are hopped through in 5 min with none
used over a minute. "Put it away" runs a "Phone away" timer (10 / 20 / 30 min) that sounds when it is up. Chat apps ticked
under "Apps that don't count" make no pickups. Loops are kept on the phone for 30 days. Off by default; the watcher runs
while it is on. See `CLAUDE.md` → "Loop check".

**Not built, still open.**
- **Send the loops to the server** for a dashboard ("3 loops today, mostly 21:00–23:00"). Planned with prompt 14 (pickups),
  which changes the same sync request, the same wire sample in both repos and adds the same kind of `tracking` table, retention
  and GDPR paths: one change instead of two.
- **The thresholds as settings.** 6 unlocks / 10 min and 6 apps / 5 min / 60 s are constants, chosen conservative before any
  data. Tune them from a week of stored loops first; a setting only if one size really doesn't fit.
- **Telling a chat from a loop without the list.** The log can't (same timing). A `NotificationListenerService` (prompt 15's
  notification interruptions) could see that a pickup followed a message, and not count it; it is a permission of its own.
- **Pre-ticking the obvious chat apps** — the default SMS app (`RoleManager.ROLE_SMS`) and the dialer — instead of starting
  with nothing ticked.
- **Using the server's distraction flags** to weight a loop ("6 pick-ups, 5 of them Instagram"), or to check in sooner when the
  loop is in distraction apps. Left out: the check must work without knowing what an app is, and the flags are the chime's
  refresh today.
- **An exact away timer** (on the minute) needs the "Alarms & reminders" permission on Android 14; the inexact one may come a
  little late in Doze. A reboot during the timer loses its sound.
- **The server's silence.** Like the chime, quiet hours and away days don't mute it; honouring away days is an option.
- **Ending the away timer early** (a "Back now" on the card), or saying something when the phone is picked up during it.
  Left out so the timer never shames.



---

## Smaller ideas

- **Diagnostics export.** "Copy diagnostics" on the status screen: app version, Android version, OEM, device id,
  outbox counts, cursor, last 20 sync reports (no package names). For bug reports.
- **OEM battery-killer guidance.** On Xiaomi / Huawei / Samsung / OnePlus, link the specific "autostart" or
  "never sleeping apps" setting (dontkillmyapp.com has the list). Battery-optimization exclusion alone doesn't
  cover them.
- **App hibernation.** An app never opened for months can be hibernated (Android 12+), which stops its jobs.
  Check `PackageManagerCompat.getUnusedAppRestrictionsStatus` and offer the "pause app activity if unused"
  setting from the status screen.
- **Launcher time — done 2026-10-22, on the phone.** Time on the home screen and in Recents was recorded as the
  launcher's package (a few-second row between every two apps). `UsageStatsCollector.collect` now drops every
  installed home app's sessions (`10a53b3`), instead of a server-side ignore suggestion per launcher. Rows sent
  before that stay on the server.
- **Backfill on first sign-in.** The first run reads 7 days of the usage log; the OS sometimes keeps more.
  `queryEvents` from 14 or 30 days back is free to try on first run.
- **Expedited sync on app close.** When the status screen goes to the background, enqueue an expedited one-off
  sync so what the user just saw counted is on the server a minute later rather than fifteen.
