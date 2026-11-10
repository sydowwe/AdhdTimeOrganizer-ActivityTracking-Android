# Status / handoff

What a new session needs in order to pick up where the last one stopped. Keep it short: replace stale lines rather
than adding to them, and drop finished items once they are committed and nothing more is owed on them. The rules
live in `CLAUDE.md` and the behaviour in KDoc, not here.

_Last updated: 2026-11-10._

## Where things stand

- `main` at 1.7.0 (code 10), tag `v1.7.0`, plus the review fixes (`145919d`..`bc36a0b`). No version bump since. Bump with `test-release` once the fixes are worth putting on the phone.
- **The release build can't be signed here:** `packageRelease` fails with "keystore password was incorrect" for
  `adhd-tracker`. Owner's setup (`keystore.properties`), off-limits to a session. R8 (`minifyReleaseWithR8`) still
  runs and passes before it, which is what checks the keep rules.
- **The owner's uncommitted work:** the status-screen title change (`MainActivity.kt`, `activity_main.xml`,
  `strings.xml`, `CLAUDE.md`; the "Version 1.4.0 (7) · host" line under the title). Not ours, so never commit it in a
  fix commit. `strings.xml` holds both: stage only our hunks (`git apply --cached` with a patch of just those lines).
- Current work: the follow-ups from [review-2026-11-10.md](review-2026-11-10.md).

## Review follow-up

**Batch 1 (items 1, 3, 5, 6, 7): done and committed**, one `fix:` commit each. 546 tests pass and `lintDebug`
exits 0. None of it has been seen on a device yet.

**Item 4: done** (`bc36a0b`). Every receiver's `goAsync()` goes through `util/Broadcasts.kt` `holdBroadcastFor(job)`:
the broadcast is finished when the job ends or after 8 s, and slower work goes on in its own scope. It holds rather
than finishing as soon as the state is written, because the ramp's alarm wake *is* the evaluation. A new receiver
should use it too. The shared runner for the six monitors was **not** done; it is still optional cleanup.

Next, in this order (details in the review):

1. **Item 2**: a 400 to everything empties the outbox. Recommended: a circuit breaker (stop after a few refused
   singles in one run). **Owner to decide.**
2. CI workflow; check that Robolectric + `MigrationTestHelper` can run the Room migrations; a `SyncEngine` test.
   (No `kotlinx-coroutines-test` dependency yet.)
3. Diagnostics screen (privacy-safe ring buffer + "Copy diagnostics"), then a device pass over the six unverified
   features.
4. Status-screen reshape, plus the UX and security points. Optional: the shared monitor runner.

## Open questions for the owner

- Item 2: breaker, server-body check, or both.
- Item 3 reuses `SESSION_ENDED`, so a lost Keystore key is notified as "Signed out by the server". The text below
  that title is right, the title isn't quite. Its own reason and wording, or fine as it is?

## Gotchas found while working

- A string set only from a layout is never formatted, so `%%` in it shows as `%%`. Use `formatted="false"` to quiet
  lint's `StringFormatInvalid`.
- Gson fills a field missing from the JSON with `null`, even where Kotlin's type is non-null. Anything read back from
  prefs needs a completeness check inside the `try` (see `BudgetPreferences.requireComplete`).
