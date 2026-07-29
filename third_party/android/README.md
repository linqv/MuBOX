# Vendored Android libraries

This directory is a repository-local Maven repository. Feature modules own
their binary dependencies through normal Maven coordinates; the application
module must not reference individual AAR files.

## `com.artifex.mupdf:fitz:1.27.1`

- Purpose: MuPDF Android/Java binding used by `feature:reader` for local
  PDF/EPUB/MOBI/AZW3 reading.
- Source used for this repository: official MuPDF AAR supplied locally at
  `/tmp/mupdf-plan/fitz-1.27.1.aar`.
- SHA-256:
  `005b747a7b3e3a22e6bb6f0f4a1e1eb1bfd3493793412d5a7ebfe654c6626229`
- MuPDF licensing and redistribution obligations must be handled before a
  public release.

## `is.xyz.mpv:mpv-android-lib:0.0.1`

- Purpose: embedded mpv Android runtime used by `feature:video`.
- SHA-256:
  `c8e6a563ffe104fa73ced45b786e616247450bf25b6537d245d4f83d2842e304`

Do not replace these artifacts without updating their coordinates, hashes,
and packaging contract tests.
