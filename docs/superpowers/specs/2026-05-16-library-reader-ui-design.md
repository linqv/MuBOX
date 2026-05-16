# Library And Reader UI Design

## Goal

Turn ComicDav from a direct WebDAV file opener into a manga-reader style app with separate file directories, a favorites-style library, a polished WebDAV picker, and an immersive reader.

## Product Decisions

- Sources and Library are separate product concepts in the UI.
- File Directory remains the underlying data concept for saved source directories.
- File Directory stores only directories the user explicitly adds:
  - local SAF document-tree folders,
  - WebDAV account/path pairs saved from the WebDAV browser.
- File Directory does not store recent visits, last visited folders, automatic history, or implicit browse state.
- Local File Directory browsing behaves like an in-app file manager: the user opens a saved root folder, taps subfolders, and the app loads child folders plus CBZ/ZIP files on demand.
- The library is a favorites collection. A comic enters the library only when the user taps Add to Library from Sources, local folder browsing, or WebDAV browsing.
- The library starts as one favorite item per CBZ/ZIP file.
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
2. After folder selection, the app opens the Sources home.
3. Sources shows only manually added local folders and WebDAV directories.
4. Local source browsing opens a saved root folder and recursively navigates subfolders on demand.
5. WebDAV browse keeps the existing connection flow but becomes an add/select surface:
   - folders are navigable rows,
   - CBZ/ZIP files show source metadata,
   - each comic can be opened immediately or added to library,
   - the current WebDAV path can be saved as a source.
6. Library cards show only favorited comics with cover, title, source badge, progress, and offline state.
7. Opening a library item routes through the existing reader pipeline:
   - local URI items are copied to a temporary app cache only for the active session,
   - WebDAV items use remote range opening first and whole-file cache fallback.
8. Reader uses an immersive black canvas. A tap reveals top and bottom controls.

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

`file_directory_sources`

- `id: Long`
- `displayName: String`
- `sourceType: LOCAL | WEBDAV`
- `localTreeUri: String?`
- `webDavAccountId: String?`
- `webDavPath: String?`
- `addedAt: Long`

This table intentionally has no recent-visit fields.

## UI Direction

- Approved direction: Source + Library hybrid, based on the C mockup shown in the visual companion.
- Overall style: quiet Material 3 manga-reader UI with compact mobile density, cover-forward library surfaces, clear source browsing, and a dark immersive reader.
- Navigation model:
  - Use persistent bottom navigation on app surfaces outside the reader.
  - Tabs are Sources, Library, Offline, and Settings.
  - Sources is the default home because ComicDav's main strength is local folder and WebDAV source browsing.
  - Library remains one tap away and behaves like a favorites bookshelf.
- Sources home:
  - Shows "continue reading" at the top when progress exists.
  - Shows saved local folder and WebDAV sources as large touch rows.
  - Keeps add-source actions visible without making the page feel like a setup wizard.
- Library:
  - Uses a dense cover grid with filters for all/unread/local/WebDAV.
  - Cards show cover, title, source badge, progress, and offline/download state.
  - Empty library directs the user to browse Sources and add comics.
- Folder and WebDAV browsing:
  - Show a clear app bar, breadcrumb/path chip, folder/file rows, metadata, and primary file actions.
  - Directory rows open deeper; comic rows support Read and Add to Library.
  - The current WebDAV path can be saved as a source.
- Reader:
  - Uses a full black canvas, fit-to-screen page image, tap-to-toggle overlay controls, top close/log/status controls, bottom page counter and progress slider.
  - Reader chrome is not part of the bottom-tab shell.
  - Loading and error states keep the same black reader environment.

## Chinese UI Copy

The redesigned UI should use Chinese user-facing copy by default.

- Sources tab: `来源`
- Library tab: `书架`
- Offline tab: `离线`
- Settings tab: `设置`
- Sources screen title: `来源`
- Library screen title: `书架`
- Continue section: `继续阅读`
- Saved sources section: `已保存来源`
- Add local folder action: `添加本地文件夹`
- Add WebDAV source action: `添加 WebDAV`
- Open action: `打开`
- Read action: `阅读`
- Add to library action: `加入书架`
- Save directory action: `保存当前目录`
- Empty library title: `书架还是空的`
- Empty library body: `从来源中浏览漫画，并把想长期阅读的作品加入书架。`
- Data folder gate title: `选择 ComicDav 数据文件夹`
- Data folder gate body: `ComicDav 会把封面、离线漫画、诊断日志和后续导出的文件保存在你选择的文件夹中。`
- Data folder gate action: `选择文件夹`
- Reader loading title: `正在打开漫画`
- Reader downloading title: `正在下载漫画`
- Reader error title: `无法打开漫画`
- Reader close action: `关闭`
- Reader log action: `日志`

## Testing

- Unit-test Room DAO and repository behavior.
- Unit-test file directory source persistence and local browse view model behavior.
- Unit-test data-folder preference persistence.
- Unit-test library view model add/open state transitions where practical.
- Keep existing reader and WebDAV tests passing.
- Build verification remains `cargo test` and `./gradlew :app:testDebugUnitTest`.
