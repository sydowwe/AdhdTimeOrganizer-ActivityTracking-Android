# Test release — $ARGUMENTS

> **`$ARGUMENTS`** = optional: `patch` (default), `minor`, `major`, an explicit `X.Y.Z`, or `nobump`.
> Run `scripts/test-release.ps1` — never edit `versionCode` / `versionName` by hand. Read **Versioning** in
> `CLAUDE.md` first if you have not.

A test release puts a new version on the phone connected through adb, against the test server. It is not a
release to production (`prod-release` is a separate script, not written yet — don't improvise one).

1. Decide the bump from what changed since the last tag (`git log --oneline $(git describe --tags --abbrev=0)..HEAD`)
   using the rules in CLAUDE.md. If `$ARGUMENTS` names one, use that. Say which and why in one line.
2. Check the tree: the script refuses a dirty one, because the tag must describe what is installed. Commit your own
   work first. If the dirt is **another session's** uncommitted work, stop and ask — don't commit it, and don't
   pass `-AllowDirty` without the user saying so.
3. Run it from the repo root, passing the attribution line from your instructions as the commit trailer:

   ```powershell
   ./scripts/test-release.ps1 -Bump patch -Trailer 'Co-Authored-By: <the attribution line you were given>'
   ```

   Flags: `-Bump patch|minor|major`, `-Version X.Y.Z`, `-NoBump` (rebuild + reinstall, no commit, no tag),
   `-NoInstall`, `-SkipTests`, `-Serial <id>` when several devices are connected.
4. Ask before running it if the phone is mid-test: installing restarts the app, and the install is an update, so
   sign-in and the outbox survive. **Never** `adb uninstall` to get past an install error — that deletes the outbox.
   Report the script's error and ask.
5. Report the version, the commit and tag, the server URL it printed, and what `dumpsys` says is installed. Nothing
   is pushed; push a tag only when the user asks, by name (`git push origin vX.Y.Z`, never `--tags`).
