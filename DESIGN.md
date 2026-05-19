# MuBOX 视频播放集成设计

## 目标

以 `webcomic` 的 WebDAV、来源浏览、账号保存和 Room 数据结构为基础，以 `mpvEx` 的 libmpv 播放器、播放器生命周期、本地/网络播放经验为参考，重建一套 MuBOX 自己的内置视频播放能力。

第一版可用目标：

- 本地视频：从 MuBOX 的本地 SAF 文件夹浏览中打开视频。
- WebDAV 视频：从 MuBOX 的 WebDAV 浏览中打开视频，并通过 localhost Range 代理支持 seek。
- 播放引擎：使用 mpvEx 带的 `mpv-android-lib-v0.0.1.aar`，获得 libmpv/FFmpeg 解码能力。
- 不做外部播放器跳转。

## 核心决策

- 采用方案 A：保留 MuBOX 架构，只移植/参考 mpvEx 的必要播放能力。
- 不把 mpvEx 整包搬进 MuBOX，避免带入 Koin、完整媒体库、完整偏好系统、SMB/FTP 栈和大量无关 UI。
- 不引入 Sardine；WebDAV 继续使用 MuBOX 自有 `OkHttpWebDavClient`。
- `mpvEx` 的 `NetworkStreamingProxy` 只作为结构参考，不能照搬。它的 WebDAV Range 处理不够严格，且每次请求新建 OkHttpClient。
- 第一版不做 SMB/FTP、PiP、后台播放、播放列表、在线字幕、Anime4K 设置面板、磁盘视频缓存。
- 后续如果要追平 mpvEx 功能，按阶段逐个移植。


## 当前代码现实

### MuBOX/webcomic 已有能力

- `OkHttpWebDavClient` 已支持 `PROPFIND`、`HEAD`、`readRange()`，并有路径编码/挂载路径处理。
- `WebDavRangeProvider` 已有漫画远程 Range 缓存和 in-flight 合并经验。
- `WebDavViewModel` 当前只显示目录、`.cbz`、`.zip`，需要改成统一媒体类型识别。
- `AndroidLocalDirectoryReader` 当前只显示漫画格式，需要扩展到视频。
- `MainActivity` 当前把 WebDAV 文件点击路由到漫画阅读器，需要加视频路由。
- Room 数据库已有 library/source 模型，播放历史应独立新增，不混用漫画阅读进度。

### mpvEx 可借鉴能力

- `mpv-android-lib-v0.0.1.aar` 提供 libmpv、FFmpeg 和 `is.xyz.mpv.BaseMPVView`。
- `MPVView` 展示了 mpv option、surface 绑定和属性观察方式。
- `PlayerActivity` 展示了 mpv 初始化顺序、音频焦点、生命周期、PiP、后台播放、播放状态保存等完整播放器经验。
- `NetworkStreamingProxy` 展示了 localhost 代理和 register/unregister 模型。
- `NetworkBrowserViewModel` 展示了网络文件打开时把原路径、标题、MIME、连接 ID 传给播放器的思路。
- `VideoScanUtils.FileTypeUtils` 提供可复用的视频扩展名和 MIME 映射参考。

## 目标架构

```text
MuBOX-pro App
├── Sources
│   ├── local SAF browser
│   └── WebDAV browser
├── Reader
│   └── existing comic/document reader
└── Video
    ├── media kind detection
    ├── VideoPlayerActivity
    ├── MpvView / MpvController
    └── MuBoxVideoProxy
        └── OkHttpWebDavClient.openRangeStream()
```

### 新模块建议

```text
app/src/main/java/com/example/comicdav/video/
├── MediaKind.kt
├── MediaMimeTypes.kt
├── player/
│   ├── MuBoxMpvView.kt
│   ├── MpvController.kt
│   ├── VideoPlayerActivity.kt
│   └── VideoPlaybackStateStore.kt
└── proxy/
    ├── MuBoxVideoProxy.kt
    ├── StreamRegistry.kt
    ├── VideoStreamRequest.kt
    └── VideoRangeMemoryCache.kt
```

### WebDAV 接口扩展

`openRangeStream()` 不应只返回 `InputStream`。需要返回一个可关闭响应对象，保证关闭本地响应时也关闭 OkHttp response。

```kotlin
interface WebDavClient {
    suspend fun list(path: String): List<WebDavItem>
    suspend fun head(path: String): RemoteFileInfo
    suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray
    suspend fun openRangeStream(
        path: String,
        start: Long,
        endInclusive: Long?,
    ): WebDavStreamResponse
}

data class WebDavStreamResponse(
    val stream: InputStream,
    val statusCode: Int,
    val contentLength: Long,
    val contentRange: ContentRange?,
    val contentType: String?,
    val totalSize: Long?,
    val close: () -> Unit,
)
```

## 媒体识别

新增统一 `MediaKind`：

- `Directory`
- `Comic`
- `Video`
- `Audio`
- `Subtitle`
- `Unknown`

第一版视频扩展名：

```text
mp4, mkv, webm, avi, mov, m4v, wmv, flv, 3gp, 3g2,
mpg, mpeg, ts, mts, m2ts, vob, ogv, rm, rmvb, asf
```

第一版字幕扩展名：

```text
srt, ass, ssa, vtt, sub
```

浏览规则：

- 目录始终显示。
- 漫画继续走 Reader。
- 视频走内置 `VideoPlayerActivity`。
- 字幕第一版可显示但不单独打开，用于后续同目录字幕匹配。
- 未知文件隐藏。

## libmpv 集成

需要从 mpvEx 参考/复制的关键点：

- `app/libs/mpv-android-lib-v0.0.1.aar`
- Gradle 中 `implementation(files("libs/mpv-android-lib-v0.0.1.aar"))`
- `packaging { jniLibs { useLegacyPackaging = true } }`
- R8 keep：`-keep,allowoptimization class is.xyz.mpv.** { public protected *; }`
- `Utils.copyAssets(context)` 必须在 `BaseMPVView.initialize()` 前执行。
- Activity 需要处理 orientation/config changes。
- localhost 代理需要 network security 配置支持 cleartext 到 `127.0.0.1`。

第一版播放器 UI 保持小：

- 视频 Surface
- 点击显示控制层
- 播放/暂停
- seek bar
- 当前时间/总时长
- 关闭按钮
- 基础错误提示

暂不移植 mpvEx 的完整控制面板、PiP、后台播放、在线字幕、Anime4K、截图、播放列表。

## WebDAV 视频代理

`MuBoxVideoProxy` 绑定：

```text
127.0.0.1:<ephemeral-port>
```

注册信息：

- streamId
- accountId
- remotePath
- displayName
- size
- etag
- lastModified
- mimeType
- WebDavClient 或 client factory

HTTP 行为：

- `HEAD /stream/<id>`：返回 `200`、`Content-Length`、`Content-Type`、`Accept-Ranges: bytes`
- `GET /stream/<id>` 无 Range：返回 `200`，从 0 开始流式读取，不整文件进内存
- `GET /stream/<id>` 带 `Range: bytes=X-Y`：返回 `206`
- 无效或越界 Range：返回 `416` 和 `Content-Range: bytes */<total>`
- stream 不存在：返回 `404`

Range 规则：

- 第一版只支持单段 Range。
- `bytes=X-` 应转成远端有界请求，例如 `X-(X+8MiB-1)`。
- 严格校验远端 `206` 和 `Content-Range`。
- 远端对 Range 返回 `200` 时视为不支持 Range。
- 本地 response stream 关闭时必须关闭远端 response。
- 不记录密码、Authorization header、带凭据 URL。

## 共享 OkHttp

当前 MuBOX `OkHttpWebDavClient` 默认每个实例创建 `OkHttpClient()`。视频流会更依赖连接复用，应改为共享 client：

```kotlin
object HttpClients {
    val webDav: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .build()
}
```

测试仍需允许注入测试用 OkHttpClient/MockWebServer。

## 分阶段实施

### 阶段 1：媒体识别与浏览路由

- 新增 `MediaKind` 和扩展名/MIME 映射。
- WebDAV 列表显示视频，不再只显示漫画。
- 本地 SAF 列表显示视频。
- 漫画点击仍进 Reader。
- 视频点击进内部视频打开流程。
- 不做外部播放器跳转。

### 阶段 2：最小 libmpv 本地播放器

- 拷贝 AAR 到目标 app。
- 加 Gradle、packaging、R8、manifest 配置。
- 新增最小 `VideoPlayerActivity`、`MuBoxMpvView`、`MpvController`。
- 本地 `content://` 视频可播放、暂停、seek、关闭。

### 阶段 3：WebDAV Range Stream API 与代理

- 给 `WebDavClient` 加 `openRangeStream()`。
- 实现 `WebDavStreamResponse` 和 `ContentRange` 解析。
- 实现 `MuBoxVideoProxy`、`StreamRegistry`。
- WebDAV 视频通过 localhost URL 交给 mpv。
- 验证 mkv/mp4 seek、暂停恢复、退出清理。

### 阶段 4：字幕与播放历史

- 同目录 sidecar 字幕发现。
- 字幕也通过 proxy URL 给 mpv `sub-add`。
- 新增视频播放历史表/store。
- 打开同一视频自动恢复位置。

### 阶段 5：seek 优化

- 加 2MiB segment 的内存 LRU 缓存。
- 加 in-flight 合并。
- 加简单向前预读。
- 加诊断日志和可选设置。

### 阶段 6：mpvEx 功能追平

- PiP。
- 后台音频播放。
- 当前目录播放列表。
- 音轨/字幕轨选择面板。
- 画面比例/缩放。
- SMB/FTP。
- 磁盘视频 segment cache。

## 测试要求

单元测试：

- `MediaKind` 识别漫画、视频、音频、字幕、未知文件。
- WebDAV 浏览包含视频，隐藏未知文件。
- 本地 SAF 浏览包含视频，隐藏未知文件。
- `openRangeStream()` 发送正确 Range header。
- `openRangeStream()` 校验 `Content-Range`。
- Range 请求遇到远端 `200` 映射为不支持 Range。
- proxy 正确解析合法 Range。
- proxy 对非法 Range 返回 `416`。
- stream unregister 会关闭资源。
- 播放历史按稳定 key 保存和恢复。

构建/设备验证：

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:assembleDebug
```

手动验证：

- 本地 MP4 播放、暂停、seek、关闭。
- WebDAV MP4/MKV 播放、seek、暂停恢复。
- 退出播放器后 proxy stream 被清理。
- 断网/服务器不支持 Range 时有明确错误。

## 明确不做

- 外部播放器跳转。
- Sardine。
- 第一版 SMB/FTP。
- 第一版 PiP/后台播放。
- 第一版在线字幕。
- 第一版完整 mpvEx 设置页。
- 第一版磁盘视频缓存。
- 把 mpvEx 整个项目合并进 MuBOX。

