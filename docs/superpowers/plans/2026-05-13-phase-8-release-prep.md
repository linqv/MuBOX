# Phase 8 Release Prep Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce an internally testable Android build with documented supported formats, known limits, WebDAV compatibility, and performance checks.

**Architecture:** Release preparation is documentation plus Gradle release configuration. The app remains a single module with Rust shared libraries packaged per ABI.

**Tech Stack:** Android Gradle release build, R8 rules, Markdown docs, manual QA checklist.

---

## Files

- Modify: `/home/lin/webcomic/README.md`
- Create: `/home/lin/webcomic/docs/testing/webdav-compatibility.md`
- Create: `/home/lin/webcomic/docs/testing/performance-checklist.md`
- Create: `/home/lin/webcomic/docs/release/internal-test.md`
- Modify: `/home/lin/webcomic/app/build.gradle.kts`
- Create: `/home/lin/webcomic/app/proguard-rules.pro`

## Task 1: Release Build Configuration

- [ ] Set app name to `ComicDav`.
- [ ] Confirm package name `com.example.comicdav` or replace with the chosen release package before public distribution.
- [ ] Set `minSdk = 26` and `targetSdk = 35`.
- [ ] Add release build type with `isMinifyEnabled = true`, `isShrinkResources = true`, and `proguardFiles`.
- [ ] Create `proguard-rules.pro` keeping native bridge classes: `-keep class com.example.comicdav.nativebridge.** { *; }`.

## Task 2: README Support Matrix

- [ ] Document supported archive formats: `.cbz`, `.zip`.
- [ ] Document supported image formats: JPG, PNG, WebP.
- [ ] Document unsupported formats: CBR/RAR, PDF, 7z, encrypted ZIP, split ZIP.
- [ ] Document build commands for debug, release, Rust tests, and Android unit tests.

## Task 3: WebDAV Compatibility Document

- [ ] Create table columns: server, version, PROPFIND, HEAD, Range, ETag, path encoding, result, notes.
- [ ] Record at least one tested real server.
- [ ] Record fallback behavior for servers without Range.

## Task 4: Performance Checklist

- [ ] Create checklist for small, medium, and large comics.
- [ ] Record first open time, second open time, first page time, request count, bytes read, next-page cache hit rate.
- [ ] Record Wi-Fi and mobile network behavior separately.
- [ ] Include pass criteria: first uncached page in 1 to 3 seconds on stable Wi-Fi and second open in 0.5 to 1 second with cache.

## Task 5: Internal Test Procedure

- [ ] Document clean install.
- [ ] Document add account, browse, open, read, close, reopen, and clear cache.
- [ ] Document how to collect diagnostics after a failed open.
- [ ] Build debug APK: `./gradlew :app:assembleDebug`.
- [ ] Build release APK: `./gradlew :app:assembleRelease`.

## Verification

- [ ] Run `./gradlew :app:assembleDebug`.
- [ ] Run `./gradlew :app:assembleRelease`.
- [ ] Install the debug APK on a real Android device.
- [ ] Complete the internal test procedure once.
- [ ] Commit: `chore: prepare internal test release`.
