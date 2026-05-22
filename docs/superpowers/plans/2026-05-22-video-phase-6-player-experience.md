# Video Phase 6 Player Experience Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the first production slice of Phase 6 advanced playback experience: richer mpv state, testable player control commands, safe statistics models, gesture handling, floating option panels, and playback queue primitives.

**Status:** Completed and verified on 2026-05-22.

**Completed scope:**
- Extended `MpvPlayerState`, `MpvEngine`, and `MpvController` for advanced playback controls, observed mpv properties, tracks, delays, decoder/rendering modes, scaling, gestures, and HUD state.
- Reworked player UI so advanced controls live behind right-edge floating buttons and popup option panels instead of being permanently stacked on the playback page.
- Kept `gpu-next` and `vulkan` as separate concepts: `gpu-next` maps to mpv `vo`, while `vulkan` maps to `gpu-api`.
- Added gesture handling for brightness, volume, double-tap seek, pinch zoom, HUD feedback, and control locking.
- Added statistics snapshot/redaction models and a floating information panel.
- Added playback queue primitives, current-directory queue generation for local SAF and WebDAV opens, intent transport, and queue display in the player.

**Known remaining work:**
- Queue panel currently displays previous/current/next items; actual previous/next playback switching and auto-play-next still need a follow-up because WebDAV items must re-register proxy streams and close old stream IDs safely.
- Proxy runtime diagnostics are modeled and redacted, but live proxy metrics are not yet wired into the information panel.

**Architecture:** Keep mpv calls behind `MpvEngine` and `MpvController`; Compose and future gesture code only talk to controller methods. Add small immutable models for tracks, decoder/rendering modes, scaling, delays, statistics, and queue state so UI panels can be built without direct `MPVLib` access.

**Tech Stack:** Kotlin, Android Compose Material3, mpv-android-lib, coroutines StateFlow, JUnit4, Gradle `:app:testDebugUnitTest`.

---

## File Structure

- `app/src/main/java/com/example/comicdav/video/player/MpvController.kt`: Extend state, engine API, property observation handlers, and controller command methods.
- `app/src/main/java/com/example/comicdav/video/player/VideoPlayerActivity.kt`: Wire observed mpv properties and add floating visible playback controls for speed, track panels, delays, scale, decoder, renderer, gestures, information, lock, and queue display.
- `app/src/main/java/com/example/comicdav/video/player/VideoPlayerStatistics.kt`: Add sanitized media/proxy/runtime statistics models.
- `app/src/main/java/com/example/comicdav/video/player/VideoPlaybackQueue.kt`: Add queue model and queue navigator that closes old WebDAV streams before registering a new session.
- `app/src/main/java/com/example/comicdav/MainActivity.kt`: Build local SAF and WebDAV current-directory playback queues when opening videos.
- `app/src/test/java/com/example/comicdav/video/player/MpvControllerAdvancedControlsTest.kt`: TDD tests for speed, temporary speed, tracks, delays, decoder, renderer, scaling, and property syncing.
- `app/src/test/java/com/example/comicdav/video/player/MpvControllerGestureTest.kt`: TDD tests for volume, brightness, double-tap seek, pinch zoom, HUD clearing, and lock behavior.
- `app/src/test/java/com/example/comicdav/video/player/VideoPlayerStatisticsTest.kt`: TDD tests for credential and path redaction.
- `app/src/test/java/com/example/comicdav/video/player/VideoPlaybackQueueTest.kt`: TDD tests for previous/next behavior and WebDAV stream cleanup.
- `app/src/test/java/com/example/comicdav/video/player/MainActivityVideoQueueIntegrationTest.kt`: Source-level integration tests for current-directory queue generation.

## Task 1: Controller State And Command Surface

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/video/player/MpvController.kt`
- Test: `app/src/test/java/com/example/comicdav/video/player/MpvControllerAdvancedControlsTest.kt`

- [x] **Step 1: Write failing tests for advanced controls.**
- [x] **Step 2: Run targeted tests and verify they fail because APIs are missing.**
- [x] **Step 3: Add models, engine property methods, and controller methods.**
- [x] **Step 4: Run targeted tests and verify they pass.**

## Task 2: Safe Statistics Models

**Files:**
- Create: `app/src/main/java/com/example/comicdav/video/player/VideoPlayerStatistics.kt`
- Test: `app/src/test/java/com/example/comicdav/video/player/VideoPlayerStatisticsTest.kt`

- [x] **Step 1: Write failing tests for redacted media/proxy statistics.**
- [x] **Step 2: Run targeted tests and verify the new model is missing.**
- [x] **Step 3: Implement statistics snapshots with redaction helpers.**
- [x] **Step 4: Run targeted tests and verify they pass.**

## Task 3: Queue Navigation Primitives

**Files:**
- Create: `app/src/main/java/com/example/comicdav/video/player/VideoPlaybackQueue.kt`
- Test: `app/src/test/java/com/example/comicdav/video/player/VideoPlaybackQueueTest.kt`

- [x] **Step 1: Write failing tests for previous/next and WebDAV cleanup ordering.**
- [x] **Step 2: Run targeted tests and verify queue API is missing.**
- [x] **Step 3: Implement immutable queue and switch helper.**
- [x] **Step 4: Run targeted tests and verify they pass.**

## Task 4: Activity Observation And Visible Controls

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/video/player/VideoPlayerActivity.kt`
- Test: `app/src/test/java/com/example/comicdav/video/player/VideoPlayerActivityIntegrationTest.kt`

- [x] **Step 1: Add source-level tests for required visible controls and observed properties.**
- [x] **Step 2: Run targeted tests and verify they fail.**
- [x] **Step 3: Wire mpv observation and Compose control callbacks to controller methods.**
- [x] **Step 4: Run targeted tests and verify they pass.**

## Task 5: Gesture Overlay And HUD

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/video/player/MpvController.kt`
- Modify: `app/src/main/java/com/example/comicdav/video/player/VideoPlayerActivity.kt`
- Test: `app/src/test/java/com/example/comicdav/video/player/MpvControllerGestureTest.kt`
- Test: `app/src/test/java/com/example/comicdav/video/player/VideoPlayerActivityIntegrationTest.kt`

- [x] **Step 1: Write failing tests for gesture controller behavior and UI wiring.**
- [x] **Step 2: Implement volume, brightness, seek, zoom, HUD, and lock behavior.**
- [x] **Step 3: Wire the Compose gesture overlay outside bottom controls and floating option buttons.**
- [x] **Step 4: Run targeted tests and verify they pass.**

## Task 6: Floating Information And Queue Panels

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/MainActivity.kt`
- Modify: `app/src/main/java/com/example/comicdav/video/player/VideoPlayerActivity.kt`
- Modify: `app/src/main/java/com/example/comicdav/video/player/VideoPlayerStatistics.kt`
- Test: `app/src/test/java/com/example/comicdav/video/player/MainActivityVideoQueueIntegrationTest.kt`
- Test: `app/src/test/java/com/example/comicdav/video/player/VideoPlayerActivityIntentTest.kt`
- Test: `app/src/test/java/com/example/comicdav/video/player/VideoPlayerActivityIntegrationTest.kt`
- Test: `app/src/test/java/com/example/comicdav/video/player/VideoPlayerStatisticsTest.kt`

- [x] **Step 1: Add failing tests for statistics snapshot building, information panel wiring, queue intent extras, and current-directory queue generation.**
- [x] **Step 2: Implement snapshot building and right-edge information panel.**
- [x] **Step 3: Carry local/WebDAV queue extras into the player and show previous/current/next in the queue panel.**
- [x] **Step 4: Build local SAF and WebDAV current-directory queues from existing browser lists.**
- [x] **Step 5: Run targeted tests and verify they pass.**

## Verification

- [x] Run `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest`.
- [x] Run `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:assembleDebug`.
