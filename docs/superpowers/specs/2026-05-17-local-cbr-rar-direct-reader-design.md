# Local CBR/RAR Direct Reader Design

## Goal

Add local `.cbr` and `.rar` reading without copying the full archive into app cache. The reader may continue to cache extracted page images one page at a time because the current UI displays image files through the existing page-cache path.

## Product Decisions

- Local CBR/RAR support applies to files opened from saved local SAF folders and local library items.
- The app does not copy the whole RAR archive into `cache/local-comics` before opening it.
- Page images are extracted on demand into the existing reader page cache.
- `.cbz` and `.zip` behavior remains unchanged for this feature.
- `.cbr` and `.rar` are treated as local-only formats in the first version.
- WebDAV RAR range reading is out of scope.
- Encrypted RAR archives are unsupported and must produce a clear user-facing error.
- Multi-volume RAR archives are unsupported and must produce a clear user-facing error.
- If the selected RAR library cannot read a specific RAR5 variant, the app should fail with an explicit unsupported-format message rather than copying the whole file as a fallback.

## User Flow

1. The user opens a saved local folder from Sources.
2. The folder browser lists directories plus `.cbz`, `.zip`, `.cbr`, and `.rar` files.
3. The user taps a CBR/RAR file.
4. The app opens the file through its persisted SAF document URI.
5. The reader builds an ordered list of image entries from the archive.
6. The first page is extracted to the reader page cache and displayed.
7. Page selection and neighbor prefetch continue to ask `ReaderViewModel` for page files, and the RAR session extracts only requested pages.

## Architecture

Introduce an archive-format dispatch layer for local URI opens:

- `LocalComicOpener` decides how to open a local URI based on extension.
- CBZ/ZIP continues to use the existing cached-file path for now.
- CBR/RAR creates a Kotlin-side `ComicReaderSession` implementation backed by a RAR reader.

The Kotlin-side session should implement the existing `ComicReaderSession` interface:

- `pageCount` returns the number of supported image entries.
- `loadPageToFile(pageIndex, outputFile)` extracts exactly one archive entry to `outputFile`.
- `close()` releases archive resources and file descriptors.
- Remote range planning methods keep their default no-op behavior.

This keeps `ReaderViewModel`, `ReaderScreen`, page-cache naming, progress saving, and prefetch behavior intact.

## Direct Reading Model

For SAF URIs, the RAR session should prefer opening a `ParcelFileDescriptor` from `ContentResolver.openFileDescriptor(uri, "r")` and reading from that descriptor. This avoids loading the whole archive into memory and avoids copying it to an app-private archive file.

If the chosen RAR library only accepts seekable file descriptors or random access abstractions, adapt the descriptor into that abstraction. Do not fall back to writing the whole archive to `LocalComicImportCache`.

## Sorting And Image Filtering

CBR/RAR page ordering should match CBZ behavior:

- include `.jpg`, `.jpeg`, `.png`, and `.webp`;
- ignore directories and non-image files;
- sort by natural file-name order, using the base file name first and full path as a tie-breaker;
- fail with the existing no-images style error if no supported image entries are found.

## Error Handling

User-facing errors should be short and specific:

- `这个 RAR 漫画没有可读取的图片`
- `暂不支持加密 RAR 漫画`
- `暂不支持分卷 RAR 漫画`
- `暂不支持这个 RAR 格式`
- `无法读取所选文件`

Reader diagnostic logs should include the source URI hash or file name, archive format, page count on success, and the unsupported reason on failure. Logs should not include full content URI secrets beyond what the app already logs for local files.

## Cache Behavior

The existing `ReaderPageCache` remains the only cache involved in CBR/RAR reading:

- opening a RAR does not create a `local-comics/local-comic-*.cbz` file;
- extracting page 0 writes only the page 0 image file;
- neighbor prefetch can create additional page image files;
- current page cache pruning behavior continues to apply.

## Testing

Add unit tests around the dispatch and session seams:

- local file filtering accepts `.cbr` and `.rar`;
- library title stripping handles `.cbr` and `.rar`;
- local opener routes `.cbr` and `.rar` to the RAR session path without invoking `copyUriToCache`;
- RAR session indexes only supported image entries and sorts them naturally;
- `loadPageToFile` extracts only the requested page to the provided output file;
- unsupported encrypted or multi-volume archives map to explicit messages where the chosen library exposes those states.

Keep existing reader tests passing. Verification should include:

- `./gradlew :app:testDebugUnitTest`
- `cd comic-core && cargo test`

## Out Of Scope

- WebDAV `.cbr/.rar` streaming.
- Whole-archive offline download support for RAR beyond existing WebDAV download behavior.
- RAR password prompts.
- Multi-volume RAR support.
- Replacing the CBZ/ZIP local cache path with direct SAF reading.
