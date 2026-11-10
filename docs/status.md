# Status / handoff

What a new session needs in order to pick up where the last one stopped. Keep it short: replace stale lines rather
than adding to them, and drop finished items once they are committed and nothing more is owed on them. The rules
live in `CLAUDE.md` and the behaviour in KDoc, not here.

_Last updated: 2026-11-10._

## Where things stand

- `main` at 1.7.0 (code 10), tag `v1.7.0`, plus the review fixes (`145919d`..`4cd1021`). No version bump since.
  Bump with `test-release` once the fixes are worth putting on the phone. **None of them has been seen on a
  device.**
- **The release build can't be signed here:** `packageRelease` fails with "keystore password was incorrect" for
  `adhd-tracker`. That is the owner's setup (`keystore.properties`), off-limits to a session. R8 (`minifyReleaseWithR8`)
  runs before signing and passes, and that is the step that checks the keep rules.
- **The owner's uncommitted work:** the status-screen title change (`MainActivity.kt`, `activity_main.xml`,
  `strings.xml`, `CLAUDE.md`; the "Version 1.4.0 (7) · host" line under the title). Not ours, so never commit it in a
  fix commit. When one of our changes touches the same file, stage only our hunks: write a `-U0` patch of just those
  lines and `git apply --cached --unidiff-zero` it.
- 550 tests pass; `lintDebug` exits 0.

## Review follow-up ([review-2026-11-10.md](review-2026-11-10.md))

**Done:** the whole *Fix first* list (items 1–7), one `fix:` commit each. Their mechanisms are in the KDoc and, for
the breaker, in `CLAUDE.md` (*The wire contract*). Things a later change must respect:
- Every receiver's `goAsync()` goes through `util/Broadcasts.kt` `holdBroadcastFor(job)` (an 8 s budget). Use it for
  any new receiver too.
- A `BatchSender` is created once per run (per extras group), never per page: the breaker counts per sender.

**Next, in this order:**
1. CI workflow (`testDebugUnitTest`, `lintDebug`, `minifyReleaseWithR8` or an unsigned release); check that
   Robolectric + `MigrationTestHelper` can run the Room migrations; a `SyncEngine` test with a fake `ApiService`,
   including the breaker end to end. There is no `kotlinx-coroutines-test` dependency yet.
2. Diagnostics screen (privacy-safe ring buffer + "Copy diagnostics"), then a device pass over the six unverified
   features and the fixes above.
3. Status-screen reshape, plus the review's UX and security points.
   Optional cleanups: the shared monitor runner; a status-screen line when the breaker tripped (today it shows as
   an ordinary "waiting").

## Open questions for the owner

- Item 3 reuses `SESSION_ENDED`, so a lost Keystore key is notified as "Signed out by the server". The text below
  that title is right, the title isn't quite. Its own reason and wording, or fine as it is?
- The server-body check for 400s (item 2's second guard) was not done. Worth it later?

## Gotchas found while working

- A string set only from a layout is never formatted, so `%%` in it shows as `%%`. Use `formatted="false"` to quiet
  lint's `StringFormatInvalid`.
- Gson fills a field missing from the JSON with `null`, even where Kotlin's type is non-null. Anything read back from
  prefs needs a completeness check inside the `try` (see `BudgetPreferences.requireComplete`).
