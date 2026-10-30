# Changelog

Production releases of the Android tracker, newest first: one section per `prod/vX.Y.Z` tag, not per test tag
(`test-release` tags are frequent). `scripts/prod-release.ps1` drafts a missing section from `git log` since the previous
production tag — features, fixes, the rest; docs and version bumps left out — and stops. Edit the draft, delete its
`DRAFT` line, and run it again: the section becomes the production tag's message.
