# Library And Reader UI Design

## Goal

Turn ComicDav from a direct WebDAV file opener into a manga-reader style app with a real library, a polished WebDAV picker, and an immersive reader.

## Product Decisions

- The library starts as one item per CBZ/ZIP file.
- The data model keeps optional `seriesTitle` and `volumeTitle` fields so folder-based series grouping can be added later.
- WebDAV items added to the library store metadata, cover path, remote identity, and optional offline download state. They do not download the whole comic by default.
- Local items added to the library store a persisted document URI. They do not copy the source file by default.
- On first launch, the app requires the user to choose an application data folder using Android's document tree picker.
- User-visible files live under the chosen data folder: covers, offline files, local cover cache, diagnostics, and future exports.
- Room stores structured library data in the app-private database directory. The selected document tree is not used as the SQLite database location.
- DataStore remains for small app preferences such as the selected data-folder URI and reader settings.

## Reference Patterns

- Mihon uses a library-first manga reader model, categories, downloads, and configurable reader modes. Its reader settings include paged right-to-left, paged left-to-right, vertical, and long-strip modes.
- Modern mobile comic readers favor a cover grid library, compact source browsing, clear download/offline state, and an immersive reader with controls hidden until tap.
- ComicDav should stay focused on local files and WebDAV sources instead of adding network catalog extensions.

## User Flow

1. First launch shows a data-folder gate with a clear reason and one primary action.
2. After folder selection, the app opens the library home.
3. Empty library shows two primary ways to add books: local file and WebDAV.
4. WebDAV browse keeps the existing connection flow but becomes an add/select surface:
   - folders are navigable rows,
   - CBZ/ZIP files show source metadata,
   - each comic can be opened immediately or added to library.
5. Library cards show cover, title, source badge, progress, and offline state.
6. Opening a library item routes through the existing reader pipeline:
   - local URI items are copied to a temporary app cache only for the active session,
   - WebDAV items use remote range opening first and whole-file cache fallback.
7. Reader uses an immersive black canvas. A tap reveals top and bottom controls.

## Data Model

`library_items`

- `id: Long`
- `title: String`
- `displayName: String`
- `seriesTitle: String?`
- `volumeTitle: String?`
- `sourceType: LOCAL | WEBDAV`
- `coverPath: String?`
- `pageCount: Int?`
- `lastPageIndex: Int`
- `addedAt: Long`
- `lastOpenedAt: Long?`
- `offlineState: NOT_DOWNLOADED | DOWNLOADING | DOWNLOADED | FAILED`

`local_comic_sources`

- `libraryItemId: Long`
- `uri: String`
- `fileName: String`
- `size: Long?`
- `lastModified: Long?`

`webdav_comic_sources`

- `libraryItemId: Long`
- `accountId: String`
- `remotePath: String`
- `fileName: String`
- `size: Long?`
- `etag: String?`
- `lastModified: Long?`
- `cacheKey: String?`

## UI Direction

- Overall style: quiet Material 3 manga-reader UI, dark reader, light/dark-capable library surfaces.
- Library: dense cover grid, top app bar with title and add actions, empty state with local/WebDAV choices.
- WebDAV: path breadcrumb, source rows with folder/file icons, sticky progress/status area, add/open actions for files.
- Reader: black background, full-screen page image, overlay controls, page counter, progress slider, close/log actions, polished loading/error states.

## Testing

- Unit-test Room DAO and repository behavior.
- Unit-test data-folder preference persistence.
- Unit-test library view model add/open state transitions where practical.
- Keep existing reader and WebDAV tests passing.
- Build verification remains `cargo test` and `./gradlew :app:testDebugUnitTest`.

