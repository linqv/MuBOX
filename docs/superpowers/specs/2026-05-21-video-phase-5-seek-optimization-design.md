# MuBOX Video Phase 5 Seek Optimization Design

## Goal

Implement the fifth phase from `DESIGN.md`: improve WebDAV video seek behavior with a 2 MiB in-memory segment cache, in-flight request coalescing, simple forward prefetch, diagnostic logging, and user-visible settings.

## Current State

Video phases 1-4 are already present in the codebase:

- `MuBoxVideoProxy` serves registered WebDAV video and subtitle streams on `127.0.0.1`.
- `OkHttpWebDavClient.openRangeStream()` validates remote `206`, `Content-Range`, and `Content-Length`.
- `VideoProxyManager` registers video and sidecar subtitle streams and owns proxy shutdown.
- `VideoRangeMemoryCache` exists but is only a minimal key-value map and is not wired into playback.
- Settings currently expose video resume only; video seek optimization settings do not exist yet.

The phase 5 work should not change local video playback, mpv lifecycle, subtitle discovery, or playback history behavior.

## Chosen Approach

Use a focused optimizer behind the proxy:

```text
MuBoxVideoProxy
└── VideoSeekOptimizer
    ├── VideoRangeMemoryCache
    ├── per-stream in-flight segment fetches
    ├── per-stream forward prefetch jobs
    └── VideoProxyDiagnostics
```

`MuBoxVideoProxy` keeps HTTP responsibilities: request parsing, method dispatch, response status, headers, stream registration, active stream cleanup, and fallback error responses. `VideoSeekOptimizer` owns byte-range optimization for WebDAV `GET` requests that include a local Range header.

This keeps the existing proxy protocol stable while preventing `MuBoxVideoProxy` from absorbing cache and prefetch state.

## User-Visible Settings

Add these settings to the existing settings screen's `视频` group:

- `WebDAV 视频 seek 优化`: boolean, default `true`.
- `向前预读`: enum with `关闭`, `标准`, `积极`, default `标准`.
- `视频代理诊断日志`: enum with `关闭`, `摘要`, `详细`, default `关闭`.

Internal fixed values:

- Segment size: 2 MiB.
- Memory LRU capacity: 64 MiB.
- Standard prefetch: next 1 segment.
- Aggressive prefetch: next 2 segments.

The UI should not expose segment size, cache capacity, or in-flight behavior. Those are implementation details.

## Range Handling

For `GET /stream/<id>`:

1. `MuBoxVideoProxy` parses the Range header with the existing rules.
2. Requests without a Range header continue to use the existing full stream path and are not optimized.
3. If seek optimization is disabled, Range requests use the current direct `client.openRangeStream()` path.
4. If seek optimization is enabled, `VideoSeekOptimizer` handles only bounded byte ranges.
5. The optimizer maps the requested range to all overlapping 2 MiB segment keys.
6. Each segment is read from cache, joined from an in-flight fetch, or fetched from WebDAV.
7. The optimizer returns a `WebDavStreamResponse` backed by a `ByteArrayInputStream` containing exactly the requested byte range.
8. `MuBoxVideoProxy` writes the same HTTP `206` headers it writes today, using the originally requested range.

Open-ended local ranges are already bounded by `MuBoxVideoProxy` before remote access. The optimizer receives only finite ranges.

## Segment Cache

`VideoRangeMemoryCache` becomes a byte-aware LRU cache:

- Key: stream-scoped segment key containing stream id and segment index.
- Value: full segment bytes plus start and end offsets.
- `getSegment()` updates recency.
- `putSegment()` evicts least-recently-used segments until total bytes are within capacity.
- Oversized entries are rejected.
- `removeStream(streamId)` drops all cached segments for a closed stream.
- `clear()` drops everything during proxy shutdown.

The cache stores only successful remote fetches whose returned byte count matches the requested segment length.

## In-Flight Coalescing

For each stream, the optimizer tracks active segment fetches:

- Concurrent requests for the same stream id and segment index share one fetch.
- Joiners await the same deferred result and slice their own requested output after completion.
- Failed fetches complete all joiners exceptionally and remove the in-flight entry.
- Closing a stream cancels its in-flight fetches and prefetch jobs.
- Closing the proxy cancels all optimizer work and clears cache.

Coalescing is per segment, not per arbitrary HTTP Range, to keep keys simple and maximize reuse after seeks.

## Prefetch

Forward prefetch is best-effort:

- Disabled mode schedules nothing.
- Standard mode schedules the next 1 segment after the highest segment touched by the current request.
- Aggressive mode schedules the next 2 segments.
- Prefetch never blocks the current response.
- Prefetch skips segments already cached or already in flight.
- Prefetch is bounded by the known file size.
- Prefetch failures are logged only when diagnostics allow it; they do not affect playback.

The first version does not try to infer playback direction beyond "the next segment after the current request".

## Diagnostics

Add a video proxy diagnostics helper independent from reader diagnostics:

- `OFF`: no diagnostic output.
- `SUMMARY`: cache hit, cache miss, remote fetch, in-flight join, prefetch scheduled, prefetch skipped, fallback.
- `DETAIL`: all summary events plus segment indexes, byte ranges, cache byte totals, eviction counts, and elapsed fetch time.

Diagnostics must redact sensitive details. They may include account id and hashed or stream-local identifiers, but must not log passwords, Authorization headers, or credential-bearing URLs.

The first implementation can write through Android/Java logging or `System.err` in JVM tests. It does not need to create user-exported log files.

## Fallback And Errors

The optimizer must not make playback less reliable:

- If optimization is disabled, the existing direct remote stream path is used.
- If cache lookup or prefetch scheduling fails, the current request falls back to direct `openRangeStream()`.
- If an optimized foreground segment fetch fails before any local HTTP response body is written, the current request falls back to direct `openRangeStream()` once.
- If a direct fallback also fails, `MuBoxVideoProxy` returns the same `502` behavior it uses today.
- Remote protocol validation remains in `OkHttpWebDavClient`.
- HTTP `404`, `405`, `416`, and full-stream `200` behavior must remain unchanged.

When a registered stream is unregistered, the proxy must close active responses, cancel prefetch/in-flight work for that stream, and remove that stream's cached segments.

## Files To Change

Expected production files:

- `app/src/main/java/com/example/comicdav/data/AppSettingsStore.kt`
- `app/src/main/java/com/example/comicdav/feature/settings/SettingsScreen.kt`
- `app/src/main/java/com/example/comicdav/MainActivity.kt`
- `app/src/main/java/com/example/comicdav/video/proxy/MuBoxVideoProxy.kt`
- `app/src/main/java/com/example/comicdav/video/proxy/VideoRangeMemoryCache.kt`
- New: `app/src/main/java/com/example/comicdav/video/proxy/VideoSeekOptimizer.kt`
- New: `app/src/main/java/com/example/comicdav/video/proxy/VideoProxySettings.kt`
- New: `app/src/main/java/com/example/comicdav/video/proxy/VideoProxyDiagnostics.kt`

Expected tests:

- `app/src/test/java/com/example/comicdav/video/proxy/VideoRangeMemoryCacheTest.kt`
- `app/src/test/java/com/example/comicdav/video/proxy/VideoSeekOptimizerTest.kt`
- Updates to `app/src/test/java/com/example/comicdav/video/proxy/MuBoxVideoProxyTest.kt`
- Settings behavior is covered by `AppSettingsStore` tests if a settings test file already exists; otherwise it is verified by unit-test compilation and focused proxy tests in this phase.

## Test Plan

Unit tests must cover:

- 2 MiB segment alignment and exact response slicing.
- LRU eviction by byte capacity.
- Per-stream cache isolation.
- `removeStream()` clears only the requested stream.
- Concurrent requests for the same segment trigger one remote fetch.
- Requests for already cached segments do not call WebDAV.
- Standard prefetch schedules one forward segment.
- Aggressive prefetch schedules two forward segments.
- Disabled prefetch schedules nothing.
- Disabled seek optimization bypasses optimizer and preserves current direct range behavior.
- Optimizer fallback keeps playback working when cache or prefetch logic fails.
- Unregistering a stream clears optimizer state.
- Existing proxy tests for HEAD, GET, invalid ranges, disconnects, and active stream closing still pass.

Verification command:

```bash
./gradlew :app:testDebugUnitTest
```

## Out Of Scope

- Disk video cache.
- User-adjustable segment size or cache size.
- Playback analytics UI.
- Exported video diagnostic log files.
- Adaptive prefetch based on measured bitrate.
- Optimizing non-Range full-stream requests.
- Changing mpv player controls, subtitle loading, or resume playback.
