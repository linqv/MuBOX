# Vendored Android libraries

This directory is a repository-local Maven repository. Feature modules own
their binary dependencies through normal Maven coordinates; the application
module must not reference individual AAR files.

## `is.xyz.mpv:mpv-android-lib:0.0.1`

- Purpose: embedded mpv Android runtime used by `feature:video`.
- SHA-256:
  `c8e6a563ffe104fa73ced45b786e616247450bf25b6537d245d4f83d2842e304`

Do not replace these artifacts without updating their coordinates, hashes,
and packaging contract tests.
