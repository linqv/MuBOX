# MuPDF Local Document Reader Design

## Goal

Add local direct reading for `.pdf`, `.epub`, `.mobi`, and `.azw3` by integrating MuPDF as a document rendering engine. The first version should reuse the existing full-screen reader, page cache, page progress, and local SAF open flow wherever possible.

## Product Decisions

- MuPDF is the document engine for PDF, EPUB, MOBI, and AZW3 local files.
- Existing CBZ/ZIP/7z/TAR local archive behavior remains on the current Rust archive path.
- The first MuPDF version is local-only.
- WebDAV document streaming is out of scope for the first version.
- WebDAV document support can be added later by downloading a whole document to the existing remote cache and opening the cached file with MuPDF.
- The reader treats MuPDF documents as paged documents in the first version.
- EPUB/MOBI/AZW3 do not get text-reader controls in the first version. Font size, line height, reflow settings, text selection, search, and dictionary support are future work.
- DRM-protected files, password-protected files without a password flow, damaged files, and unsupported AZW3 variants should fail with clear user-facing messages.

## User Flow

1. The user opens a saved local folder from Sources or taps a local library item.
2. The folder browser lists directories plus supported local files:
   - existing image archives: `.cbz`, `.zip`, `.cb7`, `.7z`, `.cbt`, `.tar`;
   - MuPDF documents: `.pdf`, `.epub`, `.mobi`, `.azw3`.
3. The user taps a MuPDF document.
4. The app opens the document from the persisted SAF URI without copying the whole file into the local import cache.
5. The MuPDF session opens the document and reports a page count.
6. The reader asks for page files through the existing `ComicReaderSession.loadPageToFile()` contract.
7. The MuPDF session renders the requested page into the existing reader page cache.
8. The existing reader displays that rendered image with Coil.
9. Page progress is saved using the existing integer page index model.

## Architecture

Extend the local open dispatch into two reader families:

- `LocalArchiveFormat`
  - `.cbz`, `.zip`, `.cb7`, `.7z`, `.cbt`, `.tar`
  - uses `ComicEngine.openLocalFd()`
  - backed by `comic-core`
- `LocalDocumentFormat`
  - `.pdf`, `.epub`, `.mobi`, `.azw3`
  - uses a new MuPDF-backed session
  - backed by Android/Kotlin plus the MuPDF Android/Java binding

`LocalComicOpener` should become the local-reader dispatch seam. It should still return `ComicReaderSession` for both archive and MuPDF document opens so `ReaderViewModel` and `ReaderScreen` remain largely unchanged.

The MuPDF session should implement `ComicReaderSession`:

- `pageCount` returns MuPDF's page count.
- `loadPageToFile(pageIndex, outputFile)` renders only the requested page.
- `close()` releases MuPDF document/page/native resources and closes any file descriptor it owns.
- remote range planning methods keep their default no-op behavior.

## Rendering Model

MuPDF should render each requested page to a bitmap-sized output that is appropriate for the device display and reader cache.

Initial rendering rules:

- Render to a raster image file because the current reader displays page files through Coil.
- Prefer PNG for correctness in the first version.
- Add a maximum pixel bound to prevent very large PDF pages from exhausting memory.
- Render on `Dispatchers.IO`, behind the existing `ReaderViewModel` session mutex.
- Reuse `ReaderPageCache.pageFile()` for output paths and pruning.
- If the output file already exists and is non-empty, `loadPageToFile()` should return it without rendering again.

The first version should keep rendering deterministic and simple. Zoom-level-specific tile rendering, text-layer overlays, annotations, and selectable text are future work.

## Format Dispatch

Local filename handling should move from a single archive enum to explicit local reader formats.

Required behavior:

- `isSupportedLocalComicFileName()` returns true for all supported archive and MuPDF document extensions.
- `localComicTitleFromFileName()` strips all supported extensions.
- directory browsing includes MuPDF document files.
- library add/open for local files accepts MuPDF document files.
- unsupported extensions still fail before opening file descriptors.

Remote WebDAV browsing should not list PDF/EPUB/MOBI/AZW3 as readable in the first MuPDF step unless a whole-file download-and-open path is implemented in the same change.

## Error Handling

Use short, specific Chinese messages:

- `无法读取所选文件`
- `暂不支持这个本地阅读格式`
- `无法打开这个 PDF 文件`
- `无法打开这个 EPUB 文件`
- `无法打开这个 MOBI 文件`
- `无法打开这个 AZW3 文件`
- `暂不支持受 DRM 保护的文件`
- `暂不支持加密或需要密码的文件`
- `这个文件没有可读取的页面`
- `页面渲染失败`

Diagnostic logs should include:

- local file name;
- local reader family: archive or MuPDF document;
- document format;
- page count on success;
- render page index and output file size;
- explicit unsupported/failure reason.

Logs should not include full content URI secrets beyond the app's existing local-file logging behavior.

## Cache Behavior

The existing reader page cache remains the only first-version cache for MuPDF renders:

- opening a MuPDF document does not create a `local-comics/local-comic-*.cbz` file;
- rendering page 0 writes only the rendered page image;
- neighbor prefetch can render additional page images;
- existing page cache pruning continues to apply.

The rendered page cache key should be stable for library items and local directory opens:

- library item: keep `library-{id}`;
- local directory item: keep `directory-{uri.hashCode()}` unless a stronger stable key is added later.

## Testing

Add unit tests around dispatch and session seams:

- local file filtering accepts `.pdf`, `.epub`, `.mobi`, and `.azw3`;
- local title stripping handles the new extensions;
- local opener routes archive formats to the Rust archive session factory;
- local opener routes document formats to the MuPDF session factory;
- unsupported extensions are rejected before opening a file descriptor;
- MuPDF session returns the page count from its document adapter;
- `loadPageToFile()` skips rendering when a non-empty output file already exists;
- `loadPageToFile()` renders only the requested page to the requested output file;
- MuPDF open/render failures map to explicit user-facing messages.

Keep existing reader tests passing. Verification should include:

- `./gradlew :app:testDebugUnitTest`
- `cd comic-core && cargo test`

If the MuPDF dependency is added through Gradle or native artifacts, also verify:

- `./gradlew :app:assembleDebug`

## Out Of Scope

- WebDAV range streaming for PDF/EPUB/MOBI/AZW3.
- Password prompts.
- DRM support.
- Text selection.
- Search.
- Table of contents UI.
- Font and reflow controls for EPUB/MOBI/AZW3.
- MuPDF annotation editing.
- Tile-based zoom rendering.
- Replacing the existing Rust image-archive engine.
