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

## 1. Focus-block nudge — "you planned Thesis until 11:30, Instagram is open" — built 2026-10-28 (prompt 08)

**What was built.** The drift nudge: during a planned block, once `/now`'s distraction apps have been in front for 30 s
(time in several adds up; a minute away starts over), one nudge per block — "Planned: Thesis until 11:30 · Instagram is
open." — with Back to it (home screen) / Switch plan (the web planner) / 5 more minutes. An app mapped to the block's own
activity doesn't count. Held, never dropped, during calls, DND, car mode, quiet hours, a "Phone away" timer and the 10 min
after another kind of nudge (the new shared gap). Off by default. See `CLAUDE.md` → "Drift nudge" and "Nudges together".
The design below is kept as it was written; where it differs (one nudge per block, not per package; the watcher instead of
a WorkManager job at the block's start) the built one is in `CLAUDE.md`.

**Not built, still open.**
- **Apps mapped to another activity** (Email during a Thesis block) as drift. Left out: judging "off plan" fairly needs the
  server's per-minute verdict, which the phone's time doesn't get until the sync; unmapped apps stay neither on nor off.
- **The grace and the gap as settings** (30 s and 1 min are constants), and a grace per app.
- **Car mode for the other nudges**: only drift reads it today; the chime and the loop check might want it too.
- **Activity Recognition for driving** (Play services + a permission) if car mode proves too narrow.
- **Opening the block's timer** from Back to it (`?track=<id>`, as the start ramp's Start now does).

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

## 2. App-time budgets on the phone — "45 min of TikTok today, 15 left" — built 2026-10-28 (prompt 13)

**Built as** `budget/` (CLAUDE.md → *Daily limits*): the server's total with this phone's synced part swapped for its own
count from the usage log (not the outbox, which is nearly always empty between runs), said at 80 % and the limit while an
app of the budget is in front, claimed on the server first so its push stays quiet (decision 13-1). No full-screen card
(13-2), nothing more on reopening past the limit (13-3). What was left open:
- **A tap target.** The server's push opens the tracking dashboard on that day; the phone's opens nothing. `WEB_APP_ROOT` +
  the dashboard's route would do it, once the route is settled.
- **A line on reopening past the limit**, at most hourly — the rejected half of 13-3, if one notification a day turns out
  too easy to swipe and forget.
- **The bell row.** A limit the phone said writes no row in the web's notification bell (the claim stops the server's send
  entirely). A "said elsewhere" bell-only write would need a dispatcher option the framework doesn't have.
- **Say it in the UI that this isn't Digital Wellbeing** (below): ours counts the activity across devices.
- **WorkManager's expedited work** for the catch-up sync: today it is the ordinary "Sync now". Expedited work on Android 11
  and below runs as a foreground service and needs `getForegroundInfo`, which the worker doesn't have.

The original write-up:

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

## 4. Unlock count and first-pickup time — built 2026-10-29 (prompt 14), server half; the web's view is a frontend prompt

**Why.** How often someone *picks up* the phone is a stronger compulsion signal than total minutes — 90 unlocks
of 30 seconds each is a different day from one long video. "First pickup at 06:12, before getting up" is a
morning-routine signal.

**What was built.** Every pickup the usage log shows (start, end, unlocked or a glance) goes to the server with the sync,
**one row per pickup** rather than per-hour counts (decision 14-1: a length and a minute are the point), through the pure
`PickupCounter` on 12's `Pickups`, with a cursor of its own. The loop check's loops go with them (#16). The server answers
`POST /activity-tracking/android/pickups`: per day pickups / unlocks / glances / short checks (< 30 s) / median unlock /
first unlock from 04:00 / last unlock / longest phone-free stretch / loops, per hour of the day, the loops by rule and
answer, and a "typically" baseline. See `CLAUDE.md` → "The wire contract" and "Sessions and the cursor".

**Not built, still open.**
- **The web's view.** The frontend prompt `prompts/activity-tracking/android-pickups-dashboard.md` in the frontend repo; it
  ends with a research step for what else the dashboard could show.
- **The morning briefing's line** (decision 14-2: left out). At wake-up time the phone's last sync is often older than the
  first unlock, and "you just picked the phone up" tells nothing. A later line could say yesterday's, or the week's trend
  ("first pickup ~40 min before you got up this week").
- **Pickups inside planned blocks** against outside them — a strong signal. Prompt 15 added the range lookup it needs
  (`IPlannedBlockLookup.PlannedOverAsync`) for the notifications; pickups weren't added to it.
- **Pickups on the phone's own status screen** ("42 pickups today, first 06:12"): the counter has everything; no card yet.

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

## 7. Notification-interruption counts — built 2026-10-29 (prompt 15), not yet seen on a device; the web's view is a frontend prompt

**Why.** Notifications are the main way a phone pulls someone out of focus. "Slack interrupted you 34 times
today, 9 of them during your Thesis block" is directly actionable (mute it during blocks).

**What was built.** The usage log's `NOTIFICATION_INTERRUPTION` (12) — a notification that sounded, vibrated or peeked — read
for its package and instant only, **never its text** (decision 15-5). The constant is `@SystemApi` (the SDK hides it, which
the prompt didn't know); the platform blanks only the channel id for an app like this one, so the event should arrive. The
pure `DerailmentLinker` turns them into **derailments**: the app opened within a minute, until the user went back to the app
they were in, the screen went off, or two hours (decision 15-2: on the phone, which has the timing). Both go to the server one
row each (decision 15-3), in requests of their own after the pickups; `POST /activity-tracking/android/interruptions` answers
per day, per hour, per app, the longest derailments, and per planned activity the interruptions inside its blocks. See
`CLAUDE.md` → "The wire contract" and "Sessions and the cursor".

**Not built, still open.**
- **Seeing it on a device.** Whether MIUI (or any OEM) writes the event at all: `adb shell dumpsys usagestats` lists
  `NOTIFICATION_INTERRUPTION` rows if it does; then the `android_notification_interruption` rows on the test DB. If it
  doesn't, the alternative is a `NotificationListenerService` reading only package and instant — a Play-sensitive
  permission and a privacy ask; decide with the owner first.
- **The web's view.** The frontend prompt `prompts/activity-tracking/android-notifications-dashboard.md` in the frontend repo.
- **Acting on it from the phone:** "Slack interrupted 9 times in Thesis — mute it during blocks?" (Android can't mute
  another app's notifications without a listener; it could open that app's notification settings, or suggest DND rules).
- **A baseline** ("typically 40 a day"), as the pickups dashboard has.
- **Pickups that weren't opened by a notification** against those that were: a pickup within a minute of an interruption
  that opened something else is today neither a derailment nor counted apart.

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

## 12. Browser-based sign-in — replaced by native Google sign-in, 2026-10-29

> **Built differently (prompt 23):** no browser. "Sign in with Google" through Credential Manager sends an ID token to
> `POST auth/tracker/login/google`; the password form gains autofill hints and an asset link to the web app, so Google
> Password Manager offers the web's saved sign-ins. See `CLAUDE.md` → *Auth*. What follows is the original idea.

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

**Since prompt 14 (2026-10-29) the loops are sent** with the sync, to `android_pickup_loop` on the server; the pickups
dashboard counts them per day and per hour and by rule and answer ("3 loops today, mostly 21:00–23:00" is read off it).

**Not built, still open.**
- **The thresholds as settings.** 6 unlocks / 10 min and 6 apps / 5 min / 60 s are constants, chosen conservative before any
  data. Tune them from a week of stored loops first; a setting only if one size really doesn't fit.
- **Telling a chat from a loop without the list.** The log can't (same timing). A `NotificationListenerService` (prompt 15's
  notification interruptions) could see that a pickup followed a message, and not count it; it is a permission of its own.
- **Pre-ticking the obvious chat apps** — the default SMS app (`RoleManager.ROLE_SMS`) and the dialer — instead of starting
  with nothing ticked.
- **Using the server's distraction flags** to weight a loop ("6 pick-ups, 5 of them Instagram"), or to check in sooner when the
  loop is in distraction apps. Left out: the check must work without knowing what an app is. (The flags are kept for every
  nudge in `now/KeptDistractions` since prompt 08.)
- **An exact away timer** (on the minute) needs the "Alarms & reminders" permission on Android 14; the inexact one may come a
  little late in Doze. A reboot during the timer loses its sound.
- **The server's silence.** Like the chime, quiet hours and away days don't mute it; honouring away days is an option.
- **Ending the away timer early** (a "Back now" on the card), or saying something when the phone is picked up during it.
  Left out so the timer never shames.

## 17. Start ramp — "Thesis starts now. Start the timer?" — built 2026-10-28 (prompt 10)

**Why.** Starting is the hard part with ADHD; a drift nudge arrives after the slip. At a block's start one tap turns the
plan into a running timer.

**What was built.** At a planned block's start (an alarm, or the next pickup / sync, "started 20 min ago", while 10 min are
left) one notification with Start now / In 2 min / Not this one. Start now starts the block in the planner
(`tracker/android/start-block`) and opens the web app's `?track=<id>`, which starts the countdown. Which blocks: every block,
all but Optional (default) or Critical only, from the planner's importance. Skipped when the block is already started or the
desk is in use; held while a timer runs and under the usual silences. On by default. See `CLAUDE.md` → "Start ramp".

**Not built, still open.**
- **Web Push to the installed PWA** instead of, or beside, the app. Considered with the owner on 2026-10-28: the server's
  notification module already sends "your block starts in 10 min" with Start / Skip, and a "starts now" kind would reach
  every subscribed device. Not chosen because the owner's account had no push subscription on the test server at all, Web
  Push delivery on the Xiaomi was unproven, Chrome shows at most two action buttons, and it wouldn't join the phone's
  10-minute gap. **Worth doing separately:** install the PWA on the phone, allow notifications, and check delivery with the
  server's `POST /notification/test` (admin) — the planner's existing pushes would then reach the phone too.
- **An exact wake.** The alarm is inexact (`setAndAllowWhileIdle`): in Doze it can come late, and then the in-process look
  (a phone in use) or the next sync says it in the late form. On the minute needs `SCHEDULE_EXACT_ALARM` ("Alarms &
  reminders", denied by default on Android 14) — a permission the owner hasn't been asked about.
- **Skip short blocks** (under 15 min, say). A 10-minute email block may still be worth the nudge; left until the ramp has
  been lived with.
- **"Not today"** on the notification. Android shows at most three buttons, and the three are taken.
- **Per-activity exclusions** ("never for Lunch"). Importance covers most of it.
- **Not this one as Skip in the planner.** Today it only settles the ramp and quiets drift, as the desktop's does; marking the
  block skipped would be a server call and an explicit planner change the owner didn't ask for.
- **The desktop following the importance choice.** Its ramp still asks about every block; the same `isOptional` /
  `isCritical` on its heartbeat and a setting there would be a desktop-repo change.

---

## 18. Quick capture — park a thought in two taps — built 2026-10-28 (prompt 18)

**Why.** Mid-focus, a stray thought ("email the landlord") either derails the block or is lost. Capturing it instead of
acting on it is a standard ADHD technique, and the phone is always at hand.

**What was built.** The "Capture" Quick Settings tile, the app icon's long-press shortcut and "Save to Inbox" in the share
sheet. The thought is queued in `capture.db` and sent to `POST tracker/android/capture`, which makes it a to-do on the user's
capture list ("Inbox", made on the first capture). Never lost offline or signed out. See `CLAUDE.md` → "Quick capture".

**Not built, still open.**
- **The desktop's hotkey** (desktop prompt 08): the same route, moved to `/tracker/capture` with both trackers' attributes —
  one route, never a twin. The phone's path changes with it (one line in `ApiService`).
- **Choosing the capture list in the web app.** The server has the setting (`UserTodoListSettings.CaptureTodoListId`) but no
  picker writes it; until one does, captures go to "Inbox". A frontend change, raised with the owner.
- **A triage view** (an "inbox" page that sorts captures into lists, dates and priorities). Decided against for now (18-3):
  the Inbox is an ordinary list, and "Move to list" already exists.
- **Editing a share before it is saved.** A share saves at once (two taps); a "Save and edit" path would cost a third.
- **Capture from a nudge** ("park the thought that pulled you away" on the drift nudge). Android shows three buttons and the
  drift nudge's are taken.
- **Voice capture** (a tile long-press into speech recognition). The keyboard's own mic does it today.

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
- **The phone's data in the GDPR export (server).** `GET /user/data-export` carries the desktop and web-extension ledgers
  and `TrackerSourceStatus`, but nothing the phone sent: `AndroidSessionData` and `AndroidReportedApp` are listed as known
  gaps in `UserDataExportAndDeletionTests.KnownGaps`, and prompt 14's pickup and loop tables join them (the owner,
  2026-10-29: "write it in the future ideas"), as do 15's interruption and derailment tables (decision 15-6). Erasure is covered: every one cascades from the user. One query per table in
  `GetUserDataExportEndpoint`, then off the known-gaps list.
- **Expedited sync on app close.** When the status screen goes to the background, enqueue an expedited one-off
  sync so what the user just saw counted is on the server a minute later rather than fifteen.
